package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiVerificationProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticEvidenceMatcherTests {

	private final KoreanEvidenceAtomParser parser = new KoreanEvidenceAtomParser();
	private final SemanticEvidenceMatcher matcher = new SemanticEvidenceMatcher();

	@Test
	void closedLegalPrintedLinesSupportTheSameCompleteAuthorityClaim() {
		String claim = "장관은 국가기관등의 장이 소프트웨어 사업을 추진하는 경우 법령 준수 여부를 확인할 수 있다.";
		String source = "제7조(준수 여부 확인)\n① 장관은 국가기관등의 장이 소프트웨어\n"
			+ "사업을 추진하는 경우 법령 준수 여부를\n확인할 수 있다.\n② 장관은 개선을 권고할 수 있다.";
		assertThat(matcher.match(parser.parse(claim), matcher.index(List.of(ground(source)))).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void closedLegalWrapCannotBridgeDifferentGroundTextFields() {
		var source = new LawAiAnswerGround(1, 10L, 20L, "law", "법령", "기관", "법률",
			"2026-01-01", "CURRENT", "1", "제7조", null,
			"공개할 수 있다.\n② 다른 기준", "source", "url", 1.0d,
			"제7조(기준)\n① 장관은 사업자료를", null, List.of(10L), "matched_child_only");
		assertThat(matcher.match(parser.parse("장관은 사업자료를 공개할 수 있다."),
			matcher.index(List.of(source))).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void wrappedConditionalAuthorityCannotLoseItsTriggerThroughCoordination() {
		String source = "제7조(확인 및 공개) ① 장관은 기관이 사업을 추진하는 경우 준수 여부를\n"
			+ "확인하고, 그 결과를 공개할 수 있다.\n② 다른 기준";
		assertThat(matcher.match(parser.parse("장관은 그 결과를 공개할 수 있다."),
			matcher.index(List.of(ground(source)))).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void displayedProcedureBeforeTimingCannotBeDroppedOrReversed() {
		String rule = "국가기관등의 장은 과업내용을 확정하기 위하여 소프트웨어사업 발주 전에 사업계획서에 대하여 과업심의위원회의 심의를 받아야 한다.";
		var ground = new LawAiAnswerGround(1, 1, 1, "official_doc", "심의 절차", "기관", "공식 문서",
			null, null, "page 1", "심의", 1, rule, null, null, 0.9);
		var index = matcher.index(List.of(ground));
		assertThat(matcher.match(parser.parse(rule), index).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		for (String claim : List.of(rule.replace("발주 전에 ", ""), rule.replace("발주 전에", "발주 후에"))) {
			assertThat(matcher.match(parser.parse(claim), index).status()).as(claim)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void displayedProcedurePurposeCannotBeDroppedOrChanged() {
		String rule = "국가기관등의 장은 영 제47조제1항제1호에 따라 과업내용을 확정하기 위하여 소프트웨어사업 발주 전에 사업계획서 또는 제안요청서에 대하여 과업심의위원회의 심의를 받아야 한다.";
		var ground = new LawAiAnswerGround(1, 1, 1, "official_doc", "심의 절차", "기관", "공식 문서",
			null, null, "page 1", "심의", 1, rule, null, null, 0.9);
		var index = matcher.index(List.of(ground));
		assertThat(matcher.match(parser.parse(rule), index).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		for (String claim : List.of(rule.replace("과업내용을 확정하기 위하여 ", ""),
			rule.replace("과업내용을 확정하기 위하여", "사업계획을 변경하기 위하여"))) {
			assertThat(matcher.match(parser.parse(claim), index).status()).as(claim)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void displayedGroundIndexPreservesProcedureConditionsAndOpposingParentEvidence() {
		String conditional = "국가기관등의 장은 업무를 완료하려는 경우에는 사업자료를 통지해야 한다.";
		var conditionalGround = new LawAiAnswerGround(1, 1, 1, "official_doc", "자료 통지 절차", "기관", "공식 문서",
			null, null, "page 1", "통지", 1, conditional, null, null, 0.9);
		var conditionalIndex = matcher.index(List.of(conditionalGround));
		assertThat(matcher.match(parser.parse(conditional), conditionalIndex).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		String unconditional = "국가기관등의 장은 사업자료를 통지해야 한다.";
		assertThat(matcher.match(parser.parse(unconditional), conditionalIndex).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		String opposite = "국가기관등의 장은 업무를 완료하려는 경우에는 사업자료를 통지하지 않는다.";
		var mixedGround = new LawAiAnswerGround(2, 2, 1, "official_doc", "자료 통지 절차", "기관", "공식 문서",
			null, null, "page 2", "통지", 2, unconditional, null, null, 0.9,
			unconditional, opposite, List.of(2L, 3L), "parent_expanded");
		assertThat(matcher.match(parser.parse(unconditional), matcher.index(List.of(mixedGround))).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
	}

	@Test
	void droppedSourceConditionMustNotEraseAnOpposingWitnessFromConflictDetection() {
		var claim = parser.parse("국가기관등의 장은 사업자료를 통지해야 한다.");
		var opposite = parser.parse("국가기관등의 장은 업무를 완료하려는 경우에는 사업자료를 통지하지 않는다.");
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(claim, opposite)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
	}

	@Test
	void intendedProcedureTriggerCannotBeDroppedForTheSameInstitutionHead() {
		String rule = "국가기관등의 장은 소프트웨어사업의 과업내용을 확정하려는 경우에는 과업심의위원회의 심의ㆍ의결을 거쳐야 한다.";
		var index = SemanticEvidenceMatcher.EvidenceIndex.of(parser.parse(rule));
		assertThat(matcher.match(parser.parse(rule), index).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(parser.parse("국가기관등의 장은 과업심의위원회의 심의ㆍ의결을 거쳐야 한다."), index).status())
			.isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(parser.parse(rule.replace("국가기관등의 장", "민간기관의 장")), index).status())
			.isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void negatedMembershipContinuationDoesNotSupportPositiveTrigger() {
		var claim = parser.parse("온라인 운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하는 경우 신고해야 한다.");
		var evidence = parser.parse("온라인 운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하는 경우가 아니면 신고해야 한다.");
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void separateDutyDoesNotStrengthenPermittedActionInSameSentence() {
		var claim = parser.parse("발주기관은 심의를 해야 한다.");
		var evidence = parser.parse("발주기관은 심의를 할 수 있으며 위원회를 두어야 한다.");
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void membershipConditionDoesNotAlignDifferentIssuerOrBusinessClass() {
		var claim = parser.parse("온라인 운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하면 과업심의 대상입니다.");
		for (String evidence : List.of(
			"온라인 운영 사업이 민간기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하면 과업심의 대상입니다.",
			"온라인 운영 사업이 국가기관 등이 발주하는 모든 건설사업에 해당하면 과업심의 대상입니다.",
			"온라인 운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하지 않으면 과업심의 대상입니다."
		)) {
			assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(parser.parse(evidence))).status())
				.as(evidence).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void committeeInstallationAndNominalScopeDoNotAloneAuthorizeIndividualClassification() {
		var claim = parser.parse("SNS운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)에 해당하면 공공소프트웨어사업 과업심의 대상입니다.");
		var scope = parser.parse("적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함) - 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등과 그 밖에 소프트웨어와 관련된 서비스를 제공하는 산업과 관련된 경제활동");
		var rule = parser.parse("국가기관등의장은소프트웨어사업의추진에관한다음각호의사항을심의하기위하여소프트웨어사업과업심의위원회(이하“과업심의위원회”라한다)를두어야한다. 1.과업내용의확정");
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(scope, rule)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void explicitProcedureRulePreservesItsContentConfirmationTrigger() {
		String rule = "국가기관등의 장은 소프트웨어사업의 과업내용을 확정하려는 경우에는 과업심의위원회의 심의ㆍ의결을 거쳐야 한다.";
		var index = SemanticEvidenceMatcher.EvidenceIndex.of(parser.parse(rule));
		assertThat(matcher.match(parser.parse(rule), index).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(parser.parse(
			"SNS운영 사업은 과업심의위원회의 심의ㆍ의결을 거쳐야 한다."
		), index).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void procedureTitleAloneDoesNotCompleteANominalScopeDefinition() {
		var ground = new LawAiAnswerGround(
			1, 10L, 20L, "official_doc", "공공소프트웨어사업 과업심의 가이드", "기관", "문서", "2026-01-01", "CURRENT",
			"1", "적용 대상 사업", null,
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함)", "source", "url", 1.0d
		);
		assertThat(matcher.match(
			parser.parse("SNS운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW포함)에 해당하면 과업심의 대상입니다."),
			matcher.index(List.of(ground))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void permissionDoesNotEstablishAnObligationForTheSameActorAndAction() {
		assertThat(matcher.match(
			parser.parse("신청인은 보호조치를 신청해야 한다."),
			SemanticEvidenceMatcher.EvidenceIndex.of(parser.parse("신청인은 보호조치를 신청할 수 있다."))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void scopeDefinitionDoesNotAuthorizeChangedIssuerClassProcedureOrStrength() {
		var evidence = parser.parse(
			"국가기관 등이 발주하는 모든 SW사업(상용SW포함)은 과업심의 대상입니다."
		);
		for (String claim : List.of(
			"SNS운영 사업이 민간기관이 발주하는 모든 SW사업(상용SW포함)에 해당하면 과업심의 대상입니다.",
			"SNS운영 사업이 국가기관 등이 발주하는 모든 건설사업에 해당하면 과업심의 대상입니다.",
			"SNS운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW제외)에 해당하면 과업심의 대상입니다.",
			"SNS운영 사업은 과업심의 대상입니다.",
			"SNS운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW포함)에 해당하면 과업심의를 받아야 합니다.",
			"SNS운영 사업이 국가기관 등이 발주하는 모든 SW사업(상용SW포함)에 해당하면 보안성검토 대상입니다."
		)) {
			assertThat(matcher.match(parser.parse(claim), SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
				.as(claim).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void ambiguousNominalSubjectIsNotSilentlyDroppedBeforeAlignment() {
		var claim = parser.parse("폐하는 신고해야 한다.");
		var evidence = parser.parse("발주기관은 신고해야 한다.");
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsSamePropositionAndContradictsOnlyAlignedOppositePolarity() {
		EvidenceAtom claim = parser.parse("계약상대자는 완료 후 통지해야 한다.");
		EvidenceAtom same = parser.parse("계약상대자는 완료 후 통지해야 한다.");
		EvidenceAtom opposite = parser.parse("계약상대자는 완료 후 통지하지 않는다.");

		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(same)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(opposite)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void reportsConflictWhenAlignedPositiveAndNegativeEvidenceBothExist() {
		EvidenceAtom claim = parser.parse("계약상대자는 통지해야 한다.");
		SemanticEvidenceMatcher.SemanticMatch result = matcher.match(
			claim,
			SemanticEvidenceMatcher.EvidenceIndex.of(
				parser.parse("계약상대자는 통지해야 한다."),
				parser.parse("계약상대자는 통지하지 않는다.")
			)
		);

		assertThat(result.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
	}

	@Test
	void failsClosedBeforePolarityForDifferentSubjectOrMissingCondition() {
		EvidenceAtom claim = parser.parse("계약상대자는 완료 후 통지해야 한다.");
		EvidenceAtom otherSubjectNegative = parser.parse("발주기관은 완료 후 통지하지 않는다.");
		EvidenceAtom missingCondition = parser.parse("계약상대자는 통지해야 한다.");

		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(otherSubjectNegative)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(missingCondition)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void failsClosedForAmbiguousParseAndNumericMismatch() {
		EvidenceAtom ambiguous = parser.parse("신고하지 않아도 되지 않는 것은 아니다.");
		EvidenceAtom deadline = parser.parse("신청인은 30일 이내에 신고해야 한다.");
		EvidenceAtom wrongDeadline = parser.parse("신청인은 60일 이내에 신고해야 한다.");

		assertThat(matcher.match(ambiguous, SemanticEvidenceMatcher.EvidenceIndex.of(ambiguous)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(deadline, SemanticEvidenceMatcher.EvidenceIndex.of(wrongDeadline)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsAnExactPartialLabelValueButRejectsADifferentValue() {
		EvidenceAtom claim = parser.parse("평가기간 : 2025. 12. 17 ~ 2026. 10. 31");
		EvidenceAtom exact = parser.parse("평가기간 : 2025. 12. 17 ~ 2026. 10. 31");
		EvidenceAtom different = parser.parse("평가기간 : 2025. 12. 17 ~ 2026. 11. 30");

		assertThat(claim.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.PARTIAL);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(exact)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(different)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsAVerbatimNominalScopeFollowedOnlyByAnExplanatoryDefinition() {
		EvidenceAtom claim = parser.parse("국가기관 등이 발주하는 모든 SW사업(상용SW포함).");
		EvidenceAtom evidence = parser.parse(
			"국가기관 등이 발주하는 모든 SW사업(상용SW포함) - "
				+ "소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등과 관련된 경제활동"
		);

		assertThat(claim.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.PARTIAL);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void doesNotTreatANegatedContinuationAsAnExplanatoryPrefixMatch() {
		EvidenceAtom claim = parser.parse("국가기관 등이 발주하는 모든 SW사업(상용SW포함)");
		EvidenceAtom evidence = parser.parse(
			"국가기관 등이 발주하는 모든 SW사업(상용SW포함)은 적용 대상이 아닙니다."
		);

		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void alignsAnActionBearingPartialClaimWithCompleteEvidenceThatSuppliesTheImplicitSubject() {
		EvidenceAtom claim = parser.parse(
			"결론부터 말하면, 사업계획을 수립한 후 지체 없이 사업계획서 등을 제출하여 사전협의를 요청해야 합니다."
		);
		EvidenceAtom evidence = parser.parse(
			"중앙행정기관등의 장은 사업계획을 수립한 후 지체 없이 행정안전부장관에게 "
				+ "사업계획서 등의 자료를 제출하여 사전협의를 요청하여야 한다."
		);

		assertThat(claim.parseStatus()).isEqualTo(EvidenceAtom.ParseStatus.PARTIAL);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(evidence)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void mixedModalityEvidenceCannotTurnASeparateSupportedClaimIntoAConflict() {
		EvidenceAtom claim = parser.parse("신청인은 보호조치를 신청할 수 있다.");
		EvidenceAtom supported = parser.parse("신청인은 보호조치를 신청할 수 있다.");
		EvidenceAtom mixed = parser.parse(
			"신청인은 보호조치를 신청할 수 있고, 위원회는 위반자에게 금지 조치를 할 수 있다."
		);

		assertThat(matcher.match(
			claim,
			SemanticEvidenceMatcher.EvidenceIndex.of(supported, mixed)
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void shadowVerifierRecordsDisagreementWithoutChangingControlAnswer() {
		ClaimEvidenceMatcher control = new ClaimEvidenceMatcher();
		ClaimVerifier verifier = new ClaimVerifier(
			control,
			matcher,
			parser,
			new LawAiVerificationProperties(true, false, 20)
		);
		String answer = "계약상대자는 완료 후 통지해야 한다.";
		List<LawAiAnswerGround> grounds = List.of(ground("계약상대자는 통지해야 한다."));

		ClaimVerifier.VerificationResult shadow = verifier.verifyDetailed(answer, grounds);
		ClaimVerifier.VerificationResult controlOnly = new ClaimVerifier(control).verifyDetailed(answer, grounds);

		assertThat(shadow.verifiedAnswer()).isEqualTo(controlOnly.verifiedAnswer());
		assertThat(shadow.semanticShadowResults()).hasSize(1);
		assertThat(shadow.semanticShadowResults().get(0).semanticStatus())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void authoritativeModeUsesSemanticResultEvenWhenShadowCollectionIsDisabled() {
		ClaimVerifier verifier = new ClaimVerifier(
			new ClaimEvidenceMatcher(),
			matcher,
			parser,
			new LawAiVerificationProperties(false, true, 20)
		);

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			"계약상대자는 완료 후 통지해야 한다.",
			List.of(ground("계약상대자는 통지해야 한다."))
		);

		assertThat(result.unsupportedClaims()).containsExactly("계약상대자는 완료 후 통지해야 한다.");
		assertThat(result.semanticShadowResults()).isEmpty();
	}

	@Test
	void requiredTemplateSlotsCannotMatchWhenTheQuestionProvidedNoSlotValue() {
		PropositionTemplate incomplete = new PropositionTemplate(
			Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
			Set.of(PropositionTemplate.RequiredSlot.ACTION)
		);

		assertThat(matcher.match(incomplete, parser.parse("계약상대자는 통지해야 한다.")).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	private LawAiAnswerGround ground(String text) {
		return new LawAiAnswerGround(
			1, 10L, 20L, "law", "법령", "기관", "법률", "2026-01-01", "CURRENT",
			"1", "제1조", null, text, "source", "url", 1.0d
		);
	}
}
