package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimVerifierTests {

	private final ClaimVerifier verifier = new ClaimVerifier();

	@Test
	void keepsStrongClaimWhenEvidenceTermsOverlap() {
		String answer = "공공소프트웨어사업이면 과업심의 대상입니다. 단순 H/W 도입은 비대상으로 확인해야 합니다.";

		String verified = verifier.verify(answer, List.of(ground(
			"공공소프트웨어사업 과업심의의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이며 단순 H/W 도입 설치는 비대상"
		)));

		assertThat(verified).contains("과업심의 대상입니다");
		assertThat(verified).contains("단순 H/W 도입");
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
		assertThat(verified).contains("추가 확인이 필요합니다");
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
		String answer = "제출 기한은 30일입니다. 대상 사업은 공공소프트웨어사업입니다.";

		String verified = verifier.verify(answer, List.of(ground(
			"공공소프트웨어사업 과업심의 가이드",
			"적용 대상 사업",
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상입니다."
		)));

		assertThat(verified).doesNotContain("30일");
		assertThat(verified).contains("공공소프트웨어사업");
	}

	@Test
	void keepsCautiousFollowUpSentenceWithoutDowngradingSupportedAnswer() {
		String answer = "데이터 전처리 절차는 오류 원인 분석, 대상 선정, 방법 결정 순서로 진행됩니다. 일정·비용·제출 양식 등 구체 내용은 문서에 불충분하니 별도 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"2022년 공공데이터 활용기업 맞춤형지원 활용사례",
			"데이터 전처리 절차",
			"데이터 전처리 절차 오류 원인 분석 > 대상 선정 > 방법 결정. 데이터 전처리 방법 삭제, 대체, 예측값 삽입 등"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).contains("오류 원인 분석");
		assertThat(result.verifiedAnswer()).contains("별도 확인이 필요합니다");
	}

	@Test
	void keepsSupportedNumericClaimsWhenEvidenceContainsNumbers() {
		String answer = "관광두레 주민사업체에는 최대 5년간 1억 1천만 원 상당 맞춤형 지원이 제공됩니다. 세부 조건은 공고별로 달라질 수 있으니 확인이 필요합니다.";

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(answer, List.of(ground(
			"관광두레 주민사업체 공모",
			"지원",
			"관광두레 주민사업체에 최대 5년간 1억 1천만 원 상당 맞춤형 지원 제공"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).contains("최대 5년간");
		assertThat(result.verifiedAnswer()).contains("1억 1천만 원");
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
			"평가기간 : 2025. 12. 17 ~ 2026. 10. 31. IRM에 기관의 정보자원 현황을 기한 내 등록하였는지 확인"
		)));

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.unsupportedClaims()).isEmpty();
		assertThat(result.verifiedAnswer()).contains("2025년 12월 17일");
		assertThat(result.verifiedAnswer()).contains("2026년 10월 31일");
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
