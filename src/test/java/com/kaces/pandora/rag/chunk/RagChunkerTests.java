package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.importing.ExtractedDocument;
import com.kaces.pandora.rag.importing.ExtractedPage;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagChunkerTests {

	// 메소드 설명: RagChunker 처리 흐름을 수행합니다.
	private final RagChunker chunker = new RagChunker();

	@Test
	// 메소드 설명: skipsTableOfContentsPage 처리 흐름을 수행합니다.
	void skipsTableOfContentsPage() {
		ExtractedDocument document = new ExtractedDocument(List.of(
			new ExtractedPage(3, """
				CONTENTS
				I. 개요 ........................................................................ 04
				II. 과업내용 확정 ............................................................ 09
				III. 과업내용 변경 ........................................................... 17
				"""),
			new ExtractedPage(4, """
				Ⅰ. 개요
				공공소프트웨어사업 과업심의 가이드의 목적은 발주 전 과업내용 확정과 적정 사업기간 산정 등의 업무를 지원하는 데 있습니다.
				""")
		));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<RagDocumentChunkRow> chunks = chunker.chunk(1, document, null);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).hasSize(1);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkTitle()).contains("Ⅰ. 개요");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkText()).doesNotContain("CONTENTS");
	}

	@Test
	// 메소드 설명: preservesHeadingAndListLinesInsideChunkText 처리 흐름을 수행합니다.
	void preservesHeadingAndListLinesInsideChunkText() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(5, """
			적용 대상 사업
			+ 국가기관 등이 발주하는 모든 SW사업
			- 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리 등과 관련된 서비스
			※ 단순 H/W(Appliance 포함) 도입·설치는 비대상
			""")));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<RagDocumentChunkRow> chunks = chunker.chunk(1, document, null);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).hasSize(1);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkTitle()).contains("적용 대상 사업");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkText()).contains("+ 국가기관");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkText()).contains("\n- 소프트웨어");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkText()).contains("\n※ 단순");
	}

	@Test
	// 메소드 설명: keepsShortSemanticHeadingWhenSeparatedByBlankLine 처리 흐름을 수행합니다.
	void keepsShortSemanticHeadingWhenSeparatedByBlankLine() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(5, """
			적용 대상 사업

			국가기관 등이 발주하는 모든 SW사업
			- 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리 등과 관련된 서비스
			""")));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<RagDocumentChunkRow> chunks = chunker.chunk(1, document, null);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).hasSize(1);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkTitle()).contains("적용 대상 사업");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkText()).startsWith("적용 대상 사업\n국가기관 등이 발주하는 모든 SW사업");
	}

	@Test
	// 메소드 설명: splitsLongTextIntoSearchableSizedChunks 처리 흐름을 수행합니다.
	void splitsLongTextIntoSearchableSizedChunks() {
		StringBuilder builder = new StringBuilder("1. 제안요청서 작성 기준\n");
		// 메소드 설명: for 처리 흐름을 수행합니다.
		for (int i = 0; i < 120; i++) {
			builder.append("제안요청서에는 과업내용, 요구사항, 계약조건, 평가요소와 평가방법을 명시하여야 합니다. ");
		}
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(38, builder.toString())));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<RagDocumentChunkRow> chunks = chunker.chunk(1, document, null);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).hasSizeGreaterThan(1);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.chunkText().length()).isLessThanOrEqualTo(1700));
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(0).chunkTitle()).contains("제안요청서 작성 기준");
	}

	@Test
	// 메소드 설명: carriesHeadingIntoLaterChunksWhenLongSectionIsSplit 처리 흐름을 수행합니다.
	void carriesHeadingIntoLaterChunksWhenLongSectionIsSplit() {
		StringBuilder builder = new StringBuilder("1. 제안요청서 작성 기준\n");
		// 메소드 설명: for 처리 흐름을 수행합니다.
		for (int i = 0; i < 50; i++) {
			builder.append("제안요청서에는 과업내용과 요구사항을 상세히 명시하여야 하며 ");
			builder.append("계약조건과 평가방법도 함께 확인할 수 있도록 작성하여야 합니다. ");
		}
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(38, builder.toString())));

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<RagDocumentChunkRow> chunks = chunker.chunk(1, document, null);

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).hasSizeGreaterThan(1);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks.get(1).chunkText()).startsWith("1. 제안요청서 작성 기준\n");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.chunkText().length()).isLessThanOrEqualTo(1700));
	}
}
