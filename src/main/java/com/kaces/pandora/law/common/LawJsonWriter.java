package com.kaces.pandora.law.common;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LawJsonWriter {

	private final ObjectMapper objectMapper;

	public LawJsonWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	public String write(Object value) {
		try {
			if (value instanceof JsonNode jsonNode) {
				return objectMapper.writeValueAsString(jsonNode);
			}
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}
}
