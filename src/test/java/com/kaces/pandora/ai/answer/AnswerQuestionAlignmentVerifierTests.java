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
			"정보화사업 사전협의 대상기관은 중앙행정기관과 공공기관입니다.",
			"정보화사업 사전협의 대상기관은 중앙행정기관과 공공기관입니다."
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
	void rejectsPeriodWordsThatBelongToBackgroundInsteadOfTheAnswerPredicate() {
		String claim = "정보화사업 사전협의 시기와 별개로 정보화사업 사전협의 대상 기관은 중앙행정기관입니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"정보화사업 사전협의는 언제 해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_RELATION");
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void rejectsQuestionSubjectThatAppearsOnlyBeforeTheTerminalProposition() {
		String claim = "가명정보와 무관하게 일반정보는 별도 보관해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"가명정보의 추가정보는 별도 보관해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_SUBJECT");
		assertThat(result.missingGroups()).contains("SUBJECT");
	}

	@Test
	void rejectsSubjectAndPredicateSplitAcrossCoordinatedAtoms() {
		String claim = "가명정보의 추가정보는 검토하고 일반정보는 별도 보관해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"가명정보의 추가정보는 별도 보관해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).containsAnyOf("SUBJECT", "RELATION");
	}

	@Test
	void rejectsQuestionConditionThatAppearsOnlyBeforeTheTerminalProposition() {
		String claim = "예산 확정 전에 검토하는 것과 별개로 정보화사업은 사전협의를 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"예산 확정 전에 정보화사업 사전협의를 해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_CONDITION");
		assertThat(result.missingGroups()).contains("CONDITION");
	}

	@Test
	void rejectsConditionAndPredicateSplitAcrossCoordinatedAtoms() {
		String claim = "예산 확정 전에는 준비 절차를 진행하고 정보화사업 사전협의는 계약 이후에 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"예산 확정 전에 정보화사업 사전협의를 해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_CONDITION");
		assertThat(result.missingGroups()).contains("CONDITION");
	}

	@Test
	void doesNotTreatWonSyllableInsideSupportAsAnAmountAnswer() {
		String claim = "청년 창업 지원 사업은 신청 대상입니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"청년 창업 지원 금액은 얼마인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_RELATION");
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void rejectsAmountTopicWithoutAnActualAmountValue() {
		String claim = "지원 금액은 별도 안내이고 신청 대상은 청년입니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"청년 지원 금액은 얼마인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void rejectsCurrencyUnitEmbeddedInsideANonAmountWord() {
		String claim = "청년 지원 사업은 3원칙을 적용합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"청년 지원 금액은 얼마인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void acceptsNumericAmountInTheTerminalProposition() {
		String claim = "청년 지원 금액은 300만원입니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"청년 지원 금액은 얼마인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void rejectsPeriodTopicWithoutAnActualPeriodValue() {
		String claim = "사전협의 시기는 별도 안내이고 대상 기관은 협의를 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"사전협의 시기는 언제인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void rejectsNonTemporalRoleFollowedByFromParticle() {
		String claim = "사전협의는 담당자부터 확인해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"사전협의 시기는 언제인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void rejectsLegalCitationFollowedByUntilParticle() {
		String claim = "사전협의는 제10조까지 적용합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"사전협의 시기는 언제인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void acceptsExplicitRelativePeriodInTheTerminalProposition() {
		String claim = "사전협의는 예산 확정 전에 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"사전협의 시기는 언제인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
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
			"가명정보의 추가정보는 보호 대상입니다.",
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
	void requiresEveryExplicitCompoundEntityComponent() {
		String claim = "가명정보는 별도 보관해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"가명정보의 추가정보는 별도 보관해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_SUBJECT");
		assertThat(result.missingGroups()).contains("SUBJECT");
	}

	@Test
	void acceptsDirectCompoundEntityConclusion() {
		String claim = "가명정보의 추가정보는 별도 보관해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"가명정보의 추가정보는 별도 보관해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void rejectsConfiguredPrivacyNoticeIntentMissingFromTheConclusion() {
		String claim = "온라인 서비스 개인정보 처리방침은 별도로 보관해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"온라인 서비스 개인정보 처리방침을 고지해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void acceptsConfiguredPrivacyNoticeIntentInTheConclusion() {
		String claim = "온라인 서비스 개인정보 처리방침을 공개하여 고지해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"온라인 서비스 개인정보 처리방침을 고지해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void acceptsSupportedClaimThatCoversSubjectRelationAndExplicitCondition() {
		String claim = "정보화사업은 예산 확정 전에 사전협의를 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"예산 확정 전에 정보화사업 사전협의를 해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
		assertThat(result.reasonCode()).isEqualTo("ALIGNED");
		assertThat(result.missingGroups()).isEmpty();
		assertThat(result.matchedClaim()).isEqualTo(claim);
	}

	@Test
	void requiresEveryExplicitConditionInTheQuestion() {
		String claim = "정보화사업은 예산 확정 전에 사전협의를 해야 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"예산 확정 전에 그리고 계약 체결 후에 정보화사업 사전협의를 해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_CONDITION");
		assertThat(result.missingGroups()).contains("CONDITION");
	}

	@Test
	void configuredEntityAnchorsOverrideBroadRecallAliasesForFinalAnswers() {
		String claim = "지능정보사회 실행계획은 정보화사업을 대상으로 합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"지능정보사회 실행계획의 예비검토는 어떤 사업을 대상으로 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_SUBJECT");
		assertThat(result.missingGroups()).contains("SUBJECT");
	}

	@Test
	void acceptsDirectStatutoryObligationFormulation() {
		String claim = "정보화사업자는 자료를 제출하여야 한다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"정보화사업자는 자료를 제출해야 하나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
		assertThat(result.reasonCode()).isEqualTo("ALIGNED");
	}

	@Test
	void acceptsDirectStatutoryPermissionFormulation() {
		String claim = "신청인은 처리 결과 자료를 열람할 수 있다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"신청인은 처리 결과 자료를 열람할 수 있나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).isTrue();
		assertThat(result.reasonCode()).isEqualTo("ALIGNED");
	}

	@Test
	void acceptsDirectNounFormContainingRequestedSubjectAndRelation() {
		String claim = "공공소프트웨어사업 과업심의 대상";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"공공소프트웨어사업은 과업심의 대상인가?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
		assertThat(result.reasonCode()).isEqualTo("ALIGNED");
	}

	@Test
	void acceptsNaturalClassificationQuestionWithDirectClassificationEvidence() {
		String claim =
			"신청인의 회사 이메일 주소는 다른 정보와 쉽게 결합하여 신청인을 알아볼 수 있는 정보로서 "
				+ "개인정보에 해당한다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"이메일 만으로도 개인정보라고 볼 수 있나?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
		assertThat(result.reasonCode()).isEqualTo("ALIGNED");
	}

	@Test
	void acceptsEquivalentNaturalClassificationForms() {
		String claim = "이메일 주소는 다른 정보와 쉽게 결합하여 개인을 알아볼 수 있으면 개인정보에 해당한다.";

		for (String question : List.of(
			"이메일 주소를 개인정보로 볼 수 있나?",
			"이메일 주소는 개인정보인가?",
			"이메일 주소는 개인정보에 해당하나?"
		)) {
			AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
				question,
				claimResult(supported(claim, claim))
			);

			assertThat(result.aligned()).as("question=%s result=%s", question, result).isTrue();
		}
	}

	@Test
	void rejectsClassificationEvidenceThatOmitsTheQuestionSubject() {
		String unrelatedClaim = "주민등록번호는 개인정보에 해당한다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"이메일 주소를 개인정보로 볼 수 있나?",
			claimResult(supported(unrelatedClaim, unrelatedClaim))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.missingGroups()).contains("SUBJECT");
	}

	@Test
	void acceptsMetaPredicatesWhenTheyAreTheRequestedLexicalRelation() {
		for (String predicate : List.of("검토", "확인", "조사")) {
			String claim = "기관은 신청서를 " + predicate + "합니다.";

			AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
				"기관은 신청서를 " + predicate + "하나요?",
				claimResult(supported(claim, claim))
			);

			assertThat(result.aligned()).as("predicate=%s result=%s", predicate, result).isTrue();
			assertThat(result.reasonCode()).as(predicate).isEqualTo("ALIGNED");
		}
	}

	@Test
	void doesNotTreatMetaPredicateStemInNounModifierAsRequestedAction() {
		String claim = "공공소프트웨어사업은 검토 대상인지 검토합니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"공공소프트웨어사업은 검토 대상인가?",
			claimResult(supported(claim, "공공소프트웨어사업은 검토 대상입니다."))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.missingGroups()).contains("DIRECT_CONCLUSION");
	}

	@Test
	void rejectsMetaPredicatesThatOnlyDescribeCheckingTheQuestion() {
		for (String claim : List.of(
			"공공소프트웨어사업은 과업심의 대상인지 확인합니다.",
			"공공소프트웨어사업은 과업심의 대상인지 검토합니다.",
			"공공소프트웨어사업은 과업심의 대상인지 문의합니다.",
			"공공소프트웨어사업은 과업심의 대상인지 질문합니다.",
			"공공소프트웨어사업은 과업심의 대상인지 알아봅니다."
		)) {
			AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
				"공공소프트웨어사업은 과업심의 대상인가?",
				claimResult(supported(claim, "공공소프트웨어사업은 과업심의 대상입니다."))
			);

			assertThat(result.aligned()).as("claim=%s result=%s", claim, result).isFalse();
			assertThat(result.missingGroups()).as(claim).contains("DIRECT_CONCLUSION");
		}
	}

	@Test
	void rejectsInterrogativeAndBareWhetherRestatements() {
		for (String claim : List.of(
			"공공소프트웨어사업은 과업심의 대상?",
			"공공소프트웨어사업은 과업심의 대상인가?",
			"공공소프트웨어사업은 과업심의 대상인가요?",
			"공공소프트웨어사업은 과업심의 대상인지"
		)) {
			AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
				"공공소프트웨어사업은 과업심의 대상인가?",
				claimResult(supported(claim, "공공소프트웨어사업은 과업심의 대상입니다."))
			);

			assertThat(result.aligned()).as("claim=%s result=%s", claim, result).isFalse();
			assertThat(result.missingGroups()).as(claim).contains("DIRECT_CONCLUSION");
		}
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
