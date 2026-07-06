package com.kaces.pandora.rag.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HwpxTextCleanerTests {

	@Test
	// 메소드 설명: removesInlineBookmarkFieldCode 처리 흐름을 수행합니다.
	void removesInlineBookmarkFieldCode() {
		String cleaned = HwpxTextCleaner.clean(
			"20?13. Requirements detail;0;0;0;HWPHYPERLINK_TYPE_HWPHWPHYPERLINK_TARGET_BOOKMARKHWPHYPERLINK_JUMP_CURRENTTABBookmark target"
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).isEqualTo("13. Requirements detail");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).doesNotContain("HWPHYPERLINK");
	}

	@Test
	// 메소드 설명: removesMultilineBookmarkFieldCode 처리 흐름을 수행합니다.
	void removesMultilineBookmarkFieldCode() {
		String cleaned = HwpxTextCleaner.clean("""
			0
			?1. Review committee;0;0;0;
			HWPHYPERLINK_TYPE_HWP
			HWPHYPERLINK_TARGET_BOOKMARK
			HWPHYPERLINK_JUMP_CURRENTTAB
			1. Review committee
			""");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).isEqualTo("1. Review committee");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).doesNotContain("HWPHYPERLINK");
	}

	@Test
	// 메소드 설명: keepsReadableUrlInsideSentence 처리 흐름을 수행합니다.
	void keepsReadableUrlInsideSentence() {
		String cleaned = HwpxTextCleaner.clean(
			"See site (0https\\://example.test/path;1;0;0;https://example.test/pathHWPHYPERLINK_TYPE_URLHWPHYPERLINK_TARGET_BOOKMARKHWPHYPERLINK_JUMP_CURRENTTABexample.test) for details"
		);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).isEqualTo("See site (https://example.test/path) for details");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).doesNotContain("HWPHYPERLINK");
	}

	@Test
	// 메소드 설명: removesStandaloneLinkControlPrefix 처리 흐름을 수행합니다.
	void removesStandaloneLinkControlPrefix() {
		String cleaned = HwpxTextCleaner.clean("Reference 0http\\://example.test/file");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).isEqualTo("Reference http://example.test/file");
	}

	@Test
	// 메소드 설명: removesStandaloneMailLinkControlPrefix 처리 흐름을 수행합니다.
	void removesStandaloneMailLinkControlPrefix() {
		String cleaned = HwpxTextCleaner.clean("Contact 0mailto:help@example.test");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).isEqualTo("Contact help@example.test");
	}

	@Test
	// 메소드 설명: keepsPlainMailLinkWhenCleaningOtherHwpxArtifact 처리 흐름을 수행합니다.
	void keepsPlainMailLinkWhenCleaningOtherHwpxArtifact() {
		String cleaned = HwpxTextCleaner.clean("""
			Contact mailto:help@example.test
			20?13. Requirements detail;0;0;0;HWPHYPERLINK_TYPE_HWPHWPHYPERLINK_TARGET_BOOKMARKHWPHYPERLINK_JUMP_CURRENTTABBookmark target
			""");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).contains("Contact mailto:help@example.test");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).contains("13. Requirements detail");
	}

	@Test
	// 메소드 설명: leavesNormalTextUnchanged 처리 흐름을 수행합니다.
	void leavesNormalTextUnchanged() {
		String value = """
			Article 1

			Normal mailto:help@example.test
			https://example.test/plain
			""";

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(HwpxTextCleaner.clean(value)).isEqualTo(value.trim());
	}

	@Test
	// 메소드 설명: removesPdfExtractionControlGlyphs 처리 흐름을 수행합니다.
	void removesPdfExtractionControlGlyphs() {
		String cleaned = HwpxTextCleaner.clean("적용 대상 사업\n-\u0007소프트웨어의 운영·유지관리");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(cleaned).isEqualTo("적용 대상 사업\n-소프트웨어의 운영·유지관리");
	}
}
