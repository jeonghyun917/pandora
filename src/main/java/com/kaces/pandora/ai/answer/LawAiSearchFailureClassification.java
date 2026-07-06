package com.kaces.pandora.ai.answer;

public record LawAiSearchFailureClassification(
	String failureType,
	String failureStage,
	boolean retryable,
	boolean evalCandidate
) {
	private static final LawAiSearchFailureClassification NONE =
		new LawAiSearchFailureClassification("NONE", "NONE", false, false);

	static LawAiSearchFailureClassification none() {
		return NONE;
	}

	static LawAiSearchFailureClassification claimUnsupported() {
		return new LawAiSearchFailureClassification("ANSWER_CLAIM_UNSUPPORTED", "ANSWER_VERIFICATION", true, true);
	}

	static LawAiSearchFailureClassification classify(
		String resultMsg,
		String diagnosticMessage,
		int qdrantHitCount,
		int vectorChunkCount,
		int lexicalChunkCount,
		int mergedCount,
		int rankedCount,
		int intentFilteredCount,
		int judgeCandidateCount,
		int judgedCount,
		int finalGroundCount,
		int topicAlignedCount,
		int relevantCount,
		int directEvidenceCount,
		String evidenceSelectionPolicy
	) {
		if ("OK".equals(resultMsg) && finalGroundCount > 0) {
			return none();
		}
		String diagnostic = diagnosticMessage == null ? "" : diagnosticMessage;
		if (diagnostic.contains("운영 내부 상태")) {
			return new LawAiSearchFailureClassification("UNSUPPORTED_OPERATIONAL_STATUS", "PRECHECK", false, false);
		}
		if (diagnostic.contains("근거를 만들어내") || diagnostic.contains("문서를 있다고 말")) {
			return new LawAiSearchFailureClassification("UNSUPPORTED_FABRICATION_REQUEST", "PRECHECK", false, false);
		}
		if (qdrantHitCount > 0 && vectorChunkCount == 0 && lexicalChunkCount == 0) {
			return new LawAiSearchFailureClassification("INDEX_DB_MAPPING_MISMATCH", "DB_LOOKUP", false, false);
		}
		if (mergedCount == 0 || (qdrantHitCount == 0 && lexicalChunkCount == 0)) {
			return new LawAiSearchFailureClassification("SEARCH_NO_CANDIDATE", "RETRIEVAL", true, true);
		}
		if (rankedCount == 0) {
			return new LawAiSearchFailureClassification("RANKING_DROPPED_ALL", "RERANK", true, true);
		}
		if (intentFilteredCount == 0) {
			return new LawAiSearchFailureClassification("DICTIONARY_OR_INTENT_GAP", "INTENT_FILTER", true, true);
		}
		if (judgeCandidateCount == 0) {
			return new LawAiSearchFailureClassification("JUDGE_CANDIDATE_EMPTY", "EVIDENCE_JUDGE", true, true);
		}
		if (judgedCount == 0) {
			if (directEvidenceCount == 0 && (topicAlignedCount > 0 || relevantCount > 0 || mentionsDirectEvidence(diagnostic))) {
				return new LawAiSearchFailureClassification("JUDGE_NO_DIRECT_EVIDENCE", "EVIDENCE_JUDGE", true, true);
			}
			if (diagnostic.contains("핵심 개념")) {
				return new LawAiSearchFailureClassification("JUDGE_NO_CONCEPT_EVIDENCE", "EVIDENCE_JUDGE", true, true);
			}
			return new LawAiSearchFailureClassification("JUDGE_REJECTED_ALL", "EVIDENCE_JUDGE", true, true);
		}
		if (finalGroundCount == 0 && judgedCount > 0) {
			return new LawAiSearchFailureClassification("GROUND_TEXT_FILTERED", "GROUND_BUILD", true, true);
		}
		if ("NO_GROUNDS".equals(resultMsg)) {
			return new LawAiSearchFailureClassification("NO_GROUNDS_UNCLASSIFIED", stageFromPolicy(evidenceSelectionPolicy), true, true);
		}
		return new LawAiSearchFailureClassification("UNKNOWN", "UNKNOWN", true, true);
	}

	private static boolean mentionsDirectEvidence(String diagnostic) {
		return diagnostic != null && (diagnostic.contains("직접근거") || diagnostic.contains("직접 답"));
	}

	private static String stageFromPolicy(String evidenceSelectionPolicy) {
		if (evidenceSelectionPolicy == null || evidenceSelectionPolicy.isBlank() || "empty".equals(evidenceSelectionPolicy)) {
			return "RETRIEVAL";
		}
		return "EVIDENCE_JUDGE";
	}
}
