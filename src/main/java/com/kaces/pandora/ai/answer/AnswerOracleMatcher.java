package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.List;

final class AnswerOracleMatcher {

	private AnswerOracleMatcher() {
	}

	static Result evaluate(String answer, LawAiEvalRequest.EvalCase evalCase) {
		List<List<String>> propositions = groups(evalCase == null ? null : evalCase.requiredPropositionGroups());
		List<List<String>> conditions = groups(evalCase == null ? null : evalCase.requiredConditionGroups());
		List<String> forbidden = terms(evalCase == null ? null : evalCase.forbiddenAnswerTerms());
		List<String> matched = new ArrayList<>();
		List<String> missingPropositions = missingGroups(answer, propositions, matched);
		List<String> missingConditions = missingGroups(answer, conditions, matched);
		List<String> forbiddenMatched = forbidden.stream()
			.filter(expression -> ExplicitOracleTermMatcher.matches(answer, expression))
			.toList();
		boolean passed = missingPropositions.isEmpty()
			&& missingConditions.isEmpty()
			&& forbiddenMatched.isEmpty();
		return new Result(
			passed,
			List.copyOf(matched),
			missingPropositions,
			missingConditions,
			forbiddenMatched,
			failureMessage(missingPropositions, missingConditions, forbiddenMatched)
		);
	}

	private static List<String> missingGroups(
		String answer,
		List<List<String>> groups,
		List<String> matched
	) {
		List<String> missing = new ArrayList<>();
		for (List<String> group : groups) {
			String matchingAlias = group.stream()
				.filter(alias -> ExplicitOracleTermMatcher.matches(answer, alias))
				.findFirst()
				.orElse(null);
			if (matchingAlias == null) {
				missing.add(String.join("|", group));
			}
			else {
				matched.add(matchingAlias);
			}
		}
		return List.copyOf(missing);
	}

	private static String failureMessage(
		List<String> missingPropositions,
		List<String> missingConditions,
		List<String> forbiddenMatched
	) {
		List<String> messages = new ArrayList<>();
		if (!missingPropositions.isEmpty()) {
			messages.add("missing proposition groups=" + String.join(";", missingPropositions));
		}
		if (!missingConditions.isEmpty()) {
			messages.add("missing condition groups=" + String.join(";", missingConditions));
		}
		if (!forbiddenMatched.isEmpty()) {
			messages.add("matched forbidden expressions=" + String.join("|", forbiddenMatched));
		}
		return String.join("; ", messages);
	}

	private static List<List<String>> groups(List<List<String>> groups) {
		return groups == null ? List.of() : groups;
	}

	private static List<String> terms(List<String> terms) {
		return terms == null ? List.of() : terms;
	}

	record Result(
		boolean passed,
		List<String> matchedExpressions,
		List<String> missingPropositionGroups,
		List<String> missingConditionGroups,
		List<String> forbiddenMatchedExpressions,
		String message
	) {
	}
}
