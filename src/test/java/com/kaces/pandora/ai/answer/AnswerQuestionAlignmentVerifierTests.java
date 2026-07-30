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

	@Test
	void configuredContractProcedureRequiresEveryConfiguredAnswerStage() {
		String claim = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_ANSWER_COVERAGE");
		assertThat(result.missingGroups()).contains("ANSWER_COVERAGE");
	}

	@Test
	void configuredProjectReviewPurchaseScopeRequiresTheRuleAndBoundaryTogether() {
		String question = "단순 소프트웨어 구매면 과업심의 안해도 돼?";
		String rule = "과업심의 적용 대상은 국가기관 등이 발주하는 모든 소프트웨어사업이다.";
		String boundary = "단순 하드웨어 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상이다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult complete = verifier.verify(
			question,
			claimResult(supported(rule, rule), supported(boundary, boundary))
		);
		AnswerQuestionAlignmentVerifier.AlignmentResult incomplete = verifier.verify(
			question,
			claimResult(supported(rule, rule))
		);

		assertThat(complete.aligned()).as(complete.toString()).isTrue();
		assertThat(incomplete.aligned()).isFalse();
		assertThat(incomplete.reasonCode()).isNotEqualTo("ALIGNED");
	}

	@Test
	void configuredPreConsultationTargetScopeRequiresInstitutionAndProjectScopeTogether() {
		String question = "기타공공기관 사전협의 대상 알려줘";
		String institution = "사전협의 대상기관에는 공공기관이 포함된다.";
		String projectScope = "사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업이다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult complete = verifier.verify(
			question,
			claimResult(supported(institution, institution), supported(projectScope, projectScope))
		);
		AnswerQuestionAlignmentVerifier.AlignmentResult incomplete = verifier.verify(
			question,
			claimResult(supported(institution, institution))
		);

		assertThat(complete.aligned()).as(complete.toString()).isTrue();
		assertThat(incomplete.aligned()).isFalse();
		assertThat(incomplete.reasonCode()).isNotEqualTo("ALIGNED");
	}

	@Test
	void configuredTrafficCrosswalkStopRequiresPedestrianConditionAndStopDutyTogether() {
		String question = "운전중 우회전할때 횡단보도에서 멈춰야 하나?";
		String direct =
			"보행자가 횡단보도를 통행하고 있거나 통행하려고 하는 때에는 "
				+ "횡단보도 앞에서 일시정지하여야 한다.";
		String incomplete = "차량은 횡단보도 앞에서 일시정지하여야 한다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult complete = verifier.verify(
			question,
			claimResult(supported(direct, direct))
		);
		AnswerQuestionAlignmentVerifier.AlignmentResult missingCondition = verifier.verify(
			question,
			claimResult(supported(incomplete, incomplete))
		);

		assertThat(complete.aligned()).as(complete.toString()).isTrue();
		assertThat(missingCondition.aligned()).isFalse();
		assertThat(missingCondition.reasonCode()).isNotEqualTo("ALIGNED");
	}

	@Test
	void configuredCctvPublicPlaceExceptionRequiresPrincipleAndLegalExceptionTogether() {
		String question =
			"개인정보보호위원회 CCTV 안내서에서 공개된 장소에 CCTV를 설치할 수 있는 예외는?";
		String direct =
			"공개된 장소에서 고정형 영상정보처리기기 설치는 원칙적으로 금지되고, "
				+ "법 제25조에서 정하는 사유에 해당하는 경우에만 설치할 수 있다.";
		String incomplete = "공개된 장소에서 고정형 영상정보처리기기 설치는 원칙적으로 금지된다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult complete = verifier.verify(
			question,
			claimResult(supported(direct, direct))
		);
		AnswerQuestionAlignmentVerifier.AlignmentResult missingException = verifier.verify(
			question,
			claimResult(supported(incomplete, incomplete))
		);

		assertThat(complete.aligned()).as(complete.toString()).isTrue();
		assertThat(missingException.aligned()).isFalse();
		assertThat(missingException.reasonCode()).isNotEqualTo("ALIGNED");
	}

	@Test
	void configuredPerformancePeriodRequiresBothPeriodAndAggregation() {
		String period =
			"IRM 성과측정 평가기간은 2025. 12. 17 ~ 2026. 10. 31입니다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"IRM 성과측정은 언제해?",
			claimResult(supported(period, period))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.reasonCode()).isEqualTo("MISSING_ANSWER_COVERAGE");
		assertThat(result.missingGroups()).contains("ANSWER_COVERAGE");
	}

	@Test
	void configuredPerformancePeriodCanCoverPeriodAndAggregationAcrossSupportedClaims() {
		String period =
			"IRM 성과측정 평가기간은 2025. 12. 17 ~ 2026. 10. 31입니다.";
		String aggregation =
			"평가기간 동안 요청 수와 완료 수를 모두 합산하여 산정한다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"IRM 성과측정은 언제해?",
			claimResult(
				supported(period, period),
				supported(aggregation, aggregation)
			)
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void configuredPrivacyConsentNoticeRequiresTheRefusalRight() {
		String partial =
			"개인정보처리자는 수집·이용 동의를 받을 때 다음 사항을 정보주체에게 알려야 한다.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"개인정보 수집 동의 받을 때 거부권도 알려야 해?",
			claimResult(supported(partial, partial))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.missingGroups()).contains("ANSWER_COVERAGE");
	}

	@Test
	void configuredDocumentPurposeCoverageIsADirectSupportedConclusion() {
		String purpose =
			"현장에서 이해하기 쉽도록 개인정보 처리 시 준수해야 하는 사항을 안내할 목적으로 마련되었습니다.";
		LawAiAnswerGround ground = new LawAiAnswerGround(
			1,
			1L,
			1L,
			"official_doc",
			"개인정보 처리 통합 안내서",
			"",
			"",
			"",
			"CURRENT",
			"p.4",
			"발간 목적",
			4,
			purpose,
			"",
			"",
			1.0
		);

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"개인정보 처리 통합 안내서는 왜 만든 거야?",
			claimResult(supported(purpose, purpose)),
			List.of(ground)
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void configuredContractProcedureCanCoverRequiredStagesAcrossSupportedClaims() {
		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918",
			contractProcedureClaimResult()
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void configuredContractPolicyStillRejectsAnUnrelatedContractProposition() {
		String claim = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uACC4\uC57D\uC11C\uC5D0 \uC11C\uBA85\uD574\uC57C \uD55C\uB2E4.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION", "CONDITION");
	}

	@Test
	void configuredContractPolicyDoesNotBridgeAnExplicitDurationValueQuestion() {
		String claim = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";

		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"\uC6A9\uC5ED \uACB0\uACFC\uBCF4\uACE0 \uC81C\uCD9C \uAE30\uD55C\uC740 \uBA70\uCE60\uC778\uAC00?",
			claimResult(supported(claim, claim))
		);

		assertThat(result.aligned()).as(result.toString()).isFalse();
		assertThat(result.missingGroups()).contains("RELATION");
	}

	@Test
	void configuredContractProcedureBridgeRecognizesCompletedWorkConditionEnding() {
		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"\uACFC\uC5C5\uC744 \uBAA8\uB450 \uB9C8\uCCE4\uC73C\uBA74 \uACC4\uC57D\uAE30\uAC04 \uC804 \uC644\uB8CC\uBCF4\uACE0\uAC00 \uAC00\uB2A5\uD55C\uAC00?",
			contractProcedureClaimResult()
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	@Test
	void configuredContractProcedureBridgeRecognizesRemainingWorkContextEnding() {
		AnswerQuestionAlignmentVerifier.AlignmentResult result = verifier.verify(
			"\uC5C5\uBB34\uAC00 \uB0A8\uC544 \uC788\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uC11C\uB9CC \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB098?",
			contractProcedureClaimResult()
		);

		assertThat(result.aligned()).as(result.toString()).isTrue();
	}

	private ClaimVerifier.VerificationResult contractProcedureClaimResult() {
		String completion = "계약상대자는 용역을 완성하였을 때에는 그 사실을 계약담당공무원에게 서면으로 통지하고 필요한 검사를 받아야 한다.";
		String payment = "검사에 합격한 때에는 소정의 절차에 따라 대가지급을 청구할 수 있다.";
		return claimResult(
			supported(completion, completion),
			supported(payment, payment)
		);
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
