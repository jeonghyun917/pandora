package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ClaimEvidenceMatcherNumericTests {

	private final ClaimEvidenceMatcher matcher = new ClaimEvidenceMatcher();

	@Test
	void rejectsSameDigitsWhenWonMagnitudesDiffer() {
		ClaimEvidenceMatcher.Match match = match(
			"위반 시 과태료 금액은 100만원입니다.",
			"위반 시 과태료 금액은 100원입니다."
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void supportsEquivalentAmountsExpressedWithDifferentWonUnits() {
		ClaimEvidenceMatcher.Match match = match(
			"위반 시 과태료 금액은 100만원입니다.",
			"위반 시 과태료 금액은 1,000,000원입니다."
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void distinguishesEverySupportedLegalUnitConservatively() {
		assertUnitMismatch("한도는 2억원입니다.", "한도는 2만원입니다.");
		assertUnitMismatch("한도는 2만원입니다.", "한도는 2천원입니다.");
		assertUnitMismatch("한도는 2천원입니다.", "한도는 2원입니다.");
		assertUnitMismatch("비율은 10%입니다.", "비율은 10점입니다.");
		assertUnitMismatch("기간은 3년입니다.", "기간은 3개월입니다.");
		assertUnitMismatch("기간은 3개월입니다.", "기간은 3월입니다.");
		assertUnitMismatch("기간은 3월입니다.", "기간은 3일입니다.");
		assertUnitMismatch("평가는 5점입니다.", "평가는 5개입니다.");
		assertUnitMismatch("수량은 5개입니다.", "수량은 5건입니다.");
		assertUnitMismatch("수량은 5건입니다.", "수량은 5명입니다.");
		assertUnitMismatch("수량은 5명입니다.", "수량은 5회입니다.");
		assertUnitMismatch("수량은 5회입니다.", "수량은 5차입니다.");
		assertUnitMismatch("수량은 5차입니다.", "수량은 5시간입니다.");
	}

	@Test
	void preservesBareNumbersAndArticleNumbers() {
		assertThat(match("문서 식별번호는 20260716입니다.", "문서 식별번호는 20260716입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("제10조에 따라 신청서를 제출해야 합니다.", "제10조에 따라 신청서를 제출해야 합니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void preservesDottedDateEquivalence() {
		ClaimEvidenceMatcher.Match match = match(
			"평가기간은 2025년 12월 17일부터 2026년 10월 31일까지입니다.",
			"평가기간: 2025. 12. 17 ~ 2026. 10. 31."
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void preservesCompositeKoreanMoneyMatching() {
		ClaimEvidenceMatcher.Match match = match(
			"지원금은 1억 1천만 원입니다.",
			"지원금은 1억 1천만 원입니다."
		);

		assertThat(match.status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void distinguishesInclusiveAndExclusiveNumericBounds() {
		assertUnitMismatch("기준은 10억원 이상입니다.", "기준은 10억원 미만입니다.");
		assertUnitMismatch("기준은 10억원 이하입니다.", "기준은 10억원 초과입니다.");
		assertUnitMismatch("기준은 10억원 이상입니다.", "기준은 10억원 초과입니다.");
		assertUnitMismatch("기준은 10억원 이하입니다.", "기준은 10억원 미만입니다.");

		assertThat(match("기준은 10억원 이상입니다.", "기준은 10억원 이상입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("기준은 10억원 이하입니다.", "기준은 10억원 이하입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("기준은 10억원 미만입니다.", "기준은 10억원 미만입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("기준은 10억원 초과입니다.", "기준은 10억원 초과입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void normalizesDigitGroupsWithinTheKoreanManUnit() {
		assertThat(match("지원금은 1천100만원입니다.", "지원금은 11,000,000원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("지원금은 11,000,000원입니다.", "지원금은 1천100만원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void normalizesNaturalDigitGroupBeforeTheKoreanManUnit() {
		assertThat(match("지원금은 2천3백만원입니다.", "지원금은 23,000,000원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("지원금은 2천3백만원입니다.", "지원금은 3,002,000원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void normalizesTrailingBareWonInsideScaledMoneyExpression() {
		assertThat(match("지원금은 1천100원입니다.", "지원금은 1,100원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(match("지원금은 1천100원입니다.", "지원금은 100원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(match("지원금은 5억3000원입니다.", "지원금은 500,003,000원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void recognizesNumericBoundAfterObjectParticle() {
		assertThat(match("기준은 10억원을 초과합니다.", "기준은 10억원입니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(match("기준은 10억원을 초과합니다.", "기준은 10억원을 초과합니다.").status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
	}

	@Test
	void recognizesNaturalLanguageNumericBounds() {
		assertUnitMismatch("지원 기준은 10억원을 넘는 사업이 대상입니다.", "지원 기준은 10억원인 사업이 대상입니다.");
		assertThat(match(
			"지원 기준은 10억원을 넘는 사업이 대상입니다.",
			"지원 기준은 10억원을 넘는 사업이 대상입니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);

		assertUnitMismatch("지원 기준은 10억원을 넘지 않는 사업이 대상입니다.", "지원 기준은 10억원인 사업이 대상입니다.");
		assertUnitMismatch("지원 기준은 10억원보다 큰 사업이 대상입니다.", "지원 기준은 10억원인 사업이 대상입니다.");
		assertUnitMismatch("지원 기준은 10억원보다 작은 사업이 대상입니다.", "지원 기준은 10억원인 사업이 대상입니다.");
	}

	@Test
	void rejectsReversedNumericEntityAssociations() {
		assertThat(match(
			"과태료는 법인 500만원, 개인 300만원입니다.",
			"과태료는 법인 300만원, 개인 500만원입니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void rejectsReversedDateRangeEndpoints() {
		assertThat(match(
			"평가 기간 시작일은 2025년 12월 17일, 종료일은 2026년 10월 31일로 정합니다.",
			"평가 기간 시작일은 2026. 10. 31, 종료일은 2025. 12. 17로 정합니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doesNotAssembleOneDateFromUnrelatedNumbers() {
		assertThat(match(
			"신청일은 2025년 12월 17일입니다.",
			"2025년 사업은 12월부터 진행 기간이 17일입니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void requiresTheSameSemanticRoleForAnAmount() {
		assertThat(match(
			"관광사업 지원금은 1억원입니다.",
			"관광사업 과태료는 1억원입니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void requiresTheSameSemanticRoleForACompleteDate() {
		assertThat(match(
			"신청일은 2025년 12월 17일입니다.",
			"사업기간은 2025. 12. 17입니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void doesNotUseSubstringMatchingForKoreanWordNumbers() {
		assertThat(match(
			"과태료는 백만원입니다.",
			"과태료는 이백만원입니다."
		).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	private void assertUnitMismatch(String claim, String evidence) {
		assertThat(match(claim, evidence).status())
			.as("claim <%s> must not be supported by <%s>", claim, evidence)
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	private ClaimEvidenceMatcher.Match match(String claim, String evidence) {
		return matcher.match(claim, List.of(ground(evidence)));
	}

	private LawAiAnswerGround ground(String snippet) {
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
			"근거",
			1,
			snippet,
			null,
			null,
			0.9
		);
	}
}
