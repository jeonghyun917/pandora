package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import org.junit.jupiter.api.Test;

class KoreanEvidenceAtomParserTests {

	private final KoreanEvidenceAtomParser parser = new KoreanEvidenceAtomParser();

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
