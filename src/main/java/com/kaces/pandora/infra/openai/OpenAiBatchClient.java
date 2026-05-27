package com.kaces.pandora.infra.openai;


import com.kaces.pandora.semantic.batch.OpenAiBatchStatus;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class OpenAiBatchClient {

	private final LawAiProperties properties;
	private final RestClient restClient;
	private final HttpClient httpClient;

	// 메소드 설명: OpenAiBatchClient 처리 흐름을 수행합니다.
	public OpenAiBatchClient(LawAiProperties properties) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofMinutes(5));
		this.restClient = RestClient.builder()
			.baseUrl("https://api.openai.com")
			.requestFactory(requestFactory)
			.build();
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	// 메소드 설명: uploadBatchFile 처리 흐름을 수행합니다.
	public String uploadBatchFile(Path file) {
		MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
		body.add("purpose", "batch");
		body.add("file", new FileSystemResource(file));
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response = restClient.post()
			.uri("/v1/files")
			.header("Authorization", "Bearer " + apiKey())
			.contentType(MediaType.MULTIPART_FORM_DATA)
			.body(body)
			.retrieve()
			.body(Map.class);
		Object id = response == null ? null : response.get("id");
		if (!(id instanceof String fileId) || fileId.isBlank()) {
			throw new IllegalStateException("OpenAI file upload response did not contain file id.");
		}
		return fileId;
	}

	// 메소드 설명: createEmbeddingBatch 처리 흐름을 수행합니다.
	public OpenAiBatchStatus createEmbeddingBatch(String inputFileId, String target, int count) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response = restClient.post()
			.uri("/v1/batches")
			.header("Authorization", "Bearer " + apiKey())
			.contentType(MediaType.APPLICATION_JSON)
			.body(Map.of(
				"input_file_id", inputFileId,
				"endpoint", "/v1/embeddings",
				"completion_window", "24h",
				"metadata", Map.of(
					"project", "pandora",
					"target", target,
					"count", String.valueOf(count)
				)
			))
			.retrieve()
			.body(Map.class);
		return toStatus(response);
	}

	// 메소드 설명: retrieveBatch 처리 흐름을 수행합니다.
	public OpenAiBatchStatus retrieveBatch(String batchId) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response = restClient.get()
			.uri("/v1/batches/{batchId}", batchId)
			.header("Authorization", "Bearer " + apiKey())
			.retrieve()
			.body(Map.class);
		return toStatus(response);
	}

	// 메소드 설명: downloadFile 처리 흐름을 수행합니다.
	public Path downloadFile(String fileId, Path destination) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("https://api.openai.com/v1/files/" + fileId + "/content"))
				.header("Authorization", "Bearer " + apiKey())
				.timeout(Duration.ofMinutes(10))
				.GET()
				.build();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(destination));
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				throw new IllegalStateException("OpenAI file download failed with HTTP " + response.statusCode());
			}
			return response.body();
		} catch (Exception exception) {
			throw new IllegalStateException("OpenAI file download failed.", exception);
		}
	}

	// 메소드 설명: toStatus 처리 흐름을 수행합니다.
	private OpenAiBatchStatus toStatus(Map<?, ?> response) {
		if (response == null) {
			throw new IllegalStateException("OpenAI batch response is empty.");
		}
		Map<?, ?> counts = response.get("request_counts") instanceof Map<?, ?> countMap ? countMap : Map.of();
		return new OpenAiBatchStatus(
			stringValue(response.get("id")),
			stringValue(response.get("status")),
			stringValue(response.get("input_file_id")),
			stringValue(response.get("output_file_id")),
			stringValue(response.get("error_file_id")),
			intValue(counts.get("total")),
			intValue(counts.get("completed")),
			intValue(counts.get("failed"))
		);
	}

	// 메소드 설명: apiKey 처리 흐름을 수행합니다.
	private String apiKey() {
		String apiKey = properties.openai().apiKey();
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("OPENAI_API_KEY environment variable is required.");
		}
		return apiKey;
	}

	// 메소드 설명: stringValue 처리 흐름을 수행합니다.
	private String stringValue(Object value) {
		return value instanceof String text ? text : null;
	}

	// 메소드 설명: intValue 처리 흐름을 수행합니다.
	private int intValue(Object value) {
		return value instanceof Number number ? number.intValue() : 0;
	}
}
