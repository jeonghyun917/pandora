package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

class LawChunkActivationSagaTests {
	@Test
	void qdrantFailureLeavesOldDatabaseVersionActiveAndDurablyRetryable() {
		LawChunkMapper mapper = preparedMapper();
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		doThrow(new IllegalStateException("down")).when(qdrant).promoteLawCandidatePoints(List.of(202L));
		LawChunkActivationSaga saga = new LawChunkActivationSaga(mapper, qdrant, new LawAiProperties(null, null, null, null), directTransactions());

		ChunkActivationResult result = saga.activate(42L, 2);

		assertThat(result.activated()).isFalse();
		verify(mapper).claimCandidateActivation(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString());
		verify(mapper).releaseCandidateActivation(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString());
		verify(mapper, never()).retireOtherChunkVersions(42L, 2);
		verify(mapper, never()).activateChunkVersion(42L, 2);
	}

	@Test
	void databaseFlipFailureDemotesCandidateAndRetainsActivatingForRetry() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.findChunkVersionStatus(42L, 2)).thenReturn("CANDIDATE", "ACTIVATING");
		doThrow(new IllegalStateException("db")).when(mapper).retireOtherChunkVersions(42L, 2);
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		when(qdrant.findLawPointIdsWithActivationStatus(List.of(202L), "ACTIVE")).thenReturn(Set.of(202L));
		LawChunkActivationSaga saga = new LawChunkActivationSaga(mapper, qdrant, new LawAiProperties(null, null, null, null), directTransactions());

		ChunkActivationResult result = saga.activate(42L, 2);

		assertThat(result.activated()).isFalse();
		verify(qdrant).markLawPointsCandidate(List.of(202L));
		verify(mapper, never()).updateChunkVersionStatus(42L, 2, "ACTIVE");
	}

	@Test
	void incompletePostActivationVerificationDemotesCandidateAndReleasesOwnership() {
		LawChunkMapper mapper = preparedMapper();
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		when(qdrant.findLawPointIdsWithActivationStatus(List.of(202L), "ACTIVE")).thenReturn(Set.of());
		LawChunkActivationSaga saga = new LawChunkActivationSaga(mapper, qdrant, new LawAiProperties(null, null, null, null), directTransactions());

		ChunkActivationResult result = saga.activate(42L, 2);

		assertThat(result.activated()).isFalse();
		verify(qdrant).markLawPointsCandidate(List.of(202L));
		verify(mapper).releaseCandidateActivation(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void thrownPostActivationVerificationAlsoDemotesCandidateAndReleasesOwnership() {
		LawChunkMapper mapper = preparedMapper();
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		doThrow(new IllegalStateException("status unavailable")).when(qdrant).findLawPointIdsWithActivationStatus(List.of(202L), "ACTIVE");
		LawChunkActivationSaga saga = new LawChunkActivationSaga(mapper, qdrant, new LawAiProperties(null, null, null, null), directTransactions());

		ChunkActivationResult result = saga.activate(42L, 2);

		assertThat(result.activated()).isFalse();
		verify(qdrant).markLawPointsCandidate(List.of(202L));
		verify(mapper).releaseCandidateActivation(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString());
	}

	@Test
	void secondCallerThatLosesCasNeverMutatesTheWinnerCandidatePoints() {
		LawChunkMapper mapper = preparedMapper();
		when(mapper.claimCandidateActivation(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString())).thenReturn(0);
		QdrantClient qdrant = mock(QdrantClient.class);
		when(qdrant.findExistingLawCandidatePointIds(List.of(202L))).thenReturn(Set.of(202L));
		LawChunkActivationSaga saga = new LawChunkActivationSaga(mapper, qdrant, new LawAiProperties(null, null, null, null), directTransactions());

		ChunkActivationResult result = saga.activate(42L, 2);

		assertThat(result.activated()).isFalse();
		verify(qdrant, never()).promoteLawCandidatePoints(List.of(202L));
		verify(qdrant, never()).markLawPointsCandidate(List.of(202L));
	}

	private LawChunkMapper preparedMapper() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		when(mapper.findChunkVersionStatus(42L, 2)).thenReturn("CANDIDATE");
		when(mapper.findChunkVersionVerification(42L, 2, "text-embedding-3-small", "law_chunks"))
			.thenReturn(new LawChunkVersionVerification(1, 1, 1, 0, true, 0));
		when(mapper.findChunkIdsByDocumentIdAndVersion(42L, 2)).thenReturn(List.of(202L));
		when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of(101L, 202L));
		when(mapper.findActiveChunkIdsByDocumentId(42L)).thenReturn(List.of(101L));
		when(mapper.claimCandidateActivation(org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq(2), org.mockito.ArgumentMatchers.anyString())).thenReturn(1);
		return mapper;
	}

	private LawActivationTransactionExecutor directTransactions() {
		return new LawActivationTransactionExecutor() {
			@Override public <T> T inTransaction(java.util.function.Supplier<T> action) { return action.get(); }
		};
	}
}
