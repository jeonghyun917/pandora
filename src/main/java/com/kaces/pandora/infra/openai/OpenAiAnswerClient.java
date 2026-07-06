package com.kaces.pandora.infra.openai;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class OpenAiAnswerClient {

	private static final String TRUNCATED_NOTICE = "\n\n출력 길이 제한으로 일부 설명이 생략되었을 수 있습니다. 필요한 경우 범위를 좁혀 다시 질문해 주세요.";

	private final LawAiProperties properties;
	private final RestClient restClient;
	private final ObjectMapper objectMapper;
	private final HttpClient httpClient;

	// 메소드 설명: OpenAiAnswerClient 처리 흐름을 수행합니다.
	public OpenAiAnswerClient(LawAiProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(10));
		requestFactory.setReadTimeout(Duration.ofMinutes(3));
		this.restClient = RestClient.builder()
			.baseUrl("https://api.openai.com")
			.requestFactory(requestFactory)
			.build();
		this.httpClient = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(10))
			.build();
	}

	// 메소드 설명: answer 처리 흐름을 수행합니다.
	public String answer(String question, String context) {
		return answer(question, context, answerMaxOutputTokens());
	}

	public String answer(String question, String context, int maxOutputTokens) {
		String apiKey = properties.openai().apiKey();
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("OPENAI_API_KEY environment variable is required.");
		}

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response = restClient.post()
			.uri("/v1/responses")
			.header("Authorization", "Bearer " + apiKey)
			.body(Map.of(
				"model", properties.openai().answerModel(),
				"instructions", instructions(),
				"input", userInput(question, context),
				"reasoning", Map.of("effort", answerReasoningEffort()),
				"text", Map.of("verbosity", answerVerbosity()),
				"max_output_tokens", safeMaxOutputTokens(maxOutputTokens)
			))
			.retrieve()
			.body(Map.class);

		return extractOutputText(response);
	}

	// 메소드 설명: answerStreaming 처리 흐름을 수행합니다.
	public String answerStreaming(String question, String context, Consumer<String> onDelta) {
		return answerStreaming(question, context, onDelta, answerMaxOutputTokens());
	}

	public String answerStreaming(String question, String context, Consumer<String> onDelta, int maxOutputTokens) {
		String apiKey = properties.openai().apiKey();
		if (apiKey == null || apiKey.isBlank()) {
			throw new IllegalStateException("OPENAI_API_KEY environment variable is required.");
		}

		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			String requestBody = objectMapper.writeValueAsString(Map.of(
				"model", properties.openai().answerModel(),
				"instructions", instructions(),
				"input", userInput(question, context),
				"reasoning", Map.of("effort", answerReasoningEffort()),
				"text", Map.of("verbosity", answerVerbosity()),
				"max_output_tokens", safeMaxOutputTokens(maxOutputTokens),
				"stream", true
			));
			HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.openai.com/v1/responses"))
				.timeout(Duration.ofMinutes(3))
				.header("Authorization", "Bearer " + apiKey)
				.header("Content-Type", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
				.build();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			HttpResponse<java.io.InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
				throw new IllegalStateException("OpenAI streaming answer failed: HTTP " + response.statusCode() + " " + errorBody);
			}
			return readStreamingAnswer(response, onDelta);
		} catch (IOException exception) {
			throw new IllegalStateException("OpenAI streaming answer failed.", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("OpenAI streaming answer was interrupted.", exception);
		}
	}

	// 메소드 설명: readStreamingAnswer 처리 흐름을 수행합니다.
	private String readStreamingAnswer(HttpResponse<java.io.InputStream> response, Consumer<String> onDelta) throws IOException {
		StringBuilder answer = new StringBuilder();
		boolean[] truncated = {false};
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("data:")) {
					continue;
				}
				String payload = line.substring("data:".length()).trim();
				if (payload.isBlank() || "[DONE]".equals(payload)) {
					continue;
				}
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				JsonNode event = objectMapper.readTree(payload);
				String type = event.path("type").asText("");
				if ("response.incomplete".equals(type) && isMaxOutputTokenLimit(event.path("response"))) {
					truncated[0] = true;
				}
				if ("response.output_text.delta".equals(type)) {
					String delta = event.path("delta").asText("");
					if (!delta.isBlank()) {
						answer.append(delta);
						if (onDelta != null) {
							onDelta.accept(delta);
						}
					}
				}
			}
		}
		String value = answer.toString().trim();
		if (value.isBlank()) {
			throw new IllegalStateException("OpenAI streaming answer did not contain text.");
		}
		return truncated[0] ? value + TRUNCATED_NOTICE : value;
	}

	// 메소드 설명: instructions 처리 흐름을 수행합니다.
	private String instructions() {
		return """
			You are a Korean legal information assistant for a law and public document RAG search service.
			Answer naturally in Korean for a non-lawyer.
			Use only the provided evidence and synthesize it into a direct answer.
			Keep the answer concise: 2 short paragraphs or up to 4 bullets.
			Do not include evidence numbers or bracket citations like [1] in the answer body.
			Do not use em dashes, en dashes, or decorative separators. Use Korean commas and periods instead.
			If the evidence is insufficient, say what is missing instead of guessing.
			Do not present the answer as legal advice or a final legal judgment.
			""";
	}

	// 메소드 설명: userInput 처리 흐름을 수행합니다.
	private String userInput(String question, String context) {
		return """
			질문:
			%s

			근거 문서:
			%s

			답변 지침:
			- 첫 문장부터 결론을 말하고, 법령/문서 문구를 그대로 나열하지 마세요.
			- 조건, 예외, 확인할 사항이 있으면 함께 묶어 설명하세요.
			- 답변 본문에는 [1] 같은 근거 번호를 붙이지 마세요. 근거 목록은 별도로 제공됩니다.
			- 확실하지 않은 세부 절차나 금액은 확인이 필요하다고 짧게 말하세요.
			""".formatted(question, context);
	}

	// 메소드 설명: extractOutputText 처리 흐름을 수행합니다.
	private String extractOutputText(Map<?, ?> response) {
		if (response == null) {
			throw new IllegalStateException("OpenAI answer response is empty.");
		}
		boolean truncated = isMaxOutputTokenLimit(response);
		Object outputText = response.get("output_text");
		if (outputText instanceof String text && !text.isBlank()) {
			String answer = text.trim();
			return truncated ? answer + TRUNCATED_NOTICE : answer;
		}

		Object outputObject = response.get("output");
		List<?> output = outputObject instanceof List<?> outputList ? outputList : List.of();
		StringBuilder builder = new StringBuilder();
		for (Object outputItem : output) {
			if (!(outputItem instanceof Map<?, ?> outputMap)) {
				continue;
			}
			Object contentObject = outputMap.get("content");
			List<?> content = contentObject instanceof List<?> contentList ? contentList : List.of();
			for (Object contentItem : content) {
				if (contentItem instanceof Map<?, ?> contentMap) {
					Object textObject = contentMap.get("text");
					if (textObject instanceof String text && !text.isBlank()) {
						builder.append(text).append('\n');
					}
				}
			}
		}
		String answer = builder.toString().trim();
		if (answer.isBlank()) {
			throw new IllegalStateException("OpenAI answer response did not contain text.");
		}
		return truncated ? answer + TRUNCATED_NOTICE : answer;
	}

	// 메소드 설명: isMaxOutputTokenLimit 처리 흐름을 수행합니다.
	private boolean isMaxOutputTokenLimit(Map<?, ?> response) {
		if (response == null) {
			return false;
		}
		Object status = response.get("status");
		if (!"incomplete".equals(status)) {
			return false;
		}
		Object details = response.get("incomplete_details");
		if (!(details instanceof Map<?, ?> detailsMap)) {
			return false;
		}
		return "max_output_tokens".equals(detailsMap.get("reason"));
	}

	// 메소드 설명: isMaxOutputTokenLimit 처리 흐름을 수행합니다.
	private boolean isMaxOutputTokenLimit(JsonNode response) {
		return "incomplete".equals(response.path("status").asText(""))
			&& "max_output_tokens".equals(response.path("incomplete_details").path("reason").asText(""));
	}

	// 메소드 설명: answerReasoningEffort 처리 흐름을 수행합니다.
	private String answerReasoningEffort() {
		return properties.openai().answerReasoningEffort();
	}

	// 메소드 설명: answerVerbosity 처리 흐름을 수행합니다.
	private String answerVerbosity() {
		return properties.openai().answerVerbosity();
	}

	// 메소드 설명: answerMaxOutputTokens 처리 흐름을 수행합니다.
	private int answerMaxOutputTokens() {
		return properties.openai().answerMaxOutputTokens();
	}

	private int safeMaxOutputTokens(int maxOutputTokens) {
		return maxOutputTokens > 0 ? maxOutputTokens : answerMaxOutputTokens();
	}
}
