package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EvaluationTermMatcherTests {

	@Test
	void matchesKoreanAnswerExpressionVariants() {
		String answer = """
			동의를 받을 때는 개인정보의 처리 목적과 최소한의 수집 항목을 알려야 합니다.
			개인정보 보유·이용 기간 또는 기간 산정 기준도 함께 안내해야 합니다.
			정보주체의 동의 거부 권리와 거부 시 불이익 여부도 확인할 수 있어야 합니다.
			""";

		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "개인정보의 수집")).isTrue();
		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "이용 목적")).isTrue();
		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "개인정보의 항목")).isTrue();
		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "보유 및 이용기간")).isTrue();
		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "동의를 거부할 권리")).isTrue();
	}

	@Test
	void rejectsUnrelatedTerms() {
		String answer = "과업심의 대상 여부는 소프트웨어사업의 성격과 예외 사유를 확인해야 합니다.";

		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "동의를 거부할 권리")).isFalse();
		assertThat(EvaluationTermMatcher.matchesAnswerTerm(answer, "개인정보의 항목")).isFalse();
	}
}
