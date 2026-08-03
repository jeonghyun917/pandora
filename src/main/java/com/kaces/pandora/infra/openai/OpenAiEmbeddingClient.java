package com.kaces.pandora.infra.openai;


import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiEmbeddingClient {
	private static final Logger log = LoggerFactory.getLogger(OpenAiEmbeddingClient.class);

	private final LawAiProperties properties;
	private final EmbeddingRequestExecutor requestExecutor;
	private final OpenAiRequestBodyTransportRetry requestBodyTransportRetry;
	private final Consumer<OpenAiRequestBodyTransportRetry.FailureSummary> transportFailureRecorder;

	// 메소드 설명: OpenAiEmbeddingClient 처리 흐름을 수행합니다.
	@Autowired
	public OpenAiEmbeddingClient(LawAiProperties properties) {
		this(properties, defaultRequestExecutor(), new OpenAiRequestBodyTransportRetry(), OpenAiEmbeddingClient::logEscapedTransportFailure);
	}

	OpenAiEmbeddingClient(
		LawAiProperties properties,
		EmbeddingRequestExecutor requestExecutor,
		OpenAiRequestBodyTransportRetry requestBodyTransportRetry
	) {
		this(properties, requestExecutor, requestBodyTransportRetry, OpenAiEmbeddingClient::logEscapedTransportFailure);
	}

	OpenAiEmbeddingClient(
		LawAiProperties properties,
		EmbeddingRequestExecutor requestExecutor,
		OpenAiRequestBodyTransportRetry requestBodyTransportRetry,
		Consumer<OpenAiRequestBodyTransportRetry.FailureSummary> transportFailureRecorder
	) {
		this.properties = properties;
		this.requestExecutor = requestExecutor;
		this.requestBodyTransportRetry = requestBodyTransportRetry;
		this.transportFailureRecorder = transportFailureRecorder;
	}

	private static EmbeddingRequestExecutor defaultRequestExecutor() {
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofMinutes(3));
		RestClient restClient = RestClient.builder()
			.baseUrl("https://api.openai.com")
			.requestFactory(requestFactory)
			.build();
		return (apiKey, model, inputs) -> restClient.post()
			.uri("/v1/embeddings")
			.header("Authorization", "Bearer " + apiKey)
			.body(Map.of(
				"model", model,
				"input", inputs
			))
			.retrieve()
			.body(Map.class);
	}

	// 메소드 설명: embed 처리 흐름을 수행합니다.
	public List<List<Double>> embed(List<String> inputs) {
		String apiKey = properties.openai().apiKey();
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("OPENAI_API_KEY 환경변수가 필요합니다.");
		}
		if (inputs.isEmpty()) {
			return List.of();
		}

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response;
		try {
			response = requestBodyTransportRetry.execute(
				"openai-embeddings",
				() -> requestExecutor.execute(apiKey, properties.openai().embeddingModel(), inputs),
				transportFailureRecorder
			);
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("OpenAI embedding request retry was interrupted.", interrupted);
		}

		Object dataObject = response == null ? null : response.get("data");
		List<?> data = dataObject instanceof List<?> dataList ? dataList : List.of();
		List<List<Double>> embeddings = new ArrayList<>(data.size());
		for (Object item : data) {
			Map<?, ?> itemMap = (Map<?, ?>) item;
			List<?> vector = (List<?>) itemMap.get("embedding");
			embeddings.add(vector.stream().map(value -> ((Number) value).doubleValue()).toList());
		}
		return embeddings;
	}

	private static void logEscapedTransportFailure(OpenAiRequestBodyTransportRetry.FailureSummary summary) {
		log.warn(
			"OpenAI transport failure escaped. operationStage=embedding outerExceptionClass={} causeChainClasses={} springOuterMarkerExact={} requestBodyTransportMarkerExact={} classifierAccepted={} attempts={} retryExhausted={} interrupted={}",
			summary.outerExceptionClass(),
			summary.causeChainClasses(),
			summary.springOuterMarkerExact(),
			summary.requestBodyTransportMarkerExact(),
			summary.classifierAccepted(),
			summary.attempts(),
			summary.retryExhausted(),
			summary.interrupted()
		);
	}

	@FunctionalInterface
	interface EmbeddingRequestExecutor {
		Map<?, ?> execute(String apiKey, String model, List<String> inputs);
	}
}
