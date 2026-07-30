package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimEvidenceMatcherRelationTests {

	private final ClaimEvidenceMatcher matcher = new ClaimEvidenceMatcher();

	@Test
	void exactDocumentIdentityClaimCanUseTheSelectedDocumentTitle() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"결론부터 말씀드리면, 찾으시는 문서는 "
				+ "\"공공소프트웨어사업 과업심의 가이드(2022. 12.)\"입니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
				"본문에는 문서 제목을 다시 서술하지 않는다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match.evidenceSentence())
			.isEqualTo("공공소프트웨어사업 과업심의 가이드(2022. 12.)");
	}

	@Test
	void documentTitleMetadataCannotSupportAContentOrObligationClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"공공소프트웨어사업 과업심의 가이드는 모든 하드웨어 구매를 심의하도록 요구합니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
				"이 문장은 문서 내용과 무관하다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void documentIdentityDescriptorMustMatchSelectedDocumentMetadata() {
		LawAiAnswerGround ground = ground(
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"본문에는 문서 제목을 다시 서술하지 않는다."
		);

		assertThat(matcher.match(
			"요약하면, 요청하신 문서는 제목이 "
				+ "\"공공소프트웨어사업 과업심의 가이드(2022. 12.)\"인 공식 가이드 문서입니다.",
			List.of(ground)
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(
			"요약하면, 요청하신 문서는 제목이 "
				+ "\"공공소프트웨어사업 과업심의 가이드(2022. 12.)\"인 법률 문서입니다.",
			List.of(ground)
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void requiresTheAddedConceptInAMultiConceptRelationClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"과업심의 대상 사업은 사전협의도 함께 해야 합니다.",
			List.of(ground("과업심의 대상 사업은 과업심의를 함께 해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsAMultiConceptRelationWhenTheAddedConceptIsGrounded() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"과업심의 대상 사업은 사전협의도 함께 해야 합니다.",
			List.of(ground("과업심의 대상 사업은 사전협의도 함께 해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void concessiveRadoDoesNotCreateAnAdditiveRelationAnchor() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 소프트웨어 구매라도 그 사업이 소프트웨어사업에 해당하면 과업심의 대상이다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void requiresTheSecondConceptBeforeAnExplicitRelationCueWithoutDoParticle() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"과업심의 대상 사업은 사전협의와 함께 진행해야 합니다.",
			List.of(ground("과업심의 대상 사업은 과업심의와 함께 진행해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsAnExplicitRelationCueWhenTheSecondConceptIsGrounded() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"과업심의 대상 사업은 사전협의와 함께 진행해야 합니다.",
			List.of(ground("과업심의 대상 사업은 사전협의와 함께 진행해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void requiresRelationAnchorEvenWithoutObligationOrOtherLegalMode() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보는 공공데이터와 연관됩니다.",
			List.of(ground("개인정보는 보호 대상 정보와 연관됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsRelationAnchorWithoutObligationWhenBothConceptsAreGrounded() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보는 공공데이터와 연관됩니다.",
			List.of(ground("개인정보는 공공데이터와 연관됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void doesNotSupportNarrowEligibilityClaimWithBroadEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"특정 요건을 충족한 사업은 대상입니다.",
			List.of(ground("사업은 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsNarrowEligibilityClaimWhenTheConditionIsGrounded() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"특정 요건을 충족한 사업은 대상입니다.",
			List.of(ground("특정 요건을 충족한 사업은 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void doesNotBroadenConditionalEvidenceIntoAnUnconditionalClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"사업은 대상입니다.",
			List.of(ground("법령에서 정한 경우 사업은 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doesNotInventAConditionMissingFromBroadEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"법령에서 정한 경우 사업은 대상입니다.",
			List.of(ground("사업은 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doesNotTreatDifferentNumericEligibilityConditionsAsEquivalent() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"연 매출 10억원 이상인 사업은 지원 대상입니다.",
			List.of(ground("총자산 10억원 이상인 사업은 지원 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void digitEndedSentenceBoundaryCannotPoolANumericScopeFromTheNextSentence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"중소기업의 신청기한은 2026. 10. 31.입니다.",
			List.of(ground(
				"신청기한은 2026. 10. 31. 중소기업은 지원 대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doesNotTreatDifferentNonNumericEligibilityConditionsAsEquivalent() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"청년 요건을 충족한 사업은 대상입니다.",
			List.of(ground("매출 요건을 충족한 사업은 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void differentVerbEndingConditionsAreNotEquivalent() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"신고하면 사업은 대상입니다.",
			List.of(ground("퇴사하면 사업은 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameVerbEndingConditionRemainsSupported() {
		String proposition = "신고하면 사업은 대상입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void recognizesNegatedPermissionAsContradictory() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"자료 제출은 허용됩니다.",
			List.of(ground("자료 제출은 허용되지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void recognizesNegatedRequirementAsContradictory() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"자료 제출은 요구됩니다.",
			List.of(ground("자료 제출은 요구되지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void requiresBothParticipantsInAnExplicitRelationClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보는 공공데이터와 연관됩니다.",
			List.of(ground("보호정보는 공공데이터와 연관됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsBroadRuleWithoutConflictingWithItsScopedException() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"국가기관 발주 소프트웨어사업은 과업심의 대상입니다.",
			List.of(ground(
				"국가기관 발주 소프트웨어사업은 과업심의 대상이며, "
					+ "단순 H/W 도입은 과업심의 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void supportsTheScopedExceptionFromTheSameEvidenceFragment() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입은 과업심의 비대상입니다.",
			List.of(ground(
				"국가기관 발주 소프트웨어사업은 과업심의 대상이며, "
					+ "단순 H/W 도입은 과업심의 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void headlessPermissionClauseCannotBorrowThePreviousSubjectForAnotherActor() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"대기업은 지원을 신청할 수 있습니다.",
			List.of(ground(
				"중소기업은 지원 대상이며, 지원을 신청할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsAllowedExceptionWithoutConflictingWithGeneralProhibition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"범죄 예방을 위해 CCTV를 설치할 수 있습니다.",
			List.of(ground(
				"공개된 장소의 CCTV 설치는 원칙적으로 금지됩니다. "
					+ "예외적으로 범죄 예방을 위해 CCTV를 설치할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void leadingSummaryFrameDoesNotTurnAGeneralRuleIntoAnExceptionCondition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"결론부터 말하면, 공개된 장소에 CCTV(고정형 영상정보처리기기)를 설치하는 것은 "
				+ "원칙적으로 금지되어 있습니다.",
			List.of(
				ground(
					"누구든지 공개된 장소에 고정형 영상정보처리기기를 설치·운영하는 것은 "
						+ "원칙적으로 금지됩니다."
				),
				ground(
					"예외적으로 「개인정보 보호법」 제25조에서 정하는 사유에 해당하는 경우에만 "
						+ "고정형 영상정보처리기기를 설치·운영할 수 있습니다."
				)
			)
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void leadingSubstantiveConditionIsNotDiscardedAsSummaryDiscourse() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"자료를 정리하면 시스템에 제출할 수 있습니다.",
			List.of(ground("자료를 시스템에 제출할 수 있습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void unrelatedOppositeActionIsInsufficientRatherThanContradictory() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청서를 제출할 수 있습니다.",
			List.of(ground("기관은 신청서를 열람할 수 없습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void reversedActorAndRecipientAreNotTheSameProposition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 사업자에게 결과를 통지해야 합니다.",
			List.of(ground("사업자는 기관에게 결과를 통지해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void explicitRecipientCannotBorrowEvidenceWithoutARecipient() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 시민에게 자료를 제출해야 합니다.",
			List.of(ground("기관은 자료를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameExplicitRecipientRemainsSupported() {
		String proposition = "기관은 시민에게 자료를 제출해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void differentExplicitSubjectsAreNotTheSameObligation() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 결과를 통지해야 합니다.",
			List.of(ground("사업자는 결과를 통지해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void substringRelatedEntitiesAreNotTheSameSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보처리자는 자료를 파기해야 합니다.",
			List.of(ground("개인정보는 자료를 파기해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void explicitSubjectCannotBorrowANonSubjectMention() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 제출해야 합니다.",
			List.of(ground("기관 지침에 따라 자료를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void labelCannotOverrideADifferentExplicitBodySubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관A: 신청서를 제출해야 합니다.",
			List.of(ground("기관A: 기관B는 신청서를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void labelsAreIgnoredWhenTheSameExplicitBodySubjectIsPresent() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개요: 기관은 신청서를 제출해야 합니다.",
			List.of(ground("요약: 기관은 신청서를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void sharedConditionalPlaceholderDoesNotAlignDifferentTargetKinds() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우에는 과업심의 비대상입니다.",
			List.of(ground(
				"소프트웨어 품목에 다수의 제품이 존재하며 그 중 직접구매 대상 "
					+ "상용소프트웨어가 1개라도 있을 경우에는 직접구매 대상으로 한다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void preConsultationTargetCannotBorrowAProjectReviewExclusion() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우는 사전협의 대상이 아닙니다.",
			List.of(ground(
				"단순 H/W 도입·설치, 단순 동영상 제작, 네트워크 등 인프라 수수료와 같이 "
					+ "소프트웨어사업으로 볼 수 없는 경우는 비대상"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void equivalentExampleMarkersSupportTheSameCategoricalException() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입·설치, 단순 동영상 제작, 네트워크 등 인프라 수수료처럼 "
				+ "소프트웨어사업으로 볼 수 없는 경우에는 과업심의 비대상입니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"단순 H/W(Appliance 포함) 도입·설치, 단순 동영상 제작, 네트워크 등 "
					+ "인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void equivalentExampleMarkersDoNotMergeDifferentExceptionLists() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입·설치, 단순 동영상 제작, 네트워크 등 인프라 수수료처럼 "
				+ "소프트웨어사업으로 볼 수 없는 경우에는 과업심의 비대상입니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"교육비, 출장비와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void classifiedBusinessConditionIsSupportedByTheUniversalBusinessRule() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"그 사업이 소프트웨어사업에 해당하면 과업심의 대상이다",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void classifiedInformationProjectConditionCannotBorrowASoftwareBusinessRule() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"그 사업이 정보화사업에 해당하면 과업심의 대상이다",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"국가기관 등이 발주하는 모든 SW사업(상용SW 포함)은 적용 대상이다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void directPreConsultationDefinitionSupportsTheSameDefinition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"대상사업은 대상기관이 추진하는 모든 정보화사업입니다",
			List.of(ground(
				"정보화사업 사전협의 안내",
				"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
					+ "대상기관이 추진하는 모든 정보화사업임"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void liveOcrBulletsDoNotContaminateTheDirectPreConsultationDefinition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"대상사업은 대상기관이 추진하는 모든 정보화사업입니다.",
			List.of(ground(
				"2024년 정보화사업 사전협의 안내자료(배포용)",
				"대상 사업 p.28 대상 사업 대상 사업 p.28 대상 사업 "
					+ "사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
					+ "대상기관이 추진하는 모든 정보화사업임 "
					+ "*디지털서비스전문계약제도이용계약,공모, R&D,민간투자형소프트웨어사업등"
					+ "예산과목및계약방식에관계없음 "
					+ "∙사업금액이 아래에 해당하는 사업은 제외하되, 신규로 추진하는 사업은 대상에 포함 "
					+ "-(중앙·공공기관) 10억원 미만 -(광역·공기업) 2억원 미만 "
					+ "-(기초·공기업) 1억원 미만"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void livePreConsultationParaphraseMatchesTheDirectOcrDefinition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"적용대상은 예산과목·계약방식과 관계없이 기관이 추진하는 모든 정보화사업입니다.",
			List.of(ground(
				"2024년 정보화사업 사전협의 안내자료(배포용)",
				"대상 사업 p.28 대상 사업 대상 사업 p.28 대상 사업 "
					+ "사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
					+ "대상기관이 추진하는 모든 정보화사업임 "
					+ "*디지털서비스전문계약제도이용계약,공모, R&D,민간투자형소프트웨어사업등"
					+ "예산과목및계약방식에관계없음 "
					+ "∙사업금액이 아래에 해당하는 사업은 제외하되, 신규로 추진하는 사업은 대상에 포함"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void genericAppliedTargetDefinitionDoesNotCrossScopeOrBorrowAnAction() {
		String claim = "적용대상은 기관이 추진하는 모든 정보화사업입니다.";
		for (String evidence : List.of(
			"보안성 검토의 대상사업은 대상기관이 추진하는 모든 정보화사업임",
			"사전협의에서 대상기관이 추진하는 모든 정보화사업을 검토합니다.",
			"사전협의의 대상사업은 대상기관이 추진하는 일부 정보화사업임"
		)) {
			assertThat(matcher.match(claim, List.of(ground(evidence))).status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void genericAppliedTargetDefinitionStillRequiresSubstantiveRelationAnchors() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"적용대상은 보안성 검토와 관계없이 기관이 추진하는 모든 정보화사업입니다.",
			List.of(ground(
				"사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
					+ "대상기관이 추진하는 모든 정보화사업임"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void repeatedOcrHeadingPrefixDoesNotChangeTheDirectDefinition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"대상사업은 대상기관이 추진하는 모든 정보화사업입니다.",
			List.of(ground(
				"정보화사업 사전협의 안내",
				"대상 사업 p.28 대상 사업 대상 사업 p.28 대상 사업 "
					+ "사전협의의 대상사업은 예산과목 및 계약방식과 관계없이 "
					+ "대상기관이 추진하는 모든 정보화사업임"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void hardwareExclusionExampleParaphraseMatchesTheShortDirectException() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"비대상 사례로는 단순 하드웨어 도입·설치, 단순 동영상 제작, "
				+ "네트워크 등 인프라 수수료처럼 소프트웨어사업으로 볼 수 없는 경우가 있습니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"단순 H/W(Appliance 포함) 도입·설치, 단순 동영상 제작, "
					+ "네트워크 등 인프라 수수료와 같이 "
					+ "소프트웨어사업으로 볼 수 없는 경우는 비대상"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void genericTargetWithoutAnEnumerationCannotBorrowASingleTitleScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 비대상입니다.",
			List.of(ground("과업심의 가이드", "이 사업은 비대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void inlineUniversalTargetHeadingIsSelfContainedWithinASingleDocumentScope() {
		String source =
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)";
		ClaimEvidenceMatcher.Match match = matcher.match(
			source,
			List.of(ground("공공소프트웨어사업 과업심의 가이드", source))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void inlineUniversalTargetHeadingCannotBorrowAnAmbiguousMultiScopeTitle() {
		String source =
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)";
		ClaimEvidenceMatcher.Match match = matcher.match(
			source,
			List.of(ground("과업심의 및 사전협의 통합 가이드", source))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void genericEnumeratedExclusionCannotBorrowAnAmbiguousMultiScopeTitle() {
		String claim = "비대상 사례로는 단순 H/W 도입·설치, 단순 동영상 제작, "
			+ "네트워크 등 인프라 수수료처럼 소프트웨어사업으로 볼 수 없는 경우가 있습니다.";
		ClaimEvidenceMatcher.Match match = matcher.match(
			claim,
			List.of(ground(
				"과업심의 및 사전협의 통합 가이드",
				"단순 H/W 도입·설치, 단순 동영상 제작, 네트워크 등 "
					+ "인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void liveProjectReviewSnippetSupportsTheDirectHardwareExclusion() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"비대상 사례로는 단순 하드웨어 도입·설치, 단순 동영상 제작, "
				+ "네트워크 등 인프라 수수료처럼 소프트웨어사업으로 볼 수 없는 경우가 있습니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
				"적용 대상 사업 p.5 적용 대상 사업 적용 대상 사업 p.5 적용 대상 사업 "
					+ "적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함) - "
					+ "소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등과 그 밖에 "
					+ "소프트웨어와 관련된 서비스를 제공하는 산업과 관련된 경제활동"
					+ "(‘소프트웨어 진흥법’제2조) ※ 단순 H/W(Appliance 포함) 도입·설치, "
					+ "단순 동영상 제작, 네트워크 등 인프라 수수료와 같이 "
					+ "소프트웨어사업으로 볼 수 없는 경우는 비대상"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void patientTopicSupportsTheSameObjectOfAnExplicitDutyBearer() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보는 처리 목적에 필요한 범위에서 최소한으로 수집해야 합니다.",
			List.of(ground(
				"개인정보 보호법",
				"개인정보처리자는 개인정보의 처리 목적을 명확하게 하여야 하고 "
					+ "그 목적에 필요한 범위에서 최소한의 개인정보만을 적법하고 정당하게 "
					+ "수집하여야 한다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void livePrivacyMinimumCollectionParaphraseMatchesTheDirectStatutoryDuty() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"원칙적으로 개인정보처리자는 처리 목적에 필요한 범위에서 최소한의 개인정보만 "
				+ "적법·정당하게 수집해야 합니다.",
			List.of(ground(
				"개인정보 보호법",
				"제3조(개인정보 보호 원칙) 등 제3조(개인정보 보호 원칙) 등 "
					+ "제3조(개인정보 보호 원칙) 등 ① 개인정보처리자는 개인정보의 처리 목적을 "
					+ "명확하게 하여야 하고 그 목적에 필요한 범위에서 최소한의 개인정보만을 "
					+ "적법하고 정당하게 수집하여야 한다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void exclusiveObjectParticleDoesNotChangeTheExclusiveRole() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청서만 제출해야 합니다.",
			List.of(ground("기관은 신청서만을 제출하여야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void equivalentKoreanDutyEndingsUseTheSamePredicateAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보처리자는 개인정보를 수집해야 합니다.",
			List.of(ground("개인정보처리자는 개인정보를 수집하여야 한다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void projectReviewTargetDoesNotConflictWithImpactAssessmentExclusion() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어의 개발·제작·운영·유지관리 등 소프트웨어 관련 경제활동으로 보는 사업은 "
				+ "과업심의 대상입니다.",
			List.of(ground(
				"소프트웨어의 개발·제작·운영·유지관리 등 소프트웨어 관련 경제활동으로 보는 사업은 "
					+ "소프트웨어사업 영향평가 제외대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void projectReviewTargetIgnoresTheMixedImpactAssessmentExampleFragment() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어의 개발·제작·운영·유지관리 등 소프트웨어 관련 경제활동으로 보는 사업은 "
				+ "과업심의 대상입니다.",
			List.of(ground(
				"제안요청서 작성 예시 < SW영향평가 적용 대상 사업 > 본 사업은 소프트웨어사업 "
					+ "영향평가를 미리 실시한 사업임 < 적용 제외 사업 > 민간투자형 소프트웨어사업은 "
					+ "소프트웨어사업 영향평가 제외대상 사업임"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void negatedTargetCopulaContradictsIncludedTargetInTheSameScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입은 과업심의 대상이 아닙니다.",
			List.of(ground("단순 H/W 도입은 과업심의 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void negatedTargetInclusionContradictsPositiveInTheSameScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상에 포함됩니다.",
			List.of(ground("이 사업은 과업심의 대상에 포함되지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void mergedTargetTokenPreservesTheNamedProcedureScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어사업은 과업심의대상입니다.",
			List.of(ground("소프트웨어사업은 영향평가제외대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void mergedNonTargetTokenPreservesTheNamedProcedureScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어사업은 과업심의비대상입니다.",
			List.of(ground("소프트웨어사업은 영향평가비대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void mergedNonTargetTokenContradictsPositiveTargetInTheSameScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어사업은 과업심의대상입니다.",
			List.of(ground("소프트웨어사업은 과업심의비대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void targetClassifiedAsNotApplicableContradictsPositiveTarget() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업은 과업심의 대상으로 보지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void doubleNegatedTargetPolarityFailsClosed() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상이 아닌 것은 아닙니다.",
			List.of(ground("이 사업은 과업심의 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doubleNegatedEvidenceCannotSupportOrContradictASimpleTargetClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업은 과업심의 대상이 아닌 것은 아닙니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doubleNegatedTargetClassificationFailsClosed() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업을 과업심의 대상으로 보지 않는 것은 아닙니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void separateNegativeActionDoesNotTurnAClearExclusionIntoDoubleNegation() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업은 과업심의 비대상이며 별도 신청도 하지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void impactAssessmentDoesNotAlignWithEnvironmentalImpactAssessment() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 영향평가 대상입니다.",
			List.of(ground("이 사업은 환경영향평가 대상에서 제외됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void separatelyQualifiedPreConsultationProceduresDoNotAlign() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부 사전협의 대상입니다.",
			List.of(ground("이 사업은 자치분권 사전협의 대상에서 제외됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void mixedSpacingPreservesDifferentPreConsultationQualifiers() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부 사전협의대상입니다.",
			List.of(ground("이 사업은 자치분권 사전협의제외대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void mixedSpacingStillContradictsWithinTheSameQualifiedProcedure() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부 사전협의대상입니다.",
			List.of(ground("이 사업은 전자정부 사전협의제외대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void genitivePreConsultationQualifiersDoNotDisappear() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부의 사전협의 대상입니다.",
			List.of(ground("이 사업은 자치분권의 사전협의 대상에서 제외됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameGenitiveQualifierStillAllowsOppositePolarityDetection() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부의 사전협의 대상입니다.",
			List.of(ground("이 사업은 전자정부의 사전협의 대상에서 제외됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void cannotClassifyAsNonTargetEvidenceFailsClosed() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업을 과업심의 대상으로 보지 않을 수 없습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void cannotClassifyAsNonTargetClaimFailsClosed() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업을 과업심의 대상으로 보지 않을 수 없습니다.",
			List.of(ground("이 사업은 과업심의 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void genericNegativeActionContradictsPositiveEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개하지 않습니다.",
			List.of(ground("기관은 자료를 공개합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void inabilityNegationContradictsPositiveEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개하지 못합니다.",
			List.of(ground("기관은 자료를 공개합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void progressiveInabilityNegationContradictsPositiveEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개하지 못하고 있습니다.",
			List.of(ground("기관은 자료를 공개합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void oneSyllableNegativePredicateContradictsPositiveEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청을 받지 못합니다.",
			List.of(ground("기관은 신청을 받습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void genericPositiveActionContradictsNegativeEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 공개하지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void sameGenericNegativeActionRemainsSupported() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개하지 않습니다.",
			List.of(ground("기관은 자료를 공개하지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void differentPositivePredicateCannotSupportTheClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 보관합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void plannedPredicateRequiresTheSamePlannedAction() {
		ClaimEvidenceMatcher.Match mismatch = matcher.match(
			"기관은 자료를 공개할 예정입니다.",
			List.of(ground("기관은 자료를 폐기할 예정입니다."))
		);
		ClaimEvidenceMatcher.Match same = matcher.match(
			"기관은 자료를 공개할 예정입니다.",
			List.of(ground("기관은 자료를 공개할 예정입니다."))
		);

		assertThat(mismatch.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(same.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void ongoingPredicateRequiresTheSameOngoingAction() {
		ClaimEvidenceMatcher.Match mismatch = matcher.match(
			"기관은 자료를 공개 중입니다.",
			List.of(ground("기관은 자료를 폐기 중입니다."))
		);
		ClaimEvidenceMatcher.Match same = matcher.match(
			"기관은 자료를 공개 중입니다.",
			List.of(ground("기관은 자료를 공개 중입니다."))
		);

		assertThat(mismatch.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(same.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void plannedActionCannotGroundAClaimOfCompletedAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 공개할 예정입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void lightPositivePredicateStillRequiresTheSameAssertedAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 사업을 진행합니다.",
			List.of(ground("기관은 사업을 중단합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void lightImplementationPredicateStillRequiresTheSameAssertedAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 조사를 실시합니다.",
			List.of(ground("기관은 조사를 중단합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void numericAnchorsDoNotHideADifferentFiniteAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"평가는 2026년 1월 1일에 실시됩니다.",
			List.of(ground("평가는 2026년 1월 1일에 취소됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sharedProcedureTermDoesNotHideADifferentFiniteAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 심사 절차를 진행합니다.",
			List.of(ground("기관은 심사 절차를 중단합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void mereMentionOfAPositivePredicateCannotGroundTheAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 공개 여부와 무관하게 보관합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void targetClassificationMentionCannotGroundAPositiveAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 공개 대상에서 제외합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void actionUnderReviewCannotGroundAPositiveAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료가 공개 대상인지 검토합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void nominalEvidenceCanStillGroundTheSamePositivePredicate() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 공개 원칙에 따라 처리합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void nominalCueInsideANegatedClauseCannotGroundAPositivePredicate() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 공개 원칙에 따라 처리하지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void substringInsideAnOppositeNominalPredicateCannotGroundTheAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground("기관은 자료를 비공개 처리합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void terminalChoiceCannotBeTreatedAsANominalAssertion() {
		for (String evidence : List.of(
			"처리 방식은 비공개 또는 공개",
			"처리 방식은 비공개 및 공개"
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"처리 방식은 공개됩니다.",
				List.of(ground(evidence))
			);

			assertThat(match.status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void prohibitionMeasureRequiresTheSameActionUnlessPolarityIsOpposite() {
		for (String evidence : List.of(
			"자료 공개는 금지 권고됩니다.",
			"자료 공개는 금지 제안됩니다.",
			"자료 공개는 금지 검토됩니다."
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"자료 공개는 금지 조치됩니다.",
				List.of(ground(evidence))
			);

			assertThat(match.status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void subjectMarkedNounBeforeProhibitionCanReachOppositePolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보호조치 신청 남용은 금지 조치됩니다.",
			List.of(ground("보호조치 신청 남용은 허용됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void subjectMarkedNounBeforeProhibitionCannotBorrowAnotherSubjectsPolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보호조치 신청 남용은 금지 조치됩니다.",
			List.of(ground("보호조치 신청 결정은 허용됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void lexicalNounEndingInSubjectParticleCharacterRemainsAProhibitionObject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"휴가 금지 조치됩니다.",
			List.of(ground("외출 금지 조치됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void particleMarkedLexicalProhibitionObjectsRemainDistinct() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"휴가 금지를 시행합니다.",
			List.of(ground("병가 금지를 시행합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameLexicalProhibitionObjectRemainsSupported() {
		String proposition = "휴가 금지 조치됩니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void longerLexicalNounEndingInSubjectParticleCharacterIsNotAFalseSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"출입허가 금지 조치됩니다.",
			List.of(ground("출입허가를 금지 조치됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void longerLexicalProhibitionObjectsRemainDistinct() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"출입허가 금지 조치됩니다.",
			List.of(ground("외출 금지 조치됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void prohibitionTargetAlignsAcrossKoreanSubjectParticles() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보 수집이 금지 조치됩니다.",
			List.of(ground("개인정보 수집은 금지 조치됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void prohibitionTargetCanReachOppositePolarityAcrossSubjectParticles() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보호조치 신청 남용이 금지 조치됩니다.",
			List.of(ground("보호조치 신청 남용은 허용됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void prohibitionTargetCannotTradePlacesWithTheResponsibleSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 사업자가 금지 대상입니다.",
			List.of(ground("사업자는 기관이 금지 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void permissionTargetRemovalPreservesADifferentQualifiedResponsibleSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"중앙 기관은 지방 기관이 금지 대상입니다.",
			List.of(ground("서부 기관은 지방 기관이 금지 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void prohibitionTargetCannotTradePlacesWithAnAllowedResponsibleSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 사업자가 금지 대상입니다.",
			List.of(ground("사업자는 기관이 허용됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void allowedTargetCannotTradePlacesWithAProhibitedResponsibleSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보호조치에서 기관은 사업자가 허용 대상입니다.",
			List.of(ground("보호조치에서 사업자는 기관이 금지 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void alignedResponsibleSubjectAndPermissionTargetReachOppositePolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 사업자가 금지 대상입니다.",
			List.of(ground("기관은 사업자가 허용됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void alignedAllowedTargetCanReachAProhibitedOpposite() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보호조치에서 기관은 사업자가 허용 대상입니다.",
			List.of(ground("보호조치에서 기관은 사업자가 금지 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void multiwordSubjectsMustMatchBeyondTheirSharedHead() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"중앙 기관은 자료를 공개해야 합니다.",
			List.of(ground("지방 기관은 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void lexicalModifiersEndingInParticleCharactersRemainPartOfTheSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"국가 기관은 자료를 공개해야 합니다.",
			List.of(ground("평가 기관은 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void longerLexicalModifiersEndingLikeSubjectParticlesRemainDistinct() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"사업가 지원 기관은 자료를 공개해야 합니다.",
			List.of(ground("예술가 지원 기관은 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void lexicalModifiersEndingLikeFocusParticlesRemainDistinct() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정확도 평가 기관은 자료를 공개해야 합니다.",
			List.of(ground("신뢰도 평가 기관은 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void multiwordPermissionTargetsMustMatchBeyondTheirSharedHead() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보 수집은 금지됩니다.",
			List.of(ground("위치정보 수집은 금지됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void lexicalModifiersEndingInParticleCharactersRemainPartOfPermissionTargets() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"국가 기관이 금지 대상입니다.",
			List.of(ground("평가 기관은 금지 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void oppositePermissionCannotBorrowAMultiwordTargetHead() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 개인정보 수집이 금지 조치됩니다.",
			List.of(ground("기관은 위치정보 수집은 허용 조치됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void multiwordObjectsMustMatchBeyondTheirSharedHead() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 개인정보 자료를 삭제해야 합니다.",
			List.of(ground("기관은 위치정보 자료를 삭제해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void multiwordRecipientsMustMatchBeyondTheirSharedHead() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 취약 아동에게 자료를 공개해야 합니다.",
			List.of(ground("기관은 일반 아동에게 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void neutralNominalPredicatesStillRequireTheSameSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 개인정보 담당자입니다.",
			List.of(ground("사업자는 개인정보 담당자입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void namedUniversalScopeDefinitionAlignsItsRelationalSubjectLabel() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"사전협의 대상은 국가기관 등이 추진하는 모든 정보화사업입니다.",
			List.of(ground("사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업임"))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void namedUniversalScopeDefinitionDoesNotCrossScopeOrBorrowAnAction() {
		String claim = "사전협의 대상은 국가기관 등이 추진하는 모든 정보화사업입니다.";
		for (String evidence : List.of(
			"보안성 검토의 대상사업은 대상기관이 추진하는 모든 정보화사업임",
			"사전협의에서 대상기관이 추진하는 모든 정보화사업을 검토합니다.",
			"사전협의의 대상사업은 대상기관이 추진하는 일부 정보화사업임"
		)) {
			assertThat(matcher.match(claim, List.of(ground(evidence))).status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void namedUniversalScopeDefinitionDoesNotEraseAnOppositeRelationalLabel() {
		String claim = "사전협의 대상은 모든 정보화사업입니다.";
		for (String evidence : List.of(
			"사전협의 비대상은 모든 정보화사업입니다.",
			"사전협의 예외 대상은 모든 정보화사업입니다.",
			"사전협의 대상 제외사항은 모든 정보화사업입니다.",
			"사전협의 배제 대상은 모든 정보화사업입니다.",
			"사전협의 불포함 대상은 모든 정보화사업입니다.",
			"사전협의 비해당 대상은 모든 정보화사업입니다."
		)) {
			assertThat(matcher.match(claim, List.of(ground(evidence))).status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void identicalMultiwordRolesRemainSupported() {
		for (String proposition : List.of(
			"중앙 기관은 자료를 공개해야 합니다.",
			"국가 기관은 자료를 공개해야 합니다.",
			"개인정보 수집은 금지됩니다.",
			"기관은 개인정보 자료를 삭제해야 합니다.",
			"기관은 취약 아동에게 자료를 공개해야 합니다.",
			"기관은 개인정보 담당자입니다."
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(proposition, List.of(ground(proposition)));

			assertThat(match.status())
				.as("proposition=%s", proposition)
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void narrowRoleEvidenceCannotSupportABroaderRoleClaim() {
		for (List<String> pair : List.of(
			List.of("기관은 자료를 공개해야 합니다.", "중앙 기관은 자료를 공개해야 합니다."),
			List.of("기관은 수집을 금지 조치합니다.", "기관은 개인정보 수집을 금지 조치합니다."),
			List.of("기관은 서류를 제출해야 합니다.", "기관은 보완 서류를 제출해야 합니다."),
			List.of("기관은 아동에게 통지해야 합니다.", "기관은 취약 아동에게 통지해야 합니다.")
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void broaderRoleEvidenceCanSupportANarrowerRoleClaim() {
		for (List<String> pair : List.of(
			List.of("중앙 기관은 자료를 공개해야 합니다.", "기관은 자료를 공개해야 합니다."),
			List.of("기관은 보완 서류를 제출해야 합니다.", "기관은 서류를 제출해야 합니다."),
			List.of("기관은 취약 아동에게 통지해야 합니다.", "기관은 아동에게 통지해야 합니다.")
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void genitiveAndLongRoleQualifiersMustRemainDistinct() {
		for (List<String> pair : List.of(
			List.of("개인정보의 수집은 금지됩니다.", "위치정보의 수집은 금지됩니다."),
			List.of(
				"중앙 공공 디지털 정보 관리 기관은 자료를 공개해야 합니다.",
				"지방 공공 디지털 정보 관리 기관은 자료를 공개해야 합니다."
			),
			List.of(
				"개인정보 처리 시스템 관리자 접근 권한 신청은 불가능합니다.",
				"공공데이터 처리 시스템 관리자 접근 권한 신청은 불가능합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void genitiveTargetsDoNotCreateFalseConflictsAcrossContrast() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"온라인 개인정보의 수집은 허용됩니다.",
			List.of(ground(
				"온라인 위치정보의 수집은 금지되지만 온라인 개인정보의 수집은 허용됩니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void labelsDoNotOverrideAnAmbiguousButExplicitProhibitionTarget() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관A: 보호조치 신청 남용은 금지 조치됩니다.",
			List.of(ground("기관B: 보호조치 신청 남용은 허용됩니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void adverbInsideProhibitionRequestDoesNotInvertTheRequestPolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"공익신고자는 위원회에 불이익조치 금지를 바로 신청할 수 있습니다.",
			List.of(ground("공익신고자는 위원회에 불이익조치 금지를 신청할 수 있습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void differentRecipientAliasDoesNotTurnAProhibitionRequestIntoAContradiction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"공익신고자는 위원회에 불이익조치 금지를 바로 신청할 수 있습니다.",
			List.of(ground("공익신고자는 권익위에 불이익조치 금지를 신청할 수 있습니다."))
		);

		assertThat(match.status()).isNotIn(
			ClaimEvidenceMatcher.Status.CONTRADICTED,
			ClaimEvidenceMatcher.Status.CONFLICTED
		);
	}

	@Test
	void prohibitionNounInsideARequestDoesNotHideOppositeRequestPermission() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"금지 조치의 해제를 신청할 수 있습니다.",
			List.of(ground("금지 조치의 해제를 신청할 수 없습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void sameRequestContainingAProhibitionNounRemainsSupported() {
		String proposition = "금지 조치의 해제를 신청할 수 있습니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void laterPermissionClauseCannotMaskAnEarlierProhibition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보조금을 신청할 수 있습니다.",
			List.of(ground(
				"보조금을 신청하는 것을 금지하지만 이의를 신청할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void laterPermissionClauseRemainsSupportedForItsOwnObject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이의를 신청할 수 있습니다.",
			List.of(ground(
				"보조금을 신청하는 것을 금지하지만 이의를 신청할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void postpositionedProhibitionRequestAlignsWithItsExpandedAction() {
		for (List<String> pair : List.of(
			List.of(
				"공익신고자는 불이익조치 금지를 신청할 수 있습니다.",
				"공익신고자는 불이익조치를 하지 못하게 하는 신청을 할 수 있습니다."
			),
			List.of(
				"공익신고자는 불이익조치 금지를 요청할 수 있습니다.",
				"공익신고자는 불이익조치를 하지 못하게 요청할 수 있습니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void aProhibitionObjectIsNotDiscardedOutsideARequestForThatProhibition() {
		assertThat(matcher.match(
			"기관은 자료 금지를 시행합니다.",
			List.of(ground("기관은 자료 공개를 시행합니다."))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void repeatedObjectDoesNotCarryEarlierNegativePolarityIntoALaterAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보조금을 철회할 수 있습니다.",
			List.of(ground(
				"보조금을 신청할 수 없지만 보조금을 철회할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void repeatedObjectPreservesTheLaterActionsOwnOppositePolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"보조금을 철회할 수 없습니다.",
			List.of(ground(
				"보조금을 신청할 수 없지만 보조금을 철회할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void impossibilityInEarlierActionDoesNotLeakIntoLaterPermission() {
		String evidence =
			"기관은 보조금을 신청하는 것이 불가능하지만 보조금을 철회할 수 있습니다.";

		assertThat(matcher.match(
			"보조금을 철회할 수 있습니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(
			"보조금을 철회할 수 없습니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void subjectMarkedPermissionTargetsKeepTheirOwnPolarityAcrossContrast() {
		String evidence = "보조금 신청은 가능하지만 이의 신청은 불가능합니다.";

		assertThat(matcher.match(
			"보조금 신청은 불가능합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
		assertThat(matcher.match(
			"보조금 신청은 가능합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(
			"이의 신청은 불가능합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void oppositePermissionForTheSameSubjectMarkedTargetIsConflicted() {
		String evidence = "보조금 신청은 가능하지만 보조금 신청은 불가능합니다.";

		for (String claim : List.of(
			"보조금 신청은 가능합니다.",
			"보조금 신청은 불가능합니다."
		)) {
			assertThat(matcher.match(claim, List.of(ground(evidence))).status())
				.as("claim=%s", claim)
				.isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
		}
	}

	@Test
	void independentCoordinatedClausesCannotLendPredicatesAcrossSubjects() {
		for (List<String> pair : List.of(
			List.of("자료는 삭제합니다.", "자료는 보관하고 개인정보는 삭제합니다."),
			List.of("자료는 비공개합니다.", "자료는 공개하되 개인정보는 비공개합니다."),
			List.of("자료는 비공개됩니다.", "자료는 공개되며 개인정보는 비공개됩니다."),
			List.of("기관은 책임자입니다.", "기관은 담당자이며 사업자는 책임자입니다.")
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void independentCoordinatedClausesRemainGroundsForTheirOwnSubjects() {
		for (List<String> pair : List.of(
			List.of("자료는 보관합니다.", "자료는 보관하고 개인정보는 삭제합니다."),
			List.of("자료는 공개합니다.", "자료는 공개하되 개인정보는 비공개합니다."),
			List.of("자료는 공개됩니다.", "자료는 공개되며 개인정보는 비공개됩니다."),
			List.of("기관은 담당자입니다.", "기관은 담당자이며 사업자는 책임자입니다.")
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void repeatedMatrixSubjectsCannotLendAnotherObjectsPredicate() {
		for (List<String> pair : List.of(
			List.of(
				"기관은 자료를 삭제합니다.",
				"기관은 자료를 보관하고 기관은 개인정보를 삭제합니다."
			),
			List.of(
				"기관은 자료를 비공개합니다.",
				"기관은 자료를 공개하되 기관은 개인정보를 비공개합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void anOmittedSharedSubjectCannotPoolDistinctObjectsOrRecipients() {
		for (List<String> pair : List.of(
			List.of(
				"기관은 자료를 삭제합니다.",
				"기관은 자료를 보관하고 개인정보를 삭제합니다."
			),
			List.of(
				"기관은 자료를 비공개합니다.",
				"기관은 자료를 공개하되 개인정보를 비공개합니다."
			),
			List.of(
				"기관은 아동에게 안내합니다.",
				"기관은 아동에게 통지하고 성인에게 안내합니다."
			),
			List.of(
				"기관은 기존 자료를 삭제합니다.",
				"기관은 기존 자료를 보관하고 신규 자료를 삭제합니다."
			),
			List.of(
				"기관은 취약 아동에게 안내합니다.",
				"기관은 취약 아동에게 통지하고 일반 아동에게 안내합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void aCarriedSharedSubjectKeepsTheLaterRoleGrounded() {
		for (List<String> pair : List.of(
			List.of(
				"기관은 개인정보를 삭제합니다.",
				"기관은 자료를 보관하고 개인정보를 삭제합니다."
			),
			List.of(
				"기관은 개인정보를 비공개합니다.",
				"기관은 자료를 공개하되 개인정보를 비공개합니다."
			),
			List.of(
				"기관은 성인에게 안내합니다.",
				"기관은 아동에게 통지하고 성인에게 안내합니다."
			),
			List.of(
				"기관은 신규 자료를 삭제합니다.",
				"기관은 기존 자료를 보관하고 신규 자료를 삭제합니다."
			),
			List.of(
				"기관은 일반 아동에게 안내합니다.",
				"기관은 취약 아동에게 통지하고 일반 아동에게 안내합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void aSharedSubjectChainCannotPoolIntermediateRolesOrActions() {
		String evidence = "기관은 자료를 보관하고 개인정보를 삭제하고 결과를 통지합니다.";

		assertThat(matcher.match(
			"기관은 자료를 삭제합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(
			"기관은 개인정보를 삭제합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void rolelessPermissionContrastKeepsEachActionsOwnPolarity() {
		String evidence = "설치할 수 있지만 운영할 수 없습니다.";

		assertThat(matcher.match("설치할 수 있습니다.", List.of(ground(evidence))).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match("운영할 수 있습니다.", List.of(ground(evidence))).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void exclusiveRoleClaimsRequireTheSameExclusiveRoleInEvidence() {
		for (List<String> pair : List.of(
			List.of("기관은 신청서만 제출해야 합니다.", "기관은 보고서만 제출해야 합니다."),
			List.of("기관은 아동에게만 통지해야 합니다.", "기관은 성인에게만 통지해야 합니다."),
			List.of("기관만 자료를 공개해야 합니다.", "사업자만 자료를 공개해야 합니다."),
			List.of("기관은 신청서만 제출해야 합니다.", "기관은 신청서와 보고서를 제출해야 합니다."),
			List.of("기관은 아동에게만 통지해야 합니다.", "기관은 아동과 성인에게 통지해야 합니다."),
			List.of("기관만 자료를 공개해야 합니다.", "기관과 사업자는 자료를 공개해야 합니다.")
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void disjunctiveEvidenceCannotGroundAChosenBranch() {
		for (List<String> pair : List.of(
			List.of(
				"기관은 신청서를 제출해야 합니다.",
				"기관은 신청서 또는 보고서를 제출해야 합니다."
			),
			List.of(
				"기관은 자료를 공개해야 합니다.",
				"기관 또는 사업자는 자료를 공개해야 합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void conjunctiveEvidenceCanStillGroundAnIncludedBranch() {
		for (List<String> pair : List.of(
			List.of(
				"기관은 신청서를 제출해야 합니다.",
				"기관은 신청서와 보고서를 제출해야 합니다."
			),
			List.of(
				"기관은 자료를 공개해야 합니다.",
				"기관과 사업자는 자료를 공개해야 합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void contrastRecipientsCannotCrossSupport() {
		String evidence = "기관은 아동에게 통지했지만 성인에게 안내합니다.";

		assertThat(matcher.match(
			"기관은 아동에게 안내합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(
			"기관은 성인에게 안내합니다.",
			List.of(ground(evidence))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void exactExclusiveAndDisjunctivePropositionsRemainSupported() {
		for (String proposition : List.of(
			"기관은 신청서만 제출해야 합니다.",
			"기관은 아동에게만 통지해야 합니다.",
			"기관만 자료를 공개해야 합니다.",
			"기관은 신청서 또는 보고서를 제출해야 합니다."
		)) {
			assertThat(matcher.match(proposition, List.of(ground(proposition))).status())
				.as("proposition=%s", proposition)
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void ambiguousAttributiveContextCannotBeAssignedToEitherSubject() {
		String evidence = "기관은 요청하고 사업자가 적법하게 제출된 자료를 검토합니다.";

		for (String claim : List.of(
			"기관은 제출된 자료를 검토합니다.",
			"사업자는 제출된 자료를 검토합니다."
		)) {
			assertThat(matcher.match(claim, List.of(ground(evidence))).status())
				.as("claim=%s", claim)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void leadingAttributiveScopeActorCanModifyTheExplicitMatrixTopic() {
		assertThat(matcher.match(
			"일정 규모 이상의 정보화사업은 사전협의 대상입니다.",
			List.of(ground(
				"대상기관이 추진하는 일정 규모 이상의 정보화사업은 사전협의 대상입니다."
			))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void anEmbeddedAttributiveSubjectCannotBecomeTheMatrixActor() {
		for (List<String> pair : List.of(
			List.of(
				"사업자는 자료를 검토합니다.",
				"기관은 요청했지만 사업자가 제출한 자료를 검토합니다."
			),
			List.of(
				"사업자는 자료를 삭제합니다.",
				"기관은 보관하고 사업자가 제공한 자료를 삭제합니다."
			),
			List.of(
				"사업자는 적법하게 제출한 자료를 검토합니다.",
				"기관은 요청하고 사업자가 적법하게 제출한 자료를 검토합니다."
			),
			List.of(
				"사업자는 법령에 따라 제출한 자료를 검토합니다.",
				"기관은 요청하고 사업자가 법령에 따라 제출한 자료를 검토합니다."
			)
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void aStandaloneMatrixSubjectIsNotDiscardedBecauseItsObjectHasAnAttributiveModifier() {
		for (List<String> pair : List.of(
			List.of("사업자는 중요한 자료를 검토합니다.", "사업자가 중요한 자료를 검토합니다."),
			List.of("기관은 제출된 자료를 검토합니다.", "기관이 제출된 자료를 검토합니다.")
		)) {
			assertThat(matcher.match(pair.get(0), List.of(ground(pair.get(1)))).status())
				.as("claim=%s evidence=%s", pair.get(0), pair.get(1))
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void restrictiveRemaindersCannotBeDroppedFromPermissionClaims() {
		for (String evidence : List.of(
			"기관은 신청할 수 있지만 특별한 사유가 필요합니다.",
			"기관은 신청하고 특별한 사유가 있는 경우에 한합니다.",
			"기관은 신청하되 특별한 사유가 있는 경우에 한합니다.",
			"기관은 신청할 수 있지만 기관은 별도의 승인을 받아야 합니다.",
			"기관은 신청할 수 있지만 사전 인증을 받아야 합니다.",
			"기관은 신청할 수 있지만 보증금을 납부해야 합니다.",
			"기관은 신청할 수 있지만 허가가 있어야 합니다.",
			"기관은 신청할 수 있지만 승인을 전제로 합니다.",
			"기관은 신청할 수 있지만 특별한 사유가 요구됩니다."
		)) {
			assertThat(matcher.match(
				"기관은 신청할 수 있습니다.",
				List.of(ground(evidence))
			).status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void aThresholdPremiseCannotBeDroppedFromItsConclusion() {
		assertThat(matcher.match(
			"사업은 대상입니다.",
			List.of(ground("지원 요건은 10억원 이상이고 사업은 대상입니다."))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void unconditionalTargetHeadingCannotContradictAConditionalExclusion() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"제외사유에 해당하면 상용소프트웨어는 직접구매 대상에서 제외될 수 있습니다.",
			List.of(ground("상용소프트웨어 직접구매 대상"))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void resourceLimitedExceptionCannotContradictTheBroaderGeneralRule() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"공공기관이 추진하는 정보화사업은 원칙적으로 사전협의 대상입니다.",
			List.of(ground(
				"기술료, 자격증 시험 수수료 수입 등 기관 자체 수입으로 추진하는 "
					+ "정보화사업은 사전협의의 대상이 아닙니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void matchingResourceLimitedPropositionStillKeepsTheTrueContradiction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관 자체 수입으로 추진하는 정보화사업은 사전협의 대상입니다.",
			List.of(ground(
				"기관 자체 수입으로 추진하는 정보화사업은 사전협의의 대상이 아닙니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void differentResourceLimitedPropositionsDoNotBorrowOppositePolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관 보조금으로 추진하는 정보화사업은 사전협의 대상입니다.",
			List.of(ground(
				"기관 자체 수입으로 추진하는 정보화사업은 사전협의의 대상이 아닙니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameConditionalExclusionRemainsSupported() {
		String proposition =
			"제외사유에 해당하면 상용소프트웨어는 직접구매 대상에서 제외될 수 있습니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void destructionDutyCannotGroundASeparateMinimumCollectionBurden() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보처리자가 수집한 정보가 최소한인지에 대한 입증책임은 "
				+ "개인정보처리자에게 있습니다.",
			List.of(ground(
				"감사기구의 장은 개인정보 취급자 관리·감독 체계를 갖추어 운영하여야 하며, "
					+ "감사 종료 등으로 수집한 개인정보가 불필요하게 된 때에는 "
					+ "지체없이 파기하여야 한다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void responsibilityRecipientAlignsWithTheSameResponsibleActor() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"수집정보의 최소수집 입증책임은 개인정보처리자에게 있습니다.",
			List.of(ground(
				"개인정보처리자는 수집정보의 최소수집 입증책임을 부담합니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void responsibilityMentionDoesNotMakeTheNotificationActorResponsible() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"입증책임은 개인정보처리자에게 있습니다.",
			List.of(ground(
				"개인정보처리자는 정보주체에게 입증책임 관련 내용을 안내합니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void possessiveResponsibilityCannotBorrowADifferentBearer() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"최소수집은 개인정보처리자의 입증책임입니다.",
			List.of(ground("최소수집은 정보주체의 입증책임입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void samePossessiveResponsibilityBearerRemainsSupported() {
		String proposition = "최소수집은 개인정보처리자의 입증책임입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void responsibilityActorCannotSpanAcrossALaterSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보처리자는 최소수집임을 입증해야 합니다.",
			List.of(ground(
				"개인정보처리자는 수집정보를 관리하고 "
					+ "정보주체가 최소수집임을 입증해야 합니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameResponsibilityActorRemainsSupported() {
		String proposition = "개인정보처리자는 최소수집임을 입증해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void universalProjectScopeCannotBorrowDistributiveInstitutionEvidence() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"대상기관은 중앙·지방·공공기관 등 대상기관이 추진하는 모든 정보화사업입니다.",
			List.of(ground(
				"각 기관은 추진(발주) 예정인 사전협의 대상사업을 "
					+ "「기관별 정보화사업 사전협의 추진계획」에 포함하여 제출 "
					+ "* 중앙행정기관의 정보화 업무를 출연·위탁받은 "
					+ "공공기관·공기업 정보화사업 포함 ➋ 사전협의 신청"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameBoundUniversalScopeRemainsSupported() {
		String proposition =
			"대상기관은 중앙·지방·공공기관 등 대상기관이 추진하는 모든 정보화사업입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void universalQuantifierRemainsBoundToItsNoun() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"모든 정보화사업은 사전협의 대상입니다.",
			List.of(ground("각 기관은 사전협의 대상사업을 제출합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void universalScopeIncludesTheHeadOfASpacedNounPhrase() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"지원 범위는 모든 정보화 사업입니다.",
			List.of(ground("지원 범위는 모든 정보화 정책입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameSpacedUniversalNounPhraseRemainsSupported() {
		String proposition = "지원 범위는 모든 정보화 사업입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void partialEnumerationCannotEntailBroaderTargetScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"예비검토 대상은 정보시스템 구축·운영·공공앱 개발·공공 AI 사업 등 "
				+ "정보화 관련 사업입니다.",
			List.of(ground(
				"공공 AI 사업 및 공공앱 개발 사업은 예비검토 대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void openEnumerationParticleCannotBorrowOnlyOneListedMember() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"개인정보·민감정보 등의 자료를 파기해야 합니다.",
			List.of(ground("민감정보의 자료를 파기해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void completeOpenEnumerationWithParticleRemainsSupported() {
		String proposition = "개인정보·민감정보 등의 자료를 파기해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void openEnumerationPreservesMeaningfulLeadingQualifiers() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"공공 모바일 앱 개발·AI 사업 등은 지원 대상입니다.",
			List.of(ground("민간 모바일 앱 개발·AI 사업 등은 지원 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameQualifiedOpenEnumerationRemainsSupported() {
		String proposition = "공공 모바일 앱 개발·AI 사업 등은 지원 대상입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void copularOpenEnumerationPreservesMeaningfulLeadingQualifiers() {
		for (String copula : List.of("입니다", "이다", "임")) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"예비검토 대상은 공공 모바일 앱 개발·AI 사업 등" + copula + ".",
				List.of(ground("예비검토 대상은 민간 모바일 앱 개발·AI 사업 등" + copula + "."))
			);

			assertThat(match.status())
				.as("copula=%s", copula)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void sameCopularOpenEnumerationRemainsSupported() {
		for (String copula : List.of("입니다", "이다", "임")) {
			String proposition = "예비검토 대상은 공공 모바일 앱 개발·AI 사업 등" + copula + ".";

			ClaimEvidenceMatcher.Match match = matcher.match(
				proposition,
				List.of(ground(proposition))
			);

			assertThat(match.status())
				.as("copula=%s", copula)
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void completeOpenEndedEnumerationRemainsSupported() {
		String proposition =
			"예비검토 대상은 정보시스템 구축·운영·공공앱 개발·공공 AI 사업 등 "
				+ "정보화 관련 사업입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void enumerationMembersCannotBeStitchedAcrossGrounds() {
		String claim =
			"예비검토 대상은 정보시스템 구축·운영·공공앱 개발·공공 AI 사업 등 "
				+ "정보화 관련 사업입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			claim,
			List.of(
				ground("정보시스템 구축·운영 사업은 예비검토 대상입니다."),
				ground("공공앱 개발·공공 AI 사업 등은 예비검토 대상입니다.")
			)
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void tocLikeFragmentCannotGroundTargetClassification() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 보안성 검토 대상입니다.",
			List.of(ground(
				"목차 Ⅰ. 보안성 검토 개요 1 Ⅱ. 정보화사업 대상 사업 및 시기 2 "
					+ "Ⅲ. 추진체계 및 역할 4 [별첨 1] 보안성 검토 신청서 9 "
					+ "[참조 1] 주요 검토내용 13"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void structuralSelfAssertionWithoutExplicitSubjectRemainsInsufficient() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 적용 대상입니다.",
			List.of(ground(
				"목차입니다 보안성 검토 개요 정보화사업 적용 대상 사업 및 시기 "
					+ "추진체계 및 역할 추진절차 별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void checkboxFormRowsCannotGroundACommercialSoftwareExclusionRule() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"민간투자형 소프트웨어사업 등 법령상 제외사유에 해당하는 경우와 "
				+ "국가계약법 시행규칙·지방계약법 시행규칙상 제외사유가 적용되는 품목은 "
				+ "직접구매에서 제외될 수 있습니다.",
			List.of(ground(
				"번호 ①상용소프트웨어 품목 수량 ②직접구매 여부 ③제외 사유 ④비고 "
					+ "1 직접구매 [ ] 제 외 [ ] 2 직접구매 [ ] 제 외 [ ] "
					+ "3 직접구매 [ ] 제 외 [ ] 4 직접구매 [ ] 제 외 [ ] "
					+ "「소프트웨어사업 계약 및 관리감독에 관한 지침」 제7조 제8항에 따라 "
					+ "직접구매 대상 상용소프트웨어 구매계획을 위와 같이 명시합니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void directCommercialSoftwareExclusionRuleRemainsSupported() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"민간투자형 소프트웨어사업은 직접구매 대상에서 제외될 수 있습니다.",
			List.of(ground(
				"민간투자형 소프트웨어사업은 직접구매 대상에서 제외될 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void structuralSelfAssertionCannotBypassDenseTocFiltering() {
		for (String prefix : List.of("목차입니다", "개요입니다")) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"정보화사업은 적용 대상입니다.",
				List.of(ground(
					prefix + " 보안성 검토 개요 정보화사업은 적용 대상 사업 및 시기 "
						+ "추진체계 및 역할 추진절차 별첨 참조 신청서 결과서 기타 안내"
				))
			);

			assertThat(match.status())
				.as("prefix=%s", prefix)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void multilineStructuralSelfAssertionCannotBypassDenseTocFiltering() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 적용 대상입니다.",
			List.of(ground(
				"목차입니다\n"
					+ "정보화사업은 적용 대상 사업 및 시기\n"
					+ "추진체계 및 역할\n"
					+ "추진절차\n"
					+ "별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void structuralSourceVariantsCannotBypassDenseTocFiltering() {
		for (String prefix : List.of(
			"목차는 다음과 같습니다",
			"개요는 아래와 같습니다",
			"목차",
			"개요:",
			"목 차",
			"차례",
			"차 례",
			"CONTENTS"
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"정보화사업은 적용 대상입니다.",
				List.of(ground(
					prefix + "\n"
						+ "정보화사업은 적용 대상 사업 및 시기\n"
						+ "추진체계 및 역할\n"
						+ "추진절차\n"
						+ "별첨 참조 신청서 결과서 기타 안내"
				))
			);

			assertThat(match.status())
				.as("prefix=%s", prefix)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void decoratedStructuralSourceVariantsCannotBypassDenseTocFiltering() {
		for (String prefix : List.of(
			"Ⅰ. 목차",
			"[목차]",
			"목차.",
			"□ 목차",
			"○ 목차",
			"※ 개요",
			"① 목차"
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"정보화사업은 적용 대상입니다.",
				List.of(ground(
					prefix + "\n"
						+ "정보화사업은 적용 대상 사업 및 시기\n"
						+ "추진체계 및 역할\n"
						+ "추진절차\n"
						+ "별첨 참조 신청서 결과서 기타 안내"
				))
			);

			assertThat(match.status())
				.as("prefix=%s", prefix)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void denseParentContextCannotLendNominalChildFalseAssertiveness() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 적용 대상입니다.",
			List.of(groundWithContext(
				"정보화사업은 적용 대상 사업 및 시기",
				"목차는 다음과 같습니다\n"
					+ "정보화사업은 적용 대상 사업 및 시기\n"
					+ "추진체계 및 역할\n"
					+ "추진절차\n"
					+ "별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void structuralMarkerAndDenseLabelsCannotBeSplitAcrossGroundFields() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 적용 대상입니다.",
			List.of(groundWithContext(
				"목차",
				"정보화사업은 적용 대상 사업 및 시기\n"
					+ "추진체계 및 역할\n"
					+ "추진절차\n"
					+ "별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void chunkTitleStructuralMarkerAppliesToDenseBodyFields() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 적용 대상입니다.",
			List.of(groundWithChunkTitleAndContext(
				"목차",
				"정보화사업은 적용 대상 사업 및 시기",
				"정보화사업은 적용 대상 사업 및 시기\n"
					+ "추진체계 및 역할\n"
					+ "추진절차\n"
					+ "별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void assertiveLegalSentenceWithSeveralStructuralTermsRemainsSupported() {
		String proposition =
			"기관은 추진체계 및 역할, 추진절차와 검토내용을 별첨 신청서에 명시해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void multilineAssertiveProseWithStructuralTermsRemainsSupported() {
		String proposition =
			"기관은 추진체계 및 역할, 추진절차와 검토내용을 별첨 신청서에 명시해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition + "\n기타 안내"))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void assertiveProseInsideDenseStructuralSourceRemainsSupported() {
		String proposition =
			"기관은 추진체계 및 역할, 추진절차와 검토내용을 신청서에 명시해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground("개요입니다.\n" + proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void assertiveProseInsideStructuralSourceVariantsRemainsSupported() {
		String proposition =
			"기관은 추진체계 및 역할, 추진절차와 검토내용을 신청서에 명시해야 합니다.";

		for (String prefix : List.of(
			"목차는 다음과 같습니다",
			"개요는 아래와 같습니다",
			"목차",
			"개요:"
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				proposition,
				List.of(ground(prefix + "\n" + proposition))
			);

			assertThat(match.status())
				.as("prefix=%s", prefix)
				.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		}
	}

	@Test
	void assertiveChildInsideDenseParentContextRemainsSupported() {
		String proposition =
			"기관은 추진체계 및 역할, 추진절차와 검토내용을 신청서에 명시해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(groundWithContext(
				proposition,
				"목차\n추진체계 및 역할\n추진절차\n별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void finiteAssertiveChildOutsideTheCueListRemainsSupportedInDenseContext() {
		String proposition = "정보화사업은 적용 대상으로 봅니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(groundWithContext(
				proposition,
				"목차\n추진체계 및 역할\n추진절차\n별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void labeledNumericChildRemainsSupportedInDenseContext() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"평가기간은 2025년 12월 17일부터 2026년 10월 31일까지입니다.",
			List.of(groundWithContext(
				"평가기간: 2025. 12. 17 ~ 2026. 10. 31.",
				"목차\n추진체계 및 역할\n추진절차\n별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void tocPageNumberCannotActivateLabeledNumericEvidenceForANonnumericClaim() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 적용 대상입니다.",
			List.of(ground(
				"목차\n"
					+ "정보화사업: 적용 대상 사업 및 시기 2\n"
					+ "추진체계 및 역할 4\n"
					+ "추진절차 6\n"
					+ "별첨 참조 신청서 결과서 9"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void tocPageNumberCannotGroundAQualifiedStageClaim() {
		String toc = "목차\n"
			+ "정보화사업: 적용 대상 사업 및 시기 2\n"
			+ "추진체계 및 역할 4\n"
			+ "추진절차 6\n"
			+ "별첨 참조 신청서 결과서 9";

		for (String claim : List.of(
			"정보화사업은 2단계 적용 대상입니다.",
			"정보화사업은 2단계 적용 비대상입니다."
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(claim, List.of(ground(toc)));

			assertThat(match.status())
				.as("claim=%s", claim)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void qualifiedNumberInsideATocHeadingCannotGroundAClassification() {
		String toc = "목차\n"
			+ "정보화사업: 2단계 적용 대상 사업 및 시기 2\n"
			+ "추진체계 및 역할 4\n"
			+ "추진절차 6\n"
			+ "별첨 참조 신청서 결과서 9";

		for (String claim : List.of(
			"정보화사업은 2단계 적용 대상입니다.",
			"정보화사업은 2단계 적용 비대상입니다."
		)) {
			assertThat(matcher.match(claim, List.of(ground(toc))).status())
				.as("claim=%s", claim)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void labeledQualifiedStageRemainsSupportedInDenseContext() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"사업 단계는 2단계입니다.",
			List.of(groundWithContext(
				"사업 단계: 2단계",
				"목차\n추진체계 및 역할\n추진절차\n별첨 참조 신청서 결과서 기타 안내"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void structuralWordInsideAnAssertiveSubjectIsNotAnIntroMarker() {
		String proposition = "목차 작성자는 추진절차와 검토내용을 신청서에 명시해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void articleHeadingCannotGroundTargetClassification() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업은 보안성 검토 대상입니다.",
			List.of(ground("제15조(보안성 검토 대상)"))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void shortNominalHeadingCannotGroundTargetClassification() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 보안성 검토 대상입니다.",
			List.of(ground("보안성 검토 대상"))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void nominalTableOfContentsLabelsCannotGroundTargetClassification() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"정보화사업 보안성 검토내용은 추진체계 및 역할입니다.",
			List.of(ground(
				"보안성 검토 대상 사업 및 시기 정보화사업 추진체계 및 역할 검토내용"
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void directTargetSentenceStillSupportsAfterStructuralFiltering() {
		String proposition = "정보화사업은 보안성 검토 대상입니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void directLegalDutySentenceStillSupportsAfterStructuralFiltering() {
		String proposition = "기관은 보안성 검토를 신청해야 합니다.";

		assertThat(matcher.match(
			proposition,
			List.of(ground(proposition))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void assertiveConnectiveClauseStillSupportsAfterStructuralFiltering() {
		String proposition = "정보화사업은 보안성 검토 대상이며";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void negatedTargetExclusionFailsClosed() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업은 과업심의 대상에서 제외하지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void negatedTargetExclusionWithTopicParticleFailsClosed() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 과업심의 대상입니다.",
			List.of(ground("이 사업은 과업심의 대상에서 제외하지는 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void targetNegationRecognizesInterveningParticlesAndInability() {
		for (String evidence : List.of(
			"이 사업은 과업심의 대상으로 보지는 못합니다.",
			"이 사업은 과업심의 대상으로 판단조차 하지 않습니다.",
			"이 사업은 과업심의 대상에 포함조차 되지 않습니다.",
			"이 사업은 과업심의 대상으로 판단은 못합니다.",
			"이 사업은 과업심의 대상으로 판단조차 못합니다.",
			"이 사업은 과업심의 대상에 포함은 못합니다.",
			"이 사업은 과업심의 대상에 포함조차 못합니다."
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"이 사업은 과업심의 대상입니다.",
				List.of(ground(evidence))
			);

			assertThat(match.status())
				.as("evidence=%s", evidence)
				.isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
		}
	}

	@Test
	void feeExemptionNegationIsARealPredicateContradictionNotTargetAmbiguity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"수수료는 면제됩니다.",
			List.of(ground("수수료는 면제되지 않습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void qualifiedDocumentContextCanScopeAGenericPolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부 사전협의 대상입니다.",
			List.of(ground(
				"전자정부 사전협의 안내",
				"이 사업은 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void differentlyQualifiedDocumentContextCannotScopeAGenericPolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 전자정부 사전협의 대상입니다.",
			List.of(ground(
				"자치분권 사전협의 안내",
				"이 사업은 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void unnamedTargetClaimCannotBorrowANamedEvidenceScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 대상입니다.",
			List.of(ground("이 사업은 환경영향평가 비대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void unnamedTargetPolarityStillAlignsWhenBothSidesAreGeneric() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 대상입니다.",
			List.of(ground("이 사업은 비대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void namedProcedureContextDoesNotRestrictANonTargetPredicate() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 공개합니다.",
			List.of(ground(
				"환경영향평가 안내",
				"기관은 자료를 공개합니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void compoundProcedureContextCannotBeBorrowedAsASingleNamedScope() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"이 사업은 자치분권 사전협의 대상입니다.",
			List.of(ground(
				"전자정부 및 자치분권 사전협의 통합 안내",
				"이 사업은 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void symbolicAndPostpositionedCompoundQualifiersRemainAmbiguous() {
		for (String title : List.of(
			"전자정부·자치분권 사전협의 통합 안내",
			"전자정부ㆍ자치분권 사전협의 통합 안내",
			"전자정부와 자치분권 사전협의 통합 안내",
			"정보보안과 자치분권 사전협의 통합 안내",
			"전자정부/자치분권 사전협의 통합 안내",
			"전자정부&자치분권 사전협의 통합 안내",
			"전자정부, 자치분권 사전협의 통합 안내",
			"전자정부-자치분권 사전협의 통합 안내"
		)) {
			ClaimEvidenceMatcher.Match match = matcher.match(
				"이 사업은 자치분권 사전협의 대상입니다.",
				List.of(ground(title, "이 사업은 비대상입니다."))
			);

			assertThat(match.status())
				.as("title=%s", title)
				.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		}
	}

	@Test
	void restrictiveRemainderCannotBeDetachedFromAHeadlessTargetPredicate() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"모든 사업은 과업심의 대상입니다.",
			List.of(ground(
				"과업심의 대상이며, 예산이 10억원 이상인 사업만 신청할 수 있습니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void ambiguousMultiProcedureContextDoesNotScopeAGenericPolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입은 사전협의 대상입니다.",
			List.of(ground(
				"과업심의 및 사전협의 통합 가이드",
				"단순 H/W 도입은 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void eligibilityTargetSubjectIsNotDiscarded() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"지원 대상은 서류를 제출해야 합니다.",
			List.of(ground("기관은 서류를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameConditionalScopeStillDetectsTheOppositeTarget() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우에는 과업심의 대상입니다.",
			List.of(ground(
				"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우에는 과업심의 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void equivalentSoftwareConditionStillDetectsTheOppositeTarget() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우에는 과업심의 대상입니다.",
			List.of(ground(
				"단순 H/W 도입·설치 등 SW사업으로 볼 수 없는 경우에는 과업심의 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void differentExplicitObjectsAreNotTheSameObligation() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 결과를 통지해야 합니다.",
			List.of(ground("기관은 자료를 통지해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void subjectMentionedOnlyAsAModifierDoesNotAlignTheActor() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 결과를 통지해야 합니다.",
			List.of(ground("사업자는 기관의 결과를 통지해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sharedDeterminerDoesNotAlignDifferentSubjects() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"해당 기관은 자료를 공개해야 합니다.",
			List.of(ground("해당 사업자는 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void contextualInstitutionDoesNotBecomeTheTargetSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"국가기관은 과업심의 대상입니다.",
			List.of(ground("국가기관 등이 발주하는 SW사업은 과업심의 대상입니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void everyExplicitlyCoordinatedSubjectMustAlign() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관과 사업자는 자료를 공개해야 합니다.",
			List.of(ground("기관과 시민은 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void whitespaceDelimitedCoordinatedSubjectsMustAllAlign() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관 및 사업자는 자료를 공개해야 합니다.",
			List.of(ground("사업자는 자료를 공개해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void permissionActionDoesNotHideADifferentObject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청서를 제출할 수 있습니다.",
			List.of(ground("기관은 보고서를 제출할 수 있습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void coordinatedObjectsCannotBorrowOnlyTheFinalObject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청서와 보고서를 제출해야 합니다.",
			List.of(ground("기관은 보고서를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void whitespaceDelimitedCoordinatedObjectsMustAllAlign() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청서 및 보고서를 제출해야 합니다.",
			List.of(ground("기관은 보고서를 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void explicitObjectCannotBorrowEvidenceWithoutTheSameObjectRole() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 신청서를 제출해야 합니다.",
			List.of(ground("기관은 신청서 확인 후 보고서 제출해야 합니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void sameCoordinatedObjectsRemainSupported() {
		String proposition = "기관은 신청서와 보고서를 제출해야 합니다.";

		ClaimEvidenceMatcher.Match match = matcher.match(
			proposition,
			List.of(ground(proposition))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void differentPurposeDoesNotSupportTheSamePermissionAction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"범죄 예방을 위해 CCTV를 설치할 수 있습니다.",
			List.of(ground("교통 단속을 위해 CCTV를 설치할 수 있습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void everyMeaningfulPurposeAnchorMustAlign() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"아동 대상 강력 범죄의 신속한 예방을 위해 CCTV를 설치할 수 있습니다.",
			List.of(ground("성인 대상 강력 범죄의 신속한 예방을 위해 CCTV를 설치할 수 있습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void preservesTrueConflictForTheSameAtomicProposition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입은 과업심의 대상입니다.",
			List.of(
				ground("단순 H/W 도입은 과업심의 대상입니다."),
				ground("단순 H/W 도입은 과업심의 비대상입니다.")
			)
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
	}

	@Test
	void detectsContradictionForTheSameScopedTarget() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입도 과업심의 대상입니다.",
			List.of(ground(
				"단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 과업심의 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void documentContextCanIdentifyTheScopeOfAGenericTargetPolarity() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"단순 H/W 도입도 과업심의 대상입니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void explicitExceptionListKeepsAnEvidenceLimitedDenialFailClosedWithoutForcingContradiction() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"제공된 문서만으로 보안성 검토 절차를 생략할 수 있는 일반적 예외를 확정할 수 없습니다.",
			List.of(ground(
				"보안성 검토 절차 이행 생략 대상 "
					+ "1. 단순 장비 도입 "
					+ "2. 단순 제품 교체 "
					+ "상기 항목에 해당하는 사업은 보안성 검토 절차의 이행을 생략할 수 있다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void evidenceLimitedUncertaintyIsNeitherContradictedNorSupportedByTheBareActionPolarity() {
		String claim = "제공된 문서만으로 자료를 제출할 수 있는 일반적 예외를 확정할 수 없습니다.";

		assertThat(matcher.match(
			claim,
			List.of(ground("자료를 제출할 수 있습니다."))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(
			claim,
			List.of(ground("자료를 제출할 수 없습니다."))
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void unqualifiedDocumentActionDoesNotActivateTheEvidenceLimitedDenialRule() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"문서 기록을 생략할 수 있는지 확정할 수 없습니다.",
			List.of(ground("다른 문서의 기록 이행을 생략할 수 있습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsGeneralRuleWhenScopedExceptionIsInAnotherGround() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"국가기관 발주 소프트웨어사업은 과업심의 대상입니다.",
			List.of(
				ground("국가기관 발주 소프트웨어사업은 과업심의 대상입니다."),
				ground("단순 H/W 도입은 과업심의 비대상입니다.")
			)
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void negativeClassificationBoundaryDoesNotConflictWithThePositiveBusinessCondition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어사업에 해당하면 과업심의 대상입니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"적용 대상 사업은 국가기관 등이 발주하는 모든 SW사업입니다. "
					+ "단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void negativeClassificationBoundaryAloneDoesNotContradictThePositiveBusinessCondition() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"소프트웨어사업에 해당하면 과업심의 대상입니다.",
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상입니다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void exactGeneralSupportWinsOverConditionalOppositeGroundForSameSubject() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"사업은 지원 대상입니다.",
			List.of(
				ground("사업은 지원 대상입니다."),
				ground("법령에서 정한 경우 사업은 지원 비대상입니다.")
			)
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void permissiveFallbackIsInsufficientForAStrongerNotificationDuty() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기간을 특정할 수 없으면 기간을 결정하는 기준을 알려야 합니다.",
			List.of(ground(
				"동의를 거부할 권리가 있다는 사실 및 동의 거부에 따른 불이익이 있는 경우 "
					+ "그 불이익의 내용을 알려야 한다(제2항). "
					+ "보유 및 이용 기간은 구체적으로 기간을 정해서 알려야 하나, "
					+ "보유 및 이용 기간을 특정할 수 없는 경우에는 보유 및 이용 기간을 "
					+ "결정하는데 사용되는 기준을 알려도 된다."
			))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void explicitProhibitionOfTheSamePermissionActionRemainsContradictory() {
		ClaimEvidenceMatcher.Match match = matcher.match(
			"기관은 자료를 제출할 수 있습니다.",
			List.of(ground("기관은 자료를 제출할 수 없습니다."))
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
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
			"공식 가이드 문서",
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

	private LawAiAnswerGround groundWithContext(String matchedChildText, String parentContextText) {
		return groundWithChunkTitleAndContext("근거", matchedChildText, parentContextText);
	}

	private LawAiAnswerGround groundWithChunkTitleAndContext(
		String chunkTitle,
		String matchedChildText,
		String parentContextText
	) {
		return new LawAiAnswerGround(
			1,
			1,
			1,
			"official_doc",
			"공식 문서",
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page 1",
			chunkTitle,
			1,
			matchedChildText,
			null,
			null,
			0.9,
			matchedChildText,
			parentContextText,
			List.of(1L),
			"matched_child_parent"
		);
	}
}
