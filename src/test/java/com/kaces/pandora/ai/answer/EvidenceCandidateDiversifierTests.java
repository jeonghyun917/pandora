package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class EvidenceCandidateDiversifierTests {

	private final EvidenceCandidateDiversifier diversifier = new EvidenceCandidateDiversifier();

	@Test
	void preservesRankingWhileRemovingExactAndNearDuplicateCandidates() {
		LawSemanticChunkRow first = chunk(1, 10, "첫 문서", "적용 대상", "국가기관이 추진하는 정보화사업은 적용 대상입니다.");
		LawSemanticChunkRow exactDuplicate = chunk(2, 10, "첫 문서", "적용 대상", "서로 다른 추출 텍스트");
		LawSemanticChunkRow textDuplicate = chunk(3, 11, "복제 문서", "다른 제목", "국가기관이 추진하는 정보화사업은 적용 대상입니다.");
		LawSemanticChunkRow second = chunk(4, 12, "둘째 문서", "제외 대상", "단순 하드웨어 도입은 제외 대상입니다.");

		List<LawSemanticChunkRow> result = diversifier.diversify(
			List.of(first, exactDuplicate, textDuplicate, second),
			3
		);

		assertThat(result).containsExactly(first, second);
	}

	@Test
	void handlesEmptyInputAndLimit() {
		LawSemanticChunkRow first = chunk(1, 10, "첫 문서", "대상", "첫 번째 근거 문장입니다.");
		LawSemanticChunkRow second = chunk(2, 11, "둘째 문서", "대상", "두 번째 근거 문장입니다.");

		assertThat(diversifier.diversify(null, 3)).isEmpty();
		assertThat(diversifier.diversify(List.of(first, second), 1)).containsExactly(first);
	}

	@Test
	void doesNotReserveExactKeyForCandidateRejectedByTextDeduplication() {
		LawSemanticChunkRow selected = chunk(1, 10, "기준 문서", "대상", "같은 본문입니다.");
		LawSemanticChunkRow rejectedByText = chunk(2, 11, "후보 문서", "대상", "같은 본문입니다.");
		LawSemanticChunkRow usefulSameLocation = chunk(3, 11, "후보 문서", "대상", "서로 다른 유용한 본문입니다.");

		assertThat(diversifier.diversify(List.of(selected, rejectedByText, usefulSameLocation), 3))
			.containsExactly(selected, usefulSameLocation);
	}

	private LawSemanticChunkRow chunk(long chunkId, long documentId, String title, String chunkTitle, String text) {
		return new LawSemanticChunkRow(
			chunkId,
			documentId,
			"official_doc",
			String.valueOf(documentId),
			title,
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page 1",
			chunkTitle,
			text,
			1,
			null,
			null,
			1,
			"hash-" + chunkId,
			chunkTitle,
			"target_scope"
		);
	}
}
