package com.kaces.pandora.semantic.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LawSemanticIndexServiceTests {

	@Test
	void indexCandidateRejectsNonPositiveDocumentOrVersionWithoutIndexing() {
		LawSemanticIndexService service = new LawSemanticIndexService(
			mock(LawChunkMapper.class), new LawAiProperties(null, null, null, null),
			mock(OpenAiEmbeddingClient.class), mock(QdrantClient.class), mock(LawJsonWriter.class),
			mock(LawSemanticIndexStatusPersistenceService.class)
		);

		assertThat(service.indexCandidate("law", 0L, 2, 10).indexed()).isZero();
		assertThat(service.indexCandidate("law", 42L, 0, 10).requested()).isZero();
	}

	@Test
	void indexCandidateWritesOnlyToTheIsolatedCandidateCollection() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		QdrantClient qdrantClient = mock(QdrantClient.class);
		when(mapper.findSemanticIndexCandidatesByDocumentIdAndVersion("law", 42L, 2,
			"text-embedding-3-small", "law_chunks", 10)).thenReturn(List.of(candidateChunk()));
		when(mapper.updateChunkIndexStatus(101L, "INDEXED", null)).thenReturn(1);
		OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
		when(embeddingClient.embed(List.of(candidateChunk().embeddingInput()))).thenReturn(List.of(List.of(0.1d)));
		LawSemanticIndexService service = new LawSemanticIndexService(
			mapper, new LawAiProperties(null, null, null, null), embeddingClient, qdrantClient, mock(LawJsonWriter.class),
			new LawSemanticIndexStatusPersistenceService(mapper)
		);

		service.indexCandidate("law", 42L, 2, 10);

		verify(qdrantClient).upsertLawCandidates(List.of(candidateChunk()), List.of(List.of(0.1d)));
		verify(qdrantClient, never()).upsert(List.of(candidateChunk()), List.of(List.of(0.1d)));
	}

	@Test
	void exactIndexChecksOwnershipAfterEmbeddingBeforeAnyQdrantOrDatabaseMutation() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		QdrantClient qdrantClient = mock(QdrantClient.class);
		OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
		when(embeddingClient.embed(List.of(candidateChunk().embeddingInput()))).thenReturn(List.of(List.of(0.1d)));
		LawSemanticIndexService service = new LawSemanticIndexService(
			mapper, new LawAiProperties(null, null, null, null), embeddingClient, qdrantClient, mock(LawJsonWriter.class),
			new LawSemanticIndexStatusPersistenceService(mapper)
		);
		AtomicInteger checkpoints = new AtomicInteger();

		assertThatThrownBy(() -> service.indexExactChunks(List.of(candidateChunk()), () -> {
			if (checkpoints.incrementAndGet() == 3) {
				throw new IllegalStateException("lost");
			}
		})).isInstanceOf(IllegalStateException.class).hasMessage("lost");

		verify(qdrantClient).ensureCollection();
		verify(embeddingClient).embed(List.of(candidateChunk().embeddingInput()));
		verify(qdrantClient, never()).upsert(org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyList());
		verify(mapper, never()).upsertEmbeddingStatus(org.mockito.ArgumentMatchers.anyLong(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
	}

	private LawSemanticChunkRow candidateChunk() {
		return new LawSemanticChunkRow(101L, 42L, "law", "x", "Title", "", "", "", "CURRENT",
			"Article 1", "Scope", "candidate text", null, "$.body", "", 0, "a".repeat(64), "Scope", "provision", "PASS");
	}
}
