package com.kaces.pandora.infra.openai;


import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.ArrayList;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiEmbeddingClient {

	private final LawAiProperties properties;
	private final RestClient restClient;

	// 메소드 설명: OpenAiEmbeddingClient 처리 흐름을 수행합니다.
	public OpenAiEmbeddingClient(LawAiProperties properties) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofMinutes(3));
		this.restClient = RestClient.builder()
			.baseUrl("https://api.openai.com")
			.requestFactory(requestFactory)
			.build();
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
		Map<?, ?> response = restClient.post()
			.uri("/v1/embeddings")
			.header("Authorization", "Bearer " + apiKey)
			.body(Map.of(
				"model", properties.openai().embeddingModel(),
				"input", inputs
			))
			.retrieve()
			.body(Map.class);

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
}
