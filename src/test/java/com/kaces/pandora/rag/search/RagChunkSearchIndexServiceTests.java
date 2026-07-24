package com.kaces.pandora.rag.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class RagChunkSearchIndexServiceTests {

	@Test
	void marksEvenZeroTermChunksCompleteSoBackfillCanAdvance() {
		RagDocumentMapper mapper = mock(RagDocumentMapper.class);
		RagChunkSearchTermExtractor extractor = mock(RagChunkSearchTermExtractor.class);
		LawSemanticChunkRow chunk = chunk(10L, 20L, "hash-10");
		when(mapper.findChunkSearchTermBackfillCandidates(100)).thenReturn(List.of(chunk));
		when(extractor.extract(chunk)).thenReturn(List.of());
		when(mapper.countMissingChunkSearchTerms()).thenReturn(0);
		RagChunkSearchIndexService service = new RagChunkSearchIndexService(mapper, extractor);

		RagChunkSearchIndexService.BackfillResult result = service.backfill(100);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<RagChunkSearchIndexStateRow>> states = ArgumentCaptor.forClass(List.class);
		verify(mapper).upsertChunkSearchIndexStates(states.capture());
		assertThat(states.getValue())
			.containsExactly(new RagChunkSearchIndexStateRow(10L, 20L, "hash-10", 0));
		verify(mapper).markChunkSearchIndexReady();
		assertThat(result.remainingChunks()).isZero();
	}

	@Test
	void reportsNotReadyWhenSchemaIsNotYetAvailable() {
		RagDocumentMapper mapper = mock(RagDocumentMapper.class);
		doThrow(new IllegalStateException("missing table")).when(mapper).findChunkSearchIndexStatus();
		RagChunkSearchIndexService service = new RagChunkSearchIndexService(
			mapper,
			mock(RagChunkSearchTermExtractor.class)
		);

		assertThat(service.isReady()).isFalse();
	}

	private static LawSemanticChunkRow chunk(long chunkId, long documentId, String contentHash) {
		return new LawSemanticChunkRow(
			chunkId, documentId, "official_doc", String.valueOf(documentId), "title",
			null, null, null, null, null, "chunk title", "body", 1,
			null, null, 1, contentHash, "parent", "body", "PASS"
		);
	}
}
