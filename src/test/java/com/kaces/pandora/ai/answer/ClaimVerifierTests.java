package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimVerifierTests {

	private final ClaimVerifier verifier = new ClaimVerifier();

	@Test
	void keepsStrongClaimWhenEvidenceTermsOverlap() {
		String answer = "공공소프트웨어사업이면 과업심의 대상입니다. "
			+ "단순 H/W 도입은 과업심의 비대상으로 확인해야 합니다.";

		String verified = verifier.verify(answer, List.of(ground(
			"공공소프트웨어사업 과업심의의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이며 단순 H/W 도입·설치는 비대상으로 확인해야 한다."
		)));

		assertThat(verified).contains("과업심의 대상입니다");
		assertThat(verified).contains("단순 H/W 도입");
	}

	@Test
	void exactOfficialHardwareBoundaryRemainsSupportedWithSlashAndMiddleDot() {
		String boundary =
			"단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상이다.";
		assertThat(new ClaimEvidenceAtomizer().atomize(boundary)).containsExactly(boundary);

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			boundary,
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"적용 대상 사업",
				boundary
			))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.supportedStrongClaimCount()).isEqualTo(1);
	}

	@Test
	void verifiesCoordinatedGeneralProhibitionAndItsScopedExceptionSeparately() {
		String answer = "결론부터 말하면, 공개된 장소에 CCTV(고정형 영상정보처리기기)를 "
			+ "설치하는 것은 원칙적으로 금지되어 있으며, 법 제25조에서 정한 사유에 "
			+ "해당하는 경우에만 예외적으로 허용됩니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			answer,
			List.of(
				ground(
					"고정형 영상정보처리기기 설치 안내",
					"설치 원칙",
					"공개된 장소에서 고정형 영상정보처리기기를 설치·운영하는 것은 "
						+ "원칙적으로 금지됩니다."
				),
				ground(
					"고정형 영상정보처리기기 설치 안내",
					"허용 예외",
					"법 제25조에서 정하는 사유에 해당하는 경우에만 "
						+ "고정형 영상정보처리기기를 설치·운영할 수 있습니다."
				)
			)
		);

		assertThat(result.insufficientEvidence())
			.as("result=%s", result)
			.isFalse();
		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.supportedStrongClaimCount()).isEqualTo(2);
		assertThat(result.verifiedAnswer())
			.contains("원칙적으로 금지")
			.contains("법 제25조")
			.contains("예외적으로 허용");
	}

	@Test
	void removesUnsupportedStrongClaimWhenMostClaimsRemainSupported() {
		String answer = "공공소프트웨어사업이면 과업심의 대상입니다. 과태료는 500만원입니다. 신청 절차는 추가 확인이 필요합니다.";

		String verified = verifier.verify(answer, List.of(ground(
			"공공소프트웨어사업 과업심의의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상"
		)));

		assertThat(verified).contains("과업심의 대상입니다");
		assertThat(verified).doesNotContain("과태료는 500만원");
		assertThat(verified).doesNotContain("추가 확인이 필요합니다");
	}

	@Test
	void removesPreConsultationExclusionBorrowedFromProjectReviewEvidence() {
		String supportedClaim = "사전협의 대상은 국가기관 등이 추진하는 모든 정보화사업입니다.";
		String borrowedExclusion =
			"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우는 사전협의 대상이 아닙니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			supportedClaim + " " + borrowedExclusion,
			List.of(
				ground(
					"2024년 정보화사업 사전협의 안내자료",
					"대상 사업",
					"사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업임"
				),
				ground(
					"공공소프트웨어사업 과업심의 가이드",
					"적용 대상 사업",
					"단순 H/W 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우는 비대상"
				)
			)
		);

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).contains(supportedClaim);
		assertThat(result.verifiedAnswer()).doesNotContain(borrowedExclusion);
		assertThat(result.unsupportedClaims()).containsExactly(borrowedExclusion);
	}

	@Test
	void failsClosedWhenNegatedTargetClassificationHasOnlyUnrelatedEvidence() {
		String claim = "이 사업은 과업심의 대상으로 보지 않습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			claim,
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"개발사업은 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.unsupportedClaims()).containsExactly(claim);
		assertThat(result.strongClaimCount()).isEqualTo(1);
	}

	@Test
	void keepsNegatedTargetClassificationWhenDirectlyGrounded() {
		String claim = "이 사업은 과업심의 대상으로 보지 않습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			claim,
			List.of(ground(
				"공공소프트웨어사업 과업심의 가이드",
				"비대상 사업",
				"이 사업은 과업심의 대상으로 보지 않습니다."
			))
		);

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(claim);
		assertThat(result.strongClaimCount()).isEqualTo(1);
		assertThat(result.supportedStrongClaimCount()).isEqualTo(1);
	}

	@Test
	void failsClosedWhenCautionTextContainsAnUnsupportedProposition() {
		String caution = "이 사업이 과업심의 대상인지 확인되지 않습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			caution,
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"개발사업은 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.unsupportedClaims()).containsExactly(caution);
		assertThat(result.strongClaimCount()).isEqualTo(1);
	}

	@Test
	void failsClosedWhenUnsupportedNounFormConclusionHasOnlyUnrelatedGrounds() {
		String conclusion = "기관A 고위험 분류";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			conclusion,
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"기관B는 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.unsupportedClaims()).containsExactly(conclusion);
	}

	@Test
	void failsClosedWhenUnsupportedMarkdownBulletHasOnlyUnrelatedGrounds() {
		String conclusion = "- 기관A 고위험 분류";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			conclusion,
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"기관B는 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.unsupportedClaims()).containsExactly(conclusion);
	}

	@Test
	void failsClosedWhenLabeledColonFragmentCarriesAnUnsupportedConclusion() {
		String conclusion = "결론: 기관A 고위험 분류";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			conclusion,
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"기관B는 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.unsupportedClaims()).containsExactly(conclusion);
	}

	@Test
	void treatsBareStructuralLabelAsNonSubstantiveButStillFailsClosedWithoutSupport() {
		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			"결론:",
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"기관B는 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.strongClaimCount()).isZero();
	}

	@Test
	void verifiesNumericOnlyAtomAndRecordsUnsupportedNumber() {
		String answer = "123";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			answer,
			List.of(ground(
				"환경영향평가 안내",
				"평가 대상",
				"기관B는 환경영향평가 대상입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
		assertThat(result.unsupportedNumericClaims()).containsExactly(answer);
		assertThat(result.strongClaimCount()).isEqualTo(1);
	}

	@Test
	void preservesStructuralHeadingWhenFollowingSubstantiveAtomIsSupported() {
		String answer = "결론:\n기관은 신청서를 제출해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			answer,
			List.of(ground(
				"신청 절차 안내",
				"신청 방법",
				"기관은 신청서를 제출해야 합니다."
			))
		);

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.strongClaimCount()).isEqualTo(1);
	}

	@Test
	void failsClosedWhenGenericNegativeAssertionHasOppositeEvidence() {
		String claim = "기관은 자료를 공개하지 않습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			claim,
			List.of(ground(
				"자료 공개 지침",
				"공개 원칙",
				"기관은 자료를 공개합니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).containsExactly(claim);
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void failsClosedWhenInabilityNegationHasOppositeEvidence() {
		String claim = "기관은 자료를 공개하지 못합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			claim,
			List.of(ground(
				"자료 공개 지침",
				"공개 원칙",
				"기관은 자료를 공개합니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).containsExactly(claim);
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void failsClosedWhenPlannedActionDiffersFromEvidence() {
		String claim = "기관은 자료를 공개할 예정입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			claim,
			List.of(ground(
				"자료 처리 계획",
				"처리 예정",
				"기관은 자료를 폐기할 예정입니다."
			))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(claim);
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void failsClosedWhenContradictedClaimIsMixedWithSupportedRemainder() {
		String contradictedClaim = "단순 H/W 도입은 예비검토 대상입니다.";
		String supportedRemainder = "중앙행정기관의 10억원 미만 계속사업은 예비검토 대상에서 제외됩니다.";
		String answer = contradictedClaim + " " + supportedRemainder;

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"전자정부 성과관리 지침",
			"예비검토 대상 사업",
			"단순 H/W 도입은 예비검토 대상에서 제외됩니다. "
				+ "중앙행정기관의 10억원 미만 계속사업은 예비검토 대상에서 제외됩니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.contradictedClaims()).containsExactly(contradictedClaim);
		assertThat(result.supportedStrongClaimCount()).isEqualTo(1);
	}

	@Test
	void blocksAnswerWhenNoStrongClaimIsSupported() {
		String answer = "과태료는 500만원입니다. 기한은 10일입니다.";

		String verified = verifier.verify(answer, List.of(ground(
			"공공소프트웨어사업 과업심의의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상"
		)));

		assertThat(verified).contains("제공된 근거만으로는 답변을 확정하기 어렵습니다");
		assertThat(verified).doesNotContain("과태료는 500만원");
		assertThat(verified).doesNotContain("기한은 10일");
	}

	@Test
	void blocksUnsupportedSingleSentenceStrongClaim() {
		String answer = "과태료는 500만원입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공소프트웨어사업 과업심의의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상"
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
		assertThat(result.verifiedAnswer()).contains("제공된 근거만으로는 답변을 확정하기 어렵습니다");
	}

	@Test
	void keepsSupportedSingleSentenceStrongClaim() {
		String answer = "공공소프트웨어사업이면 과업심의 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공소프트웨어사업 과업심의의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
		assertThat(result.unsupportedClaims()).isEmpty();
	}

	@Test
	void removesNumericClaimWhenEvidenceDoesNotContainTheNumber() {
		String answer = "제출 기한은 30일입니다. 공공소프트웨어사업은 과업심의 대상입니다.";

		String verified = verifier.verify(answer, List.of(ground(
			"공공소프트웨어사업 과업심의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 공공소프트웨어사업은 과업심의 대상입니다."
		)));

		assertThat(verified).doesNotContain("30일");
		assertThat(verified).contains("공공소프트웨어사업");
	}

	@Test
	void removesUnsupportedCautiousFollowUpWhileKeepingSupportedAnswer() {
		String answer = "데이터 전처리 절차는 오류 원인 분석, 대상 선정, 방법 결정 순서로 진행됩니다. 일정·비용·제출 양식 등 구체 내용은 문서에 불충분하니 별도 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"2022년 공공데이터 활용기업 맞춤형지원 활용사례",
			"데이터 전처리 절차",
			"데이터 전처리 절차는 오류 원인 분석 > 대상 선정 > 방법 결정. 데이터 전처리 방법 삭제, 대체, 예측값 삽입 등"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).containsExactly(
			"일정·비용·제출 양식 등 구체 내용은 문서에 불충분하니 별도 확인이 필요합니다."
		);
		assertThat(result.verifiedAnswer()).contains("오류 원인 분석");
		assertThat(result.verifiedAnswer()).doesNotContain("별도 확인이 필요합니다");
	}

	@Test
	void verifiesStrongClaimEvenWhenSameSentenceEndsWithCaution() {
		String answer = "위반 시 과태료는 100만원이며 추가 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"신청 절차 안내",
			"신청 방법",
			"신청서는 담당 기관에 제출합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void verifiesNonNumericStrongClaimBeforeCautionCue() {
		String answer = "이 사업은 과업심의 대상이며 추가 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"신청 절차 안내",
			"신청 방법",
			"신청서는 담당 기관에 제출합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void verifiesEligibilityClaimBeforeContrastiveCautionConnector() {
		String answer = "신청 자격이 있습니다만 별도 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"신청 절차 안내",
			"신청 방법",
			"신청서는 담당 기관에 제출합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void failsClosedWhenOnlyUnsupportedCautiousRemainderSurvives() {
		String unsupportedClaim = "이 사업은 과업심의 대상입니다.";
		String answer = unsupportedClaim + " 추가 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"신청 절차 안내",
			"신청 방법",
			"신청서는 담당 기관에 제출합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(
			unsupportedClaim,
			"추가 확인이 필요합니다."
		);
	}

	@Test
	void failsClosedWhenGroundsAreAbsent() {
		String answer = "위반 시 과태료는 100만원입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of());

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void preservesInsufficientEvidenceStatusWhenGuardAlreadyRefused() {
		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE,
			List.of(ground("신청 절차 안내", "신청 방법", "신청서는 담당 기관에 제출합니다."))
		);

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void removesUnsupportedCautiousFollowUpFromSupportedNumericClaim() {
		String answer = "관광두레 주민사업체에는 최대 5년간 1억 1천만 원 상당 맞춤형 지원이 제공됩니다. 세부 조건은 공고별로 달라질 수 있으니 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"관광두레 주민사업체 공모",
			"지원",
			"관광두레 주민사업체에는 최대 5년간 1억 1천만 원 상당 맞춤형 지원이 제공됩니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).containsExactly(
			"세부 조건은 공고별로 달라질 수 있으니 확인이 필요합니다."
		);
		assertThat(result.verifiedAnswer()).contains("최대 5년간");
		assertThat(result.verifiedAnswer()).contains("1억 1천만 원");
		assertThat(result.verifiedAnswer()).doesNotContain("세부 조건은");
	}

	@Test
	void removesUnsupportedSentenceButKeepsGroundedNonStrongProcedure() {
		String answer = "데이터 전처리 절차는 오류 원인 분석, 대상 선정, 방법 결정 순서입니다. 통보·적용: 최종 피드백과 적용 방안을 통보하고 필요시 추가 보완을 진행합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"2022년 공공데이터 활용기업 맞춤형지원 활용사례",
			"데이터 전처리 절차",
			"데이터 전처리 절차 오류 원인 분석 > 대상 선정 > 방법 결정. 데이터 전처리 방법 삭제, 대체, 예측값 삽입 등"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).contains("통보·적용: 최종 피드백과 적용 방안을 통보하고 필요시 추가 보완을 진행합니다.");
		assertThat(result.verifiedAnswer()).contains("오류 원인 분석");
		assertThat(result.verifiedAnswer()).doesNotContain("통보·적용");
	}

	@Test
	void keepsDateRangeClaimWhenEvidenceUsesDottedDates() {
		String answer = "IRM 평가는 전체적으로 2025년 12월 17일부터 2026년 10월 31일까지 실시됩니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"2026년도 정보자원관리시스템 기반 정보자원 관리 수준측정 해설서",
			"평가 방법",
			"IRM 평가는 2025. 12. 17부터 2026. 10. 31까지 실시됩니다. 기관의 정보자원 현황을 기한 내 등록하였는지 확인"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).contains("2025년 12월 17일");
		assertThat(result.verifiedAnswer()).contains("2026년 10월 31일");
	}

	@Test
	void rejectsClaimThatContradictsEvidencePolarity() {
		String answer = "단순 H/W 도입도 과업심의 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공소프트웨어사업 과업심의 가이드",
			"적용 대상 사업",
			"단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상"
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).containsExactly(answer);
		assertThat(result.evidenceLinks())
			.singleElement()
			.satisfies(link -> {
				assertThat(link.relation()).isEqualTo("CONTRADICTED");
				assertThat(link.evidenceSentence()).contains("비대상");
			});
	}

	@Test
	void doesNotCombineSeparateEvidenceSentencesIntoArtificialSupport() {
		String answer = "정보시스템은 반드시 30일 이내 제출해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("정보화사업 지침", "제출 대상", "정보시스템은 검토 대상에 포함됩니다."),
			ground("민원 처리 지침", "처리 기간", "일반 민원의 처리기간은 30일입니다.")
		));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
		assertThat(result.evidenceLinks()).isEmpty();
	}

	@Test
	void recordsConcreteEvidenceSentenceForSupportedClaim() {
		String answer = "공공소프트웨어사업은 과업심의 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공공소프트웨어사업 과업심의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 공공소프트웨어사업은 과업심의 대상입니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.evidenceLinks())
			.singleElement()
			.satisfies(link -> {
				assertThat(link.relation()).isEqualTo("SUPPORTED");
				assertThat(link.groundNumber()).isEqualTo(1);
				assertThat(link.evidenceSentence()).contains("과업심의 대상");
			});
	}

	@Test
	void doesNotTreatDescriptiveEvidenceAsAnObligation() {
		String answer = "정보화사업은 보안성 검토를 받아야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"정보화사업 보안 가이드",
			"보안성 검토 안내",
			"정보화사업 보안성 검토 절차와 담당 기관을 안내합니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void doesNotUpgradePermissionIntoAnObligation() {
		String answer = "기관은 자료를 제출해야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"자료 제출 안내",
			"제출 방법",
			"기관은 필요한 경우 자료를 제출할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void doesNotGeneralizeNarrowEvidenceIntoAUniversalClaim() {
		String answer = "모든 정보화사업은 사전협의 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"정보화사업 사전협의 지침",
			"대상 사업",
			"대상기관이 추진하는 일정 규모 이상의 정보화사업은 사전협의 대상입니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void keepsClaimWhenTheSameNarrowingConditionIsGrounded() {
		String answer = "일정 규모 이상의 정보화사업은 사전협의 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"정보화사업 사전협의 지침",
			"대상 사업",
			"대상기관이 추진하는 일정 규모 이상의 정보화사업은 사전협의 대상입니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	@Test
	void verifiesOrdinaryFactualSentencesInsteadOfPassingThemThrough() {
		String answer = "IRM 충실성은 등록정보의 최신성을 평가하는 지표이다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"IRM 사용자 안내서",
			"권한 관리",
			"IRM 관리자는 사용자 권한을 등록하고 변경할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void verifiesOrdinaryFactualSentenceEvenWithLeadingCautionCue() {
		String answer = "근거상 IRM 충실성은 등록정보의 최신성을 평가하는 지표입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"IRM 사용자 안내서",
			"권한 관리",
			"IRM 관리자는 사용자 권한을 등록하고 변경할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void linksOrdinaryFactualSentenceToConcreteEvidence() {
		String answer = "IRM 충실성은 등록정보의 완전성을 평가하는 지표입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"IRM 평가 해설서",
			"충실성",
			"IRM 충실성은 등록정보의 완전성을 평가하는 지표입니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.evidenceLinks())
			.singleElement()
			.satisfies(link -> assertThat(link.evidenceSentence()).contains("등록정보의 완전성"));
	}

	@Test
	void failsClosedWhenSelectedGroundsConflict() {
		String answer = "단순 H/W 도입은 과업심의 대상입니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(
			ground("과업심의 안내", "대상 사업", "단순 H/W 도입은 과업심의 대상입니다."),
			ground("과업심의 예외", "제외 사업", "단순 H/W 도입은 과업심의 대상에서 제외됩니다.")
		));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).containsExactly(answer);
		assertThat(result.evidenceLinks())
			.singleElement()
			.satisfies(link -> assertThat(link.relation()).isEqualTo("CONFLICTED"));
	}

	@Test
	void treatsProhibitionApplicationAsAnAllowedActionInsteadOfAProhibition() {
		String answer = "공익신고자는 불이익조치 금지 신청을 할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"보호조치 신청",
			"공익신고자는 불이익조치를 하지 못하게 하는 신청을 할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	@Test
	void treatsPostpositionedProhibitionApplicationAsAnAllowedAction() {
		String answer = "공익신고자는 불이익조치 금지를 신청할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"보호조치 신청",
			"공익신고자는 불이익조치를 하지 못하게 하는 신청을 할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	@Test
	void treatsPostpositionedProhibitionRequestAsAnAllowedAction() {
		String answer = "공익신고자는 불이익조치 금지를 요청할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"보호조치 요청",
			"공익신고자는 불이익조치를 하지 못하게 요청할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	@Test
	void treatsProhibitionMeasureAsAnAllowedActionObject() {
		String answer = "공익신고자는 불이익조치 금지 조치를 신청할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"보호조치 신청",
			"공익신고자는 불이익조치를 하지 못하게 하는 조치를 신청할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.contradictedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	@Test
	void explicitProhibitionPredicateRemainsFailClosedWhenCompoundRolesDiverge() {
		String answer = "공익신고자는 불이익조치 금지 신청을 할 수 있지만 신청 남용은 금지됩니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"보호조치 신청",
			"공익신고자는 불이익조치를 하지 못하게 하는 신청을 할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(
			"공익신고자는 불이익조치 금지 신청을 할 수 있지만",
			"신청 남용은 금지됩니다."
		);
		assertThat(result.verifiedAnswer()).doesNotContain("신청 남용은 금지됩니다");
	}

	@Test
	void prohibitionMeasurePredicateIsNotRemovedAsAnActionObject() {
		String answer = "보호조치 신청 남용은 금지 조치됩니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"보호조치 신청",
			"보호조치 신청 남용은 허용됩니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.contradictedClaims()).containsExactly(answer);
	}

	@Test
	void readingAProhibitionApplicationFormDoesNotSupportTheRightToApply() {
		String answer = "공익신고자는 불이익조치 금지 신청을 할 수 있습니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"공익신고자 보호 안내",
			"신청서 열람",
			"공익신고자는 불이익조치 금지 신청서를 열람할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
	}

	@Test
	void doesNotSupportNoticeDutyWithUnrelatedPermissionEvidence() {
		String answer = "개인정보 동의 거부에 따른 불이익을 알려야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"개인정보 제공 안내",
			"동의 없는 제공",
			"개인정보 동의 거부에 따른 불이익을 확인할 수 있습니다."
		)));

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.unsupportedClaims()).containsExactly(answer);
		assertThat(result.evidenceLinks()).isEmpty();
	}

	@Test
	void supportsNoticeDutyWithMatchingNoticeEvidence() {
		String answer = "개인정보처리자는 동의를 받을 때 거부권과 불이익 여부를 알려야 합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"개인정보 동의 안내",
			"동의를 받을 때 알릴 사항",
			"개인정보처리자는 동의를 받을 때 동의 거부권과 거부에 따른 불이익 여부를 알려야 합니다."
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).isEqualTo(answer);
	}

	@Test
	void supportsAtomicContractCompletionProcedureParaphrasesFromDirectArticles() {
		String inspectionArticle = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";
		String paymentArticle = "\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uB54C\uC5D0\uB294 \uB300\uAC00\uC9C0\uAE09\uCCAD\uAD6C\uC11C\uB97C \uC81C\uCD9C\uD558\uB294 \uB4F1 \uC18C\uC815\uC758 \uC808\uCC28\uC5D0 \uB530\uB77C \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.";
		LawAiAnswerGround inspectionGround = ground(
			"(\uACC4\uC57D\uC608\uADDC) \uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
			"\uC81C20\uC870(\uAC80\uC0AC)",
			inspectionArticle
		);
		LawAiAnswerGround paymentGround = ground(
			"(\uACC4\uC57D\uC608\uADDC) \uC6A9\uC5ED\uACC4\uC57D\uC77C\uBC18\uC870\uAC74",
			"\uC81C27\uC870(\uB300\uAC00\uC758 \uC9C0\uAE09)",
			paymentArticle
		);
		ClaimEvidenceMatcher matcher = new ClaimEvidenceMatcher();
		assertThat(new ClaimEvidenceAtomizer().atomize(inspectionArticle))
			.as("inspection article atoms")
			.containsExactly(inspectionArticle);
		assertThat(matcher.match(inspectionArticle, List.of(inspectionGround)).status())
			.as("exact inspection article")
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uBA74 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.",
			List.of(inspectionGround)
		).status())
			.as("completion condition paraphrase")
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(
			"\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD569\uB2C8\uB2E4.",
			List.of(inspectionGround)
		).status())
			.as("polite terminal paraphrase")
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(paymentArticle, List.of(paymentGround)).status())
			.as("exact payment article")
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(
			"\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uD6C4\uC5D0\uB294 \uB300\uAC00\uC9C0\uAE09\uCCAD\uAD6C\uC11C\uB97C \uC81C\uCD9C\uD558\uB294 \uB4F1 \uC18C\uC815\uC758 \uC808\uCC28\uC5D0 \uB530\uB77C \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.",
			List.of(paymentGround)
		).status())
			.as("payment timing paraphrase only")
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		String answer = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uBA74 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD569\uB2C8\uB2E4. "
			+ "\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uB54C\uC5D0\uB294 \uB300\uAC00\uC9C0\uAE09\uCCAD\uAD6C\uC11C\uB97C \uC81C\uCD9C\uD558\uB294 \uB4F1 \uC18C\uC815\uC758 \uC808\uCC28\uC5D0 \uB530\uB77C \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			answer,
			List.of(inspectionGround, paymentGround)
		);

		assertThat(result.insufficientEvidence()).as("result=%s", result).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.supportedStrongClaimCount()).isEqualTo(2);
	}

	private LawAiAnswerGround ground(String title, String chunkTitle, String snippet) {
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
			chunkTitle,
			1,
			snippet,
			null,
			null,
			0.9
		);
	}
}
