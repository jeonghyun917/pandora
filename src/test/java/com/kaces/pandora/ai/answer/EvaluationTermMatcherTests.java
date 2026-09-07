package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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

	@Test
	void forbiddenEvidenceIgnoresIncidentalParentContextButChecksTheMatchedChild() {
		LawAiAnswerGround directGround = ground(
			"사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업임",
			"부록: 제안요청서 작성 예시"
		);
		LawAiAnswerGround templateGround = ground(
			"부록: 제안요청서 작성 예시",
			null
		);

		assertThat(EvaluationTermMatcher.matchesForbiddenEvidenceTerm(directGround, "작성 예시"))
			.isFalse();
		assertThat(EvaluationTermMatcher.matchesForbiddenEvidenceTerm(templateGround, "작성 예시"))
			.isTrue();
	}

	private LawAiAnswerGround ground(String matchedChildText, String parentContextText) {
		return new LawAiAnswerGround(
			1, 1L, 1L, "official_doc", "정보화사업 안내서", "", "", "", "CURRENT",
			"p.1", "대상사업", 1, matchedChildText, "", "", 1.0,
			matchedChildText, parentContextText, List.of(1L),
			parentContextText == null ? "matched_child_only" : "parent_context_expanded"
		);
	}
}
