package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class EvaluationTermMatcher {

	private static final Set<String> WEAK_TOKENS = Set.of(
		"및", "또는", "그리고", "등", "그", "해당", "관련", "기준", "사항", "내용",
		"개인정보", "정보주체"
	);

	private EvaluationTermMatcher() {
	}

	static boolean matchesAnswerTerm(String text, String expectedTerm) {
		String normalizedText = KoreanQueryNormalizer.normalizeForMatch(text);
		String normalizedExpected = KoreanQueryNormalizer.normalizeForMatch(expectedTerm);
		if (normalizedText.isBlank() || normalizedExpected.isBlank()) {
			return false;
		}
		if (normalizedText.contains(normalizedExpected)) {
			return true;
		}
		String canonicalText = canonical(normalizedText);
		String canonicalExpected = canonical(normalizedExpected);
		if (!canonicalExpected.isBlank() && canonicalText.contains(canonicalExpected)) {
			return true;
		}
		List<String> requiredTokens = strongTokens(expectedTerm);
		if (requiredTokens.isEmpty()) {
			return false;
		}
		long matches = requiredTokens.stream()
			.filter(token -> canonicalText.contains(canonical(token)))
			.count();
		if (requiredTokens.size() == 1) {
			return matches == 1;
		}
		int requiredMatches = Math.max(2, requiredTokens.size() - 1);
		return matches >= requiredMatches;
	}

	private static String canonical(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(value)
			.replace("및", "")
			.replace("과", "")
			.replace("와", "")
			.replace("의", "");
	}

	private static List<String> strongTokens(String value) {
		String normalized = KoreanQueryNormalizer.normalizeForMatch(value);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> rawTokens = new ArrayList<>();
		StringBuilder token = new StringBuilder();
		for (int index = 0; index < value.length(); index++) {
			char ch = value.charAt(index);
			if (Character.isLetterOrDigit(ch)) {
				token.append(ch);
			}
			else {
				addToken(rawTokens, token);
			}
		}
		addToken(rawTokens, token);
		if (rawTokens.size() <= 1) {
			rawTokens = splitKnownCompound(normalized);
		}
		LinkedHashSet<String> strong = new LinkedHashSet<>();
		for (String rawToken : rawTokens) {
			String normalizedToken = KoreanQueryNormalizer.normalizeForMatch(rawToken);
			for (String splitToken : splitKnownCompound(normalizedToken)) {
				String canonicalToken = canonical(stripCommonJosa(splitToken));
				if (canonicalToken.length() >= 2 && !WEAK_TOKENS.contains(canonicalToken)) {
					strong.add(canonicalToken);
				}
			}
		}
		return List.copyOf(strong);
	}

	private static void addToken(List<String> tokens, StringBuilder token) {
		if (!token.isEmpty()) {
			tokens.add(token.toString());
			token.setLength(0);
		}
	}

	private static List<String> splitKnownCompound(String normalized) {
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> tokens = new ArrayList<>();
		String remaining = normalized;
		for (String marker : List.of("수집", "이용", "목적", "항목", "보유", "기간", "거부", "권리", "불이익", "제3자", "제공")) {
			if (remaining.contains(marker)) {
				tokens.add(marker);
				remaining = remaining.replace(marker, " ");
			}
		}
		if (tokens.isEmpty()) {
			tokens.add(normalized);
		}
		return tokens;
	}

	private static String stripCommonJosa(String token) {
		String result = token;
		for (String suffix : List.of("으로", "에서", "에게", "까지", "부터", "하고", "하면", "은", "는", "이", "가", "을", "를", "의", "와", "과")) {
			if (result.endsWith(suffix) && result.length() > suffix.length() + 1) {
				return result.substring(0, result.length() - suffix.length());
			}
		}
		return result;
	}
}
