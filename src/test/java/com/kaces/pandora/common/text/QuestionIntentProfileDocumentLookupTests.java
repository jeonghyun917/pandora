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
			"공공기관 고정형 영상정보처리기기 설치 운영 가이드라인은 뭐야?",
			"공공소프트웨어사업 과업심의의 가이드 문서 찾아줘"
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
		assertThat(QuestionIntentProfile.from(questions.get(4)).documentIdentityQuestion()).isTrue();
		assertThat(QuestionIntentProfile.from(
			"근로기준법 문서에서 연차휴가의 근거 조항을 찾아줘"
		).documentIdentityQuestion())
			.as("finding content inside a document is not a document-title identity request")
			.isFalse();
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

	@Test
	void relatedSourceNounPhrasesAreDocumentDiscoveryRequests() {
		assertThat(List.of(
			"CCTV 관련 법령",
			"개인정보 처리 관련 규정 찾아줘",
			"공공데이터 관련 행정규칙 알려줘",
			"생성형 AI 개인정보 가이드",
			"보조금 집행 관련 자료 알려주세요"
		))
			.map(QuestionIntentProfile::from)
			.allSatisfy(profile -> {
				assertThat(profile.documentDiscoveryQuestion()).isTrue();
				assertThat(profile.documentIdentityQuestion()).isFalse();
				assertThat(profile.intentTypes()).contains("document_discovery");
			});
	}

	@Test
	void discoveryRetainsCctvRecallExpansionWithoutRequiringALegalProposition() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("CCTV 관련 법령");

		assertThat(profile.entities()).extracting(QuestionEntity::id).contains("cctv");
		assertThat(profile.focusedKeywords())
			.anyMatch(keyword -> keyword.contains("고정형 영상정보처리기기"));
		assertThat(profile.preferredTargets()).contains("law", "admrul", "official_doc");
		assertThat(profile.directEvidenceGroups()).isEmpty();
		assertThat(profile.preferredSectionTypes()).isEmpty();
	}

	@Test
	void discoveryKeepsPolicySearchExpansionButDoesNotActivateItsAnswerContract() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("CCTV 수사기관 제공 관련 법령");

		assertThat(profile.documentDiscoveryQuestion()).isTrue();
		assertThat(profile.focusedKeywords())
			.anyMatch(keyword -> keyword.contains("CCTV 자료 수사기관 제공"));
		assertThat(profile.matchedPolicyIds()).contains("cctv_investigation");
		assertThat(profile.configuredAnswerCoverageGroups()).isEmpty();
	}

	@Test
	void substantiveLegalQuestionsAndContentLookupsAreNotDocumentDiscovery() {
		assertThat(List.of(
			"CCTV 관련 법령상 설치 조건은?",
			"CCTV 설치가 가능한가?",
			"개인정보 보호법에서 CCTV 보관기간을 알려줘",
			"근로기준법 문서에서 연차휴가의 근거 조항을 찾아줘",
			"관련 법령"
		))
			.map(QuestionIntentProfile::from)
			.allSatisfy(profile -> assertThat(profile.documentDiscoveryQuestion()).isFalse());
	}
}
