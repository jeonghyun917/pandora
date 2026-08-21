package com.kaces.pandora.infra.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class OpenAiEmbeddingClientTests {

	@AfterEach
	void clearInterruptedFlag() {
		Thread.interrupted();
	}

	@Test
	void retriesTheEmbeddingRequestBodyWriteAndReturnsTheRetriedEmbedding() {
		AtomicInteger attempts = new AtomicInteger();
		List<OpenAiRequestBodyTransportRetry.RetryMetadata> metadata = new ArrayList<>();
		OpenAiRequestBodyTransportRetry retry = new OpenAiRequestBodyTransportRetry(delay -> { }, metadata::add);
		OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
			properties(),
			(apiKey, model, inputs) -> {
				if (attempts.incrementAndGet() == 1) {
					throw new ResourceAccessException(
						"Could not write JSON: Error writing request body to server",
						new IOException("Error writing request body to server")
					);
				}
				return Map.of("data", List.of(Map.of("embedding", List.of(0.25d, 0.5d))));
			},
			retry
		);

		assertThat(client.embed(List.of("query"))).containsExactly(List.of(0.25d, 0.5d));
		assertThat(attempts).hasValue(2);
		assertThat(metadata).hasSize(1);
	}

	@Test
	void recordsMessageFreeDiagnosticWhenRejectedWrapperEscapesEmbedding() {
		List<OpenAiRequestBodyTransportRetry.FailureSummary> diagnostics = new ArrayList<>();
		OpenAiEmbeddingClient client = clientThatFails(
			new IllegalStateException("SECRET question context request body", new IOException("SECRET transport message")),
			diagnostics::add
		);

		assertThatThrownBy(() -> client.embed(List.of("sensitive query"))).isInstanceOf(IllegalStateException.class);

		assertThat(diagnostics).containsExactly(new OpenAiRequestBodyTransportRetry.FailureSummary(
			IllegalStateException.class.getName(),
			List.of(IllegalStateException.class.getName(), IOException.class.getName()),
			false,
			false,
			false,
			1,
			false,
			false
		));
		assertThat(diagnostics.toString()).doesNotContain("SECRET", "sensitive query");
	}

	@Test
	void doesNotRecordEscapeDiagnosticWhenRecognizedRetrySucceeds() {
		AtomicInteger attempts = new AtomicInteger();
		List<OpenAiRequestBodyTransportRetry.FailureSummary> diagnostics = new ArrayList<>();
		OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
			properties(),
			(apiKey, model, inputs) -> {
				if (attempts.incrementAndGet() == 1) {
					throw requestBodyWriteFailure();
				}
				return Map.of("data", List.of(Map.of("embedding", List.of(0.25d))));
			},
			new OpenAiRequestBodyTransportRetry(delay -> { }, metadata -> { }),
			diagnostics::add
		);

		assertThat(client.embed(List.of("query"))).containsExactly(List.of(0.25d));

		assertThat(attempts).hasValue(2);
		assertThat(diagnostics).isEmpty();
	}

	@Test
	void recordsClassAndMarkerOnlyDiagnosticForHttpAndSemanticFailures() {
		List<OpenAiRequestBodyTransportRetry.FailureSummary> httpDiagnostics = new ArrayList<>();
		OpenAiEmbeddingClient httpClient = clientThatFails(
			new HttpClientErrorException(HttpStatus.BAD_REQUEST),
			httpDiagnostics::add
		);
		List<OpenAiRequestBodyTransportRetry.FailureSummary> semanticDiagnostics = new ArrayList<>();
		OpenAiEmbeddingClient semanticClient = clientThatFails(
			new IllegalStateException("SECRET semantic mapping failure"),
			semanticDiagnostics::add
		);

		assertThatThrownBy(() -> httpClient.embed(List.of("query"))).isInstanceOf(HttpClientErrorException.class);
		assertThatThrownBy(() -> semanticClient.embed(List.of("query"))).isInstanceOf(IllegalStateException.class);

		assertThat(httpDiagnostics).singleElement().satisfies(summary -> {
			assertThat(summary.classifierAccepted()).isFalse();
			assertThat(summary.attempts()).isEqualTo(1);
			assertThat(summary.retryExhausted()).isFalse();
			assertThat(summary.toString()).doesNotContain("query");
		});
		assertThat(semanticDiagnostics).singleElement().satisfies(summary -> {
			assertThat(summary.classifierAccepted()).isFalse();
			assertThat(summary.attempts()).isEqualTo(1);
			assertThat(summary.retryExhausted()).isFalse();
			assertThat(summary.toString()).doesNotContain("SECRET");
		});
	}

	@Test
	void recordsOriginalTransportDiagnosticWhenRetryDelayIsInterrupted() {
		AtomicInteger attempts = new AtomicInteger();
		List<OpenAiRequestBodyTransportRetry.FailureSummary> diagnostics = new ArrayList<>();
		OpenAiEmbeddingClient client = new OpenAiEmbeddingClient(
			properties(),
			(apiKey, model, inputs) -> {
				attempts.incrementAndGet();
				throw new ResourceAccessException(
					"SECRET outer transport message",
					new IOException("Error writing request body to server")
				);
			},
			new OpenAiRequestBodyTransportRetry(delay -> {
				throw new InterruptedException("SECRET interrupted delay");
			}, metadata -> { }),
			diagnostics::add
		);

		assertThatThrownBy(() -> client.embed(List.of("SECRET query"))).isInstanceOf(IllegalStateException.class);

		assertThat(attempts).hasValue(1);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
		assertThat(diagnostics).containsExactly(new OpenAiRequestBodyTransportRetry.FailureSummary(
			ResourceAccessException.class.getName(),
			List.of(ResourceAccessException.class.getName(), IOException.class.getName()),
			false,
			true,
			true,
			1,
			false,
			true
		));
		assertThat(diagnostics.toString()).doesNotContain("SECRET");
	}

	private OpenAiEmbeddingClient clientThatFails(
		RuntimeException failure,
		java.util.function.Consumer<OpenAiRequestBodyTransportRetry.FailureSummary> diagnosticRecorder
	) {
		return new OpenAiEmbeddingClient(
			properties(),
			(apiKey, model, inputs) -> {
				throw failure;
			},
			new OpenAiRequestBodyTransportRetry(delay -> { }, metadata -> { }),
			diagnosticRecorder
		);
	}

	private ResourceAccessException requestBodyWriteFailure() {
		return new ResourceAccessException(
			"Could not write JSON: Error writing request body to server",
			new IOException("Error writing request body to server")
		);
	}

	private LawAiProperties properties() {
		return new LawAiProperties(
			new LawAiProperties.OpenAi("test-key", "test-embedding-model", null, null, null, 0),
			null,
			null,
			null
		);
	}
}
