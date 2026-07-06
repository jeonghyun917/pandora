package com.kaces.pandora.common.error;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice(basePackages = "com.kaces.pandora")
public class LawApiExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(LawApiExceptionHandler.class);
	private static final String GENERIC_BAD_REQUEST_MESSAGE = "요청 값이 올바르지 않습니다.";
	private static final String GENERIC_SERVER_ERROR_MESSAGE = "요청 처리 중 오류가 발생했습니다. 잠시 후 다시 시도해 주세요.";

	@ExceptionHandler(IllegalArgumentException.class)
	// 메소드 설명: handleBadRequest 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.badRequest().body(Map.of(
			"message",
			publicMessage(exception.getMessage(), GENERIC_BAD_REQUEST_MESSAGE)
		));
	}

	@ExceptionHandler(ResponseStatusException.class)
	// 메소드 설명: handleResponseStatus 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> handleResponseStatus(ResponseStatusException exception) {
		return ResponseEntity.status(exception.getStatusCode()).body(Map.of(
			"message",
			publicMessage(exception.getReason(), GENERIC_SERVER_ERROR_MESSAGE)
		));
	}

	@ExceptionHandler(IllegalStateException.class)
	// 메소드 설명: handleServerState 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> handleServerState(IllegalStateException exception) {
		log.warn("Request failed due to invalid server state.", exception);
		return ResponseEntity.internalServerError().body(Map.of("message", GENERIC_SERVER_ERROR_MESSAGE));
	}

	@ExceptionHandler(RuntimeException.class)
	// 메소드 설명: handleUnexpected 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> handleUnexpected(RuntimeException exception) {
		log.warn("Unexpected API error.", exception);
		return ResponseEntity.internalServerError().body(Map.of("message", GENERIC_SERVER_ERROR_MESSAGE));
	}

	private String publicMessage(String message, String fallback) {
		if (message == null || message.isBlank()) {
			return fallback;
		}
		String trimmed = message.trim();
		if (trimmed.length() > 120 || trimmed.matches("(?i).*("
			+ "exception|sql|qdrant|openai|connection refused|stack|trace|jdbc|mybatis|mapper|java\\.|org\\.|com\\.|i/o error|unsupported media type"
			+ ").*")) {
			return fallback;
		}
		return trimmed;
	}
}
