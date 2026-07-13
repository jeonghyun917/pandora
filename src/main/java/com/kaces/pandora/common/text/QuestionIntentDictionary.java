package com.kaces.pandora.common.text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

final class QuestionIntentDictionary {
	private static final String RESOURCE_NAME = "query-intent-dictionary.properties";
	private static final Properties PROPERTIES = loadProperties();

	private QuestionIntentDictionary() {
	}

	static List<String> values(String key, List<String> fallback) {
		String value = PROPERTIES.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		List<String> values = splitValues(value);
		return values.isEmpty() ? fallback : values;
	}

	static List<List<String>> groups(String key, List<List<String>> fallback) {
		String value = PROPERTIES.getProperty(key);
		if (value == null || value.isBlank()) {
			return fallback;
		}
		List<List<String>> groups = value.lines()
			.flatMap(line -> List.of(line.split(";")).stream())
			.map(QuestionIntentDictionary::splitValues)
			.filter(group -> !group.isEmpty())
			.toList();
		return groups.isEmpty() ? fallback : groups;
	}

	static List<String> keys(String key) {
		return values(key, List.of());
	}

	static List<QuestionEntity> matchedEntities(String normalizedQuestion) {
		String normalized = normalizedQuestion == null ? "" : normalizedQuestion;
		return keys("entities").stream()
			.map(QuestionIntentDictionary::entity)
			.filter(entity -> !entity.aliases().isEmpty())
			.filter(entity -> containsAny(normalized, entity.aliases()))
			.toList();
	}

	static List<List<String>> matchedSynonymGroups(String normalizedQuestion) {
		String normalized = normalizedQuestion == null ? "" : normalizedQuestion;
		return keys("synonyms").stream()
			.map(key -> values("synonym." + key, List.of()))
			.filter(group -> !group.isEmpty())
			.filter(group -> containsAny(normalized, group))
			.toList();
	}

	private static QuestionEntity entity(String id) {
		return new QuestionEntity(
			id,
			stringValue("entity." + id + ".label", id),
			stringValue("entity." + id + ".type", "domain"),
			values("entity." + id + ".aliases", List.of()),
			values("entity." + id + ".anchors", List.of()),
			values("entity." + id + ".targets", List.of()),
			values("entity." + id + ".focused", List.of()),
			values("entity." + id + ".section_types", List.of()),
			groups("entity." + id + ".direct", List.of())
		);
	}

	private static String stringValue(String key, String fallback) {
		String value = PROPERTIES.getProperty(key);
		return value == null || value.isBlank() ? fallback : value.trim();
	}

	private static boolean containsAny(String normalized, List<String> terms) {
		if (normalized == null || normalized.isBlank()) {
			return false;
		}
		for (String term : terms) {
			if (normalized.contains(KoreanQueryNormalizer.normalizeForMatch(term))) {
				return true;
			}
		}
		return false;
	}

	private static List<String> splitValues(String value) {
		return List.of(String.valueOf(value == null ? "" : value).split("\\|"))
			.stream()
			.map(String::trim)
			.filter(item -> !item.isBlank())
			.distinct()
			.toList();
	}

	private static Properties loadProperties() {
		Properties properties = new Properties();
		try (InputStream input = QuestionIntentDictionary.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
			if (input == null) {
				return properties;
			}
			properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
			return properties;
		} catch (Exception ignored) {
			return properties;
		}
	}
}
