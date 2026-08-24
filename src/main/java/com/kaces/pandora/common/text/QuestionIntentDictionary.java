package com.kaces.pandora.common.text;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

final class QuestionIntentDictionary {
	private static final String RESOURCE_NAME = "query-intent-dictionary.properties";
	private static final String WORD_MATCH_PREFIX = "word:";
	private static final List<String> KOREAN_PARTICLE_SUFFIXES = List.of(
		"은", "는", "이", "가", "을", "를", "의", "에", "에서", "에게", "한테", "께",
		"도", "만", "부터", "까지", "와", "과", "로", "으로"
	);
	private static final List<String> KOREAN_VERB_SUFFIXES = List.of(
		"하다", "한다", "하는", "하여", "하고", "하면", "하며", "한", "할", "함", "해", "해서",
		"해야", "했다", "했던", "합니다", "하세요", "하겠다", "하지", "하므로", "하도록"
	);
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

	static List<String> stableAliases(String entityId) {
		return values("entity." + entityId + ".stable_aliases", List.of());
	}

	static List<List<String>> matchedSynonymGroups(String question) {
		String source = question == null ? "" : question;
		String normalized = KoreanQueryNormalizer.normalizeForMatch(source);
		return keys("synonyms").stream()
			.filter(key -> matchesSynonymGroup(key, source, normalized))
			.map(key -> values("synonym." + key, List.of()))
			.filter(group -> !group.isEmpty())
			.toList();
	}

	static List<String> matchedPolicyIds(String source, String normalized) {
		return keys("policies").stream()
			.filter(policyId -> matchesPolicy(source, normalized, policyId))
			.toList();
	}

	private static boolean matchesSynonymGroup(String key, String source, String normalized) {
		List<String> synonyms = values("synonym." + key, List.of());
		if (synonyms.isEmpty()) {
			return false;
		}
		List<List<String>> matchGroups = groups("synonym." + key + ".match", List.of());
		if (matchGroups.isEmpty()) {
			return containsAny(normalized, synonyms);
		}
		return matchGroups.stream().allMatch(group -> matchesAny(source, normalized, group));
	}

	static boolean matchesAny(String source, String normalized, List<String> terms) {
		for (String term : terms) {
			if (term.startsWith(WORD_MATCH_PREFIX)) {
				if (containsAsKoreanWord(source, term.substring(WORD_MATCH_PREFIX.length()))) {
					return true;
				}
			}
			else if (normalized.contains(KoreanQueryNormalizer.normalizeForMatch(term))) {
				return true;
			}
		}
		return false;
	}

	static boolean matchesPolicy(String source, String normalized, String policyId) {
		List<List<String>> matchGroups = groups("policy." + policyId + ".match", List.of());
		return !matchGroups.isEmpty()
			&& matchGroups.stream().allMatch(group -> matchesAny(source, normalized, group));
	}

	private static boolean containsAsKoreanWord(String source, String term) {
		String candidate = String.valueOf(term == null ? "" : term).trim().toLowerCase(Locale.ROOT);
		if (source == null || source.isBlank() || candidate.isBlank()) {
			return false;
		}
		String searchable = source.toLowerCase(Locale.ROOT);
		for (int index = searchable.indexOf(candidate); index >= 0; index = searchable.indexOf(candidate, index + 1)) {
			int end = index + candidate.length();
			boolean startsAtWordBoundary = index == 0
				|| !Character.isLetterOrDigit(searchable.codePointBefore(index));
			if (startsAtWordBoundary && endsAtKoreanWordBoundary(searchable, end)) {
				return true;
			}
		}
		return false;
	}

	private static boolean endsAtKoreanWordBoundary(String searchable, int end) {
		if (end >= searchable.length() || !Character.isLetterOrDigit(searchable.codePointAt(end))) {
			return true;
		}
		String suffix = searchable.substring(end);
		return matchesBoundedSuffix(suffix, KOREAN_PARTICLE_SUFFIXES)
			|| matchesBoundedSuffix(suffix, KOREAN_VERB_SUFFIXES);
	}

	private static boolean matchesBoundedSuffix(String source, List<String> suffixes) {
		for (String suffix : suffixes) {
			if (!source.startsWith(suffix)) {
				continue;
			}
			int end = suffix.length();
			if (end >= source.length() || !Character.isLetterOrDigit(source.codePointAt(end))) {
				return true;
			}
		}
		return false;
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
