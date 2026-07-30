package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Adds material-token and local-polarity checks to the legacy fuzzy matcher for explicit answer oracles.
 * Retrieval evaluation keeps using {@link EvaluationTermMatcher} unchanged.
 */
final class ExplicitOracleTermMatcher {

	private static final List<String> GRAMMATICAL_NEGATIONS = List.of(
		"안됩니다", "안된다", "안됨",
		"아닙", "아니", "않", "없", "불가"
	);
	private static final List<String> CLAUSE_SEPARATORS = List.of("하지만", "그러나", "반면", "다만");
	private static final List<String> LOCAL_POLARITY_BRIDGES = List.of(
		"이라고는", "라고는", "이라고", "라는", "라고", "다고",
		"한다면", "하면", "할경우", "하는경우", "한경우", "했을경우",
		"단정해서는", "단정할수", "말할수", "볼수", "할수", "해서는",
		"이라는것은", "라는것은", "인것은", "것은",
		"반드시", "절대", "전혀",
		"으로", "은", "는", "이", "가", "도", "만", "지"
	);
	private static final Pattern SUPERSEDED_BY_FINAL_ASSERTION = Pattern.compile(
		"(?s)(^|[.!?\\r\\n])\\s*[^.!?\\r\\n]*?(?:하지만|그러나|반면|지만|으나)"
			+ "[,:;\\s]*(?=(?:실제로(?:는)?|사실상|사실은|결론적으로|결국|오히려))"
	);

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
		List<String> materialTokens = materialTokens(expectedExpression);
		return !materialTokens.isEmpty() && materialTokens.stream().allMatch(normalizedClause::contains);
	}

	private static List<String> materialTokens(String expectedExpression) {
		return java.util.Arrays.stream(expectedExpression.split("[^\\p{L}\\p{N}]+"))
			.map(KoreanQueryNormalizer::normalizeForMatch)
			.map(ExplicitOracleTermMatcher::stripTrailingParticle)
			.filter(token -> token.length() >= 2)
			.filter(token -> !isPureNegation(token))
			.toList();
	}

	private static boolean hasCompatiblePolarity(String clause, String expectedExpression) {
		String normalizedClause = KoreanQueryNormalizer.normalizeForMatch(clause);
		String normalizedExpected = KoreanQueryNormalizer.normalizeForMatch(expectedExpression);
		int expectedNegation = firstNegationIndex(normalizedExpected);
		String core = expectedNegation >= 0
			? stripTrailingParticle(normalizedExpected.substring(0, expectedNegation))
			: normalizedExpected;
		if (core.isBlank()) {
			return false;
		}
		int coreIndex = normalizedClause.indexOf(core);
		if (coreIndex < 0) {
			if (expectedNegation >= 0) {
				return false;
			}
			List<String> tokens = materialTokens(expectedExpression);
			if (tokens.isEmpty()) {
				return false;
			}
			String predicateAnchor = tokens.get(tokens.size() - 1);
			int anchorIndex = normalizedClause.lastIndexOf(predicateAnchor);
			return anchorIndex >= 0
				&& localNegationParity(normalizedClause.substring(anchorIndex + predicateAnchor.length())) == 0;
		}
		String expectedSuffix = normalizedExpected.substring(core.length());
		String answerSuffix = normalizedClause.substring(coreIndex + core.length());
		return localNegationParity(expectedSuffix) == localNegationParity(answerSuffix);
	}

	private static List<String> clauses(String answer) {
		String separated = SUPERSEDED_BY_FINAL_ASSERTION.matcher(answer).replaceAll("$1");
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

	private static boolean isPureNegation(String value) {
		return GRAMMATICAL_NEGATIONS.stream().anyMatch(value::startsWith);
	}

	private static int localNegationParity(String suffix) {
		String remaining = suffix;
		int negations = 0;
		for (int steps = 0; steps < 24 && !remaining.isBlank(); steps++) {
			String marker = startsWith(remaining, GRAMMATICAL_NEGATIONS);
			if (marker != null) {
				negations++;
				remaining = remaining.substring(marker.length());
				continue;
			}
			String bridge = startsWith(remaining, LOCAL_POLARITY_BRIDGES);
			if (bridge != null) {
				remaining = remaining.substring(bridge.length());
				continue;
			}
			break;
		}
		return negations % 2;
	}

	private static String startsWith(String value, List<String> prefixes) {
		return prefixes.stream()
			.filter(value::startsWith)
			.findFirst()
			.orElse(null);
	}

	private static String stripTrailingParticle(String value) {
		for (String suffix : List.of("이라고", "으로", "라고", "은", "는", "이", "가")) {
			if (value.endsWith(suffix) && value.length() > suffix.length()) {
				return value.substring(0, value.length() - suffix.length());
			}
		}
		return value;
	}

}
