package com.kaces.pandora.rag.document;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RagDocumentControllerTests {

	@Test
	void encodesKoreanPreviewFilenameAsRfc5987ContentDisposition() {
		String header = RagDocumentController.inlineDisposition("개인정보 처리 가이드.pdf");

		assertThat(header)
			.startsWith("inline;")
			.contains("filename*=");
	}
}
