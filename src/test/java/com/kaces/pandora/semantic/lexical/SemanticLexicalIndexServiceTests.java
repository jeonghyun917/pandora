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
	void recoveryFingerprintBindsActualBodyAndFieldWeights() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.markRecoveryIndexReady(any(), any(), any(Integer.class), any(Double.class))).thenReturn(1);
		var service = new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer());
		var first = service.rebuildRecovery("6dca282f-56c2-477e-b1b1-66d35d5e9d56", List.of(document("law", 1, "법", "조", "원문")));
		var changed = service.rebuildRecovery("6dca282f-56c2-477e-b1b1-66d35d5e9d57", List.of(document("law", 1, "법", "조", "변조")));
		assertThat(changed.contentFingerprint()).isNotEqualTo(first.contentFingerprint());
		var weighted = new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer(), () -> "unused",
			new LawAiLexicalProperties(1.2, .75, 9, 6, 7, 1, 24, 100));
		assertThat(weighted.rebuildRecovery("6dca282f-56c2-477e-b1b1-66d35d5e9d58", List.of(document("law", 1, "법", "조", "원문"))).contentFingerprint())
			.isNotEqualTo(first.contentFingerprint());
	}

	@Test
	void recoveryBuildNeverPublishesAsOrdinaryReady() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.markRecoveryIndexReady(any(), any(), any(Integer.class), any(Double.class))).thenReturn(1);
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer());
		String generation = "6dca282f-56c2-477e-b1b1-66d35d5e9d56";
		var result = service.rebuildRecovery(generation, List.of(document("law", 1, "법", "조", "본문")));
		assertThat(result.indexVersion()).isEqualTo("recovery_" + generation);
		assertThat(result.activeChunkCount()).isEqualTo(1);
		verify(mapper, never()).findActiveSearchableChunks();
		verify(mapper, never()).markIndexReady(any(), any(), any(Integer.class), any(Double.class));
		verify(mapper).markRecoveryIndexReady(eq(result.indexVersion()), eq(result.contentFingerprint()), eq(1), eq(result.averageWeightedLength()));
	}

	@Test
	void recoveryRejectsDuplicateIdentityBeforeWriting() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer());
		var row = document("law", 1, "법", "조", "본문");
		assertThatThrownBy(() -> service.rebuildRecovery("6dca282f-56c2-477e-b1b1-66d35d5e9d56", List.of(row, row)))
			.isInstanceOf(IllegalArgumentException.class);
		org.mockito.Mockito.verifyNoInteractions(mapper);
	}

	@Test
	void failedRecoveryPublicationIsNotReportedReady() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer());
		assertThatThrownBy(() -> service.rebuildRecovery("6dca282f-56c2-477e-b1b1-66d35d5e9d56", List.of(document("law", 1, "법", "조", "본문"))))
			.isInstanceOf(IllegalStateException.class);
		verify(mapper).markIndexFailed("recovery_6dca282f-56c2-477e-b1b1-66d35d5e9d56");
	}

	@Test
	void abandonedRecoveryOnlyChangesOneBuildingVersion() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		String version = "6dca282f-56c2-477e-b1b1-66d35d5e9d56";
		when(mapper.markIndexFailed(version)).thenReturn(1);
		SemanticLexicalIndexService service = new SemanticLexicalIndexService(mapper, new KoreanLexicalTokenizer());
		assertThat(service.recoverAbandonedBuild(version)).isTrue();
		verify(mapper, never()).markChunksReady(any());
		verify(mapper, never()).markIndexReady(any(), any(), any(Integer.class), any(Double.class));
		when(mapper.markIndexFailed(version)).thenReturn(0);
		assertThat(service.recoverAbandonedBuild(version)).isFalse();
		assertThatThrownBy(() -> service.recoverAbandonedBuild("not-a-version"))
			.isInstanceOf(IllegalArgumentException.class);
	}

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
		verify(mapper).markIndexFailed("lexical-build-failed");
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
