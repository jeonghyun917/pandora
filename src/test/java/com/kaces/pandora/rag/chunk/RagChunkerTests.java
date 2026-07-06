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
	void dropsIsolatedShortSemanticFragments() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(2, """
			⑬

			대상사업

			평가방법
			""")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV4(1, document, null, "정보화사업 사전협의 안내서");

		assertThat(chunks).isEmpty();
	}

	@Test
	void dropsRepeatedPublicationFooterFragments() {
		ExtractedDocument document = new ExtractedDocument(List.of(
			new ExtractedPage(10, "RESTORING PUBLIC FINANCES © OECD 2026"),
			new ExtractedPage(11, " 173 RESTORING PUBLIC FINANCES © OECD 2026"),
			new ExtractedPage(12, "다.")
		));

		List<RagDocumentChunkRow> chunks = chunker.chunkV4(1, document, null, "Restoring Public Finances");

		assertThat(chunks).isEmpty();
	}

	@Test
	void dropsStandaloneTableUnitPageMarkers() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(44, "- 44 - - 자연증가 - (단위: 명)")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV4(1, document, null, "인구동향조사 보도자료");

		assertThat(chunks).isEmpty();
	}

	@Test
	void mergesShortSemanticHeadingWithFollowingBody() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(2, """
			평가방법

			평가위원은 평가항목과 배점에 따라 제안서를 검토하고 평가 결과를 통보한다.
			""")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV4(1, document, null, "제안요청서 작성 가이드");

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).chunkText()).startsWith("평가방법\n평가위원은");
		assertThat(chunks.get(0).sectionType()).isEqualTo("requirement");
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

	@Test
	void v2ChunksCarrySectionMetadataForEmbedding() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(5, """
			적용 대상 사업
			+ 국가기관 등이 발주하는 모든 SW사업
			- 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지관리 등과 관련된 서비스
			※ 단순 H/W(Appliance 포함) 도입·설치는 비대상
			""")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV2(1, document, null, "공공소프트웨어사업 과업심의 가이드");

		assertThat(chunks).hasSize(1);
		RagDocumentChunkRow chunk = chunks.get(0);
		assertThat(chunk.chunkVersion()).isEqualTo(2);
		assertThat(chunk.sectionType()).isEqualTo("target_scope");
		assertThat(chunk.parentSectionTitle()).contains("적용 대상 사업");
		assertThat(chunk.embeddingText()).contains("문서: 공공소프트웨어사업 과업심의 가이드");
		assertThat(chunk.embeddingText()).contains("섹션유형: target_scope");
		assertThat(chunk.embeddingText()).contains("적용 대상 사업");
		assertThat(chunk.sourcePath()).contains("$.v2.pages[5]");
	}

	@Test
	void v2UsesSemanticSectionTitleWhenPageHeaderIsMisleading() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(5, """
			과업내용 변경의 확정을 위한 과업심의위원회

			적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함)
			- 소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등과 그 밖에 소프트웨어와 관련된 서비스를 제공하는 산업과 관련된 경제활동
			※ 단순 H/W(Appliance 포함) 도입·설치, 단순 동영상 제작, 네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상
			""")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV2(1, document, null, "공공소프트웨어사업 과업심의 가이드");

		assertThat(chunks).hasSizeGreaterThanOrEqualTo(2);
		RagDocumentChunkRow chunk = chunks.stream()
			.filter(item -> "target_scope".equals(item.sectionType()))
			.findFirst()
			.orElseThrow();
		assertThat(chunk.sectionType()).isEqualTo("target_scope");
		assertThat(chunk.chunkTitle()).isEqualTo("p.5 적용 대상 사업");
		assertThat(chunk.parentSectionTitle()).isEqualTo("적용 대상 사업");
		assertThat(chunk.embeddingText()).contains("섹션: 적용 대상 사업");
	}

	@Test
	void v3MergesDanglingTargetScopeLeadWithContinuation() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(2, """
			ㅇ 사전협의 대상사업은
			예산과목 및 계약방식과 관계없이
			*
			대상기관이 추진하는 모든 정보화사업
			에 해당
			* 디지털서비스 전문계약제도 이용 계약, 공모, R&D 등 예산과목 및 계약방식에 관계 없음
			""")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV3(1, document, null, "정보화사업 사전협의 안내서");

		RagDocumentChunkRow chunk = chunks.stream()
			.filter(item -> item.chunkText().contains("사전협의 대상사업은"))
			.findFirst()
			.orElseThrow();
		assertThat(chunk.chunkVersion()).isEqualTo(3);
		assertThat(chunk.sectionType()).isEqualTo("target_scope");
		assertThat(chunk.chunkText()).contains("사전협의 대상사업은");
		assertThat(chunk.chunkText()).contains("대상기관이 추진하는 모든 정보화사업");
		assertThat(chunk.embeddingText()).contains("SECTION_TYPE: target_scope");
	}

	@Test
	void v4UsesIsolatedVersionPathAndStructuredEmbeddingText() {
		ExtractedDocument document = new ExtractedDocument(List.of(new ExtractedPage(5, """
			적용 대상 사업
			+ 국가기관 등이 발주하는 모든 SW사업
			※ 단순 H/W(Appliance 포함) 도입·설치는 비대상
			""")));

		List<RagDocumentChunkRow> chunks = chunker.chunkV4(1, document, null, "공공소프트웨어사업 과업심의 가이드");

		RagDocumentChunkRow chunk = chunks.get(0);
		assertThat(chunk.chunkVersion()).isEqualTo(4);
		assertThat(chunk.sourcePath()).contains("$.v4.pages[5]");
		assertThat(chunk.sectionType()).isEqualTo("target_scope");
		assertThat(chunk.embeddingText()).contains("DOCUMENT_TITLE: 공공소프트웨어사업 과업심의 가이드");
		assertThat(chunk.embeddingText()).contains("SECTION_TYPE: target_scope");
	}
}
