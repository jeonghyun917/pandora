package com.kaces.pandora.rag.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import org.junit.jupiter.api.Test;

class RagChunkSearchTermExtractorTests {

	private final RagChunkSearchTermExtractor extractor = new RagChunkSearchTermExtractor();

	@Test
	void extractsNormalizedTermsWithFieldWeights() {
		LawSemanticChunkRow chunk = chunk(
			89612L,
			42L,
			"2025 개인정보 분쟁조정 사례집",
			"개인정보 해당 여부",
			"이메일 주소",
			"신청인의 이메일 주소는 수집된 개인정보에 해당합니다."
		);

		assertThat(extractor.extract(chunk))
			.anySatisfy(term -> {
				assertThat(term.term()).isEqualTo("개인정보");
				assertThat(term.weight()).isGreaterThanOrEqualTo(4);
			})
			.anySatisfy(term -> assertThat(term.term()).isEqualTo("이메일"))
			.noneSatisfy(term -> assertThat(term.term()).isEqualTo("해당합니다"));
	}

	@Test
	void excludesWeakQuestionTerms() {
		LawSemanticChunkRow chunk = chunk(
			1L,
			2L,
			"",
			"",
			"",
			"개인정보라고 볼 수 있는지 여부를 확인"
		);

		assertThat(extractor.extract(chunk))
			.extracting(RagChunkSearchTermRow::term)
			.contains("개인정보")
			.doesNotContain("여부", "확인");
	}

	@Test
	void doesNotCopyDocumentTitleTermsIntoEveryChunk() {
		LawSemanticChunkRow chunk = chunk(
			5L,
			6L,
			"개인정보 처리 통합 안내서",
			"",
			"",
			"이메일 주소는 개인을 식별하는 정보와 결합될 수 있습니다."
		);

		assertThat(extractor.extract(chunk))
			.extracting(RagChunkSearchTermRow::term)
			.contains("이메일")
			.doesNotContain("안내서", "통합");
	}

	@Test
	void doesNotDropTermsAfterTheFormerOneHundredSixtyTermBoundary() {
		StringBuilder text = new StringBuilder();
		for (int index = 0; index < 220; index++) {
			text.append("term").append(index).append(' ');
		}

		assertThat(extractor.extract(chunk(3L, 4L, "", "", "", text.toString())))
			.extracting(RagChunkSearchTermRow::term)
			.contains("term219")
			.hasSizeGreaterThan(160);
	}

	private static LawSemanticChunkRow chunk(
		long chunkId,
		long documentId,
		String title,
		String parentTitle,
		String chunkTitle,
		String chunkText
	) {
		return new LawSemanticChunkRow(
			chunkId, documentId, "official_doc", String.valueOf(documentId), title,
			null, null, null, null, null, chunkTitle, chunkText, 1,
			null, null, 1, null, parentTitle, "body", "PASS"
		);
	}
}
