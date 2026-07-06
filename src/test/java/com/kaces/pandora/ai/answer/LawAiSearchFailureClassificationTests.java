package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawAiSearchFailureClassificationTests {

	@Test
	void classifiesSearchNoCandidateAsRetryableEvalCandidate() {
		LawAiSearchFailureClassification classification = LawAiSearchFailureClassification.classify(
			"NO_GROUNDS",
			"후보 문서가 0건입니다.",
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			"empty"
		);

		assertThat(classification.failureType()).isEqualTo("SEARCH_NO_CANDIDATE");
		assertThat(classification.failureStage()).isEqualTo("RETRIEVAL");
		assertThat(classification.retryable()).isTrue();
		assertThat(classification.evalCandidate()).isTrue();
	}

	@Test
	void classifiesQdrantDbMappingMismatchAsOperationalIssue() {
		LawAiSearchFailureClassification classification = LawAiSearchFailureClassification.classify(
			"NO_GROUNDS",
			"Qdrant 후보는 있었지만 DB chunk 본문 조회가 0건입니다.",
			10,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			"empty"
		);

		assertThat(classification.failureType()).isEqualTo("INDEX_DB_MAPPING_MISMATCH");
		assertThat(classification.failureStage()).isEqualTo("DB_LOOKUP");
		assertThat(classification.retryable()).isFalse();
		assertThat(classification.evalCandidate()).isFalse();
	}

	@Test
	void classifiesJudgeDirectEvidenceFailure() {
		LawAiSearchFailureClassification classification = LawAiSearchFailureClassification.classify(
			"NO_GROUNDS",
			"Evidence Judge가 질문에 직접 답하는 근거를 확정하지 못했습니다.",
			50,
			40,
			20,
			60,
			60,
			30,
			30,
			0,
			0,
			12,
			8,
			0,
			"relevant"
		);

		assertThat(classification.failureType()).isEqualTo("JUDGE_NO_DIRECT_EVIDENCE");
		assertThat(classification.failureStage()).isEqualTo("EVIDENCE_JUDGE");
		assertThat(classification.evalCandidate()).isTrue();
	}

	@Test
	void classifiesIntentFilterDropAsDictionaryOrIntentGap() {
		LawAiSearchFailureClassification classification = LawAiSearchFailureClassification.classify(
			"NO_GROUNDS",
			"질문 의도 필터에서 0건이 됐습니다.",
			50,
			40,
			20,
			60,
			60,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			"empty"
		);

		assertThat(classification.failureType()).isEqualTo("DICTIONARY_OR_INTENT_GAP");
		assertThat(classification.failureStage()).isEqualTo("INTENT_FILTER");
		assertThat(classification.retryable()).isTrue();
		assertThat(classification.evalCandidate()).isTrue();
	}

	@Test
	void exposesClaimVerifierFailure() {
		LawAiSearchFailureClassification classification = LawAiSearchFailureClassification.claimUnsupported();

		assertThat(classification.failureType()).isEqualTo("ANSWER_CLAIM_UNSUPPORTED");
		assertThat(classification.failureStage()).isEqualTo("ANSWER_VERIFICATION");
		assertThat(classification.retryable()).isTrue();
		assertThat(classification.evalCandidate()).isTrue();
	}
}
