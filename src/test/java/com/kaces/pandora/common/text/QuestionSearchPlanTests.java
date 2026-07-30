package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QuestionSearchPlanTests {

	@Test
	void expandsProcurementCatalogContractQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("조달청 디지털카달로그에서 구매하면 수의계약 인가?");

		assertThat(plan.lexicalKeywords()).isNotEmpty();
		assertThat(plan.expandedQueries()).hasSizeGreaterThan(1);
		assertThat(join(plan.expandedQueries())).containsAnyOf("디지털서비스", "디지털카탈로그", "수의계약", "계약방식");
		assertThat(plan.profile().intentTypes()).contains("contract_method");
	}

	@Test
	void expandsProjectReviewExceptionQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("단순 소프트웨어 구매면 과업심의 안해도됨?");

		assertThat(plan.expandedQueries()).isNotEmpty();
		assertThat(plan.profile().preferredSectionTypes()).containsAnyOf("target_scope", "exception");
		assertThat(join(plan.lexicalKeywords())).containsAnyOf("과업심의", "소프트웨어", "비대상", "제외");
		assertThat(join(plan.answerFocusInstructions())).contains("과업심의 적용 대상", "심의 면제");
	}

	@Test
	void expandsEgovPreliminaryReviewTargetQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("지능정보사회 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?");

		assertThat(plan.expandedQueries()).hasSizeGreaterThan(1);
		assertThat(plan.profile().preferredSectionTypes()).contains("target_scope");
		assertThat(join(plan.expandedQueries())).containsAnyOf("예비검토", "정보화사업", "대상");
		assertThat(plan.profile().configuredEntityAnchorGroups()).anySatisfy(group ->
			assertThat(group).contains("예비검토", "전자정부 성과관리")
		);
	}

	@Test
	void extractsOfficialDocumentSpecificQuestionProfiles() {
		QuestionSearchPlan autonomy = QuestionSearchPlan.from("자치분권 사전협의 대상기관은 어디야?");
		QuestionSearchPlan tving = QuestionSearchPlan.from("티빙 침해사고 관련 스미싱 피해는 어떻게 신고해?");
		QuestionSearchPlan quantum = QuestionSearchPlan.from("OECD 양자 기술 권고문 관련 과기정통부 역할은 뭐야?");

		assertThat(autonomy.profile().entities()).extracting(QuestionEntity::id).contains("autonomy_pre_consultation");
		assertThat(autonomy.focusedKeywords()).anyMatch(keyword -> keyword.contains("법령 제·개정 권한"));
		assertThat(tving.profile().intentTypes()).contains("procedure");
		assertThat(tving.focusedKeywords()).contains("소액결제확인서", "사건사고 사실 확인서");
		assertThat(quantum.profile().entities()).extracting(QuestionEntity::id).contains("quantum_oecd");
		assertThat(quantum.focusedKeywords()).contains("재정적 기여", "초안 작성");
	}

	@Test
	void expandsKoreanFairUseQuestionToEnglishOfficialDocumentTerms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("생성형 AI 학습의 공정이용 판단 근거는?");

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("fair_use_ai");
		assertThat(plan.profile().directEvidenceGroups()).hasSizeGreaterThanOrEqualTo(2);
		assertThat(join(plan.lexicalKeywords())).contains("fair use", "generative AI", "training");
		assertThat(join(plan.focusedKeywords())).contains("fair use", "criteria for determining fair use");
		assertThat(join(plan.expandedQueries())).contains("fair use doctrine", "training of generative AI models");
	}

	@Test
	void documentLookupQuestionDoesNotBecomeOperationRuleBecauseItAsksForGrounds() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uAC10\uC0AC\uC6D0\uC2EC\uC0AC\uADDC\uCE59 \uBB38\uC11C\uC758 \uC8FC\uC694 \uC870\uD56D \uADFC\uAC70\uB97C \uC54C\uB824\uC918"
		);

		assertThat(plan.profile().intentTypes()).doesNotContain("operation_rule");
		assertThat(plan.focusedKeywords()).doesNotContain("operation_rule");
		assertThat(join(plan.lexicalKeywords())).contains("\uAC10\uC0AC\uC6D0\uC2EC\uC0AC\uADDC\uCE59");
	}

	@Test
	void expandsPublicDataObligationQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from("공공기관 시스템은 공공데이터를 꼭 제공해야하나?");

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("public_data");
		assertThat(plan.profile().intentTypes()).contains("obligation", "target_scope");
		assertThat(plan.profile().preferredSectionTypes()).contains("requirement", "target_scope");
		assertThat(join(plan.focusedKeywords())).contains("공공데이터 제공 의무", "제공대상 공공데이터");
		assertThat(join(plan.expandedQueries())).contains("공공데이터 제공 의무");
		assertThat(join(plan.answerFocusInstructions())).contains("대상 또는 적용 범위");
	}

	@Test
	void generatesEntityIntentQueryBeforeGenericIntentFallback() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uACF5\uACF5\uAE30\uAD00\uC774 \uACF5\uACF5\uB370\uC774\uD130\uB97C \uC81C\uACF5\uD558\uC9C0 \uC54A\uC73C\uBA74 \uC5B4\uB5A4 \uBD88\uC774\uC775\uC774 \uC788\uC5B4?"
		);

		var nonEmbeddingQueries = plan.expandedQueries().stream().skip(1).toList();

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("public_data");
		assertThat(plan.profile().intentTypes()).contains("penalty");
		assertThat(plan.profile().synonymGroups().toString())
			.contains("\uACF5\uACF5\uB370\uC774\uD130")
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
		assertThat(nonEmbeddingQueries).anySatisfy(query -> {
			assertThat(query).contains("\uACF5\uACF5\uB370\uC774\uD130");
			assertThat(query).containsAnyOf("\uBD88\uC774\uC775", "\uC81C\uC7AC", "\uC870\uCE58", "\uC608\uC0B0 \uC870\uC815");
		});
		assertThat(join(plan.expandedQueries()))
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
		assertThat(nonEmbeddingQueries).noneMatch(query -> query.contains(" penalty") || query.contains(" target_scope"));
		assertThat(plan.expandedQueries()).hasSizeLessThanOrEqualTo(4);
	}

	@Test
	void whistleblowerDisadvantageDoesNotActivateInformationSystemComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uACF5\uC775\uC2E0\uACE0\uC790\uAC00 \uBD88\uC774\uC775\uC744 \uBC1B\uC73C\uBA74 \uBCF4\uD638\uC870\uCE58\uB97C \uBC1B\uC744 \uC218 \uC788\uC5B4?"
		);

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("whistleblower");
		assertThat(plan.profile().synonymGroups().toString())
			.contains("\uACF5\uC775\uC2E0\uACE0\uC790")
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
		assertThat(join(plan.expandedQueries()))
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
	}

	@Test
	void genericViolationQuestionDoesNotActivateInformationSystemComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC704\uBC18\uD558\uBA74 \uC5B4\uB5A4 \uC81C\uC7AC\uAC00 \uC788\uC5B4?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
	}

	@Test
	void domainAndComplianceTermsActivateInformationSystemComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC815\uBCF4\uD654\uC0AC\uC5C5\uC774 \uBC95\uC81C\uB3C4\uB97C \uC704\uBC18\uD558\uBA74 \uC5B4\uB5A4 \uC81C\uC7AC\uAC00 \uC788\uC5B4?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.contains("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4", "\uC704\uBC18", "\uC81C\uC7AC");
	}

	@Test
	void informationSystemReviewResultBudgetQuestionRetainsComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC815\uBCF4\uD654\uC0AC\uC5C5 \uAC80\uD1A0\uACB0\uACFC \uBBF8\uBC18\uC601 \uC2DC \uC608\uC0B0 \uC870\uC815\uC774 \uC788\uC5B4?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.contains("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4", "\uC608\uC0B0 \uC870\uC815", "\uAC80\uD1A0\uACB0\uACFC \uBC18\uC601");
	}

	@Test
	void privacyInformationSystemQuestionDoesNotActivateItComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uAC1C\uC778\uC815\uBCF4\uC2DC\uC2A4\uD15C\uC774 \uAC1C\uC778\uC815\uBCF4\uBCF4\uD638\uBC95\uC744 \uC704\uBC18\uD558\uBA74 \uC5B4\uB5A4 \uC81C\uC7AC\uAC00 \uC788\uC5B4?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
	}

	@Test
	void explicitInformationSystemCompliancePhraseActivatesItComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC815\uBCF4\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4 \uC704\uBC18 \uC2DC \uC81C\uC7AC\uB294?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.contains("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4", "\uC704\uBC18", "\uC81C\uC7AC");
	}

	@Test
	void informationSystemWithParticleActivatesItComplianceSynonyms() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC815\uBCF4\uC2DC\uC2A4\uD15C\uC774 \uBC95\uC81C\uB3C4\uB97C \uC704\uBC18\uD558\uBA74 \uC5B4\uB5A4 \uC81C\uC7AC\uAC00 \uC788\uC5B4?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.contains("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4", "\uC704\uBC18", "\uC81C\uC7AC");
	}

	@Test
	void alreadyReflectedInformationSystemQuestionDoesNotMatchNonReflectionCue() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC815\uBCF4\uD654\uC0AC\uC5C5 \uACC4\uD68D\uC5D0 \uC774\uBBF8 \uBC18\uC601\uB41C \uB0B4\uC6A9\uC774\uC57C?"
		);

		assertThat(plan.profile().synonymGroups().toString())
			.doesNotContain("\uC815\uBCF4\uD654\uC2DC\uC2A4\uD15C \uBC95\uC81C\uB3C4");
	}

	@Test
	void expandsPublicDataStandardizationQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uACF5\uACF5\uB370\uC774\uD130\uD3EC\uD138 \uACF5\uACF5\uB370\uC774\uD130\uBCA0\uC774\uC2A4 \uD45C\uC900\uD654 \uAD00\uB9AC \uB9E4\uB274\uC5BC\uC5D0\uC11C \uD45C\uC900\uC6A9\uC5B4\uB294 \uC65C \uAD00\uB9AC\uD574?"
		);

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("public_data_standardization");
		assertThat(join(plan.focusedKeywords())).contains("\uD45C\uC900\uC6A9\uC5B4", "\uB370\uC774\uD130 \uD45C\uC900");
		assertThat(plan.profile().directEvidenceGroups()).anySatisfy(group ->
			assertThat(group).contains("\uD45C\uC900\uC6A9\uC5B4")
		);
	}

	@Test
	void expandsPublicDataPreprocessingCoachingQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uACF5\uACF5\uB370\uC774\uD130 \uC804\uCC98\uB9AC \uCF54\uCE6D\uC740 \uC5B4\uB5A4 \uC808\uCC28\uB85C \uD574?"
		);

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("public_data_custom_support");
		assertThat(join(plan.focusedKeywords())).contains("\uB370\uC774\uD130 \uC804\uCC98\uB9AC \uC808\uCC28", "\uC624\uB958 \uC6D0\uC778 \uBD84\uC11D");
		assertThat(plan.profile().preferredSectionTypes()).contains("procedure");
	}

	@Test
	void expandsPseudonymAdditionalInfoQuestion() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uAC1C\uBCF4\uC704 \uAC00\uBA85\uC815\uBCF4 \uC790\uB8CC\uC5D0\uC11C \uCD94\uAC00\uC815\uBCF4\uB294 \uBD84\uB9AC\uBCF4\uAD00\uD574\uC57C \uD574?"
		);

		assertThat(plan.profile().entities()).extracting(QuestionEntity::id).contains("pseudonym_info");
		assertThat(join(plan.focusedKeywords())).contains("\uCD94\uAC00\uC815\uBCF4 \uBD84\uB9AC\uBCF4\uAD00");
		assertThat(plan.profile().directEvidenceGroups()).anySatisfy(group ->
			assertThat(group).contains("\uBD84\uB9AC\uBCF4\uAD00")
		);
	}

	@Test
	void expandsContractCompletionReportQuestionToCompletionInspectionAndPayment() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uACFC\uC5C5\uC9C0\uC2DC\uC11C \uC6A9\uC5ED\uAE30\uAC04\uC774 \uC548 \uB05D\uB0AC\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uD574\uB3C4 \uB418\uB098?"
		);

		assertThat(plan.profile().matchedPolicyIds()).contains("contract_completion");
		assertThat(plan.profile().preferredTargets()).contains("law", "admrul");
		assertThat(plan.profile().preferredSectionTypes()).contains("procedure", "period");
		assertThat(join(plan.focusedKeywords())).contains(
			"\uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
			"\uC644\uB8CC\uD1B5\uC9C0",
			"\uAC80\uC0AC",
			"\uB300\uAC00\uC758 \uC9C0\uAE09"
		);
		assertThat(plan.profile().directEvidenceGroups()).anySatisfy(group ->
			assertThat(group).contains("\uC6A9\uC5ED\uACC4\uC57D", "\uACFC\uC5C5\uB0B4\uC6A9\uC11C")
		);
		assertThat(plan.profile().directEvidenceGroups()).anySatisfy(group ->
			assertThat(group).contains("\uC644\uB8CC\uD1B5\uC9C0", "\uAC80\uC0AC", "\uB300\uAC00\uC758 \uC9C0\uAE09")
		);
	}

	@Test
	void genericResultReportQuestionDoesNotActivateContractCompletionPolicy() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC774\uBC88 \uBD84\uAE30 \uACB0\uACFC\uBCF4\uACE0\uC11C\uB294 \uC5B8\uC81C \uC791\uC131\uD574?"
		);

		assertThat(plan.profile().matchedPolicyIds()).doesNotContain("contract_completion");
		assertThat(join(plan.focusedKeywords()))
			.doesNotContain("\uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74")
			.doesNotContain("\uC644\uB8CC\uD1B5\uC9C0");
	}

	@Test
	void remainingWorkReportQuestionActivatesContractCompletionPolicyWithoutGenericReportOverreach() {
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"\uC5C5\uBB34\uAC00 \uB0A8\uC544 \uC788\uB294\uB370 \uACB0\uACFC\uBCF4\uACE0\uC11C\uB9CC \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB098?"
		);

		assertThat(plan.profile().matchedPolicyIds()).contains("contract_completion");
		assertThat(plan.profile().preferredTargets()).containsExactlyInAnyOrder("law", "admrul");
		assertThat(join(plan.focusedKeywords())).contains(
			"\uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
			"\uC644\uB8CC\uD1B5\uC9C0",
			"\uAC80\uC0AC"
		);
	}

	private String join(Iterable<String> values) {
		return String.join("\n", values);
	}
}
