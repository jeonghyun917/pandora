package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class SemanticLexicalIndexServiceTests {

	@Test
	void buildsSideBySideAndPublishesReadyOnlyAfterAllRowsAndStats() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findActiveSearchableChunks()).thenReturn(List.of(
			document("law", 11L, "국가계약법", "제55조", "검사 완료 통지"),
			document("official_doc", 11L, "계약 업무 안내서", "검사", "완료 기한")
		));
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(
			mapper,
			new KoreanLexicalTokenizer(),
			() -> "lexical-build-a"
		);

		SemanticLexicalIndexService.BuildResult result = service.rebuild();

		assertThat(result.indexVersion()).isEqualTo("lexical-build-a");
		assertThat(result.activeChunkCount()).isEqualTo(2);
		assertThat(result.contentFingerprint()).matches("[0-9a-f]{64}");
		InOrder ordered = inOrder(mapper);
		ordered.verify(mapper).insertIndexState(any(SemanticLexicalMapper.IndexStateRow.class));
		ordered.verify(mapper).insertChunks(eq("lexical-build-a"), any());
		ordered.verify(mapper).insertTerms(eq("lexical-build-a"), any());
		ordered.verify(mapper).populateTermStats("lexical-build-a");
		ordered.verify(mapper).markIndexReady(
			"lexical-build-a",
			result.contentFingerprint(),
			2,
			result.averageWeightedLength()
		);
	}

	@Test
	void batchesTermWritesAtFiveHundredRows() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		List<LexicalChunkDocument> documents = new ArrayList<>();
		for (int index = 1; index <= 501; index++) {
			documents.add(document("law", index, "법령" + index, "조문", "본문"));
		}
		when(mapper.findActiveSearchableChunks()).thenReturn(documents);
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(
			mapper,
			new KoreanLexicalTokenizer(),
			() -> "lexical-build-b"
		);

		service.rebuild();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<SemanticLexicalMapper.TermRow>> batches = ArgumentCaptor.forClass(List.class);
		verify(mapper, org.mockito.Mockito.atLeast(2)).insertTerms(eq("lexical-build-b"), batches.capture());
		assertThat(batches.getAllValues())
			.allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(500));
	}

	@Test
	void failedBuildNeverPublishesOverThePreviousReadyRevision() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findReadyRevision()).thenReturn("ready-before");
		when(mapper.findActiveSearchableChunks()).thenReturn(List.of(
			document("law", 1L, "국가계약법", "제55조", "검사")
		));
		doThrow(new IllegalStateException("simulated insert failure"))
			.when(mapper).insertTerms(eq("lexical-build-failed"), any());
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(
			mapper,
			new KoreanLexicalTokenizer(),
			() -> "lexical-build-failed"
		);

		assertThat(service.currentRevision()).isEqualTo("ready-before");
		assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class);
		verify(mapper, never()).markIndexReady(any(), any(), any(Integer.class), any(Double.class));
	}

	@Test
	void reportsNoRevisionWhenCommonSchemaIsUnavailable() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findReadyRevision()).thenThrow(new IllegalStateException("missing table"));

		assertThat(new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer()).currentRevision())
			.isNull();
	}

	@Test
	void usesTheConfiguredFieldWeightsWhenBuildingWeightedLength() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findActiveSearchableChunks()).thenReturn(List.of(
			new LexicalChunkDocument(
				"law", 1L, 2L, "parent", "a".repeat(64),
				"문서", "부모", "조문", "본문"
			)
		));
		LawAiLexicalProperties weights = new LawAiLexicalProperties(
			1.2, 0.75, 10, 9, 8, 2, 24, 100
		);
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(
			mapper, new KoreanLexicalTokenizer(), () -> "weighted-build", weights
		);

		service.rebuild();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<SemanticLexicalMapper.ChunkRow>> chunks = ArgumentCaptor.forClass(List.class);
		verify(mapper).insertChunks(eq("weighted-build"), chunks.capture());
		assertThat(chunks.getValue()).singleElement()
			.extracting(SemanticLexicalMapper.ChunkRow::weightedLength)
			.isEqualTo(29);
	}

	private static LexicalChunkDocument document(
		String target,
		long chunkId,
		String title,
		String parentTitle,
		String body
	) {
		return new LexicalChunkDocument(
			target,
			chunkId,
			1000L + chunkId,
			target + ":parent:" + chunkId,
			String.format("%064x", chunkId),
			title,
			parentTitle,
			"조문 " + chunkId,
			body
		);
	}
}
