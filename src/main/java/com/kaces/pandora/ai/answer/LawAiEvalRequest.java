package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiEvalRequest(
	List<EvalCase> cases,
	List<String> caseIds,
	Integer maxCases
) {
	public record EvalCase(
		String id,
		String question,
		List<String> targets,
		List<String> expectedTerms,
		Integer requiredMatches,
		List<String> expectedTitleTerms,
		List<String> expectedSectionTypes,
		List<String> forbiddenTerms,
		List<String> expectedDocumentTerms,
		List<String> expectedPageNumbers,
		List<String> expectedParentTerms,
		String answerDirection,
		List<String> expectedResultMsgs,
		Boolean answerVerificationRequired,
		List<String> expectedAnswerTerms,
		List<String> forbiddenAnswerTerms
	) {
		public EvalCase(
			String id,
			String question,
			List<String> targets,
			List<String> expectedTerms,
			Integer requiredMatches,
			List<String> expectedTitleTerms,
			List<String> expectedSectionTypes,
			List<String> forbiddenTerms,
			List<String> expectedDocumentTerms,
			List<String> expectedPageNumbers,
			List<String> expectedParentTerms,
			String answerDirection
		) {
			this(
				id,
				question,
				targets,
				expectedTerms,
				requiredMatches,
				expectedTitleTerms,
				expectedSectionTypes,
				forbiddenTerms,
				expectedDocumentTerms,
				expectedPageNumbers,
				expectedParentTerms,
				answerDirection,
				List.of(),
				null,
				List.of(),
				List.of()
			);
		}

		public EvalCase(
			String id,
			String question,
			List<String> targets,
			List<String> expectedTerms,
			Integer requiredMatches,
			List<String> expectedTitleTerms,
			List<String> expectedSectionTypes,
			List<String> forbiddenTerms,
			List<String> expectedDocumentTerms,
			List<String> expectedPageNumbers,
			List<String> expectedParentTerms,
			String answerDirection,
			List<String> expectedResultMsgs
		) {
			this(
				id,
				question,
				targets,
				expectedTerms,
				requiredMatches,
				expectedTitleTerms,
				expectedSectionTypes,
				forbiddenTerms,
				expectedDocumentTerms,
				expectedPageNumbers,
				expectedParentTerms,
				answerDirection,
				expectedResultMsgs,
				null,
				List.of(),
				List.of()
			);
		}
	}
}
