package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.List;

/**
 * Adds material-token and local-polarity checks to the legacy fuzzy matcher for explicit answer oracles.
 * Retrieval evaluation keeps using {@link EvaluationTermMatcher} unchanged.
 */
final class ExplicitOracleTermMatcher {

	private static final List<String> GRAMMATICAL_NEGATIONS = List.of("아니", "않", "없", "불가");
	private static final List<String> CLAUSE_SEPARATORS = List.of("하지만", "그러나", "반면", "다만");

	private ExplicitOracleTermMatcher() {
	}

	static boolean matches(String answer, String expectedExpression) {
		if (answer == null || answer.isBlank() || expectedExpression == null || expectedExpression.isBlank()) {
			return false;
		}
		return clauses(answer).stream()
			.filter(clause -> EvaluationTermMatcher.matchesAnswerTerm(clause, expectedExpression))
			.filter(clause -> hasMaterialCoverage(clause, expectedExpression))
			.anyMatch(clause -> hasCompatiblePolarity(clause, expectedExpression));
	}

	private static boolean hasMaterialCoverage(String clause, String expectedExpression) {
		String normalizedClause = KoreanQueryNormalizer.normalizeForMatch(clause);
		String normalizedExpected = KoreanQueryNormalizer.normalizeForMatch(expectedExpression);
		if (normalizedClause.contains(normalizedExpected)) {
			return true;
		}
		List<String> materialTokens = java.util.Arrays.stream(expectedExpression.split("[^\\p{L}\\p{N}]+"))
			.map(KoreanQueryNormalizer::normalizeForMatch)
			.map(ExplicitOracleTermMatcher::stripTrailingParticle)
			.filter(token -> token.length() >= 2)
			.filter(token -> !isPureNegation(token))
			.toList();
		return !materialTokens.isEmpty() && materialTokens.stream().allMatch(normalizedClause::contains);
	}

	private static boolean hasCompatiblePolarity(String clause, String expectedExpression) {
		String normalizedClause = KoreanQueryNormalizer.normalizeForMatch(clause);
		String normalizedExpected = KoreanQueryNormalizer.normalizeForMatch(expectedExpression);
		int expectedNegation = firstNegationIndex(normalizedExpected);
		boolean expectsNegation = expectedNegation >= 0;
		String core = expectsNegation
			? stripTrailingParticle(normalizedExpected.substring(0, expectedNegation))
			: normalizedExpected;
		if (core.isBlank()) {
			return false;
		}
		int coreIndex = normalizedClause.indexOf(core);
		if (coreIndex < 0) {
			return !expectsNegation || containsNegation(normalizedClause);
		}
		String suffix = normalizedClause.substring(coreIndex + core.length());
		boolean locallyNegated = startsWithLocalNegation(suffix);
		return expectsNegation == locallyNegated;
	}

	private static List<String> clauses(String answer) {
		String separated = answer;
		for (String separator : CLAUSE_SEPARATORS) {
			separated = separated.replace(separator, ".");
		}
		return java.util.Arrays.stream(separated.split("(?:[!?\\r\\n]+|\\.\\s*(?=[^0-9\\s]|$))"))
			.map(String::trim)
			.filter(value -> !value.isBlank())
			.toList();
	}

	private static int firstNegationIndex(String value) {
		return GRAMMATICAL_NEGATIONS.stream()
			.mapToInt(value::indexOf)
			.filter(index -> index >= 0)
			.min()
			.orElse(-1);
	}

	private static boolean containsNegation(String value) {
		return GRAMMATICAL_NEGATIONS.stream().anyMatch(value::contains);
	}

	private static boolean isPureNegation(String value) {
		return GRAMMATICAL_NEGATIONS.stream().anyMatch(value::startsWith);
	}

	private static String stripTrailingParticle(String value) {
		for (String suffix : List.of("이라고", "으로", "라고", "은", "는", "이", "가")) {
			if (value.endsWith(suffix) && value.length() > suffix.length()) {
				return value.substring(0, value.length() - suffix.length());
			}
		}
		return value;
	}

	private static boolean startsWithLocalNegation(String suffix) {
		return suffix.matches(
			"^(?:(?:이라고볼수|라고볼수|이라고|라고|이라는것은|라는것은|인것은|은|는|이|가|도|만))?"
				+ "(?:(?:절대|전혀|반드시))?"
				+ "(?:아니|않|없|불가).*"
		);
	}
}
