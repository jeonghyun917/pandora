package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerQuestionAlignmentVerifierTests {

	private final AnswerQuestionAlignmentVerifier verifier = new AnswerQuestionAlignmentVerifier();

	@Test
	void rejectsSupportedSideExceptionThatDoesNotAddressTheQuestionSubject() {
		ClaimVerifier.VerificationResult claimResult = claimResult(
			supported(
				"단순 H/W 도입·설치는 소프트웨어사업으로 볼 수 없어 과업심의 비대상입니다.",
				"단순 H/W 도입·설치는 소프트웨어사업으로 볼 수 없는 경우입니다."
			),
			link(
				"SNS운영 사업은 과업심의 대상입니다.",
				"CONTRADICTED",
				"SNS운영 사업은 과업심의 대상입니다."
			)
		);

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"SNS운영 사업도 과업심의 받아야해?",
			claimResult
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_SUBJECT");
		assertThat(result.missingGroups()).contains("SUBJECT");
	}

	@Test
	void rejectsTargetInstitutionClaimWhenQuestionAsksWhenPreConsultationIsRequired() {
		ClaimVerifier.VerificationResult claimResult = claimResult(supported(
			"중앙행정기관과 공공기관은 사전협의 대상기관입니다.",
			"사전협의 대상기관은 중앙행정기관과 공공기관입니다."
		));

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"정보화사업 사전협의는 언제 해야 하나?",
			claimResult
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_RELATION");
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void rejectsGenericObligationThatOmitsAnExplicitQuestionCondition() {
		ClaimVerifier.VerificationResult claimResult = claimResult(supported(
			"정보화사업은 사전협의를 해야 합니다.",
			"정보화사업은 예산 확정 전에 사전협의를 해야 합니다."
		));

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"예산 확정 전에 정보화사업 사전협의를 해야 하나?",
			claimResult
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_CONDITION");
		assertThat(result.missingGroups()).contains("CONDITION");
	}

	@Test
	void rejectsBackgroundClaimEvenWhenEvidenceContainsTheRequestedConclusion() {
		ClaimVerifier.VerificationResult claimResult = claimResult(supported(
			"가명정보는 보호 대상입니다.",
			"가명정보의 추가정보는 가명정보와 분리하여 보관해야 합니다."
		));

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"가명정보의 추가정보는 별도로 보관해야 하나?",
			claimResult
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_DIRECT_CONCLUSION");
		assertThat(result.missingGroups()).contains("RELATION", "DIRECT_CONCLUSION");
	}

	@Test
	void acceptsSupportedClaimThatCoversSubjectRelationAndExplicitCondition() {
		String claim = "정보화사업은 예산 확정 전에 사전협의를 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"예산 확정 전에 정보화사업 사전협의를 해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isTrue();
		assertThat(result.reasonCode()).isEqualTo("ALIGNED");
		assertThat(result.missingGroups()).isEmpty();
		assertThat(result.matchedClaim()).isEqualTo(claim);
	}

	@Test
	void failsClosedWhenQuestionHasNoUsableAlignmentProfile() {
		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"?",
			claimResult(supported("사전협의를 해야 합니다.", "사전협의를 해야 합니다."))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("QUESTION_PROFILE_EMPTY");
		assertThat(result.missingGroups()).contains("SUBJECT", "RELATION");
	}

	private ClaimVerifier.VerificationResult claimResult(ClaimVerifier.ClaimEvidenceLink... links) {
		List<ClaimVerifier.ClaimEvidenceLink> evidenceLinks = List.of(links);
		return new ClaimVerifier.VerificationResult(
			evidenceLinks.isEmpty() ? "" : evidenceLinks.get(0).claim(),
			false,
			false,
			List.of(),
			List.of(),
			List.of(),
			evidenceLinks,
			evidenceLinks.size(),
			(int) evidenceLinks.stream().filter(link -> "SUPPORTED".equals(link.relation())).count()
		);
	}

	private ClaimVerifier.ClaimEvidenceLink supported(String claim, String evidenceSentence) {
		return link(claim, "SUPPORTED", evidenceSentence);
	}

	private ClaimVerifier.ClaimEvidenceLink link(String claim, String relation, String evidenceSentence) {
		return new ClaimVerifier.ClaimEvidenceLink(claim, relation, 1, evidenceSentence, 3, 0.9, 0.9);
	}
}
