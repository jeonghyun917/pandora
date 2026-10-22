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
	void backfillRejectsManagedDocumentBeforeDeletingTermsOrChangingReadiness() {
		var mapper = mock(RagDocumentMapper.class);
		var guard = mock(com.kaces.pandora.rag.recovery.RagRecoveryWriteGuard.class);
		when(mapper.findChunkSearchTermBackfillCandidates(100)).thenReturn(List.of(chunk(7,1765,"hash")));
		doThrow(new IllegalStateException("managed")).when(guard).assertMutableDocument(1765L);
		var service = new RagChunkSearchIndexService(mapper, mock(RagChunkSearchTermExtractor.class));
		service.configureRecoveryWriteGuard(guard);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.backfill(100)).hasMessage("managed");
		verify(mapper).findChunkSearchTermBackfillCandidates(100);
		org.mockito.Mockito.verifyNoMoreInteractions(mapper);
	}
	@Test
	void managedDocumentIsRejectedBeforeAnyKeywordMutation() {
		var mapper = mock(RagDocumentMapper.class);
		var guard = mock(com.kaces.pandora.rag.recovery.RagRecoveryWriteGuard.class);
		doThrow(new IllegalStateException("managed")).when(guard).assertMutableDocument(1765L);
		var service = new RagChunkSearchIndexService(mapper, mock(RagChunkSearchTermExtractor.class));
		service.configureRecoveryWriteGuard(guard);
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.rebuildDocument(1765L, 1))
			.isInstanceOf(IllegalStateException.class).hasMessage("managed");
		org.mockito.Mockito.verifyNoInteractions(mapper);
	}

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
