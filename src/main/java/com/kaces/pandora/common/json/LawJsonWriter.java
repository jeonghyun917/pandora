package com.kaces.pandora.common.json;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class LawJsonWriter {

	private final ObjectMapper objectMapper;

	
	// 메소드 설명: LawJsonWriter 처리 흐름을 수행합니다.
	public LawJsonWriter(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	
	// 메소드 설명: write 처리 흐름을 수행합니다.
	public String write(Object value) {
		try {
			if (value instanceof JsonNode jsonNode) {
				
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				return objectMapper.writeValueAsString(jsonNode);
			}
			
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}
}
