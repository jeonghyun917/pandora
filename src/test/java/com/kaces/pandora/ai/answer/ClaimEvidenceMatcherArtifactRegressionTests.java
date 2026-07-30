package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimEvidenceMatcherArtifactRegressionTests {

	private final ClaimVerifier verifier = new ClaimVerifier();

	@Test
	void directSoftwareRuleSurvivesHardwareExceptionInTheSameGround() {
		String answer = "국가기관 등이 발주하는 모든 소프트웨어사업은 과업심의 적용 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공소프트웨어사업 과업심의 가이드",
			"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이며, "
				+ "단순 H/W 도입·설치는 소프트웨어사업으로 볼 수 없어 비대상입니다."
		)));

		assertThat(result.verifiedAnswer()).isEqualTo(answer);
		assertThat(result.contradictedClaims()).isEmpty();
	}

	@Test
	void explicitCctvExceptionSurvivesTheGeneralProhibition() {
		String answer = "범죄의 예방 및 수사를 위하여 필요한 경우 CCTV를 설치할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공개된 장소 설치는 원칙적으로 금지됩니다. "
				+ "예외적으로 범죄의 예방 및 수사를 위하여 필요한 경우 CCTV를 설치할 수 있습니다."
		)));

		assertThat(result.verifiedAnswer()).isEqualTo(answer);
		assertThat(result.contradictedClaims()).isEmpty();
	}

	@Test
	void unrelatedSkipListDoesNotConflictWithChecklistDuty() {
		String answer = "클라우드 이용 사업은 시스템 중요도 분류 체크리스트를 제출해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("클라우드 이용 사업은 시스템 중요도 분류 체크리스트를 포함하여 제출해야 합니다."),
			ground("일부 단순 용역은 보안성 검토 절차 이행 생략 대상입니다.")
		));

		assertThat(result.verifiedAnswer()).isEqualTo(answer);
		assertThat(result.contradictedClaims()).isEmpty();
	}

	@Test
	void unrelatedPrivacyProhibitionIsInsufficientNotContradictory() {
		String answer = "개인정보처리자는 개인정보 처리방침을 공개해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("개인정보취급자는 업무 목적 외 불필요한 접근을 금지합니다.")
		));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).isEmpty();
	}

	@Test
	void exactOppositeClaimStillFailsClosed() {
		String answer = "보안성검토 절차를 생략할 수 없습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("상기 항목에 해당하는 정보화사업은 보안성검토 절차를 생략할 수 있습니다.")
		));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).containsExactly(answer);
	}

	@Test
	void mergedWhistleblowerRoutesRemainFailClosed() {
		String answer = "이미 불이익을 받은 공익신고자는 불이익조치 금지 신청을 할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("불이익조치를 받을 우려가 명백한 경우 위원회에 불이익조치 금지를 신청할 수 있습니다."),
			ground("공익신고를 이유로 불이익조치를 받은 때에는 위원회에 보호조치를 신청할 수 있습니다.")
		));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void adverbInWhistleblowerProhibitionRequestIsNotAFalseContradiction() {
		String answer =
			"공익신고자등은 공익신고로 인한 불이익조치가 명백히 우려되면 "
				+ "위원회에 불이익조치 금지를 바로 신청할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("권익위에 불이익조치 금지를 신청할 수 있습니다.")
		));

		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isIn(
			answer,
			ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE
		);
	}

	@Test
	void conditionalCommercialSoftwareExclusionIsNotContradictedByASectionHeading() {
		String answer =
			"제외사항으로 민간투자형 소프트웨어사업 등 지침·법령상 명시된 제외사유에 "
				+ "해당하면 직접구매 대상에서 제외될 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground(
				"소프트웨어 사업 계약 및 관리감독에 관한 지침",
				"제7조(상용소프트웨어 직접구매 대상)"
			)
		));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void separateCatalogDefinitionsCannotBeMergedIntoOneCompositeActorClaim() {
		String answer =
			"디지털카탈로그는 조달청이 상품정보를 제시해 "
				+ "수요기관이 선택하는 구매 경로입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"1. \"디지털카탈로그\"란 업체가 제공하는 상품설명서를 말한다."
				+ "2. \"디지털서비스 카탈로그계약\"이란 수요기관이 상품을 선택하여 "
				+ "구매하는 공급계약을 말한다."
				+ "3. \"종합쇼핑몰\"이란 조달청이 상품정보를 제공하는 온라인 쇼핑몰을 말한다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void universalInformationProjectScopeNeedsUniversalProjectEvidence() {
		String answer =
			"대상기관은 중앙·지방·공공기관 등 대상기관이 추진하는 모든 정보화사업입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"각 기관은 추진(발주) 예정인 사전협의 대상사업을 "
				+ "「기관별 정보화사업 사전협의 추진계획」에 포함하여 제출 "
				+ "* 중앙행정기관의 정보화 업무를 출연·위탁받은 "
				+ "공공기관·공기업 정보화사업 포함 ➋ 사전협의 신청"
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void partialPreliminaryReviewExamplesCannotBecomeABroadTargetList() {
		String answer =
			"대상은 지능정보사회실행계획에 포함되는 정보시스템 구축·운영·공공앱 개발·"
				+ "공공 AI 사업 등 정보화 관련 사업입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공 AI 사업 및 공공앱 개발 사업은 예비검토와 사전협의를 강화합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void securityReviewTableOfContentsCannotBecomeDirectTargetEvidence() {
		String answer = "정보화사업은 보안성 검토 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"목차 Ⅰ. 보안성 검토 개요 1 Ⅱ. 정보화사업 대상 사업 및 시기 2 "
				+ "Ⅲ. 추진체계 및 역할 4 [별첨 1] 보안성 검토 신청서 9 "
				+ "[참조 1] 주요 검토내용 13"
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.evidenceLinks()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void separationDutySurvivesTheUnneededInformationException() {
		String answer = "가명정보의 추가정보는 가명정보와 분리하여 보관해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"가명정보의 추가정보는 가명정보와 분리하여 보관해야 합니다. "
				+ "다만, 추가정보가 불필요한 경우에는 파기해야 합니다."
		)));

		assertThat(result.verifiedAnswer()).isEqualTo(answer);
		assertThat(result.contradictedClaims()).isEmpty();
	}

	@Test
	void cautionOnlyStatementIsNotTreatedAsAContradiction() {
		String answer = "금액·비율 등 구체적인 적용 기준은 문서별 세부 규정에 따라 달라질 수 있으므로 별도 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"사업계획서는 추진 배경, 사업 범위, 소요예산 및 세부산출내역을 포함합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.contradictedClaims()).isEmpty();
	}

	@Test
	void projectReviewConditionUsesTheClassifiedBusinessInsteadOfTheGenericVerb() {
		String answer =
			"단순 소프트웨어 구매라도 그 사업이 소프트웨어사업에 해당하면 과업심의 대상이다, "
				+ "과업심의는 소프트웨어사업 해당 여부를 기준으로 판단한다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공소프트웨어사업 과업심의 가이드",
			"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).contains("소프트웨어사업에 해당하면 과업심의 대상");
	}

	@Test
	void directDefinitionSurvivesAnUnsupportedCommaJoinedAside() {
		String answer =
			"대상사업은 대상기관이 추진하는 모든 정보화사업입니다, "
				+ "예산과목·계약방식과 무관합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"정보화사업 사전협의 안내",
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
				+ "대상기관이 추진하는 모든 정보화사업임"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).contains("대상기관이 추진하는 모든 정보화사업");
	}

	@Test
	void patientTopicAlignsWithTheSameObjectOfAnExplicitDutyBearer() {
		String answer = "개인정보는 처리 목적에 필요한 범위에서 최소한으로 수집해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"개인정보 보호법",
			"개인정보처리자는 개인정보의 처리 목적을 명확하게 하여야 하고 "
				+ "그 목적에 필요한 범위에서 최소한의 개인정보만을 적법하고 정당하게 "
				+ "수집하여야 한다."
		)));

		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	private LawAiAnswerGround ground(String snippet) {
		return ground("공식 문서", snippet);
	}

	private LawAiAnswerGround ground(String title, String snippet) {
		return new LawAiAnswerGround(
			1,
			1,
			1,
			"official_doc",
			title,
			"기관",
			"official_doc",
			null,
			null,
			"page 1",
			"근거",
			1,
			snippet,
			null,
			null,
			0.9
		);
	}
}
