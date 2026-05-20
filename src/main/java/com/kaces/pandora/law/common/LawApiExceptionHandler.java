package com.kaces.pandora.law.common;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(basePackages = "com.kaces.pandora.law")
public class LawApiExceptionHandler {

	@ExceptionHandler(IllegalArgumentException.class)
	public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException exception) {
		return ResponseEntity.badRequest().body(Map.of("message", exception.getMessage()));
	}

	@ExceptionHandler(IllegalStateException.class)
	public ResponseEntity<Map<String, String>> handleServerState(IllegalStateException exception) {
		return ResponseEntity.internalServerError().body(Map.of("message", exception.getMessage()));
	}
}
