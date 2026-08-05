package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class LawMissingEmbeddingRepairOperationServiceTests {

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
		return new LawMissingEmbeddingRepairOperationService(operations, legacy);
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
