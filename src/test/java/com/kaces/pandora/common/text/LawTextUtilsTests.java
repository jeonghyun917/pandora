package com.kaces.pandora.common.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LawTextUtilsTests {

	@Test
	// 메소드 설명: formatDateNormalizesEightDigitDates 처리 흐름을 수행합니다.
	void formatDateNormalizesEightDigitDates() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(LawTextUtils.formatDate("20260520")).isEqualTo("2026. 5. 20.");
	}

	@Test
	// 메소드 설명: formatDateKeepsNonStandardValues 처리 흐름을 수행합니다.
	void formatDateKeepsNonStandardValues() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(LawTextUtils.formatDate("2026-05")).isEqualTo("2026-05");
	}

	@Test
	// 메소드 설명: emptyToNullReturnsNullForBlankText 처리 흐름을 수행합니다.
	void emptyToNullReturnsNullForBlankText() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(LawTextUtils.emptyToNull("  ")).isNull();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(LawTextUtils.emptyToNull("value")).isEqualTo("value");
	}

	@Test
	void stripHtmlTagsRemovesInlineImageTags() {
		String text = "1. 고시 명: 제주큰굿 지정<img id=\"111427631\"></img> 2. 지정 이유";

		assertThat(LawTextUtils.stripHtmlTags(text))
			.isEqualTo("1. 고시 명: 제주큰굿 지정 2. 지정 이유");
	}

	@Test
	void stripHtmlTagsKeepsLineBreaksFromBlockTags() {
		String text = "<p>첫 문단&nbsp;내용</p><div>둘째 &amp; 내용</div>";

		assertThat(LawTextUtils.stripHtmlTags(text))
			.isEqualTo("첫 문단 내용\n둘째 & 내용");
	}
}
