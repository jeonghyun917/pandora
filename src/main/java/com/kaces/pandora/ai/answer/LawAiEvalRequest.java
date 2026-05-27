package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiEvalRequest(
	List<EvalCase> cases
) {
	public record EvalCase(
		String id,
		String question,
		List<String> targets,
		List<String> expectedTerms,
		Integer requiredMatches
	) {
	}
}
