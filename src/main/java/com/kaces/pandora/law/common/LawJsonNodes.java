package com.kaces.pandora.law.common;

import java.util.List;
import tools.jackson.databind.JsonNode;

public final class LawJsonNodes {
	private LawJsonNodes() {
	}
	public static JsonNode child(JsonNode node, String fieldName) {
		return node == null || node.isNull() ? null : node.get(fieldName);
	}
	public static List<JsonNode> nodes(JsonNode node) {
		if (node == null || node.isNull()) {
			return List.of();
		}
		if (node.isArray()) {
			return node.valueStream().toList();
		}
		return List.of(node);
	}
	public static String text(JsonNode node, String fieldName, String defaultValue) {
		JsonNode value = child(node, fieldName);
		if (value == null || value.isNull()) {
			return defaultValue;
		}
		return value.isTextual() ? value.asText() : value.toString();
	}
	public static String nodeText(JsonNode node) {
		if (node == null || node.isNull()) {
			return "";
		}
		return node.isTextual() ? node.asText() : node.toString();
	}
}
