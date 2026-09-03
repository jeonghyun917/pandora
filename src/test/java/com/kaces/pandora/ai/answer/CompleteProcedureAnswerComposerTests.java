package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class CompleteProcedureAnswerComposerTests {

	@Test
	void preservesTheCompleteOrderedProcedureFromOneDirectGround() {
		String answer = CompleteProcedureAnswerComposer.compose(
			"보안성검토 절차는 어떻게 돼?",
			List.of(ground("""
				① 대상 사업 식별
				② 보안성 검토요청: 사업부서는 신청서와 사업계획서를 제출한다.
				③ 보안성 검토: 정보보안 담당자가 보안대책의 적절성을 검토한다.
				④ 검토결과 통보: 검토요청 공문 접수 후 결과서를 사업부서에 통보한다.
				⑤ 사후 조치
				"""))
		);

		assertThat(answer)
			.startsWith("절차는 다음 순서입니다.")
			.contains("② 보안성 검토요청")
			.contains("③ 보안성 검토")
			.contains("④ 검토결과 통보")
			.doesNotContain("① 대상 사업 식별", "⑤ 사후 조치");
	}

	@Test
	void declinesNonProcedureQuestionsAndIncompleteOrUnrelatedGrounds() {
		assertThat(CompleteProcedureAnswerComposer.compose(
			"보안성검토 대상은 뭐야?",
			List.of(ground("② 검토요청 ③ 검토 ④ 결과통보"))
		)).isNull();

		assertThat(CompleteProcedureAnswerComposer.compose(
			"보안성검토 절차는 어떻게 돼?",
			List.of(ground("② 검토요청 ③ 검토"))
		)).isNull();

		assertThat(CompleteProcedureAnswerComposer.compose(
			"사전협의 절차는 어떻게 돼?",
			List.of(ground("② 보안성 검토요청 ③ 보안성 검토 ④ 검토결과 통보"))
		)).isNull();
	}

	@Test
	void usesTheCompleteDisplaySnippetWhenTheMatchedChildTextIsIncomplete() {
		String completeSnippet = """
			② 보안성 검토요청: 신청서와 사업계획서를 제출한다.
			③ 보안성 검토: 보안대책의 적절성을 검토한다.
			④ 검토결과 통보: 결과서를 사업부서에 통보한다.
			""";

		String answer = CompleteProcedureAnswerComposer.compose(
			"보안성검토 절차는 어떻게 돼?",
			List.of(ground("보안성 검토 대상 사업 식별", completeSnippet))
		);

		assertThat(answer).contains("② 보안성 검토요청", "③ 보안성 검토", "④ 검토결과 통보");
	}

	@Test
	void treatsAValidatedCompleteProcedureViewAsDirectEvenWhenTheLegacyRoleIsRelatedDefinition() {
		String answer = CompleteProcedureAnswerComposer.compose(
			"보안성검토 절차는 어떻게 돼?",
			List.of(ground(
				"보안성 검토 대상 사업 식별",
				"② 보안성 검토요청: 신청서를 제출한다. "
					+ "③ 보안성 검토: 보안대책의 적절성을 검토한다. "
					+ "④ 검토결과 통보: 결과서를 사업부서에 통보한다.",
				"related_definition"
			))
		);

		assertThat(answer).contains("② 보안성 검토요청", "③ 보안성 검토", "④ 검토결과 통보");
	}

	private LawAiAnswerGround ground(String text) {
		return ground(text, text);
	}

	private LawAiAnswerGround ground(String matchedChildText, String snippet) {
		return ground(matchedChildText, snippet, "direct");
	}

	private LawAiAnswerGround ground(String matchedChildText, String snippet, String evidenceRole) {
		return new LawAiAnswerGround(
			1,
			84923,
			8,
			"official_doc",
			"2026년 정보화사업 보안성 검토 가이드",
			"행정안전부",
			"공식 가이드 문서",
			null,
			null,
			"page 2",
			"보안성 검토 절차",
			2,
			snippet,
			null,
			null,
			1.0,
			matchedChildText,
			null,
			List.of(84923L),
			"matched_child_only",
			evidenceRole
		);
	}
}
