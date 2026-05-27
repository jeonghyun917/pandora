package com.kaces.pandora.common.error;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.kaces.pandora")
public class LawApiExceptionHandler {

	
	@ExceptionHandler(IllegalArgumentException.class)
	// 메소드 설명: handleBadRequest 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
	}

	
	@ExceptionHandler(IllegalStateException.class)
	// 메소드 설명: handleServerState 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> handleServerState(IllegalStateException exception) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.internalServerError().body(Map.of("message", exception.getMessage()));
	}
}
