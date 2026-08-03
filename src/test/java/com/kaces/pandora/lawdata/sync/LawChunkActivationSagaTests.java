package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class LawChunkActivationSagaTests {
	@Test
	void differentVersionConcurrentLoserDoesNoQdrantMutation() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(null);
		when(mapper.findChunkVersionStatus(42L, 3)).thenReturn("CANDIDATE");
		when(mapper.findChunkIdsByDocumentIdAndVersion(42L, 3)).thenReturn(List.of(303L));
		when(mapper.findChunkVersionVerification(42L, 3, "text-embedding-3-small", "law_chunks"))
			.thenReturn(new LawChunkVersionVerification(1, 1, 1, 0, true, 0));
		doThrow(new DuplicateKeyException("document operation already claimed"))
			.when(mapper).insertActivationOperation(any());
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(303L))).thenReturn(Set.of(303L));

		assertThatCode(() -> saga(mapper, qdrant).activate(42L, 3)).doesNotThrowAnyException();

		verify(qdrant, never()).promoteLawCandidatePoints(any());
		verify(qdrant, never()).markLawPointsCandidate(any());
		verify(qdrant, never()).markLawPointsActive(any());
		verify(qdrant, never()).deleteLawPoints(any());
	}

	@Test
	void sameRuntimeExpiredQdrantActivationStaysBlockedWithoutQdrantMutation() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(operation(2, "delayed-owner", "runtime-one", LawChunkActivationSaga.QDRANT_ACTIVATING, Instant.now().minusSeconds(1), List.of(101L), List.of(202L)));
		QdrantClient qdrant = mock(QdrantClient.class);

		ChunkActivationResult result = saga(mapper, qdrant).activate(42L, 3);

		assertThat(result.activated()).isFalse();
		verify(qdrant, never()).markLawPointsCandidate(any());
		verify(qdrant, never()).promoteLawCandidatePoints(any());
		verify(mapper, never()).reclaimActivationOperation(anyLong(), anyString(), anyString(), any(), anyString(), anyString(), any());
	}

	@Test
	void differentRuntimeExpiredPreFlipOperationIsDemotedInProductionThenVerifiedAndReleasedWithoutPromotion() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(operation(2, "crashed-owner", LawChunkActivationSaga.QDRANT_ACTIVATING, Instant.now().minusSeconds(1), List.of(101L), List.of(202L)));
		when(mapper.reclaimActivationOperation(anyLong(), anyString(), anyString(), any(), anyString(), anyString(), any())).thenReturn(1);
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		when(qdrant.findLawPointIdsWithActivationStatus(List.of(202L), "CANDIDATE")).thenReturn(Set.of(202L));

		ChunkActivationResult result = saga(mapper, qdrant).activate(42L, 3);

		assertThat(result.activated()).isFalse();
		verify(mapper).reclaimActivationOperation(eq(42L), anyString(), eq("runtime-one"), any(), eq(LawChunkActivationSaga.QDRANT_ACTIVATING), eq(LawChunkActivationSaga.RECOVERY_REQUIRED), isNull());
		verify(qdrant).markLawPointsCandidate(List.of(202L));
		verify(qdrant).findLawPointIdsWithActivationStatus(List.of(202L), "CANDIDATE");
		verify(qdrant, never()).promoteLawCandidatePoints(any());
		verify(mapper).resetCandidateForOperation(eq(42L), eq(2), anyString(), eq(LawChunkActivationSaga.RECOVERY_REQUIRED));
		verify(mapper).transitionActivationOperation(eq(42L), anyString(), eq(LawChunkActivationSaga.RECOVERY_REQUIRED), eq(LawChunkActivationSaga.DONE), isNull());
	}

	@Test
	void stagingPresenceDoesNotReleaseAProductionPointThatRemainsActive() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(operation(2, "crashed-owner", LawChunkActivationSaga.QDRANT_ACTIVATING, Instant.now().minusSeconds(1), List.of(101L), List.of(202L)));
		when(mapper.reclaimActivationOperation(anyLong(), anyString(), anyString(), any(), anyString(), anyString(), any())).thenReturn(1);
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		when(qdrant.findLawPointIdsWithActivationStatus(List.of(202L), "CANDIDATE")).thenReturn(Set.of());
		when(qdrant.findLawPointIdsWithActivationStatus(List.of(202L), "ACTIVE")).thenReturn(Set.of(202L));

		ChunkActivationResult result = saga(mapper, qdrant).activate(42L, 3);

		assertThat(result.activated()).isFalse();
		verify(qdrant).findLawPointIdsWithActivationStatus(List.of(202L), "CANDIDATE");
		verify(mapper, never()).resetCandidateForOperation(anyLong(), anyInt(), anyString(), anyString());
		verify(mapper).transitionActivationOperation(eq(42L), anyString(), eq(LawChunkActivationSaga.RECOVERY_REQUIRED), eq(LawChunkActivationSaga.RECOVERY_REQUIRED), anyString());
	}

	@Test
	void expiredCleanupOperationOnlyCleansThePersistedPriorPointSnapshot() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(operation(2, "crashed-owner", LawChunkActivationSaga.DB_ACTIVE_CLEANUP_PENDING, Instant.now().minusSeconds(1), List.of(101L), List.of(202L)));
		when(mapper.reclaimActivationOperation(anyLong(), anyString(), anyString(), any(), anyString(), anyString(), any())).thenReturn(1);
		when(mapper.completeCandidateCleanupForOperation(anyLong(), anyInt(), anyString())).thenReturn(1);
		when(mapper.transitionActivationOperation(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(1);
		QdrantClient qdrant = mock(QdrantClient.class);

		ChunkActivationResult result = saga(mapper, qdrant).activate(42L, 3);

		assertThat(result.activated()).isTrue();
		verify(qdrant).markLawPointsRetired(List.of(101L));
		verify(qdrant).deleteLawPoints(List.of(101L));
		verify(qdrant, never()).markLawPointsCandidate(any());
	}

	@Test
	void demotionFailureRetainsRecoveryRequiredInsteadOfReleasingAmbiguousOwner() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(operation(2, "crashed-owner", LawChunkActivationSaga.QDRANT_ACTIVATING, Instant.now().minusSeconds(1), List.of(101L), List.of(202L)));
		when(mapper.reclaimActivationOperation(anyLong(), anyString(), anyString(), any(), anyString(), anyString(), any())).thenReturn(1);
		QdrantClient qdrant = mock(QdrantClient.class);
		doThrow(new IllegalStateException("qdrant unavailable")).when(qdrant).markLawPointsCandidate(List.of(202L));

		ChunkActivationResult result = saga(mapper, qdrant).activate(42L, 3);

		assertThat(result.activated()).isFalse();
		verify(mapper).transitionActivationOperation(eq(42L), anyString(), eq(LawChunkActivationSaga.RECOVERY_REQUIRED), eq(LawChunkActivationSaga.RECOVERY_REQUIRED), anyString());
		verify(mapper, never()).resetCandidateForOperation(anyLong(), anyInt(), anyString(), anyString());
	}

	@Test
	void initialClaimPersistsExactCandidateAndPriorSnapshots() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findActivationOperation(42L)).thenReturn(null);
		when(mapper.insertActivationOperation(any())).thenReturn(1);
		when(mapper.markCandidateActivatingForOperation(anyLong(), anyInt(), anyString())).thenReturn(1);
		when(mapper.transitionActivationOperation(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(1);
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L, 203L))).thenReturn(Set.of(202L, 203L));
		when(qdrant.findLawPointIdsWithActivationStatus(List.of(202L, 203L), "ACTIVE")).thenReturn(Set.of(202L, 203L));
		when(mapper.retireChunkIdsForOperation(anyLong(), any(), anyString())).thenReturn(1);
		when(mapper.activateCandidateChunksForOperation(anyLong(), anyInt(), anyString())).thenReturn(2);

		saga(mapper, qdrant).activate(42L, 2);

		ArgumentCaptor<DocumentActivationOperation> operation = ArgumentCaptor.forClass(DocumentActivationOperation.class);
		verify(mapper).insertActivationOperation(operation.capture());
		assertThat(operation.getValue())
			.extracting(DocumentActivationOperation::runtimeInstanceId, DocumentActivationOperation::priorActiveVersion, DocumentActivationOperation::priorPointIdsJson, DocumentActivationOperation::candidatePointIdsJson)
			.containsExactly("runtime-one", 1, "[101]", "[202,203]");
	}

	private LawChunkMapper preparedMapper() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		when(mapper.findChunkVersionStatus(42L, 2)).thenReturn("CANDIDATE");
		when(mapper.findChunkVersionVerification(42L, 2, "text-embedding-3-small", "law_chunks"))
			.thenReturn(new LawChunkVersionVerification(2, 2, 2, 0, true, 0));
		when(mapper.findChunkIdsByDocumentIdAndVersion(42L, 2)).thenReturn(List.of(202L, 203L));
		when(mapper.findActiveChunkIdsByDocumentId(42L)).thenReturn(List.of(101L));
		when(mapper.findActiveChunkVersion(42L)).thenReturn(1);
		when(mapper.renewActivationOperationLease(anyLong(), anyString(), anyString(), any())).thenReturn(1);
		when(mapper.transitionActivationOperation(anyLong(), anyString(), anyString(), anyString(), any())).thenReturn(1);
		when(mapper.resetCandidateForOperation(anyLong(), anyInt(), anyString(), anyString())).thenReturn(1);
		return mapper;
	}

	private LawChunkActivationSaga saga(LawChunkMapper mapper, QdrantClient qdrant) {
		return new LawChunkActivationSaga(mapper, qdrant, new LawAiProperties(null, null, null, null), directTransactions(), "runtime-one");
	}

	private DocumentActivationOperation operation(int candidateVersion, String owner, String phase, Instant leaseExpiresAt, List<Long> priorIds, List<Long> candidateIds) {
		return operation(candidateVersion, owner, "other-runtime", phase, leaseExpiresAt, priorIds, candidateIds);
	}

	private DocumentActivationOperation operation(int candidateVersion, String owner, String runtimeInstanceId, String phase, Instant leaseExpiresAt, List<Long> priorIds, List<Long> candidateIds) {
		return new DocumentActivationOperation(42L, candidateVersion, owner, runtimeInstanceId, leaseExpiresAt, phase, 1,
			"[" + priorIds.get(0) + "]", "[" + candidateIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")) + "]", null);
	}

	private LawActivationTransactionExecutor directTransactions() {
		return new LawActivationTransactionExecutor() {
			@Override public <T> T inTransaction(java.util.function.Supplier<T> action) { return action.get(); }
		};
	}
}
