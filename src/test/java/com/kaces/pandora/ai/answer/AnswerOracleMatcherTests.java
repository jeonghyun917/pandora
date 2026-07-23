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
		assertThat(complete.passed()).isTrue();
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
