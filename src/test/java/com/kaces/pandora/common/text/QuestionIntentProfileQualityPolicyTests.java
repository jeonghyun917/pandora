package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionIntentProfileQualityPolicyTests {

	@Test
	void securityReviewTargetPolicyRequiresThreeConcreteOfficialTargetGroups() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("보안성검토 대상 시스템은?");

		assertThat(profile.matchedPolicyIds()).contains("security_review_target_scope");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("정보통신망 또는 정보시스템 구축", "정보시스템 구축"),
			List.of("민감정보 또는 고유식별정보를 처리", "민감정보", "고유식별정보"),
			List.of("주요정보통신기반시설")
		);
	}

	@Test
	void permissionManagementPolicyRequiresLifecycleEvidenceBeyondNavigationText() {
		QuestionIntentProfile discovery = QuestionIntentProfile.from("IRM 사용자 권한 가이드");
		assertThat(discovery.documentDiscoveryQuestion()).isTrue();
		assertThat(discovery.matchedPolicyIds()).contains("permission_management");
		assertThat(discovery.configuredAnswerCoverageGroups()).isEmpty();

		List<QuestionIntentProfile> profiles = List.of(
			QuestionIntentProfile.from("IRM 사용자관리 메뉴 조각 말고 사용자 권한 관리 기준은?"),
			QuestionIntentProfile.from("계정 권한은 관리자가 어떻게 부여하고 만료 시 회수해?")
		);

		assertThat(profiles).allSatisfy(profile -> {
			assertThat(profile.matchedPolicyIds()).contains("permission_management");
			assertThat(profile.intentTypes()).contains("operation_rule");
			assertThat(profile.preferredSectionTypes()).contains("operation_rule");
			assertThat(profile.directEvidenceGroups()).hasSizeGreaterThanOrEqualTo(2);
		});

		QuestionIntentProfile profile = profiles.get(0);
		assertThat(matchedDirectGroups(
			profile,
			"개별기관 관리자와 정보등록 담당자가 사용자 권한을 신청하고 승인받으며, "
				+ "권한 만료 시 자동 회수한다."
		)).isGreaterThanOrEqualTo(2);
		assertThat(matchedDirectGroups(
			profile,
			"관리자 메뉴에서 사용자 권한 화면으로 이동한 뒤 목록을 조회한다."
		)).as("menu/navigation text alone is not lifecycle evidence").isLessThan(2);
	}

	@Test
	void permissionManagementPolicyRequiresBothPermissionAndActorConcepts() {
		assertThat(QuestionIntentProfile.from("권한 메뉴 위치는 어디야?").matchedPolicyIds())
			.doesNotContain("permission_management");
		assertThat(QuestionIntentProfile.from("사용자 안내서는 어디 있어?").matchedPolicyIds())
			.doesNotContain("permission_management");
	}

	@Test
	void documentPurposePolicyAddsDirectPurposeEvidence() {
		List<QuestionIntentProfile> profiles = List.of(
			QuestionIntentProfile.from("개인정보 처리 통합 안내서는 왜 만든거야?"),
			QuestionIntentProfile.from("보안 가이드라인의 발간 목적은?"),
			QuestionIntentProfile.from("재난 대응 매뉴얼을 마련한 취지가 뭐야?")
		);

		assertThat(profiles).allSatisfy(profile -> {
			assertThat(profile.matchedPolicyIds()).contains("document_purpose");
			assertThat(profile.intentTypes()).contains("definition");
			assertThat(profile.directEvidenceGroups()).hasSizeGreaterThanOrEqualTo(2);
		});

		QuestionIntentProfile profile = profiles.get(0);
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("현장에서 이해하기 쉽도록", "현장에서 이해하기 쉽게"),
			List.of("준수해야 하는 사항", "준수사항")
		);
		assertThat(matchedDirectGroups(
			profile,
			"p.2 발간 목적. 개인정보 처리 현장에서 준수해야 하는 사항을 "
				+ "이해하기 쉽도록 안내하기 위해 발간하였다."
		)).isGreaterThanOrEqualTo(2);
		assertThat(QuestionIntentProfile.from("개인정보 처리 목적은 왜 필요한가?").matchedPolicyIds())
			.as("a legal processing-purpose question is not a document-purpose lookup")
			.doesNotContain("document_purpose");
		assertThat(QuestionIntentProfile.from("민원 처리 매뉴얼 마련 절차는?").matchedPolicyIds())
			.as("preparing a manual is not necessarily asking why it was published")
			.doesNotContain("document_purpose");
		assertThat(QuestionIntentProfile.from("안내서에 적힌 개인정보 처리 목적은?").matchedPolicyIds())
			.as("a purpose described inside a document is not the document's publication purpose")
			.doesNotContain("document_purpose");
	}

	@Test
	void bareProcessingInDocumentPurposeTitleIsNotAProcedureButExplicitProcedureCuesRemain() {
		List<String> purposeQuestions = List.of(
			"개인정보 처리 통합 안내서는 왜 만든거야?",
			"민원 신고 안내서는 왜 만든 거야?",
			"정부지원 신청 가이드의 발간 목적은?",
			"안내서에 적힌 개인정보 처리 목적은?"
		);
		assertThat(purposeQuestions)
			.map(QuestionIntentProfile::from)
			.allSatisfy(profile -> {
				assertThat(profile.intentTypes()).doesNotContain("procedure");
				assertThat(profile.preferredSectionTypes()).doesNotContain("procedure");
			});

		List<String> procedureQuestions = List.of(
			"개인정보 처리 통합 안내서의 제출 절차는?",
			"개인정보 처리 가이드 신청 방법은?",
			"민원 처리 매뉴얼 등록 방법은?",
			"개인정보 처리 안내서는 왜 만들었고 신청 절차는 어떻게 돼?"
		);
		assertThat(procedureQuestions)
			.map(QuestionIntentProfile::from)
			.allSatisfy(profile -> {
				assertThat(profile.intentTypes()).contains("procedure");
				assertThat(profile.preferredSectionTypes()).contains("procedure");
			});
	}

	@Test
	void supportIntentUsesKoreanWordBoundariesForBareSupportCue() {
		assertThat(QuestionIntentProfile.from("통합지원본부는 어떤 역할을 해?").intentTypes())
			.doesNotContain("support");
		assertThat(QuestionIntentProfile.from("지원본부는 어떤 역할을 해?").intentTypes())
			.as("a bare cue at the start still needs a trailing word boundary")
			.doesNotContain("support");
		assertThat(QuestionIntentProfile.from("지원은행은 어떤 기관이야?").intentTypes())
			.as("a compound noun is not a bare cue followed by the topic particle")
			.doesNotContain("support");
		assertThat(QuestionIntentProfile.from("지원학교는 어디야?").intentTypes())
			.as("a compound noun is not the bare cue followed by a verb ending")
			.doesNotContain("support");

		List<String> supportQuestions = List.of(
			"정부의 창업 지원 정책은 뭐야?",
			"정부 지원을 받을 수 있어?",
			"정부가 지원하는 정책은 뭐야?",
			"창업 지원사업은 뭐야?",
			"공공데이터 활용기업 맞춤형지원은 어떤 사업이야?"
		);
		assertThat(supportQuestions)
			.map(QuestionIntentProfile::from)
			.allSatisfy(profile -> assertThat(profile.intentTypes()).contains("support"));
	}

	@Test
	void privacyConsentNoticePolicyRequiresPrivacyConsentAndRefusalContext() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"개인정보 수집 동의 받을 때 거부권도 알려야 해?"
		);

		assertThat(profile.matchedPolicyIds()).contains("privacy_consent_notice");
		assertThat(profile.intentTypes()).contains("privacy_notice", "obligation");
		assertThat(profile.intentTypes()).doesNotContain("target_scope");
		assertThat(profile.preferredSectionTypes()).doesNotContain("target_scope");
		assertThat(profile.directEvidenceGroups())
			.as("the evidence judge evaluates at most two mandatory direct groups")
			.hasSize(2);
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("동의를 받을 때", "동의받을 때", "개인정보 수집 동의", "개인정보 수집·이용 동의"),
			List.of("동의를 거부할 권리", "동의 거부권", "거부권")
		);
		assertThat(matchedDirectGroups(
			profile,
			"개인정보 수집 동의를 받을 때에는 수집·이용 목적, 수집 항목, 보유 및 이용기간과 "
				+ "동의를 거부할 권리 및 거부에 따른 불이익을 알려야 한다."
		)).isEqualTo(2);
		assertThat(matchedDirectGroups(
			profile,
			"개인정보 수집 동의를 받을 때에는 수집·이용 목적과 보유기간을 알려야 한다."
		)).as("consent context without refusal rights is incomplete").isEqualTo(1);
		assertThat(matchedDirectGroups(
			profile,
			"정보주체에게 동의를 거부할 권리와 거부에 따른 불이익을 알려야 한다."
		)).as("refusal rights without consent context are incomplete").isEqualTo(1);

		assertThat(QuestionIntentProfile.from("개인정보 수집 목적과 보유기간은?").matchedPolicyIds())
			.doesNotContain("privacy_consent_notice");
		assertThat(QuestionIntentProfile.from("서비스 동의 거부권은 어떻게 안내해?").matchedPolicyIds())
			.doesNotContain("privacy_consent_notice");
	}

	@Test
	void autonomyPreConsultationProcedureRequiresRequestReviewAndResultAtoms() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"자치분권 사전협의 요청할 때 어떤 절차로 검토돼?"
		);

		assertThat(profile.matchedPolicyIds()).contains("autonomy_pre_consultation_procedure");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of(
				"사전협의 요청서 작성·제출",
				"사전협의 요청서를 작성",
				"사전협의 요청서"
			),
			List.of("지방자치 관련성 검토", "지방자치 관련성을 검토", "관련성 검토"),
			List.of("협의 결과서 통보", "결과 통보서 송부", "결과 통보")
		);
	}

	@Test
	void preConsultationTimingPolicyRequiresTheOfficialPlanAndRequestSequence() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"정보화사업 사전협의는 예산 편성 전에 하는 거야 사업계획 후에 하는 거야?"
		);

		assertThat(profile.matchedPolicyIds()).contains("pre_consultation_timing");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("사업계획을 수립한 후", "사업계획 수립 후"),
			List.of("지체 없이", "발주 최소 30일 전"),
			List.of("사전협의를 요청", "사전협의 신청")
		);
		assertThat(profile.policySearchKeywords())
			.contains("사업계획을 수립한 후 지체 없이 사전협의를 요청");
	}

	@Test
	void rfpRequiredItemsPolicyRequiresBothCoreItemPairs() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"공공기관 제안요청서 작성할때 필수요소가 있나?"
		);

		assertThat(profile.matchedPolicyIds()).contains("rfp_required_items");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("과업내용, 요구사항", "과업내용과 요구사항", "과업내용 및 요구사항"),
			List.of("계약조건, 평가요소", "계약조건과 평가요소", "계약조건 및 평가요소")
		);
	}

	@Test
	void egovPreliminaryReviewScopePolicyRequiresTheOfficialTargetRelationship() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"지능정보사회 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?"
		);

		assertThat(profile.matchedPolicyIds()).contains("egov_preliminary_review_scope");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("다음 해에 정보화사업을 추진", "다음 연도에 정보화사업을 추진"),
			List.of("중앙행정기관의 장", "시도지사", "시도 교육감"),
			List.of("예비검토를 신청", "예비검토 신청")
		);
		assertThat(profile.policySearchKeywords())
			.contains(
				"전자정부 성과관리 지침",
				"다음 해에 정보화사업을 추진",
				"중앙행정기관의 장",
				"예비검토를 신청"
			);
	}

	@Test
	void projectReviewPurchasePolicyRequiresTheSoftwareBusinessRuleAndItsBoundary() {
		for (String question : List.of(
			"단순 소프트웨어 구매면 과업심의 안해도 돼?",
			"공공소프트웨어사업에서 단순 하드웨어 구매는 소프트웨어사업에 포함되나요?",
			"하드웨어만 사는 사업도 공공SW 과업심의를 해야 해?"
		)) {
			QuestionIntentProfile profile = QuestionIntentProfile.from(question);

			assertThat(profile.matchedPolicyIds()).as(question)
				.contains("project_review_purchase_scope");
			assertThat(profile.configuredAnswerCoverageGroups()).as(question).containsExactly(
				List.of(
					"국가기관 등이 발주하는 모든 소프트웨어사업",
					"국가기관등이 발주하는 모든 SW사업",
					"소프트웨어사업에 해당"
				),
				List.of(
					"소프트웨어사업으로 볼 수 없는 경우는 비대상",
					"소프트웨어사업으로 볼 수 없는",
					"단순 하드웨어",
					"단순 H/W"
				)
			);
		}
	}

	@Test
	void genericProjectReviewTargetPolicyRequiresTheAskedRelationAndBusinessScope() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("과업심의 대상은?");

		assertThat(profile.matchedPolicyIds()).contains("project_review_target_scope");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of(
				"과업심의 대상",
				"과업심의 적용 대상",
				"과업심의 대상 사업",
				"과업심의를 받아야",
				"과업심의를 해야",
				"과업심의가 필요",
				"적용 대상 사업"
			),
			List.of(
				"국가기관 등이 발주하는 모든 소프트웨어사업",
				"국가기관등이 발주하는 모든 SW사업",
				"국가기관등이 발주하는 소프트웨어사업",
				"국가기관등의 장이 발주하는 소프트웨어사업",
				"공공소프트웨어사업",
				"소프트웨어사업에 해당"
			)
		);
	}

	@Test
	void preConsultationTargetPolicyRequiresBothInstitutionAndProjectScope() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"기타공공기관 사전협의 대상 알려줘"
		);

		assertThat(profile.matchedPolicyIds()).contains("pre_consultation_target_scope");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("공공기관", "중앙공공기관", "기타공공기관"),
			List.of(
				"대상기관이 추진하는 모든 정보화사업",
				"모든 정보화사업",
				"사전협의의 대상사업"
			)
		);
	}

	@Test
	void trafficCrosswalkStopPolicyRequiresThePedestrianConditionAndStopDuty() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"운전중 우회전할때 횡단보도에서 멈춰야 하나?"
		);

		assertThat(profile.matchedPolicyIds()).contains("traffic_crosswalk_stop");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of(
				"보행자가 횡단보도를 통행하고 있거나 통행하려고 하는 때",
				"횡단보도를 통행하고 있거나 통행하려고 하는 때",
				"보행자가 통행하고 있거나 통행하려고 하는 때",
				"보행자가 있거나 통행하려는 경우"
			),
			List.of("횡단보도 앞에서 일시정지", "횡단보도 앞 일시정지", "일시정지하여야")
		);
	}

	@Test
	void cctvPublicPlacePolicyRequiresTheProhibitionAndLegalExceptionTogether() {
		for (String question : List.of(
			"개인정보보호위원회 CCTV 안내서에서 공개된 장소에 CCTV를 설치할 수 있는 예외는?",
			"공개된 장소에 CCTV를 설치하는 건 원칙적으로 가능한가?"
		)) {
			QuestionIntentProfile profile = QuestionIntentProfile.from(question);

			assertThat(profile.matchedPolicyIds()).as(question)
				.contains("cctv_public_place_exception");
			assertThat(profile.configuredAnswerCoverageGroups()).as(question).containsExactly(
				List.of("공개된 장소", "공개된장소"),
				List.of("원칙적으로 금지", "원칙적 설치 금지"),
				List.of(
					"법령에서 구체적으로 허용",
					"법 제25조에서 정하는 사유",
					"법정 예외 사유"
				)
			);
		}
	}

	@Test
	void privacyMinimumCollectionPolicyRequiresPurposeAndMinimumScopeTogether() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"개인정보는 필요한 만큼만 수집해야 해?"
		);

		assertThat(profile.matchedPolicyIds()).contains("privacy_minimum_collection");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("처리 목적을 명확하게", "처리 목적이 명확"),
			List.of(
				"목적에 필요한 범위에서 최소한의 개인정보만을 적법하고 정당하게 수집",
				"필요한 범위에서 최소한의 개인정보만 수집",
				"필요한 최소한의 개인정보만 수집",
				"최소한의 개인정보만을 수집"
			)
		);
	}

	@Test
	void pseudonymAdditionalInformationPolicyRequiresProcessingStorageAndDestruction() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"개보위 가명정보 자료에서 추가정보는 분리보관해야 해?"
		);

		assertThat(profile.matchedPolicyIds()).contains("pseudonym_additional_information");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of(
				"가명정보를 처리하는 경우",
				"가명정보 처리 시",
				"가명정보를 처리할 때",
				"가명처리 수행",
				"가명처리를 수행",
				"가명처리하는 경우"
			),
			List.of(
				"추가정보를 가명정보와 분리",
				"가명정보와 추가정보의 분리보관",
				"가명정보와추가정보의분리보관",
				"추가정보를 별도 보관"
			),
			List.of(
				"불필요한 경우 파기",
				"불필요한경우 파기",
				"필요가 없어지면 추가정보를 파기"
			)
		);
	}

	@Test
	void directEvidenceRecoveryPoliciesDoNotCaptureAdjacentQuestionTypes() {
		assertThat(QuestionIntentProfile.from("과업심의 대상은?").matchedPolicyIds())
			.doesNotContain("project_review_purchase_scope");
		assertThat(QuestionIntentProfile.from("과업심의위원회 운영 절차는?").matchedPolicyIds())
			.doesNotContain("project_review_target_scope");
		assertThat(QuestionIntentProfile.from("정보화사업 사전협의는 언제 해야 해?").matchedPolicyIds())
			.doesNotContain("pre_consultation_target_scope");
		assertThat(QuestionIntentProfile.from("가장 가까운 횡단보도 위치는?").matchedPolicyIds())
			.doesNotContain("traffic_crosswalk_stop");
		assertThat(QuestionIntentProfile.from("CCTV 영상 보관기간은 얼마야?").matchedPolicyIds())
			.doesNotContain("cctv_public_place_exception");
		assertThat(QuestionIntentProfile.from("개인정보는 언제 파기해야 해?").matchedPolicyIds())
			.doesNotContain("privacy_minimum_collection");
		assertThat(QuestionIntentProfile.from("개인정보 수집 통계가 많이 늘었어?").matchedPolicyIds())
			.doesNotContain("privacy_minimum_collection");
		assertThat(QuestionIntentProfile.from("가명정보 처리 목적은 뭐야?").matchedPolicyIds())
			.doesNotContain("pseudonym_additional_information");
		assertThat(QuestionIntentProfile.from("가명정보의 추가정보는 별도 보관해야 하나?").matchedPolicyIds())
			.doesNotContain("pseudonym_additional_information");
	}

	@Test
	void whistleblowerScopeAndDisadvantagePoliciesSelectDirectProtectionAtoms() {
		QuestionIntentProfile scope = QuestionIntentProfile.from(
			"공익신고자 보호는 어디까지 가능해?"
		);
		QuestionIntentProfile disadvantage = QuestionIntentProfile.from(
			"공익신고자에게 불이익을 주면 어떤 보호를 받을 수 있어?"
		);

		assertThat(scope.matchedPolicyIds()).contains("whistleblower_protection_scope");
		assertThat(scope.configuredAnswerCoverageGroups()).containsExactly(
			List.of("신분비밀을 보장", "비밀보장"),
			List.of("신변보호조치", "신변보호"),
			List.of("보호조치를 권익위에 신청", "보호조치 신청")
		);
		assertThat(disadvantage.matchedPolicyIds())
			.contains("whistleblower_disadvantage_protection");
		assertThat(disadvantage.configuredAnswerCoverageGroups()).containsExactly(
			List.of(
				"공익신고등을 이유로 불이익조치를 받은 때",
				"공익신고등을 이유로 불이익 조치를 받은 때",
				"신고로 불이익을 받은 경우"
			),
			List.of("보호조치를 신청", "보호조치 신청"),
			List.of("신분비밀을 보장", "비밀보장")
		);
	}

	@Test
	void nationalSafetyPlanScopePolicyRequiresPeriodAndPlanDirection() {
		QuestionIntentProfile profile = QuestionIntentProfile.from(
			"제5차 국가안전관리 기본계획의 적용 기간과 주요 내용은 뭐야?"
		);

		assertThat(profile.matchedPolicyIds()).contains("national_safety_plan_scope");
		assertThat(profile.configuredAnswerCoverageGroups()).containsExactly(
			List.of("2025년 ~ 2029년", "2025년부터 2029년"),
			List.of("중장기 목표 및 기본방향", "중장기 목표와 기본방향")
		);
		assertThat(profile.policySearchKeywords())
			.contains("국가안전관리 기본계획 적용 기간 2025년 2029년");
	}

	@Test
	void explicitPolicySearchTermsSurviveTheProfileKeywordLimit() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("IRM 사용자 권한 가이드");

		assertThat(profile.policySearchKeywords())
			.contains(
				"사용자 권한 관리 기준",
				"권한 신청 및 승인",
				"권한 부여날짜",
				"권한 만료 시 자동 회수",
				"정보등록 담당자",
				"개별기관 관리자"
			);
		assertThat(profile.policySearchKeywords()).hasSizeLessThanOrEqualTo(24);
	}

	@Test
	void everyMatchedPolicySearchTermPrecedesAuxiliaryPolicyKeywords() {
		QuestionIntentProfile profile = QuestionIntentProfile.from("사용자 권한 가이드는 왜 만든 거야?");

		assertThat(profile.matchedPolicyIds()).contains("permission_management", "document_purpose");
		assertThat(profile.policySearchKeywords())
			.contains(
				"사용자 권한 관리 기준",
				"권한 만료 시 자동 회수",
				"발간 목적",
				"안내하기 위해 발간",
				"현장에서 이해하기 쉽도록",
				"준수해야 하는 사항"
			);
		assertThat(profile.policySearchKeywords()).hasSizeLessThanOrEqualTo(24);
	}

	private static long matchedDirectGroups(QuestionIntentProfile profile, String text) {
		String normalized = KoreanQueryNormalizer.normalizeForMatch(text);
		return profile.directEvidenceGroups().stream()
			.filter(group -> group.stream()
				.anyMatch(term -> normalized.contains(KoreanQueryNormalizer.normalizeForMatch(term))))
			.count();
	}
}
