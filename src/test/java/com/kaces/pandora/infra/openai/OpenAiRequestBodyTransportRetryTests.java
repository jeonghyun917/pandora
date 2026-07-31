package com.kaces.pandora.infra.openai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

class OpenAiRequestBodyTransportRetryTests {

	@AfterEach
	void clearInterruptedFlag() {
		Thread.interrupted();
	}

	@Test
	void retriesWrappedRequestBodyWriteIOExceptionOnceAndReturnsTheSecondResult() throws InterruptedException {
		AtomicInteger attempts = new AtomicInteger();
		List<OpenAiRequestBodyTransportRetry.RetryMetadata> metadata = new ArrayList<>();
		OpenAiRequestBodyTransportRetry retry = new OpenAiRequestBodyTransportRetry(delay -> { }, metadata::add);

		String result = retry.execute("embeddings", () -> {
			if (attempts.incrementAndGet() == 1) {
				throw requestBodyWriteFailure();
			}
			return "second-result";
		});

		assertThat(result).isEqualTo("second-result");
		assertThat(attempts).hasValue(2);
		assertThat(metadata).containsExactly(new OpenAiRequestBodyTransportRetry.RetryMetadata(
			"embeddings", 1, 1, ResourceAccessException.class.getName(), IOException.class.getName()
		));
	}

	@Test
	void rethrowsTheSecondWrappedRequestBodyWriteIOExceptionAfterTwoTotalAttempts() {
		AtomicInteger attempts = new AtomicInteger();
		List<OpenAiRequestBodyTransportRetry.RetryMetadata> metadata = new ArrayList<>();
		OpenAiRequestBodyTransportRetry retry = new OpenAiRequestBodyTransportRetry(delay -> { }, metadata::add);
		ResourceAccessException finalFailure = requestBodyWriteFailure();

		assertThatThrownBy(() -> retry.execute("embeddings", () -> {
			if (attempts.incrementAndGet() == 1) {
				throw requestBodyWriteFailure();
			}
			throw finalFailure;
		})).isSameAs(finalFailure);

		assertThat(attempts).hasValue(2);
		assertThat(metadata).hasSize(1);
	}

	@Test
	void doesNotRetryHttpFourHundredResponseException() {
		AtomicInteger attempts = new AtomicInteger();
		OpenAiRequestBodyTransportRetry retry = new OpenAiRequestBodyTransportRetry(delay -> { }, metadata -> { });
		HttpClientErrorException failure = new HttpClientErrorException(HttpStatus.BAD_REQUEST);

		assertThatThrownBy(() -> retry.execute("embeddings", () -> {
			attempts.incrementAndGet();
			throw failure;
		})).isSameAs(failure);

		assertThat(attempts).hasValue(1);
	}

	@Test
	void doesNotRetryJsonSerializationFailureWithoutIoTransportCause() {
		AtomicInteger attempts = new AtomicInteger();
		OpenAiRequestBodyTransportRetry retry = new OpenAiRequestBodyTransportRetry(delay -> { }, metadata -> { });
		IllegalStateException failure = new IllegalStateException("Could not write JSON: invalid mapping");

		assertThatThrownBy(() -> retry.execute("embeddings", () -> {
			attempts.incrementAndGet();
			throw failure;
		})).isSameAs(failure);

		assertThat(attempts).hasValue(1);
	}

	@Test
	void restoresInterruptedFlagAndPropagatesInterruptedRetryDelay() {
		AtomicInteger attempts = new AtomicInteger();
		OpenAiRequestBodyTransportRetry retry = new OpenAiRequestBodyTransportRetry(delay -> {
			throw new InterruptedException("test interruption");
		}, metadata -> { });

		assertThatThrownBy(() -> retry.execute("embeddings", () -> {
			attempts.incrementAndGet();
			throw requestBodyWriteFailure();
		})).isInstanceOf(InterruptedException.class)
			.hasMessage("test interruption");

		assertThat(attempts).hasValue(1);
		assertThat(Thread.currentThread().isInterrupted()).isTrue();
	}

	private ResourceAccessException requestBodyWriteFailure() {
		return new ResourceAccessException(
			"Could not write JSON: Error writing request body to server",
			new IOException("connection reset while writing request body")
		);
	}
}
