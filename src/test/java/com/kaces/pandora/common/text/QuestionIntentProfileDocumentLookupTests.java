package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionIntentProfileDocumentLookupTests {

	@Test
	void documentIdentityLookupDoesNotTreatTitleWordsAsProcedureIntent() {
		List<String> questions = List.of(
			"개인정보위의 생성형 AI 개인정보 처리 안내서는 어떤 문서야?",
			"민원 처리 매뉴얼은 무슨 문서인지 알려줘",
			"재난 대응 가이드는 어느 문서인가?",
			"공공기관 고정형 영상정보처리기기 설치 운영 가이드라인은 뭐야?"
		);

		assertThat(questions)
			.map(QuestionSearchPlan::from)
			.allSatisfy(plan -> {
				assertThat(plan.profile().intentTypes()).doesNotContain("procedure");
				assertThat(plan.profile().preferredSectionTypes()).doesNotContain("procedure");
				assertThat(plan.focusedKeywords()).doesNotContain("procedure");
			});
		assertThat(QuestionSearchPlan.from(questions.get(0)).lexicalKeywords())
			.anyMatch(keyword -> keyword.contains("처리"));
		assertThat(QuestionSearchPlan.from(questions.get(3)).profile().directEvidenceGroups())
			.as("document identity suppression must retain entity anchors for fail-closed verification")
			.isNotEmpty();
	}

	@Test
	void actualProcedureQuestionsRemainStrictProcedureIntent() {
		List<String> questions = List.of(
			"개인정보 처리 절차는 어떻게 돼?",
			"개인정보 처리 신청 방법은?",
			"개인정보 처리 제출 시기는?",
			"이 파일이 어떤 문서인지 확인하는 절차는?",
			"어떤 문서인지 판별하는 방법을 알려줘"
		);

		assertThat(questions)
			.map(QuestionSearchPlan::from)
			.allSatisfy(plan -> {
				assertThat(plan.profile().intentTypes()).contains("procedure");
				assertThat(plan.profile().preferredSectionTypes()).contains("procedure");
			});
	}
}
