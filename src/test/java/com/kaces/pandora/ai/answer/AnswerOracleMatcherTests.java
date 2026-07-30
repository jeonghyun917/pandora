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
