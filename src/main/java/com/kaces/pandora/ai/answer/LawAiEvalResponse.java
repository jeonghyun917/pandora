package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiEvalResponse(
	int total,
	int passed,
	int failed,
	List<CaseResult> results
) {
	public record CaseResult(
		String id,
		String question,
		List<String> targets,
		boolean passed,
		int requiredMatches,
		List<String> matchedTerms,
		List<String> missingTerms,
		List<String> topMatchedTerms,
		String resultMsg,
		String message,
		List<LawAiDebugResponse.Item> selected
	) {
	}
}
