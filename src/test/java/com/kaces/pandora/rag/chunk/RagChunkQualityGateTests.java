package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagChunkQualityGateTests {

	private final RagChunkQualityGate gate = new RagChunkQualityGate();

	@Test
	void keepsSubstantiveEvidenceSearchable() {
		RagChunkQualityGate.Result result = gate.evaluate(
			"정보화사업 사전협의 안내서",
			List.of(chunk(1, "적용 대상", "대상기관이 추진하는 모든 정보화사업은 사전협의 대상에 해당합니다."))
		);

		assertThat(result.searchableCount()).isEqualTo(1);
		assertThat(result.retainedChunks().get(0).qualityStatus()).isEqualTo("PASS");
		assertThat(result.rejectedChunks()).isEmpty();
	}

	@Test
	void keepsHeadingForParentContextButNotDirectSearch() {
		RagChunkQualityGate.Result result = gate.evaluate(
			"정보화사업 사전협의 안내서",
			List.of(chunk(1, "적용 대상 사업", "적용 대상 사업"))
		);

		assertThat(result.searchableCount()).isZero();
		assertThat(result.contextOnlyCount()).isEqualTo(1);
		assertThat(result.retainedChunks().get(0).qualityReason()).isEqualTo("TITLE_OR_HEADING_ONLY");
	}

	@Test
	void rejectsDuplicateAndDecorativeFragments() {
		RagDocumentChunkRow evidence = chunk(
			1,
			"제출 서류",
			"신청기관은 사업계획서와 자체검토결과서를 제출하여야 합니다. 관련 서류는 시스템에서 확인할 수 있습니다."
		);
		RagChunkQualityGate.Result result = gate.evaluate(
			"정보화사업 사전협의 안내서",
			List.of(
				evidence,
				chunk(2, "제출 서류", evidence.chunkText()),
				chunk(3, "47", "47")
			)
		);

		assertThat(result.passCount()).isEqualTo(1);
		assertThat(result.rejectedCount()).isEqualTo(2);
		assertThat(result.rejectedChunks()).extracting(RagChunkQualityGate.RejectedChunk::reason)
			.containsExactly("DUPLICATE_TEXT", "MEANINGLESS_FRAGMENT");
	}

	@Test
	void marksNavigationInstructionAsContextOnly() {
		RagChunkQualityGate.Result result = gate.evaluate(
			"업무 처리 안내서",
			List.of(chunk(1, "첨부파일", "첨부파일 다운로드 버튼 클릭"))
		);

		assertThat(result.searchableCount()).isZero();
		assertThat(result.contextOnlyCount()).isEqualTo(1);
		assertThat(result.retainedChunks().get(0).qualityReason()).isEqualTo("NAVIGATION_NOTICE");
	}

	@Test
	void keepsAttachmentEvidenceWhenItIsNotANavigationInstruction() {
		RagChunkQualityGate.Result result = gate.evaluate(
			"신청 안내",
			List.of(chunk(1, "제출 서류", "첨부파일: 개인정보 처리방침 및 사업계획서"))
		);

		assertThat(result.searchableCount()).isEqualTo(1);
		assertThat(result.retainedChunks().get(0).qualityStatus()).isEqualTo("REVIEW");
	}

	@Test
	void keepsNavigationInstructionSearchableWhenItNamesTheBusinessSubject() {
		RagChunkQualityGate.Result result = gate.evaluate(
			"IRM 사용자 관리",
			List.of(chunk(1, "사용자 승인", "IRM > 시스템관리 > 사용자관리 > 사용자 승인관리 화면으로 이동"))
		);

		assertThat(result.searchableCount()).isEqualTo(1);
		assertThat(result.retainedChunks().get(0).qualityStatus()).isEqualTo("REVIEW");
		assertThat(result.retainedChunks().get(0).qualityReason()).isEqualTo("NAVIGATION_WITH_SUBJECT");
	}

	private RagDocumentChunkRow chunk(long id, String title, String text) {
		return new RagDocumentChunkRow(
			id,
			10,
			RagChunker.V4_CHUNK_VERSION,
			"page " + id,
			title,
			title,
			"body",
			text,
			"문서: 정보화사업 사전협의 안내서\n섹션: " + title + "\n본문: " + text,
			(int) id,
			"$.v4.pages[" + id + "]",
			null,
			(int) id,
			"hash-" + id
		);
	}
}
