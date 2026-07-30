package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiEvalResponse(
	int total,
	int passed,
	int failed,
	double passRate,
	boolean gatePassed,
	int minimumPassed,
	List<String> blockingFailureIds,
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
		List<String> matchedTitleTerms,
		List<String> missingTitleTerms,
		List<String> matchedSectionTypes,
		List<String> missingSectionTypes,
		List<String> matchedDocumentTerms,
		List<String> missingDocumentTerms,
		List<String> matchedPageNumbers,
		List<String> missingPageNumbers,
		List<String> matchedParentTerms,
		List<String> missingParentTerms,
		List<String> forbiddenMatchedTerms,
		List<String> topMatchedTerms,
		String answerDirection,
		List<String> expectedResultMsgs,
		String resultMsg,
		String message,
		List<LawAiDebugResponse.Item> selected,
		boolean answerVerificationRequired,
		boolean answerVerified,
		List<String> matchedAnswerTerms,
		List<String> missingAnswerTerms,
		List<String> forbiddenAnswerMatchedTerms,
		List<String> unsupportedAnswerClaims,
		List<String> contradictedAnswerClaims,
		List<ClaimVerifier.ClaimEvidenceLink> claimEvidenceLinks,
		String verifiedAnswer
	) {
	}
}
