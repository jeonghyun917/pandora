package com.kaces.pandora.common.json;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class LawJsonNodes {
	
	// 메소드 설명: LawJsonNodes 처리 흐름을 수행합니다.
	private LawJsonNodes() {
	}
	
	// 메소드 설명: child 처리 흐름을 수행합니다.
	public static JsonNode child(JsonNode node, String fieldName) {
		return node == null || node.isNull() ? null : node.get(fieldName);
	}
	
	// 메소드 설명: nodes 처리 흐름을 수행합니다.
	public static List<JsonNode> nodes(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (node.isArray()) {
			return node.valueStream().toList();
		}
		return List.of(node);
	}
	
	// 메소드 설명: text 처리 흐름을 수행합니다.
	public static String text(JsonNode node, String fieldName, String defaultValue) {
		JsonNode value = child(node, fieldName);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		return value.isTextual() ? value.asText() : value.toString();
	}
	
	// 메소드 설명: nodeText 처리 흐름을 수행합니다.
	public static String nodeText(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		return node.isTextual() ? node.asText() : node.toString();
	}
}
