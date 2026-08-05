package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexService;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class LawMissingEmbeddingRepairOperationServiceTests {
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");
	private static final String OPERATION_ID = "00000000-0000-0000-0000-000000000010";

	@Test
	void heartbeatOwnershipLossStopsAtCheckpointWithoutCompletingOrFailingAsTheStaleOwner() throws Exception {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		LawMissingEmbeddingRepairOperation.OperationRow running = operationRow(
			LawMissingEmbeddingRepairOperation.Status.RUNNING, "a", 0, "owner", null);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(
			operationRow(LawMissingEmbeddingRepairOperation.Status.READY, "a", 0, null, null), running);
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.PROCESSING, NOW.plusSeconds(10), null)));
		when(persistence.claimReadyItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
		CountDownLatch renewalAttempted = new CountDownLatch(1);
		when(persistence.renewItemLease(any(), anyInt(), any(), anyInt())).thenAnswer(invocation -> {
			renewalAttempted.countDown();
			return false;
		});
		when(legacy.currentRuntimeSnapshot()).thenReturn(runtime("a"));
		when(legacy.repairExact(any(), any())).thenAnswer(invocation -> {
			LawMissingEmbeddingRepairService.RepairCheckpoint checkpoint = invocation.getArgument(1);
			assertThat(renewalAttempted.await(2, TimeUnit.SECONDS)).isTrue();
			checkpoint.verifyOwnership();
			return indexedResult("b");
		});
		LawMissingEmbeddingRepairOperationService service = new LawMissingEmbeddingRepairOperationService(
			operations, legacy, persistence, Clock.fixed(NOW, ZoneOffset.UTC),
			new LawMissingEmbeddingRepairOperationService.LeasePolicy(10, Duration.ofMillis(10))
		);

		assertThat(service.step(UUID.fromString(OPERATION_ID))).isPresent();

		verify(persistence).renewItemLease(any(), anyInt(), any(), org.mockito.ArgumentMatchers.eq(10));
		verify(persistence, never()).completeClaimedItem(any(), anyInt(), any(), any(), any(), any());
		verify(persistence, never()).failClaimedItem(any(), anyInt(), any(), any());
	}

	@Test
	void stepClaimsAndIndexesExactlyOneReadyItemThenAdvancesTheDurableRevision() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		LawMissingEmbeddingRepairOperation.OperationRow before = operationRow(LawMissingEmbeddingRepairOperation.Status.READY, "a", 0, null, null);
		LawMissingEmbeddingRepairOperation.OperationRow after = operationRow(LawMissingEmbeddingRepairOperation.Status.RUNNING, "b", 1, null, null);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(before, after);
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null), stepItem(1, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.INDEXED, null, null), stepItem(1, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null))
		);
		when(persistence.claimReadyItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
		when(persistence.completeClaimedItem(any(), anyInt(), any(), any(), any(), any())).thenReturn(true);
		when(legacy.currentRuntimeSnapshot()).thenReturn(runtime("a"));
		when(legacy.repairExact(any(), any())).thenReturn(indexedResult("b"));
		LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

		var result = service.step(UUID.fromString(OPERATION_ID)).orElseThrow();

		assertThat(result.operation().progress().trustedIndexRevision()).isEqualTo("b".repeat(64));
		assertThat(result.items()).extracting(LawMissingEmbeddingRepairOperation.Item::state)
			.containsExactly(LawMissingEmbeddingRepairOperation.ItemState.INDEXED, LawMissingEmbeddingRepairOperation.ItemState.READY);
		verify(legacy, times(1)).repairExact(any(), any());
		verify(persistence).claimReadyItem(org.mockito.ArgumentMatchers.eq(OPERATION_ID), org.mockito.ArgumentMatchers.eq(0), any(),
			org.mockito.ArgumentMatchers.eq(validRuntime()), org.mockito.ArgumentMatchers.eq("a".repeat(64)), anyInt());
		verify(persistence).completeClaimedItem(org.mockito.ArgumentMatchers.eq(OPERATION_ID), org.mockito.ArgumentMatchers.eq(0), any(),
			org.mockito.ArgumentMatchers.eq(validRuntime()), org.mockito.ArgumentMatchers.eq("a".repeat(64)), org.mockito.ArgumentMatchers.eq("b".repeat(64)));
	}

	@Test
	void lastSuccessfulStepBecomesIndexingCompleteAndReplayDoesNotReindex() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(
			operationRow(LawMissingEmbeddingRepairOperation.Status.READY, "a", 0, null, null),
			operationRow(LawMissingEmbeddingRepairOperation.Status.INDEXING_COMPLETE, "b", 1, null, null),
			operationRow(LawMissingEmbeddingRepairOperation.Status.INDEXING_COMPLETE, "b", 1, null, null));
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.INDEXED, null, null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.INDEXED, null, null)));
		when(persistence.claimReadyItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
		when(persistence.completeClaimedItem(any(), anyInt(), any(), any(), any(), any())).thenReturn(true);
		when(legacy.currentRuntimeSnapshot()).thenReturn(runtime("a"));
		when(legacy.repairExact(any(), any())).thenReturn(indexedResult("b"));
		LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

		assertThat(service.step(UUID.fromString(OPERATION_ID)).orElseThrow().operation().progress().status())
			.isEqualTo(LawMissingEmbeddingRepairOperation.Status.INDEXING_COMPLETE);
		assertThat(service.step(UUID.fromString(OPERATION_ID)).orElseThrow().operation().progress().status())
			.isEqualTo(LawMissingEmbeddingRepairOperation.Status.INDEXING_COMPLETE);
		verify(legacy, times(1)).repairExact(any(), any());
	}

	@Test
	void driftOrIndexFailureFailsClosedWithSanitizedReasonAndLeavesRemainingNotAttempted() {
		for (boolean exception : List.of(false, true)) {
			LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
			LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
			LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
			when(operations.findOperationById(OPERATION_ID)).thenReturn(
				operationRow(LawMissingEmbeddingRepairOperation.Status.READY, "a", 0, null, null),
				operationRow(LawMissingEmbeddingRepairOperation.Status.FAILED, "a", 0, null, "EXACT_INDEX_FAILED"));
			when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
				List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null), stepItem(1, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null)),
				List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.FAILED, null, "EXACT_INDEX_FAILED"), stepItem(1, LawMissingEmbeddingRepairOperation.ItemState.NOT_ATTEMPTED, null, null)));
			when(persistence.claimReadyItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
			when(persistence.failClaimedItem(any(), anyInt(), any(), any())).thenReturn(true);
			when(legacy.currentRuntimeSnapshot()).thenReturn(runtime("a"));
			if (exception) {
				when(legacy.repairExact(any(), any())).thenThrow(new IllegalStateException("secret host and credential"));
			} else {
				when(legacy.repairExact(any(), any())).thenReturn(new LawMissingEmbeddingRepairService.RepairResult(true, false, runtime("a"), List.of(
					new LawMissingEmbeddingRepairService.RepairOutcome(101L, 11L, LawMissingEmbeddingRepairService.RepairState.REJECTED_CHUNK_DRIFT, "private detail"))));
			}
			LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

			var result = service.step(UUID.fromString(OPERATION_ID)).orElseThrow();

			assertThat(result.operation().progress().status()).isEqualTo(LawMissingEmbeddingRepairOperation.Status.FAILED);
			assertThat(result.items()).extracting(LawMissingEmbeddingRepairOperation.Item::state)
				.containsExactly(LawMissingEmbeddingRepairOperation.ItemState.FAILED, LawMissingEmbeddingRepairOperation.ItemState.NOT_ATTEMPTED);
			verify(persistence).failClaimedItem(org.mockito.ArgumentMatchers.eq(OPERATION_ID), org.mockito.ArgumentMatchers.eq(0), any(),
				org.mockito.ArgumentMatchers.eq(exception ? "EXACT_INDEX_FAILED" : "CANDIDATE_DRIFT"));
		}
	}

	@Test
	void liveLeasePreventsSecondWorkerAndRuntimeRestartNeverRebinds() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(operationRow(LawMissingEmbeddingRepairOperation.Status.RUNNING, "a", 0, "owner", null));
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(List.of(
			stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.PROCESSING, NOW.plusSeconds(30), null)));
		LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

		service.step(UUID.fromString(OPERATION_ID));

		verifyNoInteractions(legacy);
		verify(persistence).claimExpiredItem(org.mockito.ArgumentMatchers.eq(OPERATION_ID), org.mockito.ArgumentMatchers.eq(0), any(),
			org.mockito.ArgumentMatchers.eq(validRuntime()), org.mockito.ArgumentMatchers.eq("a".repeat(64)), anyInt());
	}

	@Test
	void restartedRuntimeFailsTheClaimedItemWithoutRebindingOrIndexing() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(
			operationRow(LawMissingEmbeddingRepairOperation.Status.READY, "a", 0, null, null),
			operationRow(LawMissingEmbeddingRepairOperation.Status.FAILED, "a", 0, null, "RUNTIME_FENCE_DRIFT"));
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.FAILED, null, "RUNTIME_FENCE_DRIFT")));
		when(persistence.claimReadyItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
		when(persistence.failClaimedItem(any(), anyInt(), any(), any())).thenReturn(true);
		when(legacy.currentRuntimeSnapshot()).thenReturn(new LawIndexIntegrityRuntimeInfo(
			"00000000-0000-0000-0000-000000000099", "a".repeat(64)));
		LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

		var result = service.step(UUID.fromString(OPERATION_ID)).orElseThrow();

		assertThat(result.operation().request().runtimeInstanceId()).isEqualTo(validRuntime());
		assertThat(result.operation().progress().status()).isEqualTo(LawMissingEmbeddingRepairOperation.Status.FAILED);
		verify(legacy, never()).repairExact(any(), any());
	}

	@Test
	void runtimeRestartObservedAfterExactWriteFailsInsteadOfAdvancingTheStoredFence() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(
			operationRow(LawMissingEmbeddingRepairOperation.Status.READY, "a", 0, null, null),
			operationRow(LawMissingEmbeddingRepairOperation.Status.FAILED, "a", 0, null, "RUNTIME_FENCE_DRIFT"));
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.READY, null, null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.FAILED, null, "RUNTIME_FENCE_DRIFT")));
		when(persistence.claimReadyItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
		when(persistence.failClaimedItem(any(), anyInt(), any(), any())).thenReturn(true);
		when(legacy.currentRuntimeSnapshot()).thenReturn(runtime("a"));
		when(legacy.repairExact(any(), any())).thenReturn(new LawMissingEmbeddingRepairService.RepairResult(true, false,
			new LawIndexIntegrityRuntimeInfo("00000000-0000-0000-0000-000000000099", "b".repeat(64)), List.of(
			new LawMissingEmbeddingRepairService.RepairOutcome(101L, 11L, LawMissingEmbeddingRepairService.RepairState.INDEXED, "indexed"))));
		LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

		service.step(UUID.fromString(OPERATION_ID));

		verify(persistence, never()).completeClaimedItem(any(), anyInt(), any(), any(), any(), any());
		verify(persistence).failClaimedItem(any(), anyInt(), any(), org.mockito.ArgumentMatchers.eq("RUNTIME_FENCE_DRIFT"));
	}

	@Test
	void expiredProcessingReconcilesCleanWithoutReindexAndMissingByOneDeterministicRepair() {
		for (LawMissingEmbeddingRepairService.RepairState inspected : List.of(
			LawMissingEmbeddingRepairService.RepairState.INDEXED, LawMissingEmbeddingRepairService.RepairState.READY)) {
			LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
			LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
			LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
			when(operations.findOperationById(OPERATION_ID)).thenReturn(
				operationRow(LawMissingEmbeddingRepairOperation.Status.RUNNING, "a", 0, "old", null),
				operationRow(LawMissingEmbeddingRepairOperation.Status.INDEXING_COMPLETE, inspected == LawMissingEmbeddingRepairService.RepairState.INDEXED ? "b" : "c", 1, null, null));
			when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
				List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.PROCESSING, NOW.minusSeconds(1), null)),
				List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.INDEXED, null, null)));
			when(persistence.claimExpiredItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
			when(persistence.completeClaimedItem(any(), anyInt(), any(), any(), any(), any())).thenReturn(true);
			when(legacy.inspectExactCandidate(any(), any())).thenReturn(new LawMissingEmbeddingRepairService.ExactInspection(runtime(inspected == LawMissingEmbeddingRepairService.RepairState.INDEXED ? "b" : "a"), inspected, 11L));
			when(legacy.repairExact(any(), any())).thenReturn(indexedResult("c"));
			LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

			assertThat(service.step(UUID.fromString(OPERATION_ID))).isPresent();
			verify(legacy, times(inspected == LawMissingEmbeddingRepairService.RepairState.READY ? 1 : 0)).repairExact(any(), any());
		}
	}

	@Test
	void expiredProcessingAmbiguityFailsInsteadOfRetrying() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationPersistenceService persistence = mock(LawMissingEmbeddingRepairOperationPersistenceService.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(operations.findOperationById(OPERATION_ID)).thenReturn(
			operationRow(LawMissingEmbeddingRepairOperation.Status.RUNNING, "a", 0, "old", null),
			operationRow(LawMissingEmbeddingRepairOperation.Status.FAILED, "a", 0, null, "RECOVERY_AMBIGUOUS"));
		when(operations.findItemsByOperationId(OPERATION_ID)).thenReturn(
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.PROCESSING, NOW.minusSeconds(1), null)),
			List.of(stepItem(0, LawMissingEmbeddingRepairOperation.ItemState.FAILED, null, "RECOVERY_AMBIGUOUS")));
		when(persistence.claimExpiredItem(any(), anyInt(), any(), any(), any(), anyInt())).thenReturn(true);
		when(persistence.failClaimedItem(any(), anyInt(), any(), any())).thenReturn(true);
		when(legacy.inspectExactCandidate(any(), any())).thenReturn(new LawMissingEmbeddingRepairService.ExactInspection(
			runtime("a"), LawMissingEmbeddingRepairService.RepairState.REJECTED_CLASSIFICATION_DRIFT, 11L));
		LawMissingEmbeddingRepairOperationService service = stepService(operations, legacy, persistence);

		service.step(UUID.fromString(OPERATION_ID));

		verify(legacy, never()).repairExact(any(), any());
		verify(persistence).failClaimedItem(any(), anyInt(), any(), org.mockito.ArgumentMatchers.eq("RECOVERY_AMBIGUOUS"));
	}

	@Test
	void registersAReadyOperationWithCanonicalHashAndOrderedItemsWithoutIndexing() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		when(operations.insertOperation(any())).thenReturn(1);
		when(operations.insertItems(any(), any())).thenReturn(1);
		LawMissingEmbeddingRepairOperationService service = service(operations, indexer);
		LawMissingEmbeddingRepairOperationService.RepairRequest request = request(
			List.of(11L), List.of(candidate(101L, "a"))
		);

		LawMissingEmbeddingRepairOperationService.OperationView view = service.register(request);

		assertThat(view.operation().request().target()).isEqualTo("law");
		assertThat(view.operation().request().requestHash()).isEqualTo(view.operation().request().idempotencyKey());
		assertThat(view.operation().request().normalizedRequest()).contains("candidate[0]=101:");
		assertThat(view.items()).extracting(LawMissingEmbeddingRepairOperation.Item::ordinal).containsExactly(0);
		assertThat(view.items()).extracting(LawMissingEmbeddingRepairOperation.Item::state)
			.containsOnly(LawMissingEmbeddingRepairOperation.ItemState.READY);
		verify(operations).insertOperation(any());
		verify(operations).insertItems(any(), any());
		verify(indexer, never()).indexExactChunks(any());
	}

	@Test
	void equivalentJsonFieldFormattingProducesTheSameRegistrationIdentity() {
		LawMissingEmbeddingRepairOperationService service = service(mock(LawMissingEmbeddingRepairOperationMapper.class), mock(LawSemanticIndexService.class));

		String first = service.canonicalNormalizedRequest(request(List.of(12L, 11L), List.of(candidate(101L, "a"), candidate(102L, "b"))));
		String second = service.canonicalNormalizedRequest(new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "00000000-0000-0000-0000-000000000001", "A".repeat(64), true,
			List.of(12L, 11L), List.of(candidate(101L, "A"), candidate(102L, "B"))
		));

		assertThat(first).isEqualTo(second);
		assertThat(service.sha256(first)).isEqualTo(service.sha256(second));
	}

	@Test
	void candidateOrderAndEveryFenceValueChangeTheCanonicalHash() {
		LawMissingEmbeddingRepairOperationService service = service(mock(LawMissingEmbeddingRepairOperationMapper.class), mock(LawSemanticIndexService.class));
		LawMissingEmbeddingRepairOperationService.RepairRequest baseline = request(
			List.of(11L), List.of(candidate(101L, "a"), candidate(102L, "b"))
		);

		assertThat(service.sha256(service.canonicalNormalizedRequest(baseline)))
			.isNotEqualTo(service.sha256(service.canonicalNormalizedRequest(request(List.of(11L), List.of(candidate(102L, "b"), candidate(101L, "a"))))));
		assertThat(service.sha256(service.canonicalNormalizedRequest(baseline)))
			.isNotEqualTo(service.sha256(service.canonicalNormalizedRequest(new LawMissingEmbeddingRepairOperationService.RepairRequest(
				"law", "00000000-0000-0000-0000-000000000002", "a".repeat(64), true,
				List.of(11L), List.of(candidate(101L, "a"), candidate(102L, "b"))
			))));
	}

	@Test
	void reusesOnlyAnIdenticalPersistedOperationWithoutDuplicatingItems() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationService service = service(operations, mock(LawSemanticIndexService.class));
		LawMissingEmbeddingRepairOperationService.RepairRequest request = request(List.of(11L), List.of(candidate(101L, "a")));
		String normalized = service.canonicalNormalizedRequest(request);
		String hash = service.sha256(normalized);
		LawMissingEmbeddingRepairOperation.OperationRow persisted = row("00000000-0000-0000-0000-000000000010", hash, normalized, 1, 1);
		when(operations.findOperationByIdempotencyKey(hash)).thenReturn(persisted);
		when(operations.findItemsByOperationId(persisted.operationId())).thenReturn(List.of(item(persisted.operationId(), 0, 101L, 11L, "a")));

		LawMissingEmbeddingRepairOperationService.OperationView view = service.register(request);

		assertThat(view.operation().request().operationId()).isEqualTo(persisted.operationId());
		verify(operations, never()).insertOperation(any());
		verify(operations, never()).insertItems(any(), any());
	}

	@Test
	void reusesAnIdenticalOperationAfterItsMutableTrustedRevisionHasAdvanced() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationService service = service(operations, mock(LawSemanticIndexService.class));
		LawMissingEmbeddingRepairOperationService.RepairRequest request = request(List.of(11L), List.of(candidate(101L, "a")));
		String normalized = service.canonicalNormalizedRequest(request);
		String hash = service.sha256(normalized);
		LawMissingEmbeddingRepairOperation.OperationRow persisted = row("00000000-0000-0000-0000-000000000012", hash, normalized, 1, 1, "b".repeat(64));
		when(operations.findOperationByIdempotencyKey(hash)).thenReturn(persisted);
		when(operations.findItemsByOperationId(persisted.operationId())).thenReturn(List.of(item(persisted.operationId(), 0, 101L, 11L, "a")));

		LawMissingEmbeddingRepairOperationService.OperationView view = service.register(request);

		assertThat(view.operation().progress().trustedIndexRevision()).isEqualTo("b".repeat(64));
		verify(operations, never()).insertOperation(any());
	}

	@Test
	void rejectsCollisionOrPreflightDriftWithoutCreatingRunnableOperation() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationService service = service(operations, mock(LawSemanticIndexService.class));
		LawMissingEmbeddingRepairOperationService.RepairRequest request = request(List.of(11L), List.of(candidate(101L, "a")));
		String hash = service.sha256(service.canonicalNormalizedRequest(request));
		when(operations.findOperationByIdempotencyKey(hash)).thenReturn(row("00000000-0000-0000-0000-000000000010", hash, "different", 1, 1));

		assertThatThrownBy(() -> service.register(request))
			.isInstanceOf(LawMissingEmbeddingRepairOperationService.RegistrationRejectedException.class);
		verify(operations, never()).insertOperation(any());
		verify(operations, never()).insertItems(any(), any());
	}

	@Test
	void rejectsMalformedOrDriftedRequestsBeforePersisting() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationService service = service(operations, mock(LawSemanticIndexService.class));
		LawMissingEmbeddingRepairOperationService.RepairRequest malformed = new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "not-a-uuid", "a".repeat(63), false, List.of(11L), List.of(candidate(101L, "a"))
		);

		assertThatThrownBy(() -> service.register(malformed))
			.isInstanceOf(LawMissingEmbeddingRepairOperationService.RegistrationRejectedException.class);
		verify(operations, never()).insertOperation(any());
	}

	@Test
	void returnsOrderedOperationOrEmptyForAnUnknownId() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairOperationService service = service(operations, mock(LawSemanticIndexService.class));
		LawMissingEmbeddingRepairOperation.OperationRow persisted = row("00000000-0000-0000-0000-000000000010", "c".repeat(64), "normalized", 2, 1);
		when(operations.findOperationById(persisted.operationId())).thenReturn(persisted);
		when(operations.findItemsByOperationId(persisted.operationId())).thenReturn(List.of(
			item(persisted.operationId(), 0, 101L, 11L, "a"), item(persisted.operationId(), 1, 102L, 11L, "b")
		));

		Optional<LawMissingEmbeddingRepairOperationService.OperationView> found = service.find(java.util.UUID.fromString(persisted.operationId()));

		assertThat(found).isPresent();
		assertThat(found.orElseThrow().items()).extracting(LawMissingEmbeddingRepairOperation.Item::ordinal).containsExactly(0, 1);
		assertThat(service.find(java.util.UUID.fromString("00000000-0000-0000-0000-000000000011"))).isEmpty();
	}

	@Test
	void duplicateInsertReadsTheCommittedWinnerWithTheCurrentReadMapper() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		when(operations.insertOperation(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("duplicate"));
		LawMissingEmbeddingRepairOperationService service = service(operations, indexer);
		LawMissingEmbeddingRepairOperationService.RepairRequest request = request(List.of(11L), List.of(candidate(101L, "a")));
		String normalized = service.canonicalNormalizedRequest(request);
		String hash = service.sha256(normalized);
		LawMissingEmbeddingRepairOperation.OperationRow winner = row("00000000-0000-0000-0000-000000000021", hash, normalized, 1, 1);
		when(operations.findOperationByIdempotencyKey(hash)).thenReturn(null);
		when(operations.findOperationByIdempotencyKeyForUpdate(hash)).thenReturn(winner);
		when(operations.findItemsByOperationId(winner.operationId())).thenReturn(List.of(item(winner.operationId(), 0, 101L, 11L, "a")));

		LawMissingEmbeddingRepairOperationService.OperationView view = service.register(request);

		assertThat(view.operation().request().operationId()).isEqualTo(winner.operationId());
		verify(operations).findOperationByIdempotencyKeyForUpdate(hash);
	}

	@ParameterizedTest(name = "rejects independently fenced request {0}")
	@MethodSource("invalidRequests")
	void rejectsEachInvalidRequestBeforePreflightOrPersistence(String ignored, LawMissingEmbeddingRepairOperationService.RepairRequest request) {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		LawMissingEmbeddingRepairOperationService service = service(operations, legacy);

		assertThatThrownBy(() -> service.register(request))
			.isInstanceOf(LawMissingEmbeddingRepairOperationService.RegistrationRejectedException.class)
			.extracting(exception -> ((LawMissingEmbeddingRepairOperationService.RegistrationRejectedException) exception).rejection())
			.isEqualTo(LawMissingEmbeddingRepairOperationService.Rejection.BAD_REQUEST);
		verifyNoInteractions(legacy);
		verify(operations, never()).insertOperation(any());
		verify(operations, never()).insertItems(any(), any());
	}

	@ParameterizedTest(name = "accepts bounded request {0}")
	@MethodSource("validBoundedRequests")
	void acceptsOneAndMaximumBoundedCandidateAndDocumentWaves(String ignored, LawMissingEmbeddingRepairOperationService.RepairRequest request) {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		when(operations.insertOperation(any())).thenReturn(1);
		when(operations.insertItems(any(), any())).thenAnswer(invocation -> ((List<?>) invocation.getArgument(1)).size());
		LawMissingEmbeddingRepairOperationService service = readyService(operations);

		LawMissingEmbeddingRepairOperationService.OperationView view = service.register(request);

		assertThat(view.items()).hasSize(request.candidates().size());
		verify(operations).insertOperation(any());
	}

	@ParameterizedTest(name = "rejects drift {0} without persistence")
	@MethodSource("driftResults")
	void rejectsRuntimeChunkClassificationAndDocumentSetDriftWithoutPersisting(
		String ignored, LawMissingEmbeddingRepairService.RepairResult drift
	) {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.preflight(any())).thenReturn(drift);
		LawMissingEmbeddingRepairOperationService service = service(operations, legacy);

		assertThatThrownBy(() -> service.register(request(List.of(11L), List.of(candidate(101L, "a")))))
			.isInstanceOf(LawMissingEmbeddingRepairOperationService.RegistrationRejectedException.class)
			.extracting(exception -> ((LawMissingEmbeddingRepairOperationService.RegistrationRejectedException) exception).rejection())
			.isEqualTo(LawMissingEmbeddingRepairOperationService.Rejection.CONFLICT);
		verify(operations, never()).insertOperation(any());
		verify(operations, never()).insertItems(any(), any());
	}

	@Test
	void incompleteItemPersistenceFailsInsteadOfReturningARunnableOperation() {
		LawMissingEmbeddingRepairOperationMapper operations = mock(LawMissingEmbeddingRepairOperationMapper.class);
		when(operations.insertOperation(any())).thenReturn(1);
		when(operations.insertItems(any(), any())).thenReturn(0);
		LawMissingEmbeddingRepairOperationService service = readyService(operations);

		assertThatThrownBy(() -> service.register(request(List.of(11L), List.of(candidate(101L, "a")))))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("complete repair operation");
	}

	private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> invalidRequests() {
		LawMissingEmbeddingRepairOperationService.RepairCandidate candidate = new LawMissingEmbeddingRepairOperationService.RepairCandidate(101L, "a".repeat(64));
		return java.util.stream.Stream.of(
			org.junit.jupiter.params.provider.Arguments.of("wrong target", raw("admrul", true, validRuntime(), validRevision(), List.of(11L), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("apply false", raw("law", false, validRuntime(), validRevision(), List.of(11L), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("zero candidates", raw("law", true, validRuntime(), validRevision(), List.of(11L), List.of())),
			org.junit.jupiter.params.provider.Arguments.of("1001 candidates", raw("law", true, validRuntime(), validRevision(), List.of(11L), candidates(1001))),
			org.junit.jupiter.params.provider.Arguments.of("duplicate candidates", raw("law", true, validRuntime(), validRevision(), List.of(11L), List.of(candidate, candidate))),
			org.junit.jupiter.params.provider.Arguments.of("nonpositive candidate", raw("law", true, validRuntime(), validRevision(), List.of(11L), List.of(new LawMissingEmbeddingRepairOperationService.RepairCandidate(0L, "a".repeat(64))))),
			org.junit.jupiter.params.provider.Arguments.of("bad candidate hash", raw("law", true, validRuntime(), validRevision(), List.of(11L), List.of(new LawMissingEmbeddingRepairOperationService.RepairCandidate(101L, "bad")))),
			org.junit.jupiter.params.provider.Arguments.of("zero documents", raw("law", true, validRuntime(), validRevision(), List.of(), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("51 documents", raw("law", true, validRuntime(), validRevision(), documents(51), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("duplicate documents", raw("law", true, validRuntime(), validRevision(), List.of(11L, 11L), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("nonpositive document", raw("law", true, validRuntime(), validRevision(), List.of(0L), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("invalid runtime", raw("law", true, "invalid", validRevision(), List.of(11L), List.of(candidate))),
			org.junit.jupiter.params.provider.Arguments.of("invalid revision", raw("law", true, validRuntime(), "a".repeat(63), List.of(11L), List.of(candidate)))
		);
	}

	private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> validBoundedRequests() {
		return java.util.stream.Stream.of(
			org.junit.jupiter.params.provider.Arguments.of("one", raw("law", true, validRuntime(), validRevision(), List.of(1L), candidates(1))),
			org.junit.jupiter.params.provider.Arguments.of("1000 candidates and 50 documents", raw("law", true, validRuntime(), validRevision(), documents(50), candidates(1000)))
		);
	}

	private static java.util.stream.Stream<org.junit.jupiter.params.provider.Arguments> driftResults() {
		LawIndexIntegrityRuntimeInfo runtime = new LawIndexIntegrityRuntimeInfo(validRuntime(), validRevision());
		return java.util.stream.Stream.of(
			org.junit.jupiter.params.provider.Arguments.of("runtime", new LawMissingEmbeddingRepairService.RepairResult(false, false, new LawIndexIntegrityRuntimeInfo(UUID.randomUUID().toString(), validRevision()), List.of())),
			org.junit.jupiter.params.provider.Arguments.of("chunk hash", new LawMissingEmbeddingRepairService.RepairResult(false, false, runtime, List.of(outcome(101L, 11L, LawMissingEmbeddingRepairService.RepairState.REJECTED_CHUNK_DRIFT)))),
			org.junit.jupiter.params.provider.Arguments.of("classification", new LawMissingEmbeddingRepairService.RepairResult(false, false, runtime, List.of(outcome(101L, 11L, LawMissingEmbeddingRepairService.RepairState.REJECTED_CLASSIFICATION_DRIFT)))),
			org.junit.jupiter.params.provider.Arguments.of("document set", new LawMissingEmbeddingRepairService.RepairResult(false, false, runtime, List.of(outcome(101L, 12L, LawMissingEmbeddingRepairService.RepairState.READY))))
		);
	}

	private static LawMissingEmbeddingRepairOperationService.RepairRequest raw(String target, boolean apply, String runtime, String revision, List<Long> documents, List<LawMissingEmbeddingRepairOperationService.RepairCandidate> candidates) {
		return new LawMissingEmbeddingRepairOperationService.RepairRequest(target, runtime, revision, apply, documents, candidates);
	}

	private static String validRuntime() {
		return "00000000-0000-0000-0000-000000000001";
	}

	private static String validRevision() {
		return "a".repeat(64);
	}

	private static List<LawMissingEmbeddingRepairOperationService.RepairCandidate> candidates(int count) {
		return java.util.stream.LongStream.rangeClosed(1L, count).mapToObj(id -> new LawMissingEmbeddingRepairOperationService.RepairCandidate(id, (id % 2 == 0 ? "b" : "a").repeat(64))).toList();
	}

	private static List<Long> documents(int count) {
		return java.util.stream.LongStream.rangeClosed(1L, count).boxed().toList();
	}

	private static LawMissingEmbeddingRepairService.RepairOutcome outcome(long chunkId, long documentId, LawMissingEmbeddingRepairService.RepairState state) {
		return new LawMissingEmbeddingRepairService.RepairOutcome(chunkId, documentId, state, state.name());
	}

	private LawMissingEmbeddingRepairOperationService readyService(LawMissingEmbeddingRepairOperationMapper operations) {
		LawMissingEmbeddingRepairService legacy = mock(LawMissingEmbeddingRepairService.class);
		when(legacy.preflight(any())).thenAnswer(invocation -> {
			LawMissingEmbeddingRepairService.RepairRequest request = invocation.getArgument(0);
			return new LawMissingEmbeddingRepairService.RepairResult(false, false, new LawIndexIntegrityRuntimeInfo(validRuntime(), validRevision()), request.candidates().stream()
				.map(candidate -> outcome(candidate.chunkId(), candidate.chunkId() == 101L ? 11L : Math.min(50L, candidate.chunkId()), LawMissingEmbeddingRepairService.RepairState.READY)).toList());
		});
		return service(operations, legacy);
	}

	private LawMissingEmbeddingRepairOperationService service(
		LawMissingEmbeddingRepairOperationMapper operations, LawSemanticIndexService indexer
	) {
		LawChunkMapper chunks = mock(LawChunkMapper.class);
		LawIndexIntegrityService integrity = mock(LawIndexIntegrityService.class);
		LawSemanticChunkRow first = chunk(101L, 11L, "a");
		LawSemanticChunkRow second = chunk(102L, 11L, "b");
		when(chunks.findSemanticChunksByIdsForIndexing(List.of(101L))).thenReturn(List.of(first));
		when(integrity.auditByChunkIds("law", List.of(101L))).thenReturn(new LawIndexIntegrityReport("law", 1, 1, 101L, List.of(
			new LawIndexIntegrityIssue(101L, 11L, LawIndexIntegrityIssue.Cause.MISSING_EMBEDDING_ROW, "a".repeat(64), null, null, null)
		)));
		LawMissingEmbeddingRepairService legacy = new LawMissingEmbeddingRepairService(
			chunks, integrity, indexer, () -> new LawIndexIntegrityRuntimeInfo("00000000-0000-0000-0000-000000000001", "a".repeat(64))
		);
		return service(operations, legacy);
	}

	private LawMissingEmbeddingRepairOperationService service(
		LawMissingEmbeddingRepairOperationMapper operations, LawMissingEmbeddingRepairService legacy
	) {
		return new LawMissingEmbeddingRepairOperationService(
			operations, legacy, new LawMissingEmbeddingRepairOperationPersistenceService(operations)
		);
	}

	private LawMissingEmbeddingRepairOperationService stepService(
		LawMissingEmbeddingRepairOperationMapper operations,
		LawMissingEmbeddingRepairService legacy,
		LawMissingEmbeddingRepairOperationPersistenceService persistence
	) {
		return new LawMissingEmbeddingRepairOperationService(
			operations, legacy, persistence, Clock.fixed(NOW, ZoneOffset.UTC)
		);
	}

	private LawIndexIntegrityRuntimeInfo runtime(String revisionMarker) {
		return new LawIndexIntegrityRuntimeInfo(validRuntime(), revisionMarker.repeat(64));
	}

	private LawMissingEmbeddingRepairService.RepairResult indexedResult(String revisionMarker) {
		return new LawMissingEmbeddingRepairService.RepairResult(true, true, runtime(revisionMarker), List.of(
			new LawMissingEmbeddingRepairService.RepairOutcome(101L, 11L, LawMissingEmbeddingRepairService.RepairState.INDEXED, "indexed")
		));
	}

	private LawMissingEmbeddingRepairOperation.OperationRow operationRow(
		LawMissingEmbeddingRepairOperation.Status status, String revisionMarker, int indexedCount, String owner, String error
	) {
		return new LawMissingEmbeddingRepairOperation.OperationRow(
			OPERATION_ID, "c".repeat(64), "normalized", "c".repeat(64), "law", validRuntime(),
			revisionMarker.repeat(64), status, 2, 1, indexedCount, status == LawMissingEmbeddingRepairOperation.Status.FAILED ? 1 : 0,
			owner, owner == null ? null : NOW.plusSeconds(30), error, NOW, NOW
		);
	}

	private LawMissingEmbeddingRepairOperation.Item stepItem(
		int ordinal, LawMissingEmbeddingRepairOperation.ItemState state, Instant leaseExpiresAt, String detail
	) {
		return new LawMissingEmbeddingRepairOperation.Item(
			OPERATION_ID, ordinal, 101L + ordinal, 11L, (ordinal == 0 ? "a" : "b").repeat(64), state,
			leaseExpiresAt == null ? null : "old", leaseExpiresAt, null, null, detail, NOW, NOW
		);
	}

	private LawMissingEmbeddingRepairOperationService.RepairRequest request(
		List<Long> documents, List<LawMissingEmbeddingRepairOperationService.RepairCandidate> candidates
	) {
		return new LawMissingEmbeddingRepairOperationService.RepairRequest(
			"law", "00000000-0000-0000-0000-000000000001", "a".repeat(64), true, documents, candidates
		);
	}

	private LawMissingEmbeddingRepairOperationService.RepairCandidate candidate(long chunkId, String marker) {
		return new LawMissingEmbeddingRepairOperationService.RepairCandidate(chunkId, marker.repeat(64));
	}

	private LawSemanticChunkRow chunk(long chunkId, long documentId, String marker) {
		return new LawSemanticChunkRow(chunkId, documentId, "law", "law-1", "Title", "", "", "", "CURRENT",
			"Article 1", "Scope", "Text", null, "$.article", "", 0, marker.repeat(64), "Scope", "provision", "PASS");
	}

	private LawMissingEmbeddingRepairOperation.OperationRow row(String operationId, String hash, String normalized, int candidateCount, int documentCount) {
		return row(operationId, hash, normalized, candidateCount, documentCount, "a".repeat(64));
	}

	private LawMissingEmbeddingRepairOperation.OperationRow row(
		String operationId, String hash, String normalized, int candidateCount, int documentCount, String trustedIndexRevision
	) {
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		return new LawMissingEmbeddingRepairOperation.OperationRow(operationId, hash, normalized, hash, "law",
			"00000000-0000-0000-0000-000000000001", trustedIndexRevision, LawMissingEmbeddingRepairOperation.Status.READY,
			candidateCount, documentCount, 0, 0, null, null, null, now, now);
	}

	private LawMissingEmbeddingRepairOperation.Item item(String operationId, int ordinal, long chunkId, long documentId, String marker) {
		Instant now = Instant.parse("2026-08-05T00:00:00Z");
		return new LawMissingEmbeddingRepairOperation.Item(operationId, ordinal, chunkId, documentId, marker.repeat(64),
			LawMissingEmbeddingRepairOperation.ItemState.READY, null, null, null, null, null, now, now);
	}
}
