package com.kaces.pandora.ai.answer;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

public record LawAiEvalResponse(
	int total,
	int passed,
	int failed,
	double passRate,
	boolean gatePassed,
	int minimumPassed,
	List<String> blockingFailureIds,
	int semanticShadowDisagreementCount,
	int unsafeSemanticShadowDisagreementCount,
	List<CaseResult> results
) {
	public record AttributedClaim(ClaimVerifier.ClaimEvidenceLink link, String target, long documentId, long chunkId) {}

	static List<AttributedClaim> attributeClaims(
		List<ClaimVerifier.ClaimEvidenceLink> links, List<LawAiAnswerGround> grounds
	) {
		if (links.isEmpty()) {
			return List.of();
		}
		var byNumber = new HashMap<Integer, LawAiAnswerGround>();
		var ambiguous = new HashSet<Integer>();
		for (var ground : grounds) {
			if (ground.number() > 0 && byNumber.putIfAbsent(ground.number(), ground) != null) {
				ambiguous.add(ground.number());
			}
		}
		return links.stream()
			.filter(link -> byNumber.containsKey(link.groundNumber()) && !ambiguous.contains(link.groundNumber()))
			.map(link -> {
				var ground = byNumber.get(link.groundNumber());
				return new AttributedClaim(link, ground.target(), ground.documentId(), ground.chunkId());
			})
			.toList();
	}

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
		List<AttributedClaim> attributedClaims,
		List<ClaimMatcherShadowResult> semanticShadowDisagreements,
		int unsafeSemanticShadowDisagreementCount,
		String verifiedAnswer,
		EvaluationVerificationEnvelope verificationEnvelope
	) {
	}
}
