package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class KoreanEvidenceAtomParserTests {

	@Test
	void negativeAntecedentIsPreservedWithoutNegatingItsExplicitDutyConclusion() {
		var atom = new KoreanEvidenceAtomParser().parse(
			"발주기관이 법령을 준수하지 않으면, 발주기관은 자료를 보완해야 한다.");
		org.junit.jupiter.api.Assertions.assertAll(
			() -> assertThat(atom.conditions()).contains("발주기관이법령을준수하지않"),
			() -> assertThat(atom.actions()).containsExactly("보완"),
			() -> assertThat(atom.objects()).containsExactly("자료"),
			() -> assertThat(atom.subjects()).containsExactly("발주기관"),
			() -> assertThat(atom.modality()).isEqualTo(EvidenceAtom.Modality.REQUIRED),
			() -> assertThat(atom.polarity()).isEqualTo(EvidenceAtom.Polarity.POSITIVE));
	}

	@Test
	void negativeAntecedentCannotHideAnUnparsedOversizedCondition() {
		var atom = new KoreanEvidenceAtomParser().parse(
			"매우 긴 조건 ".repeat(50) + "법령을 준수하지 않으면, 발주기관은 자료를 보완해야 한다.");
		assertThat(atom.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.AMBIGUOUS);
	}

	@Test
	void negativeAntecedentCannotBorrowTheActorOfALaterSentence() {
		var atom = new KoreanEvidenceAtomParser().parse(
			"기관이 법령을 준수하지 않으면, 자료를 보완해야 한다. 업체는 비용을 지급해야 한다.");
		assertThat(atom.subjects()).contains("기관", "업체");
		assertThat(atom.polarity()).isEqualTo(EvidenceAtom.Polarity.NEGATIVE);
	}

	@Test
	void negativeAntecedentRejectsMixedConclusionsAndNestedConditions() {
		for (String text : List.of(
			"법제도를 준수하지 않으면 신청인은 자료를 제출해야 하고 발주기관은 과태료를 납부해야 한다.",
			"법제도를 준수하지 않으면 신청인은 자료를 제출하지 않은 경우에만 과태료를 납부해야 한다.")) {
			assertThat(new KoreanEvidenceAtomParser().parse(text).parseStatus()).as(text)
				.isEqualTo(EvidenceAtom.ParseStatus.AMBIGUOUS);
		}
	}

	@Test
	void membershipProjectionCannotBorrowAnActionFromAnEarlierSentence() {
		var atom = new KoreanEvidenceAtomParser().parse(
			"민간기관은 자료를 공개해야 한다. 사업이 SW사업에 해당하면 발주기관은 심의를 해야 한다.");
		assertThat(atom.subjects()).contains("민간기관", "발주기관");
	}

	@Test
	void membershipPremiseIsNotAConclusionRelationOrActor() {
		var atom = new KoreanEvidenceAtomParser().parse(
			"만약 온라인 운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하는 경우, 발주기관은 자료를 통지해야 한다.");
		assertThat(atom.subjects()).containsExactly("발주기관");
		assertThat(atom.relations()).doesNotContain("해당", "포함");
		assertThat(atom.targetScopes()).isEmpty();
		assertThat(atom.conditions()).anySatisfy(condition ->
			assertThat(condition).contains("국가기관", "발주", "sw사업", "상용sw", "해당"));
	}

	@Test
	void exceptionConditionInstitutionHeadIsNotTheConclusionActor() {
		var atom = new KoreanEvidenceAtomParser().parse(
			"다만 국가기관등의 장은 참석할 수 없는 경우에는 발주기관은 자료를 통지해야 한다.");
		assertThat(atom.subjects()).containsExactly("발주기관");
	}
	@Test
	void exceptionTriggerDoesNotAbsorbItsExplicitActorAndConclusion() {
		var atom = parser.parse("다만, 일정이 부족한 경우에는 발주기관은 계약체결 전까지 심의를 받아야 한다.");
		assertThat(atom.exceptions()).containsExactly("일정이부족");
		assertThat(atom.subjects()).contains("발주기관");
		assertThat(atom.conditions()).contains("계약체결전까지", "일정이부족");
		assertThat(atom.actions()).contains("심의");
		assertThat(atom.modality()).isEqualTo(EvidenceAtom.Modality.REQUIRED);
		assertThat(parser.parse("다만, 일정이 충분한 경우에는 발주기관은 계약체결 전까지 심의를 받아야 한다.").exceptions())
			.doesNotContain("일정이부족");
	}

	@Test
	void triggerOnlyExceptionRemainsARequiredCondition() {
		var atom=parser.parse("발주기관은 심의를 받아야 한다. 다만 긴급사업의 경우.");
		assertThat(atom.conditions()).contains("긴급사업의경우");
		assertThat(atom.exceptions()).containsExactly("긴급사업의경우");
	}

	@Test
	void preservesIssuerAndBusinessClassInParenthesizedMembershipCondition() {
		for (String ending : List.of("해당하면", "해당하는 경우")) {
			var atom = parser.parse("온라인 운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 "
				+ ending + " 과업심의 대상입니다.");
			assertThat(atom.conditions()).as(ending).isNotEmpty();
			assertThat(String.join("", atom.conditions())).as(ending)
				.contains("국가기관", "발주", "sw사업", "상용sw");
		}
	}

	@Test
	void recognizesRequiredEoyaEndingWithoutConvertingPermissionToDuty() {
		var parser = new KoreanEvidenceAtomParser();
		for (String text : List.of(
			"발주기관은 심의위원회를 두어야 한다.",
			"발주기관은심의위원회를두어야한다.",
			"신청인은 절차를 거쳐야 합니다."
		)) {
			assertThat(parser.parse(text).modality()).as(text).isEqualTo(EvidenceAtom.Modality.REQUIRED);
		}
		assertThat(parser.parse("발주기관은 심의위원회를 둘 수 있다.").modality())
			.isNotEqualTo(EvidenceAtom.Modality.REQUIRED);
		assertThat(parser.parse("발주기관은 심의를 할 수 있다.").modality())
			.isEqualTo(EvidenceAtom.Modality.PERMITTED);
	}

	private final KoreanEvidenceAtomParser parser = new KoreanEvidenceAtomParser();

	@Test
	void explicitBeforeActionTimingRemainsACondition() {
		assertThat(parser.parse("발주기관은 발주 전에 자료를 통지해야 한다.").conditions()).contains("발주전");
		assertThat(parser.parse("신청인은 제출 전까지 자료를 확인해야 한다.").conditions()).contains("제출전까지");
		assertThat(parser.parse("발주기관은 발주 후 자료를 통지해야 한다.").conditions()).doesNotContain("발주전");
	}

	@Test
	void explicitObjectActionPurposeRemainsACondition() {
		assertThat(parser.parse("국가기관등의 장은 과업내용을 확정하기 위하여 소프트웨어사업 발주 전에 과업심의위원회의 심의를 받아야 한다.").conditions())
			.contains("과업내용확정");
		assertThat(parser.parse("발주기관은 사업계획을 변경하기 위하여 자료를 통지해야 한다.").conditions())
			.contains("사업계획변경");
	}

	@Test
	void institutionHeadIsAnActorAndIntentClauseIsNotAnActor() {
		var atom = parser.parse("국가기관등의 장은 소프트웨어사업의 과업내용을 확정하려는 경우에는 과업심의위원회의 심의ㆍ의결을 거쳐야 한다.");
		assertThat(atom.subjects()).containsExactly("국가기관등의장");
		assertThat(parser.parse("지방자치단체의 장은 사업을 추진하려는 경우에는 신고해야 한다.").subjects())
			.containsExactly("지방자치단체의장");
	}

	@Test
	void intendedObjectActionRemainsAnExplicitCondition() {
		var atom = parser.parse("국가기관등의 장은 소프트웨어사업의 과업내용을 확정하려는 경우에는 과업심의위원회의 심의ㆍ의결을 거쳐야 한다.");
		assertThat(atom.conditions()).contains("과업내용확정");
		assertThat(parser.parse("국가기관등의 장은 과업심의위원회의 심의ㆍ의결을 거쳐야 한다.").conditions())
			.doesNotContain("과업내용확정");
		assertThat(parser.parse("국가기관등의 장은 사업계획을 변경하려는 경우에는 신고해야 한다.").conditions())
			.contains("사업계획변경").doesNotContain("과업내용확정");
		assertThat(parser.parse("국가기관등의 장은 과업내용을 확정하지 않으려는 경우에는 신고해야 한다.").conditions())
			.doesNotContain("과업내용확정");
	}

	@Test
	void issuerRelativeVerbDoesNotCreateAnActor() {
		EvidenceAtom atom = parser.parse("적용 대상 사업은 국가기관 등이 발주하는 모든 SW사업(상용SW포함)입니다.");
		assertThat(atom.subjects()).contains("사업").doesNotContain("발주하");
		assertThat(parser.parse("담당자는 수행하는 사업의 요건을 확인해야 한다.").subjects())
			.containsExactly("담당자");
		assertThat(parser.parse("민간인은 등록되는 사업을 확인해야 한다.").subjects())
			.containsExactly("민간인");
	}

	@Test
	void extractsActorActionConditionAndRequiredModality() {
		EvidenceAtom atom = parser.parse("계약상대자는 이행을 완료하면 서면으로 통지해야 한다.");

		assertThat(atom.subjects()).contains("계약상대자");
		assertThat(atom.actions()).contains("통지");
		assertThat(atom.conditions()).contains("이행완료");
		assertThat(atom.modality()).isEqualTo(EvidenceAtom.Modality.REQUIRED);
		assertThat(atom.polarity()).isEqualTo(EvidenceAtom.Polarity.POSITIVE);
		assertThat(atom.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.COMPLETE);
	}

	@Test
	void distinguishesPermissionProhibitionAndNoObligation() {
		assertThat(parser.parse("발주기관은 계약을 변경할 수 있다.").modality())
			.isEqualTo(EvidenceAtom.Modality.PERMITTED);
		assertThat(parser.parse("발주기관은 계약을 변경할 수 없다.").modality())
			.isEqualTo(EvidenceAtom.Modality.PROHIBITED);
		EvidenceAtom noDuty = parser.parse("소상공인은 해당 서류를 제출할 의무가 없다.");
		assertThat(noDuty.modality()).isEqualTo(EvidenceAtom.Modality.REQUIRED);
		assertThat(noDuty.polarity()).isEqualTo(EvidenceAtom.Polarity.NEGATIVE);
	}

	@Test
	void extractsConditionExceptionScopeAndNumericDeadline() {
		EvidenceAtom atom = parser.parse(
			"신청인은 재난이 발생한 경우 30일 이내에 신고해야 한다. 다만 국외 체류자는 제외한다."
		);

		assertThat(atom.conditions()).anyMatch(value -> value.contains("재난발생"));
		assertThat(atom.exceptions()).anyMatch(value -> value.contains("국외체류자"));
		assertThat(atom.targetScopes()).contains("국외체류자제외");
		assertThat(atom.numericAnchors()).contains("30일이내");
	}

	@Test
	void preservesDifferentPopulationScopesInsteadOfCollapsingThem() {
		EvidenceAtom atom = parser.parse("국가기관은 신고해야 하고 지방자치단체는 승인받아야 한다.");

		assertThat(atom.subjects()).contains("국가기관", "지방자치단체");
		assertThat(atom.actions()).contains("신고", "승인");
	}

	@Test
	void ambiguousDoubleNegationFailsClosed() {
		EvidenceAtom atom = parser.parse("신고하지 않아도 되지 않는 것은 아니다.");

		assertThat(atom.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.AMBIGUOUS);
		assertThat(atom.reasonCodes()).contains("AMBIGUOUS_DOUBLE_NEGATION");
	}

	@Test
	void mixedPermissionAndProhibitionAcrossDifferentActorsFailsClosed() {
		EvidenceAtom atom = parser.parse(
			"신청인은 보호조치를 신청할 수 있고, 위원회는 위반자에게 금지 조치를 할 수 있다."
		);

		assertThat(atom.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.AMBIGUOUS);
		assertThat(atom.reasonCodes()).contains("AMBIGUOUS_MIXED_MODALITY");
	}

	@Test
	void questionFactoryReturnsEmptyDiscoveryTemplateAndRequiredAnswerTemplate() {
		QuestionPropositionTemplateFactory factory = new QuestionPropositionTemplateFactory(parser);
		PropositionTemplate discovery = factory.from(
			"개인정보보호 관련 법령 찾아줘",
			QuestionIntentProfile.from("개인정보보호 관련 법령 찾아줘")
		);
		PropositionTemplate answer = factory.from(
			"계약상대자는 완료 후 통지해야 하나?",
			QuestionIntentProfile.from("계약상대자는 완료 후 통지해야 하나?")
		);

		assertThat(discovery.requiredSlots()).isEmpty();
		assertThat(answer.requiredSlots()).contains(
			PropositionTemplate.RequiredSlot.SUBJECT,
			PropositionTemplate.RequiredSlot.ACTION,
			PropositionTemplate.RequiredSlot.MODALITY
		);
	}
}
