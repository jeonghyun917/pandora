package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KoreanQueryNormalizerTests {

	@Test
	void stripsColloquialDefinitionSuffixesFromCoreTerms() {
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("인공지능위원회라는건")).isEqualTo("인공지능위원회");
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("인공지능위원회라는건 뭐야?")).isEqualTo("인공지능위원회");
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("보안성검토라는게뭐야")).isEqualTo("보안성검토");
	}

	@Test
	void expandsCommitteeLikeSearchKeywords() {
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("인공지능위원회"))
			.contains("인공지능위원회", "국가인공지능전략위원회", "인공지능전략위원회");
	}

	@Test
	void expandsKnownCompoundTermsWithSpacingVariants() {
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("보안성검토라는게 뭐야?"))
			.contains("보안성검토", "보안성 검토", "정보화사업 보안성 검토");
		assertThat(KoreanQueryNormalizer.isWeakQuestionTerm("가능해")).isTrue();
	}

	@Test
	void expandsPublicInstitutionPreConsultationTargetAliases() {
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("기타공공기관"))
			.contains("기타공공기관", "공공기관", "중앙·공공기관", "대상기관", "대상기관이 추진하는 모든 정보화사업");
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("사전협의 대상"))
			.contains("사전협의의 대상사업", "대상기관이 추진하는 모든 정보화사업", "예산과목 및 계약방식과 관계없이");
	}

	@Test
	void expandsDigitalCatalogTypoToProcurementPurchaseTerms() {
		assertThat(KoreanQueryNormalizer.expandSearchKeywords("디지털카달로그에서"))
			.contains("디지털카달로그", "디지털카탈로그", "디지털서비스몰", "상용SW 직접구매");
		assertThat(KoreanQueryNormalizer.procurementCatalogFocusedKeywords("조달청 디지털카달로그에서 구매하면 수의계약 인가?"))
			.containsExactly("디지털서비스몰", "수의계약");
		assertThat(KoreanQueryNormalizer.isProcurementCatalogContractQuestion("조달청 디지털카달로그에서 구매하면 수의계약 인가?"))
			.isTrue();
	}

	@Test
	void extractsReusableQuestionIntentProfile() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("조달청 디지털카달로그에서 구매하면 수의계약 인가?");

		assertThat(profile.focusedKeywords()).contains("디지털서비스몰", "수의계약");
		assertThat(profile.intentTypes()).contains("contract_method", "purchase_channel");
		assertThat(profile.preferredSectionTypes()).contains("procedure");
		assertThat(profile.intentGroups())
			.anySatisfy(group -> assertThat(group).contains("수의계약", "계약방법"));
	}

	@Test
	void classifiesProjectReviewExclusionAsTargetAndExceptionIntent() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("단순 SW 구매면 과업심의 안 해도 돼?");

		assertThat(profile.intentTypes()).contains("target_scope", "exception_scope", "review_required");
		assertThat(profile.preferredSectionTypes()).contains("target_scope", "exception");
	}

	@Test
	void classifiesColloquialProjectReviewNeedQuestionAsTargetScope() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("SNS운영 사업도 과업심의 받아야해?");

		assertThat(KoreanQueryNormalizer.isWeakQuestionTerm("받아야해")).isTrue();
		assertThat(plan.profile().intentTypes()).contains("target_scope", "review_required", "operation_rule");
		assertThat(plan.profile().preferredSectionTypes()).contains("target_scope");
		assertThat(plan.lexicalKeywords()).contains("과업심의", "대상사업");
		assertThat(plan.focusedKeywords())
			.anySatisfy(keyword -> assertThat(keyword).contains("국가기관등"));
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("공공소프트웨어사업 과업심의 적용 대상 사업"));
	}

	@Test
	void extractsConfiguredEntitiesAndSynonymGroups() {
		QuestionIntentProfile irm = QuestionIntentProfile.from("IRM 사용자 권한 가이드");
		QuestionIntentProfile procurement = QuestionIntentProfile.from("조달청 디지털카달로그에서 구매하면 수의계약인가?");

		assertThat(irm.entities()).extracting(QuestionEntity::id).contains("irm");
		assertThat(irm.preferredTargets()).contains("official_doc", "internal_doc");
		assertThat(irm.synonymGroups()).anySatisfy(group -> assertThat(group).contains("IRM", "정보자원관리시스템"));
		assertThat(procurement.entities()).extracting(QuestionEntity::id).contains("procurement_catalog");
		assertThat(procurement.synonymGroups()).anySatisfy(group -> assertThat(group).contains("디지털카탈로그", "디지털카달로그", "디지털서비스몰"));
	}

	@Test
	void buildsSearchPlanWithEmbeddingQueryAndFocusedKeywords() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("보안성검토 대상 시스템은?");

		assertThat(plan.embeddingQuery()).contains("보안성검토");
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("보안성 검토 대상"));
		assertThat(plan.focusedKeywords()).anyMatch(keyword -> keyword.contains("보안성"));
		assertThat(plan.lexicalKeywords()).anyMatch(keyword -> keyword.contains("정보시스템"));
		assertThat(plan.excludedHints()).contains("목차", "작성예시");
	}

	@Test
	void buildsMultiQueriesFromEntityAndIntentDictionary() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("단순 소프트웨어 구매면 과업심의 안해도됨?");

		assertThat(plan.expandedQueries()).hasSizeGreaterThan(1);
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("과업심의 제외"));
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("단순 H/W"));
	}

	@Test
	void buildsClarificationQuestionsFromEntityAndIntentProfile() {
		QuestionSearchPlan irm = QuestionSearchPlan.from("IRM 성과측정은 언제해?");
		QuestionSearchPlan genericTarget = QuestionSearchPlan.from("대상은?");

		assertThat(irm.clarificationQuestions()).anyMatch(question -> question.contains("정보자원관리시스템"));
		assertThat(genericTarget.clarificationQuestions()).anyMatch(question -> question.contains("제도명"));
	}

	@Test
	void extractsEgovPreliminaryReviewEntityAndSearchQueries() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("지능정보사회 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?");

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("egov_preliminary_review");
		assertThat(plan.profile().preferredTargets()).contains("admrul");
		assertThat(plan.profile().directEvidenceGroups()).anySatisfy(group -> assertThat(group).contains("예비검토"));
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("전자정부 성과관리 지침 예비검토 대상 사업"));
		assertThat(plan.focusedKeywords()).anyMatch(keyword -> keyword.contains("정보화사업"));
	}

	@Test
	void classifiesInformationSystemComplianceConsequenceQuestions() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("정보화시스템 법제도 준수안하면 어떤 불이익?");

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("it_compliance");
		assertThat(plan.profile().intentTypes()).contains("penalty", "operation_rule");
		assertThat(plan.profile().preferredTargets()).contains("official_doc", "internal_doc", "admrul", "law");
		assertThat(plan.profile().directEvidenceGroups())
			.anySatisfy(group -> assertThat(group).contains("불이익", "제재", "조치"));
		assertThat(plan.focusedKeywords()).contains("정보화사업 법제도 준수", "불이익", "제재");
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("미준수"));
		assertThat(plan.expandedQueries()).anyMatch(query -> query.contains("예산 조정"));
	}

	@Test
	void keepsProtectedCompoundTermsWhenRemovingParticlesRepeatedly() {
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("횡단보도에서")).isEqualTo("횡단보도");
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("공공데이터를")).isEqualTo("공공데이터");
	}

	@Test
	void stripsQuotedClassificationParticlesFromCoreTerms() {
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("개인정보라고")).isEqualTo("개인정보");
		assertThat(KoreanQueryNormalizer.normalizeQueryTerm("개인정보라고도")).isEqualTo("개인정보");
	}

	@Test
	void excludesLowInformationConditionTokensFromSearchPlan() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("이메일 만으로도 개인정보라고 볼수있나?");

		assertThat(plan.lexicalKeywords())
			.contains("이메일", "개인정보")
			.doesNotContain("만으로", "만으로도", "개인정보라고");
	}

	@Test
	void loadsSpecificEvidencePoliciesFromConfiguration() {
		QuestionIntentProfile performance = QuestionIntentProfile.from("성과측정은 언제까지 완료해야 해?");
		QuestionIntentProfile performancePlan = QuestionIntentProfile.from("IRM 업무성과계획 수립 대상은 어떤 시스템이야?");
		QuestionIntentProfile privacy = QuestionIntentProfile.from("개인정보 보유기간이 끝나면 언제 파기해야 해?");

		assertThat(performance.matchedPolicyIds()).contains("performance_measure_period");
		assertThat(performance.directEvidenceGroups())
			.anySatisfy(group -> assertThat(group).contains("평가기간", "월말까지"));
		assertThat(performance.policySearchKeywords())
			.contains("성과측정 기간", "평가기간", "월말까지")
			.noneMatch(keyword -> keyword.matches(".*20\\d{2}.*"));
		assertThat(performancePlan.matchedPolicyIds()).contains("performance_plan_scope");
		assertThat(performancePlan.policySearchKeywords())
			.contains("업무성과계획 수립 대상", "업무성과계획 등록");
		assertThat(privacy.matchedPolicyIds()).contains("privacy_retention_destruction");
		assertThat(privacy.directEvidenceGroups())
			.anySatisfy(group -> assertThat(group).contains("지체없이파기", "파기하여야"));
	}
}
