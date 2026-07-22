package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimEvidenceAtomizerTests {

	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();

	@Test
	void separatesBroadRuleFromItsException() {
		assertThat(atomizer.atomize(
			"모든 소프트웨어사업은 과업심의 대상이며, 단순 H/W 도입은 비대상입니다."
		)).containsExactly(
			"모든 소프트웨어사업은 과업심의 대상이며",
			"단순 H/W 도입은 비대상입니다."
		);
	}

	@Test
	void separatesGeneralProhibitionFromAllowedException() {
		assertThat(atomizer.atomize(
			"공개된 장소 설치는 원칙적으로 금지됩니다. "
				+ "예외적으로 범죄 예방을 위해 설치할 수 있습니다."
		)).containsExactly(
			"공개된 장소 설치는 원칙적으로 금지됩니다.",
			"예외적으로 범죄 예방을 위해 설치할 수 있습니다."
		);
	}

	@Test
	void separatesCoordinatedGeneralProhibitionFromScopedAllowedException() {
		assertThat(atomizer.atomize(
			"결론부터 말하면, 공개된 장소에 CCTV(고정형 영상정보처리기기)를 설치하는 것은 "
				+ "원칙적으로 금지되어 있으며, 법 제25조에서 정한 사유에 해당하는 경우에만 "
				+ "예외적으로 허용됩니다."
		)).containsExactly(
			"결론부터 말하면, 공개된 장소에 CCTV(고정형 영상정보처리기기)를 설치하는 것은 "
				+ "원칙적으로 금지되어 있으며",
			"법 제25조에서 정한 사유에 해당하는 경우에만 예외적으로 허용됩니다."
		);
	}

	@Test
	void splitsOcrListMarkersButKeepsConditionWithItsConclusion() {
		assertThat(atomizer.atomize(
			"검토 항목 • 접근권한을 분리해야 합니다. ※ 분리가 불필요한 경우 파기할 수 있습니다."
		)).containsExactly(
			"검토 항목",
			"접근권한을 분리해야 합니다.",
			"분리가 불필요한 경우 파기할 수 있습니다."
		);
	}

	@Test
	void splitsOcrAsteriskAndMiddleDotBulletsWithoutWhitespace() {
		assertThat(atomizer.atomize(
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
				+ "대상기관이 추진하는 모든 정보화사업임 "
				+ "*디지털서비스전문계약제도이용계약,공모,R&D 등은 계약방식에 관계없음 "
				+ "∙사업금액이 기준 미만인 사업은 제외하되 신규사업은 대상에 포함"
		)).containsExactly(
			"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
				+ "대상기관이 추진하는 모든 정보화사업임",
			"디지털서비스전문계약제도이용계약,공모,R&D 등은 계약방식에 관계없음",
			"사업금액이 기준 미만인 사업은 제외하되",
			"신규사업은 대상에 포함"
		);
	}

	@Test
	void keepsCommaDelimitedConditionAndConclusionTogether() {
		assertThat(atomizer.atomize(
			"법령에서 정한 경우, 정보화사업은 검토 대상입니다."
		)).containsExactly("법령에서 정한 경우, 정보화사업은 검토 대상입니다.");
	}

	@Test
	void keepsThresholdConditionAndConclusionTogether() {
		assertThat(atomizer.atomize(
			"지원 요건은 10억원 이상이며, 사업은 대상입니다."
		)).containsExactly("지원 요건은 10억원 이상이며, 사업은 대상입니다.");
		assertThat(atomizer.atomize(
			"지원 요건은 10억원 이상이고 사업은 대상입니다."
		)).containsExactly("지원 요건은 10억원 이상이고 사업은 대상입니다.");
	}

	@Test
	void keepsAHeadlessTargetPredicateWithItsRestrictiveRemainder() {
		assertThat(atomizer.atomize(
			"과업심의 대상이며, 예산이 10억원 이상인 사업만 신청할 수 있습니다."
		)).containsExactly(
			"과업심의 대상이며, 예산이 10억원 이상인 사업만 신청할 수 있습니다."
		);
	}

	@Test
	void keepsAnExplicitSubjectWithItsHeadlessRestrictiveRemainder() {
		assertThat(atomizer.atomize(
			"사업은 과업심의 대상이며, 법령에서 정한 경우에 한합니다."
		)).containsExactly(
			"사업은 과업심의 대상이며, 법령에서 정한 경우에 한합니다."
		);
	}

	@Test
	void keepsPermissionWithItsRestrictiveRemainder() {
		for (String evidence : List.of(
			"기관은 신청할 수 있지만 특별한 사유가 필요합니다.",
			"기관은 신청하고 특별한 사유가 있는 경우에 한합니다.",
			"기관은 신청하되 특별한 사유가 있는 경우에 한합니다.",
			"기관은 신청할 수 있지만 기관은 별도의 승인을 받아야 합니다.",
			"기관은 신청할 수 있지만 사전 인증을 받아야 합니다.",
			"기관은 신청할 수 있지만 보증금을 납부해야 합니다.",
			"기관은 신청할 수 있지만 허가가 있어야 합니다.",
			"기관은 신청할 수 있지만 승인을 전제로 합니다."
		)) {
			assertThat(atomizer.atomize(evidence))
				.as("evidence=%s", evidence)
				.containsExactly(evidence);
		}
	}

	@Test
	void separatesRestrictiveLookingDutyForADifferentExplicitSubject() {
		assertThat(atomizer.atomize(
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이며 "
				+ "단순 H/W 도입·설치는 비대상으로 확인해야 한다."
		)).containsExactly(
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이며",
			"단순 H/W 도입·설치는 비대상으로 확인해야 한다."
		);
	}

	@Test
	void separatesContrastiveClausesWithDistinctExplicitObjects() {
		assertThat(atomizer.atomize(
			"보조금을 신청하는 것을 금지하지만 이의를 신청할 수 있습니다."
		)).containsExactly(
			"보조금을 신청하는 것을 금지하지만",
			"이의를 신청할 수 있습니다."
		);
	}

	@Test
	void keepsContrastiveClausesTogetherWhenTheyShareAnExplicitObject() {
		assertThat(atomizer.atomize(
			"기관은 보조금을 신청했지만 보조금을 철회할 수 있습니다."
		)).containsExactly(
			"기관은 보조금을 신청했지만 보조금을 철회할 수 있습니다."
		);
	}

	@Test
	void separatesRepeatedObjectsWhenPermissionPolarityChangesAcrossContrast() {
		assertThat(atomizer.atomize(
			"기관은 보조금을 신청할 수 없지만 보조금을 철회할 수 있습니다."
		)).containsExactly(
			"기관은 보조금을 신청할 수 없지만",
			"보조금을 철회할 수 있습니다."
		);
	}

	@Test
	void separatesRepeatedObjectsWhenImpossibilityChangesToPermission() {
		assertThat(atomizer.atomize(
			"기관은 보조금을 신청하는 것이 불가능하지만 보조금을 철회할 수 있습니다."
		)).containsExactly(
			"기관은 보조금을 신청하는 것이 불가능하지만",
			"보조금을 철회할 수 있습니다."
		);
	}

	@Test
	void keepsInlinePageReferenceTogether() {
		assertThat(atomizer.atomize(
			"근거는 p. 15 참조"
		)).containsExactly("근거는 p. 15 참조");
	}

	@Test
	void preservesRepeatedCircleCharactersInsideAnOrganizationName() {
		assertThat(atomizer.atomize(
			"○○기관은 검토 대상입니다."
		)).containsExactly("○○기관은 검토 대상입니다.");
	}

	@Test
	void removesNumericListMarkersFromSplitItems() {
		assertThat(atomizer.atomize(
			"항목 1) 제출해야 합니다. 2) 공개해야 합니다."
		)).containsExactly(
			"항목",
			"제출해야 합니다.",
			"공개해야 합니다."
		);
	}

	@Test
	void distinguishesDottedListMarkersFromDottedDates() {
		assertThat(atomizer.atomize(
			"항목 1. 제출해야 합니다. 2. 공개해야 합니다."
		)).containsExactly(
			"항목",
			"제출해야 합니다.",
			"공개해야 합니다."
		);
		assertThat(atomizer.atomize(
			"평가기간은 2025. 12. 17 ~ 2026. 10. 31. 입니다."
		)).containsExactly("평가기간은 2025. 12. 17 ~ 2026. 10. 31. 입니다.");
	}

	@Test
	void splitsADigitEndedSentenceBeforeANewExplicitSubject() {
		assertThat(atomizer.atomize(
			"신청기한은 2026. 10. 31. 중소기업은 지원 대상입니다."
		)).containsExactly(
			"신청기한은 2026. 10. 31.",
			"중소기업은 지원 대상입니다."
		);
	}

	@Test
	void splitsOcrNumericListMarkersThatOmitWhitespaceAfterTheDot() {
		assertThat(atomizer.atomize(
			"2.가명정보와 추가정보는 분리 보관해야 함 "
				+ "다만, 불필요한 경우 파기해야 함 "
				+ "3.접근권한 분리가 어려운 경우 최소 권한만 부여해야 함"
		)).containsExactly(
			"가명정보와 추가정보는 분리 보관해야 함",
			"다만, 불필요한 경우 파기해야 함",
			"접근권한 분리가 어려운 경우 최소 권한만 부여해야 함"
		);
	}

	@Test
	void splitsInlineQuotedDefinitionsThatOmitWhitespaceBeforeTheNextNumber() {
		assertThat(atomizer.atomize(
			"1. \"카탈로그\"란 업체가 제공하는 상품설명서를 말한다."
				+ "2. \"카탈로그계약\"이란 수요기관이 선택하는 공급계약을 말한다."
				+ "3. \"종합쇼핑몰\"이란 조달청이 운영하는 온라인 쇼핑몰을 말한다."
		)).containsExactly(
			"\"카탈로그\"란 업체가 제공하는 상품설명서를 말한다.",
			"\"카탈로그계약\"이란 수요기관이 선택하는 공급계약을 말한다.",
			"\"종합쇼핑몰\"이란 조달청이 운영하는 온라인 쇼핑몰을 말한다."
		);
	}

	@Test
	void keepsLegalSubparagraphCitationsTogether() {
		assertThat(atomizer.atomize(
			"법 제 2.가목에 따른 기준을 적용해야 합니다."
		)).containsExactly("법 제 2.가목에 따른 기준을 적용해야 합니다.");
	}

	@Test
	void keepsLegalSubparagraphCitationsWithRepeatedWhitespaceTogether() {
		assertThat(atomizer.atomize(
			"법 제  2.가목에 따른 기준을 적용해야 합니다."
		)).containsExactly("법 제 2.가목에 따른 기준을 적용해야 합니다.");
	}

	@Test
	void keepsDescriptiveScopeWithItsConclusion() {
		assertThat(atomizer.atomize(
			"법령에서 정한 사업이고, 기관은 심의를 받아야 합니다."
		)).containsExactly("법령에서 정한 사업이고, 기관은 심의를 받아야 합니다.");
	}

	@Test
	void separatesDistinctSubjectMarkedPermissionTargetsAcrossContrast() {
		assertThat(atomizer.atomize(
			"보조금 신청은 가능하지만 이의 신청은 불가능합니다."
		)).containsExactly(
			"보조금 신청은 가능하지만",
			"이의 신청은 불가능합니다."
		);
	}

	@Test
	void separatesConflictingPermissionForTheSameSubjectMarkedTarget() {
		assertThat(atomizer.atomize(
			"보조금 신청은 가능하지만 보조금 신청은 불가능합니다."
		)).containsExactly(
			"보조금 신청은 가능하지만",
			"보조금 신청은 불가능합니다."
		);
	}

	@Test
	void separatesIndependentClausesAcrossGeneralConnectives() {
		assertThat(atomizer.atomize(
			"자료는 보관하고 개인정보는 삭제합니다."
		)).containsExactly("자료는 보관하고", "개인정보는 삭제합니다.");
		assertThat(atomizer.atomize(
			"자료는 공개하되 개인정보는 비공개합니다."
		)).containsExactly("자료는 공개하되", "개인정보는 비공개합니다.");
		assertThat(atomizer.atomize(
			"자료는 공개되며 개인정보는 비공개됩니다."
		)).containsExactly("자료는 공개되며", "개인정보는 비공개됩니다.");
		assertThat(atomizer.atomize(
			"기관은 담당자이며 사업자는 책임자입니다."
		)).containsExactly("기관은 담당자이며", "사업자는 책임자입니다.");
	}

	@Test
	void separatesIndependentClausesWhenTheMatrixSubjectIsRepeated() {
		assertThat(atomizer.atomize(
			"기관은 자료를 보관하고 기관은 개인정보를 삭제합니다."
		)).containsExactly(
			"기관은 자료를 보관하고",
			"기관은 개인정보를 삭제합니다."
		);
		assertThat(atomizer.atomize(
			"기관은 자료를 공개하되 기관은 개인정보를 비공개합니다."
		)).containsExactly(
			"기관은 자료를 공개하되",
			"기관은 개인정보를 비공개합니다."
		);
	}

	@Test
	void separatesCommaJoinedIndependentAssertions() {
		assertThat(atomizer.atomize(
			"대상사업은 대상기관이 추진하는 모든 정보화사업입니다, "
				+ "예산과목·계약방식과 무관합니다."
		)).containsExactly(
			"대상사업은 대상기관이 추진하는 모든 정보화사업입니다",
			"예산과목·계약방식과 무관합니다."
		);
		assertThat(atomizer.atomize(
			"그 사업이 소프트웨어사업에 해당하면 과업심의 대상이다, "
				+ "과업심의는 소프트웨어사업 해당 여부를 기준으로 판단한다."
		)).containsExactly(
			"그 사업이 소프트웨어사업에 해당하면 과업심의 대상이다",
			"과업심의는 소프트웨어사업 해당 여부를 기준으로 판단한다."
		);
	}

	@Test
	void formalContrastiveEndingIsNotSplitAsAnExceptionMarker() {
		assertThat(atomizer.atomize(
			"신청 자격이 있습니다만 별도 확인이 필요합니다."
		)).containsExactly("신청 자격이 있습니다만 별도 확인이 필요합니다.");
	}

	@Test
	void separatesDistinctRolesAndCarriesAnOmittedMatrixSubject() {
		assertThat(atomizer.atomize(
			"기관은 자료를 보관하고 개인정보를 삭제합니다."
		)).containsExactly(
			"기관은 자료를 보관하고",
			"기관은 개인정보를 삭제합니다."
		);
		assertThat(atomizer.atomize(
			"기관은 아동에게 통지하고 성인에게 안내합니다."
		)).containsExactly(
			"기관은 아동에게 통지하고",
			"기관은 성인에게 안내합니다."
		);
		assertThat(atomizer.atomize(
			"기관은 기존 자료를 보관하고 신규 자료를 삭제합니다."
		)).containsExactly(
			"기관은 기존 자료를 보관하고",
			"기관은 신규 자료를 삭제합니다."
		);
		assertThat(atomizer.atomize(
			"기관은 취약 아동에게 통지하고 일반 아동에게 안내합니다."
		)).containsExactly(
			"기관은 취약 아동에게 통지하고",
			"기관은 일반 아동에게 안내합니다."
		);
	}

	@Test
	void separatesEveryClauseInASharedSubjectChain() {
		assertThat(atomizer.atomize(
			"기관은 자료를 보관하고 개인정보를 삭제하고 결과를 통지합니다."
		)).containsExactly(
			"기관은 자료를 보관하고",
			"기관은 개인정보를 삭제하고",
			"기관은 결과를 통지합니다."
		);
	}

	@Test
	void separatesRolelessPermissionActionsAcrossContrast() {
		assertThat(atomizer.atomize(
			"설치할 수 있지만 운영할 수 없습니다."
		)).containsExactly(
			"설치할 수 있지만",
			"운영할 수 없습니다."
		);
		assertThat(atomizer.atomize(
			"기관은 아동에게 통지했지만 성인에게 안내합니다."
		)).containsExactly(
			"기관은 아동에게 통지했지만",
			"기관은 성인에게 안내합니다."
		);
	}

	@Test
	void doesNotTreatAnEmbeddedAttributiveSubjectAsANewMatrixClause() {
		for (String evidence : List.of(
			"기관은 요청했지만 사업자가 제출한 자료를 검토합니다.",
			"기관은 보관하고 사업자가 제공한 자료를 삭제합니다.",
			"기관은 요청하고 사업자가 적법하게 제출한 자료를 검토합니다.",
			"기관은 요청하고 사업자가 법령에 따라 제출한 자료를 검토합니다.",
			"기관은 요청하고 사업자가 2026년에 제출한 자료를 검토합니다.",
			"기관은 요청하고 사업자가 승인을 받고 제출한 자료를 검토합니다."
		)) {
			assertThat(atomizer.atomize(evidence))
				.as("evidence=%s", evidence)
				.containsExactly(evidence);
		}
	}

	@Test
	void keepsSharedSubjectPredicateCoordinationTogether() {
		assertThat(atomizer.atomize(
			"자료는 보관하고 삭제합니다."
		)).containsExactly("자료는 보관하고 삭제합니다.");
		assertThat(atomizer.atomize(
			"기관은 담당자이며 책임자입니다."
		)).containsExactly("기관은 담당자이며 책임자입니다.");
	}

	@Test
	void returnsNoAtomsForMissingText() {
		assertThat(atomizer.atomize(null)).isEmpty();
		assertThat(atomizer.atomize("  \r\n ")).isEmpty();
	}
}
