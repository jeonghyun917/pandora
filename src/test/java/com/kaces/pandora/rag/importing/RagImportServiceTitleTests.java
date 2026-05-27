package com.kaces.pandora.rag.importing;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.rag.document.RagDocumentMeta;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagImportServiceTitleTests {

	@Test
	// 메소드 설명: resolvesTitleThenDateFromFirstPage 처리 흐름을 수행합니다.
	void resolvesTitleThenDateFromFirstPage() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(1, """
			공공소프트웨어사업			과업심의 가이드			2022. 12.
			""")));

		String title = RagImportService.resolveTitle(emptyMeta(), document, "fallback.pdf");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(title).isEqualTo("공공소프트웨어사업 과업심의 가이드 2022. 12.");
	}

	@Test
	// 메소드 설명: resolvesDateThenTitleFromFirstPage 처리 흐름을 수행합니다.
	void resolvesDateThenTitleFromFirstPage() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(1, """
			2024. 12.
			공공SW사업 법제도			관리감독 및 지원 가이드
			""")));

		String title = RagImportService.resolveTitle(emptyMeta(), document, "fallback.pdf");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(title).isEqualTo("공공SW사업 법제도 관리감독 및 지원 가이드(2024. 12.)");
	}

	@Test
	// 메소드 설명: keepsMetadataTitleWhenProvided 처리 흐름을 수행합니다.
	void keepsMetadataTitleWhenProvided() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(1, "본문 내용")));
		RagDocumentMeta meta = new RagDocumentMeta(null, "메타 제목", null, null, null, null, null, null, null);

		String title = RagImportService.resolveTitle(meta, document, "fallback.pdf");

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(title).isEqualTo("메타 제목");
	}

	// 메소드 설명: emptyMeta 처리 흐름을 수행합니다.
	// 메소드 설명: emptyMeta 처리 흐름을 수행합니다.
	private RagDocumentMeta emptyMeta() {
		return new RagDocumentMeta(null, null, null, null, null, null, null, null, null);
	}
}
