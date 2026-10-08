package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimEvidenceAtomizerTests {

	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();
	@Test
	void closedLegalParagraphKeepsPrintedMultiLineAuthorityTogether() {
		String source = "제7조(준수 여부의 확인)\n① 장관은 국가기관등의 장이 소프트웨어\n"
			+ "사업을 추진하는 경우 법령 준수 여부를 확인하고 그 결과를\n공개할 수 있다.\n② 장관은 위반사항을 확인한 경우 개선을 권고할 수 있다.";
		assertThat(atomizer.atomizeSource(source, "준수 여부의 확인"))
			.anySatisfy(atom -> assertThat(atom).contains("장관은 국가기관등의 장이 소프트웨어", "사업을 추진하는 경우", "준수 여부를 확인하고"))
			.contains("장관은 그 결과를 공개할 수 있다.")
			.doesNotContain("장관은 국가기관등의 장이 소프트웨어");
	}

	@Test
	void legalWrappingDoesNotCrossBlankHeadingOrUnnumberedIntroduction() {
		for (String source : List.of(
			"제7조(기준)\n① 기관은 기준을\n\n준수해야 한다.\n② 다른 기준",
			"제7조(기준)\n① 기관은 기준을\n제8조(다른 기준)\n준수해야 한다.\n② 다른 기준",
			"설명에서는 다음 문장을 인용하지 않는다.\n① 기관은 기준을\n준수해야 한다.\n② 다른 기준")) {
			assertThat(atomizer.atomizeSource(source, "기준")).doesNotContain("기관은 기준을 준수해야 한다.");
		}
	}

	@Test
	void legalWrappingRejectsSpacedNewArticleHeadings() {
		for (String heading : List.of("제 8조(다른 기준)", "제8 조(다른 기준)")) {
			String source = "제7조(기준)\n① 기관은 기준을\n" + heading
				+ "\n준수해야 한다.\n② 다른 기준";
			assertThat(atomizer.atomizeSource(source, "기준"))
				.noneSatisfy(atom -> assertThat(atom).contains("기관은 기준을", "준수해야 한다"));
		}
	}

	@Test
	void closedLegalFirstParagraphMayBeginOnItsHeadingLine() {
		String source = "제7조(준수 확인) ① 장관은 국가기관등의 장이 소프트웨어\n"
			+ "사업을 추진하는 경우 준수 여부를 확인할 수 있다.\n② 장관은 개선을 권고할 수 있다.";
		assertThat(atomizer.atomizeSource(source, "준수 확인"))
			.anySatisfy(atom -> assertThat(atom).contains("장관은 국가기관등의 장이 소프트웨어 사업을", "확인할 수 있다"));
	}

	@Test
	void repeatedPageHeadingRetainsOneExplicitScopeHeading() {
		assertThat(atomizer.atomize("적용 대상 사업 p.5 적용 대상 사업 p.5 적용 대상 사업 국가기관 등이 발주하는 모든 SW사업"))
			.containsExactly("적용 대상 사업 국가기관 등이 발주하는 모든 SW사업");
	}

	@Test
	void sourceWrappedSubjectAndPredicateRemainOneCompleteClaim() {
		assertThat(atomizer.atomizeSource("발간 목적\n이 안내서는 현장에서 이해하기 쉽도록 개인정보\n"
			+ "처리 시 준수해야 하는 사항을 안내할 목적으로 마련되었습니다.\n제개정 이력", "처리"))
			.contains("이 안내서는 현장에서 이해하기 쉽도록 개인정보 처리 시 준수해야 하는 사항을 안내할 목적으로 마련되었습니다.")
			.doesNotContain("처리 시 준수해야 하는 사항을 안내할 목적으로 마련되었습니다.");
	}

	@Test
	void sourceContinuationPreservesNewSubjectExceptionAndBlankLineBoundaries() {
		assertThat(atomizer.atomizeSource("기관은 처리 기준을\n- 준수해야 했습니다.", "기준"))
			.contains("기관은 처리 기준을", "- 준수해야 했습니다.");
		assertThat(atomizer.atomizeSource("기관은 처리 기준을\n가. 준수해야 했습니다.", "기준"))
			.doesNotContain("기관은 처리 기준을 가. 준수해야 했습니다.");
		assertThat(atomizer.atomizeSource("기관은 처리 기준을\n다른 기관은 별도 기준을 적용합니다.", "기준"))
			.contains("기관은 처리 기준을", "다른 기관은 별도 기준을 적용합니다.");
		assertThat(atomizer.atomizeSource("기관은 처리 기준을\n다만 승인을 받아야 합니다.", "기준"))
			.contains("기관은 처리 기준을", "다만 승인을 받아야 합니다.");
		assertThat(atomizer.atomizeSource("기관은 처리 기준을\n\n준수해야 합니다.", "기준"))
			.contains("기관은 처리 기준을", "준수해야 합니다.");
	}

	@Test
	void preservesCommaContinuedSourceConditionAcrossWrappedLine() {
		assertThat(atomizer.atomizeSource("※ 등록요청 수 및 등록완료 수는 평가기간 동안 집계된 요청 수,\n"
			+ "완료 수를 모두 합산하여 산정\n○ 다음 평가기준", "평가방법"))
			.contains("등록요청 수 및 등록완료 수는 평가기간 동안 집계된 요청 수, 완료 수를 모두 합산하여 산정")
			.doesNotContain("등록요청 수 및 등록완료 수는 평가기간 동안 집계된 요청 수,");
	}

	@Test
	void commaContinuationDoesNotMergeIndependentSourceListItems() {
		assertThat(atomizer.atomizeSource("※ 요청 수,\n○ 별도 평가기준\n1. 등록 대상,\n2. 제외 대상", "평가방법"))
			.contains("요청 수,", "별도 평가기준", "등록 대상,", "제외 대상")
			.doesNotContain("요청 수, ○ 별도 평가기준", "등록 대상, 2. 제외 대상");
	}

	@Test
	void embeddedQualifiedHeadingPreservesAgencyInsteadOfGenericMetadata() {
		assertThat(atomizer.atomizeSource("사전 협의 후 의뢰한다.\n문화체육관광부 검토 대상\n"
			+ "1. 위임받은 사업\n2. 홈페이지 및 웹메일 등 웹기반 정보시스템 구축\n"
			+ "3. 인터넷전화시스템 구축", "p.2 검토 대상"))
			.contains("문화체육관광부 검토 대상: 홈페이지 및 웹메일 등 웹기반 정보시스템 구축")
			.doesNotContain("검토 대상: 홈페이지 및 웹메일 등 웹기반 정보시스템 구축");
	}

	@Test
	void embeddedHeadingDoesNotCrossExceptionOrSecondAgency() {
		assertThat(atomizer.atomizeSource("설명 문단.\n가기관 검토 대상\n1. 서버 구축\n2. 망 구축\n"
			+ "검토 생략 대상\n1. 단순 교체\n나기관 검토 대상\n1. 홈페이지 구축", "검토 대상"))
			.contains("가기관 검토 대상: 서버 구축")
			.doesNotContain("가기관 검토 대상: 단순 교체", "가기관 검토 대상: 홈페이지 구축");
	}

	@Test
	void embeddedHeadingRequiresMatchingMetadataAndImmediateFirstItem() {
		assertThat(atomizer.atomizeSource("설명.\n가기관 검토 대상\n주의 문단\n1. 서버 구축", "검토 대상"))
			.doesNotContain("가기관 검토 대상: 서버 구축");
		assertThat(atomizer.atomizeSource("설명.\n가기관 검토 대상\n1. 서버 구축", "제출 절차"))
			.doesNotContain("가기관 검토 대상: 서버 구축");
	}

	@Test
	void sourceScopePreservesCompleteItemsBeforeAnAmbiguousWrappedItem() {
		assertThat(atomizer.atomizeSource("기관 검토 대상\n1. 비밀 정보시스템 구축\n2.\n"
			+ "민감정보 처리 시스템 구축\n3. 주요 기반시설 구축\n4. 행정정보 등\n"
			+ "국가 차원의 데이터베이스 구축\n5. 홈페이지 구축\n※", "기관 검토 대상"))
			.contains("기관 검토 대상: 비밀 정보시스템 구축", "기관 검토 대상: 민감정보 처리 시스템 구축",
				"기관 검토 대상: 주요 기반시설 구축")
			.doesNotContain("기관 검토 대상: 행정정보 등", "기관 검토 대상: 홈페이지 구축");
	}

	@Test
	void standaloneListNumberDoesNotCarryScopeIntoAnotherHeading() {
		assertThat(atomizer.atomizeSource("기관 검토 대상\n1. 비밀 정보시스템 구축\n2.\n"
			+ "기관 자체 검토 대상\n1. 홈페이지 구축", "기관 검토 대상"))
			.doesNotContain("기관 검토 대상: 홈페이지 구축");
	}

	@Test
	void sourceScopeRequiresTheHeadingInTheBodyNotJustMetadata() {
		assertThat(atomizer.atomizeSource("1. 정보시스템 구축\n2. 기반시설 구축", "국가정보원 검토 대상"))
			.containsExactly("정보시스템 구축", "기반시설 구축");
	}

	@Test
	void sourceScopeDoesNotCrossNumberingReset() {
		assertThat(atomizer.atomizeSource("국가정보원 검토 대상\n1. 정보시스템 구축\n1. 홈페이지 구축",
			"국가정보원 검토 대상"))
			.doesNotContain("국가정보원 검토 대상: 홈페이지 구축");
	}

	@Test
	void sourceScopeDoesNotDistributeOverAnExceptionClause() {
		assertThat(atomizer.atomizeSource("기관 검토 대상\n1. 시스템은 검토 대상이며, 다만 단순 교체는 제외됩니다.",
			"기관 검토 대상"))
			.noneMatch(atom -> atom.startsWith("기관 검토 대상: 다만"));
	}

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
	void preservesCoordinatedPremisesBeforeConditionalConclusion() {
		for (String sentence : List.of(
			"SNS 운영 사업이 소프트웨어사업에 해당하고 발주기관이 국가기관등이면 과업심의 대상입니다.",
			"신청자가 성인이며 보호자가 동의한 경우 신청할 수 있습니다."
		)) {
			assertThat(atomizer.atomize(sentence)).containsExactly(sentence);
		}
	}

	@Test
	void returnsNoAtomsForMissingText() {
		assertThat(atomizer.atomize(null)).isEmpty();
		assertThat(atomizer.atomize("  \r\n ")).isEmpty();
	}
}
