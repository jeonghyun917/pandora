package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RagHeadingDetectorTests {
	private final RagHeadingDetector detector = new RagHeadingDetector();

	@Test
	void acceptsGeneralStructuralHeadings() {
		assertThat(detector.isHeadingLine("Ⅰ. 개요")).isTrue();
		assertThat(detector.isHeadingLine("1. 제안요청서 작성 기준")).isTrue();
		assertThat(detector.isHeadingLine("제3조 대상")).isTrue();
		assertThat(detector.isHeadingLine("[붙임1] 보안성 검토 양식")).isTrue();
		assertThat(detector.isHeadingLine("□ 절차 개요")).isTrue();
		assertThat(detector.isHeadingLine("※ 유의사항")).isTrue();
	}

	@Test
	void acceptsDomainTermsOnlyAsScoreBonus() {
		assertThat(detector.isHeadingLine("적용 대상 사업")).isTrue();
		assertThat(detector.isHeadingLine("보안성 검토 필수 항목")).isTrue();
		assertThat(detector.isHeadingLine("사전협의 대상기관")).isTrue();
	}

	@Test
	void rejectsDomainSentencesThatAreNotHeadings() {
		assertThat(detector.isHeadingLine("보안성 검토 대상 시스템은 개인정보를 처리하는 시스템입니다.")).isFalse();
		assertThat(detector.isHeadingLine("사전협의 대상 여부는 사업내용, 예산, 기관 유형을 종합적으로 검토하여 판단합니다.")).isFalse();
		assertThat(detector.isHeadingLine("국가기관 등이 발주하는 모든 SW사업")).isFalse();
	}

	@Test
	void findsBestHeadingAfterShortIntroLine() {
		assertThat(detector.bestHeading(List.of(
			"아래 내용은 신청 전에 확인해야 합니다.",
			"보안성 검토 필수 항목",
			"기관 정보와 시스템 구성도를 제출합니다."
		))).contains("보안성 검토 필수 항목");
	}
}
