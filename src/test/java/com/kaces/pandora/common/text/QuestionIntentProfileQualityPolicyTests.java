package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class QuestionIntentProfileQualityPolicyTests {

	@Test
	void permissionManagementPolicyRequiresLifecycleEvidenceBeyondNavigationText() {
		List<QuestionIntentProfile> profiles = List.of(
			QuestionIntentProfile.from("IRM 사용자 권한 가이드"),
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
