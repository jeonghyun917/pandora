package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerOracleMatcherTests {

	@Test
	void requiresEveryPropositionAndConditionGroup() {
		LawAiEvalRequest.EvalCase evalCase = oracleCase(
			List.of(
				List.of("정보자원 등록 필요", "정보자원을 등록해야"),
				List.of("기한 내", "정해진 기한")
			),
			List.of(List.of("등록 요청을 받은 경우", "등록 요청 시")),
			List.of("등록할 필요가 없다")
		);

		AnswerOracleMatcher.Result missing = AnswerOracleMatcher.evaluate(
			"정보자원을 등록해야 합니다.",
			evalCase
		);
		AnswerOracleMatcher.Result complete = AnswerOracleMatcher.evaluate(
			"등록 요청 시 정보자원을 정해진 기한 안에 등록해야 합니다.",
			evalCase
		);

		assertThat(missing.passed()).isFalse();
		assertThat(missing.missingPropositionGroups()).containsExactly("기한 내|정해진 기한");
		assertThat(missing.missingConditionGroups()).containsExactly("등록 요청을 받은 경우|등록 요청 시");
		assertThat(missing.message())
			.contains("missing proposition groups=기한 내|정해진 기한")
			.contains("missing condition groups=등록 요청을 받은 경우|등록 요청 시");
		assertThat(complete.passed()).as(complete.message()).isTrue();
	}

	@Test
	void failsWhenAnyForbiddenExpressionMatches() {
		LawAiEvalRequest.EvalCase evalCase = oracleCase(
			List.of(List.of("분리보관해야", "별도로 보관")),
			List.of(),
			List.of("분리보관할 필요가 없다", "함께 보관해도 된다")
		);

		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"추가정보는 분리보관해야 하지만 함께 보관해도 된다고 볼 수도 있습니다.",
			evalCase
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.forbiddenMatchedExpressions()).containsExactly("함께 보관해도 된다");
		assertThat(result.message()).contains("matched forbidden expressions=함께 보관해도 된다");
	}

	@Test
	void explicitOracleCannotPassViaOneLegacyRetrievalTerm() {
		LawAiEvalRequest.EvalCase evalCase = new LawAiEvalRequest.EvalCase(
			"hardware",
			"단순 하드웨어 사업도 과업심의 대상인가?",
			List.of("official_doc"),
			List.of("과업심의", "단순 H/W"),
			1,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			"비대상 여부를 답한다",
			List.of("OK"),
			true,
			List.of(),
			List.of("과업심의 대상"),
			List.of(List.of("소프트웨어사업으로 볼 수 없는", "비대상")),
			List.of()
		);

		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"이 질문은 과업심의와 관련되어 있습니다.",
			evalCase
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups())
			.containsExactly("소프트웨어사업으로 볼 수 없는|비대상");
	}

	@Test
	void rejectsHardwareExclusionWhenTheExpectedConclusionIsNegated() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"비대상이 아니라 과업심의 대상입니다",
			defaultCase("project-review-hardware-exclusion")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void rejectsRetentionAnswerThatAffirmsTheForbiddenThirtyDayConclusion() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"설치 목적에 따라 정하지만 무조건 30일입니다",
			defaultCase("cctv-retention-not-fixed-30")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void rejectsEnforcementDateAnswerThatNegatesTheExpectedPendingStatus() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"아직 시행예정이 아니라 이미 시행 중입니다. 공식 시행일을 명시해야 합니다",
			defaultCase("ai-law-enforcement-date")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void preservesDottedDatesWhileSplittingAnswerClauses() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"IRM 평가기간은 2025. 12. 17 ~ 2026. 10. 31입니다. 평가기간 중 요청 건수와 완료 건수를 집계합니다.",
			defaultCase("irm-measure-period")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheOfficialPerformanceAggregationWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"평가기간 : 2025. 12. 17 ~ 2026. 10. 31. "
				+ "등록요청 수 및 등록완료 수는 평가기간 동안 집계된 요청 수, 완료 수를 모두 합산하여 산정합니다.",
			defaultCase("performance-measure-when")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheOfficialEgovPreliminaryReviewTargetWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"다음 해에 정보화사업을 추진하고자 하는 중앙행정기관의 장, 시ㆍ도지사 및 시ㆍ도 교육감은 "
				+ "사업의 목적과 적용 범위 등을 제출하고 예비검토를 신청하여야 한다.",
			defaultCase("egov-preliminary-review-target")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheOfficialIntegratedGuidePurposeWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"이 안내서는 개인정보처리자가 개인정보 처리와 관련한 개편 내용에 대하여 "
				+ "현장에서 이해하기 쉽도록 개인정보 처리 시 준수해야 하는 사항을 "
				+ "안내할 목적으로 마련되었습니다.",
			defaultCase("privacy-integrated-guide-purpose")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheStatutoryConsentRefusalNoticeWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"개인정보 수집·이용 동의를 받을 때 동의를 거부할 권리가 있다는 사실 및 "
				+ "동의 거부에 따른 불이익이 있는 경우 그 불이익의 내용을 알려야 합니다.",
			defaultCase("privacy-consent-refusal")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheStatutoryConsentRefusalNoticeWithTheReferencedConsentParagraph() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"개인정보처리자는 제1항제1호에 따른 동의를 받을 때에는 다음 각 호의 사항을 "
				+ "정보주체에게 알려야 한다. 동의를 거부할 권리가 있다는 사실 및 "
				+ "동의 거부에 따른 불이익이 있는 경우에는 그 불이익의 내용",
			defaultCase("privacy-consent-refusal")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheCurrentStatutoryPreConsultationTimingWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"중앙행정기관등의 장은 사전협의 대상사업을 추진하려는 경우에는 "
				+ "사업계획을 수립한 후 지체 없이 행정안전부장관에게 사업계획서 등의 자료를 "
				+ "제출하여 사전협의를 요청하여야 한다.",
			defaultCase("pre-consultation-when")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheCurrentStatutoryCentralAgencyScopeWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"사전협의 대상사업은 중앙행정기관등의 장이 다른 중앙행정기관등과 "
				+ "상호연계하거나 공동이용과 관련하여 추진하는 사업으로 한다.",
			defaultCase("pre-consultation-central-agency")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheOfficialRfpEnumerationWithPunctuationSeparators() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"제안요청서에는 과업내용, 요구사항, 계약조건, 평가요소와 평가방법, "
				+ "제안서의 규격, 기타 필요한 사항 등을 기술하여야 합니다.",
			defaultCase("rfp-required-items")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsSeparatelyStatedWhistleblowerProtectionMeasures() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"공익신고자의 신분비밀을 보장합니다. "
				+ "공익신고자는 신변보호조치를 권익위에 요구할 수 있습니다. "
				+ "공익신고자는 보호조치를 권익위에 신청할 수 있습니다.",
			defaultCase("whistleblower-protection-scope")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void acceptsTheOfficialWhistleblowerDisadvantageProtectionWording() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"공익신고자등이 공익신고등을 이유로 불이익 조치를 받은 때에는 "
				+ "권익위에 보호조치를 신청할 수 있습니다. "
				+ "공익신고자의 신분비밀을 보장합니다.",
			defaultCase("whistleblower-disadvantage")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void rejectsHardwareAliasInsideAViewSupersededByTheFinalAssertion() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"비대상이라는 견해도 있지만 실제로는 과업심의 대상입니다",
			defaultCase("project-review-hardware-exclusion")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void rejectsThirtyDayAliasInsideAnExplanationSupersededByTheFinalAssertion() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"반드시 30일은 아니라는 설명도 있지만 실제로는 무조건 30일입니다. 설치 목적",
			defaultCase("cctv-retention-not-fixed-30")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void rejectsPendingAliasInsideAnExplanationSupersededByTheFinalAssertion() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"아직 시행예정이라는 설명도 있지만 실제로는 이미 시행 중입니다. 공식 시행일을 명시해야 합니다",
			defaultCase("ai-law-enforcement-date")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void rejectsAliasBeforeContrastWithAGenericFinalityMarker() {
		for (String finalityMarker : List.of("실제로는", "사실상", "사실은", "결론적으로", "결국", "오히려")) {
			AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
				"비대상으로 알려졌지만 " + finalityMarker + " 과업심의 대상입니다",
				defaultCase("project-review-hardware-exclusion")
			);

			assertThat(result.passed()).as(finalityMarker + ": " + result.message()).isFalse();
			assertThat(result.missingPropositionGroups()).as(finalityMarker).isNotEmpty();
		}
	}

	@Test
	void rejectsPendingAliasBeforeGenericFinalAssertion() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"아직 시행예정인 것으로 알려졌지만 실제로는 이미 시행 중입니다. 공식 시행일을 명시해야 합니다",
			defaultCase("ai-law-enforcement-date")
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.missingPropositionGroups()).isNotEmpty();
	}

	@Test
	void forbiddenPositiveExpressionDoesNotMatchInsideModalNegation() {
		LawAiEvalRequest.EvalCase evalCase = oracleCase(
			List.of(List.of("법령상 예외를 확인")),
			List.of(),
			List.of("공개장소에 자유롭게 설치")
		);

		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"법령상 예외를 확인해야 하며 공개장소에 자유롭게 설치할 수 없습니다.",
			evalCase
		);

		assertThat(result.passed()).as(result.message()).isTrue();
		assertThat(result.forbiddenMatchedExpressions()).isEmpty();
	}

	@Test
	void forbiddenPositiveExpressionDoesNotMatchInsideGrammaticalAnNegation() {
		LawAiEvalRequest.EvalCase evalCase = oracleCase(
			List.of(List.of("법령상 예외를 확인")),
			List.of(),
			List.of("공개장소에 자유롭게 설치")
		);

		for (String ending : List.of("안 됩니다", "안 된다", "안 됨")) {
			AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
				"법령상 예외를 확인해야 하며 공개장소에 자유롭게 설치해서는 " + ending,
				evalCase
			);

			assertThat(result.passed()).as(ending + ": " + result.message()).isTrue();
			assertThat(result.forbiddenMatchedExpressions()).as(ending).isEmpty();
		}
	}

	@Test
	void forbiddenPositiveExpressionDoesNotMatchAcrossConditionalNegationBridge() {
		LawAiEvalRequest.EvalCase evalCase = oracleCase(
			List.of(List.of("법령상 예외를 확인")),
			List.of(),
			List.of("공개장소에 자유롭게 설치")
		);

		for (String answer : List.of(
			"법령상 예외를 확인해야 하며 공개장소에 자유롭게 설치하면 안 됩니다.",
			"법령상 예외를 확인해야 하며 공개장소에 자유롭게 설치한다면 안 된다.",
			"법령상 예외를 확인해야 하며 공개장소에 자유롭게 설치할 경우 안 됨."
		)) {
			AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(answer, evalCase);

			assertThat(result.passed()).as(answer + ": " + result.message()).isTrue();
			assertThat(result.forbiddenMatchedExpressions()).as(answer).isEmpty();
		}
	}

	@Test
	void negativePropositionMatchesGrammaticalAnCannotAssertForm() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"무조건 30일이라고 단정해서는 안 됩니다. 설치 목적에 따라 기간을 정합니다",
			defaultCase("cctv-retention-not-fixed-30")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void lexicalAnnaeDoesNotCountAsGrammaticalAnNegation() {
		LawAiEvalRequest.EvalCase evalCase = oracleCase(
			List.of(List.of("법령상 예외를 확인")),
			List.of(),
			List.of("공개장소에 자유롭게 설치")
		);

		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"법령상 예외를 확인했으며 공개장소에 자유롭게 설치 안내를 제공합니다.",
			evalCase
		);

		assertThat(result.passed()).isFalse();
		assertThat(result.forbiddenMatchedExpressions()).containsExactly("공개장소에 자유롭게 설치");
	}

	@Test
	void negativePropositionMatchesModalCannotAssertForm() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"무조건 30일이라고 단정할 수 없습니다. 설치 목적에 따라 기간을 정합니다",
			defaultCase("cctv-retention-not-fixed-30")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void doubleNegationAffirmsThePositiveOraclePolarity() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"비대상이 아니라고 할 수 없습니다",
			defaultCase("project-review-hardware-exclusion")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void directConclusionRemainsValidWhenFollowedByADamanCondition() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"공개된 장소 CCTV 설치는 원칙적으로 금지입니다. 다만 법령상 예외 사유가 있는 경우에만 설치할 수 있습니다.",
			defaultCase("cctv-public-place-rule")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void latestRuntimeCctvSourceAtomSatisfiesTheExplicitOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"누구든지 공개된 장소에 고정형 영상정보처리기기를 설치·운영하는 것은 원칙적으로 금지되며 "
				+ "다른 법익의 보호를 위하여 필요한 경우 예외적으로 설치·운영이 허용됩니다. "
				+ "공개된 장소에서의 고정형 영상정보처리기기 설치는 원칙적으로 금지되고, "
				+ "예외적으로 법 제25조에서 정하는 사유에 해당하는 경우에만 설치·운영할 수 있습니다.",
			defaultCase("cctv-public-place-rule")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void latestRuntimePseudonymSourceAtomSatisfiesTheExplicitOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"가명처리 수행에 따라 추가정보에 대하여 일정기간 보관이 필요하여 보관하고 "
				+ "개인정보처리자는 추가정보를 가명정보와 분리하여 별도로 저장관리하고 "
				+ "다만, 불필요한경우 파기해야함",
			defaultCase("pipc-pseudonym-additional-info")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void latestRuntimePrivacyMinimumCollectionSourceAtomSatisfiesTheExplicitOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"개인정보처리자는 개인정보의 처리 목적을 명확하게 하여야 하고 "
				+ "그 목적에 필요한 범위에서 최소한의 개인정보만을 적법하고 정당하게 수집하여야 한다.",
			defaultCase("privacy-minimum-collection")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void officialProjectReviewScopeAndHardwareBoundarySatisfyTheOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이다. "
				+ "단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상이다.",
			defaultCase("project-review-simple-software")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void latestRuntimeProjectReviewSourceAtomSatisfiesTheExplicitOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)",
			defaultCase("project-review-target")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void officialPreConsultationInstitutionAndProjectScopeSatisfyTheOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"중앙·공공기관의 신규 사업은 대상에 포함된다. "
				+ "사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업이다.",
			defaultCase("pre-consultation-target")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void officialCctvPrincipleAndArticleExceptionSatisfyTheOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"공개된 장소의 고정형 영상정보처리기기 설치는 원칙적으로 금지되고, "
				+ "법 제25조에서 정하는 사유에 해당하는 경우에만 설치할 수 있다.",
			defaultCase("pipc-cctv-public-place-exception")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	@Test
	void officialPreConsultationPlanSequenceSatisfiesTheOracle() {
		AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(
			"사업계획을 수립한 후 지체 없이 사업계획서 등을 제출하여 사전협의를 요청해야 합니다.",
			defaultCase("pre-consultation-plan-stage")
		);

		assertThat(result.passed()).as(result.message()).isTrue();
	}

	private LawAiEvalRequest.EvalCase defaultCase(String id) {
		return LawAiEvaluationCaseCatalog.loadDefaultCases().stream()
			.filter(evalCase -> id.equals(evalCase.id()))
			.findFirst()
			.orElseThrow();
	}

	private LawAiEvalRequest.EvalCase oracleCase(
		List<List<String>> propositions,
		List<List<String>> conditions,
		List<String> forbidden
	) {
		return new LawAiEvalRequest.EvalCase(
			"oracle",
			"question",
			List.of("law"),
			List.of("retrieval term"),
			1,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			"answer directly",
			List.of("OK"),
			true,
			List.of(),
			forbidden,
			propositions,
			conditions
		);
	}
}
