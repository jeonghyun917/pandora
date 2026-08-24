package com.kaces.pandora.semantic.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.text.DocumentSearchAnchor;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiDocumentExpansionProperties;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DocumentExpansionSearchServiceTests {

	@Test
	void skipsAllDatabaseReadsWhenExpansionIsDisabled() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(false, 2, 5, 7))
			.search(anchor(), List.of("law", "official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.DISABLED);
		verifyNoInteractions(lawMapper, ragMapper);
	}

	@Test
	void skipsAllDatabaseReadsWhenExpansionBoundsAreInvalid() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 0, 5, 7))
			.search(anchor(), List.of("law", "official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.INVALID_BOUNDS);
		verifyNoInteractions(lawMapper, ragMapper);
	}

	@Test
	void skipsAllDatabaseReadsWhenAnchorIsNotStrong() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 2, 5, 7))
			.search(noStrongAnchor(), List.of("law", "official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.NO_STRONG_ANCHOR);
		verifyNoInteractions(lawMapper, ragMapper);
	}

	@Test
	void queriesOnlyTheRequestedLawFamily() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);
		when(lawMapper.findDocumentExpansionDocuments(eq(List.of("law")), eq(List.of("전자정부법")), eq(List.of("제1조")), eq(true), eq(3)))
			.thenReturn(List.of(document(10L, "law")));
		when(lawMapper.findDocumentExpansionChunks(eq(List.of(10L)), eq(List.of("제1조")), eq(List.of("목적")), eq(List.of("전자정부")), eq(true), eq(5), eq(7)))
			.thenReturn(List.of(chunk(100L, 10L, "law")));

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 2, 5, 7))
			.search(anchor(), List.of("law"), true, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(100L);
		verify(ragMapper, never()).findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyInt());
		verify(ragMapper, never()).findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyInt(), anyInt());
	}

	@Test
	void queriesOnlyTheRequestedRagFamily() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);
		when(ragMapper.findDocumentExpansionDocuments(eq(List.of("official_doc")), eq(List.of("전자정부법")), eq(List.of("제1조")), eq(3)))
			.thenReturn(List.of(document(20L, "official_doc")));
		when(ragMapper.findDocumentExpansionChunks(eq(List.of(20L)), eq(List.of("제1조")), eq(List.of("목적")), eq(List.of("전자정부")), eq(5), eq(7)))
			.thenReturn(List.of(chunk(200L, 20L, "official_doc")));

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 2, 5, 7))
			.search(anchor(), List.of("official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(200L);
		verify(lawMapper, never()).findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyBoolean(), anyInt());
		verify(lawMapper, never()).findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyInt(), anyInt());
	}

	@Test
	void combinesFamilyIdentityResultsOnceBeforeSelectingDocuments() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);
		when(lawMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyBoolean(), anyInt()))
			.thenReturn(List.of(document(10L, "law")));
		when(ragMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyInt()))
			.thenReturn(List.of(document(20L, "official_doc")));
		when(lawMapper.findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyInt(), anyInt()))
			.thenReturn(List.of(chunk(100L, 10L, "law")));
		when(ragMapper.findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyInt(), anyInt()))
			.thenReturn(List.of(chunk(200L, 20L, "official_doc")));

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 2, 5, 7))
			.search(anchor(), List.of("law", "official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED);
		assertThat(result.chunks()).extracting(LawSemanticChunkRow::chunkId).containsExactly(100L, 200L);
		verify(lawMapper).findDocumentExpansionDocuments(eq(List.of("law")), eq(List.of("전자정부법")), eq(List.of("제1조")), eq(false), eq(3));
		verify(ragMapper).findDocumentExpansionDocuments(eq(List.of("official_doc")), eq(List.of("전자정부법")), eq(List.of("제1조")), eq(3));
	}

	@Test
	void avoidsChunkReadsWhenDocumentMatchesAreAmbiguous() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);
		when(lawMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyBoolean(), anyInt()))
			.thenReturn(List.of(document(10L, "law"), document(11L, "law")));

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 1, 5, 7))
			.search(anchor(), List.of("law"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.DOCUMENT_MATCH_AMBIGUOUS);
		verify(lawMapper, never()).findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyInt(), anyInt());
		verifyNoInteractions(ragMapper);
	}

	@Test
	void failsClosedWhenLawIdentityReadFailsDespiteSuccessfulRagRead() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);
		when(lawMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyBoolean(), anyInt()))
			.thenThrow(new IllegalStateException("database unavailable"));
		when(ragMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyInt()))
			.thenReturn(List.of(document(20L, "official_doc")));

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 2, 5, 7))
			.search(anchor(), List.of("law", "official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.DB_FALLBACK_BASELINE);
		assertThat(result.chunks()).isEmpty();
		verify(lawMapper, never()).findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyInt(), anyInt());
		verify(ragMapper, never()).findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyInt(), anyInt());
	}

	@Test
	void failsClosedWithoutReturningLawChunksWhenRagChunkReadFails() {
		LawChunkMapper lawMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragMapper = mock(RagDocumentMapper.class);
		when(lawMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyBoolean(), anyInt()))
			.thenReturn(List.of(document(10L, "law")));
		when(ragMapper.findDocumentExpansionDocuments(anyList(), anyList(), anyList(), anyInt()))
			.thenReturn(List.of(document(20L, "official_doc")));
		when(lawMapper.findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyInt(), anyInt()))
			.thenReturn(List.of(chunk(100L, 10L, "law")));
		when(ragMapper.findDocumentExpansionChunks(anyList(), anyList(), anyList(), anyList(), anyInt(), anyInt()))
			.thenThrow(new IllegalStateException("database unavailable"));

		DocumentCandidateExpansion.Result result = service(lawMapper, ragMapper, properties(true, 2, 5, 7))
			.search(anchor(), List.of("law", "official_doc"), false, Set.of());

		assertThat(result.status()).isEqualTo(DocumentCandidateExpansion.Status.DB_FALLBACK_BASELINE);
		assertThat(result.chunks()).isEmpty();
	}

	@Test
	void constructorDependsOnlyOnReadMappersExpansionAndProperties() {
		Constructor<?> constructor = DocumentExpansionSearchService.class.getDeclaredConstructors()[0];

		assertThat(constructor.getParameterTypes()).containsExactly(
			LawChunkMapper.class,
			RagDocumentMapper.class,
			DocumentCandidateExpansion.class,
			LawAiDocumentExpansionProperties.class
		);
	}

	private DocumentExpansionSearchService service(
		LawChunkMapper lawMapper,
		RagDocumentMapper ragMapper,
		LawAiDocumentExpansionProperties properties
	) {
		return new DocumentExpansionSearchService(lawMapper, ragMapper, new DocumentCandidateExpansion(), properties);
	}

	private LawAiDocumentExpansionProperties properties(boolean enabled, int maxDocuments, int maxChunksPerDocument, int maxTotalChunks) {
		return new LawAiDocumentExpansionProperties(enabled, false, maxDocuments, maxChunksPerDocument, maxTotalChunks);
	}

	private DocumentSearchAnchor anchor() {
		return new DocumentSearchAnchor(
			List.of("전자정부법"), List.of("제1조"), List.of("목적"), List.of("전자정부"), List.of("law", "official_doc"),
			DocumentSearchAnchor.AnchorType.TITLE_WITH_PROVISION, DocumentSearchAnchor.Status.ELIGIBLE
		);
	}

	private DocumentSearchAnchor noStrongAnchor() {
		return new DocumentSearchAnchor(
			List.of(), List.of(), List.of(), List.of(), List.of("law"),
			DocumentSearchAnchor.AnchorType.NONE, DocumentSearchAnchor.Status.NO_STRONG_ANCHOR
		);
	}

	private DocumentIdentityCandidate document(long documentId, String target) {
		return new DocumentIdentityCandidate(documentId, target, "전자정부법", "전자정부법", 1, true, true);
	}

	private LawSemanticChunkRow chunk(long chunkId, long documentId, String target) {
		return new LawSemanticChunkRow(
			chunkId, documentId, target, String.valueOf(documentId), "전자정부법", "", "", "", "CURRENT",
			"제1조", "목적", "전자정부", null, null, null, 1, "hash-" + chunkId, "", "provision"
		);
	}
}
