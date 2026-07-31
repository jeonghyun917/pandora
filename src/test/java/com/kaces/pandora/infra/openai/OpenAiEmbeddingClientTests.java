package com.kaces.pandora.infra.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

class OpenAiEmbeddingClientTests {

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
						new IOException("connection reset while writing request body")
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

	private LawAiProperties properties() {
		return new LawAiProperties(
			new LawAiProperties.OpenAi("test-key", "test-embedding-model", null, null, null, 0),
			null,
			null,
			null
		);
	}
}
