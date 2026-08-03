package com.kaces.pandora.lawdata.sync;

import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.persistence.LawAssetMapper;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentMapper;
import com.kaces.pandora.lawdata.version.LawVersionStatusService;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionRow;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class LawDocumentWriterTests {

	@Test
	void replaceChunksPersistsExactEmbeddingHashAndCanonicalParentSourcePath() {
		LawChunkMapper chunkMapper = mock(LawChunkMapper.class);
		when(chunkMapper.findChunkIdsByDocumentId(41L)).thenReturn(List.of());
		LawDocumentWriter writer = new LawDocumentWriter(
			mock(LawDocumentMapper.class), mock(LawDetailMapper.class), chunkMapper,
			mock(LawAssetMapper.class), mock(LawJsonWriter.class), mock(QdrantClient.class),
			mock(LawVersionStatusService.class), testProperties()
		);

		writer.replaceChunks(41L, 7L, "law", "Document title", List.of(
			new SyncDetailSection("article", "Article 1", "Article 1 (Scope)", "Operative child text.", "$.law.articles[0].body", 1, 1)
		), "https://example.test/law");

		ArgumentCaptor<StoredChunk> stored = ArgumentCaptor.forClass(StoredChunk.class);
		verify(chunkMapper).insertChunk(stored.capture());
		assertThat(stored.getValue().contentHash()).isEqualTo(sha256(stored.getValue().embeddingText()));
		assertThat(stored.getValue().parentSourcePath()).isEqualTo("$.law.articles");
	}

	@Test
	void replaceChunksKeepsExistingActiveChunksUntilCandidateIsVerified() {
		LawChunkMapper chunkMapper = mock(LawChunkMapper.class);
		when(chunkMapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of(101L));
		when(chunkMapper.findActiveChunkVersion(42L)).thenReturn(1);
		when(chunkMapper.findNextChunkVersion(42L)).thenReturn(2);
		when(chunkMapper.findChunkIdsByDocumentIdAndVersion(42L, 2)).thenReturn(List.of(202L));
		LawDocumentWriter writer = new LawDocumentWriter(
			mock(LawDocumentMapper.class), mock(LawDetailMapper.class), chunkMapper,
			mock(LawAssetMapper.class), mock(LawJsonWriter.class), mock(QdrantClient.class),
			mock(LawVersionStatusService.class), testProperties()
		);

		writer.replaceChunks(42L, 8L, "law", "Document title", List.of(
			new SyncDetailSection("article", "Article 1", "Article 1 (Scope)", "Operative child text.", "$.law.articles[0].body", 1, 1)
		), "https://example.test/law");

		verify(chunkMapper, never()).deleteChunks(42L);
		ArgumentCaptor<LawChunkVersionRow> version = ArgumentCaptor.forClass(LawChunkVersionRow.class);
		verify(chunkMapper).upsertChunkVersion(version.capture());
		assertThat(version.getValue()).isEqualTo(new LawChunkVersionRow(42L, 2, "CANDIDATE", 1, false, Integer.MAX_VALUE));
	}

	@Test
	void activateCandidateLeavesCandidateUntouchedWhenDatabaseVerificationFails() {
		LawChunkMapper chunkMapper = mock(LawChunkMapper.class);
		LawDocumentWriter writer = new LawDocumentWriter(
			mock(LawDocumentMapper.class), mock(LawDetailMapper.class), chunkMapper,
			mock(LawAssetMapper.class), mock(LawJsonWriter.class), mock(QdrantClient.class),
			mock(LawVersionStatusService.class), testProperties()
		);

		ChunkActivationResult result = writer.activateCandidate(42L, 2);

		assertThat(result.activated()).isFalse();
		assertThat(result.reason()).isEqualTo("Candidate activation requires an active transaction.");
		verify(chunkMapper, never()).activateChunkVersion(42L, 2);
		verify(chunkMapper, never()).retireOtherChunkVersions(42L, 2);
	}

	@Test
	void activationWaitsForCommitBeforeMakingCandidateSearchableAndRetiringOldPoints() {
		LawChunkMapper chunkMapper = mock(LawChunkMapper.class);
		QdrantClient qdrantClient = mock(QdrantClient.class);
		when(chunkMapper.findChunkVersionVerification(42L, 2, "text-embedding-3-small", "law_chunks"))
			.thenReturn(new LawChunkVersionVerification(1, 1, 1, 0, true, 0));
		when(chunkMapper.findChunkIdsByDocumentIdAndVersion(42L, 2)).thenReturn(List.of(202L));
		when(chunkMapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of(101L, 202L));
		when(qdrantClient.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(java.util.Set.of(202L));
		when(qdrantClient.lawCandidateCollection()).thenReturn("law_chunks_candidate");
		when(qdrantClient.indexSnapshot("law_chunks_candidate")).thenReturn(Optional.of(
			new QdrantIndexSnapshot("law_chunks_candidate", "green", 0, 1, 1536, "Cosine", 1, 1)
		));
		LawDocumentWriter writer = new LawDocumentWriter(
			mock(LawDocumentMapper.class), mock(LawDetailMapper.class), chunkMapper,
			mock(LawAssetMapper.class), mock(LawJsonWriter.class), qdrantClient,
			mock(LawVersionStatusService.class), testProperties()
		);

		TransactionSynchronizationManager.initSynchronization();
		try {
			ChunkActivationResult result = writer.activateCandidate(42L, 2);

			assertThat(result.activated()).isTrue();
			verify(qdrantClient).promoteLawCandidatePoints(List.of(202L));
			verify(qdrantClient, never()).markLawPointsActive(List.of(202L));
			verify(qdrantClient, never()).markLawPointsRetired(List.of(101L));
			TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
			verify(qdrantClient).markLawPointsActive(List.of(202L));
			verify(qdrantClient).markLawPointsRetired(List.of(101L));
			verify(qdrantClient).deleteLawPointsBestEffort(List.of(101L));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void databaseVerificationRejectsUnapprovedPreviewAndUnexplainedLosses() {
		assertThat(new LawChunkVersionVerification(1, 1, 1, 0, false, 0).databaseGatesPass()).isFalse();
		assertThat(new LawChunkVersionVerification(1, 1, 1, 0, true, 1).databaseGatesPass()).isFalse();
	}

	private LawAiProperties testProperties() {
		return new LawAiProperties(null, null, null, null);
	}
}
