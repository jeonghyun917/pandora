package com.kaces.pandora.semantic.integrity;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Durable state for one bounded, exact missing-embedding repair request.
 *
 * <p>The request never changes after registration. Progress is stored separately so callers cannot
 * accidentally use a mutable counter or lease as part of the idempotency identity.</p>
 */
public record LawMissingEmbeddingRepairOperation(Request request, Progress progress) {

	public LawMissingEmbeddingRepairOperation {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(progress, "progress");
	}

	public enum Status {
		READY, RUNNING, INDEXING_COMPLETE, FAILED
	}

	public enum ItemState {
		READY, PROCESSING, INDEXED, FAILED, NOT_ATTEMPTED
	}

	public record Request(
		String operationId,
		String idempotencyKey,
		String normalizedRequest,
		String requestHash,
		String target,
		String runtimeInstanceId,
		int candidateCount,
		int documentCount,
		Instant createdAt
	) {
	}

	public record Progress(
		String trustedIndexRevision,
		Status status,
		int indexedCount,
		int failedCount,
		String leaseOwner,
		Instant leaseExpiresAt,
		String lastError,
		Instant updatedAt
	) {
	}

	/** Flat mapper projection; {@link #toOperation()} restores immutable request and mutable progress groups. */
	public record OperationRow(
		String operationId,
		String idempotencyKey,
		String normalizedRequest,
		String requestHash,
		String target,
		String runtimeInstanceId,
		String trustedIndexRevision,
		Status status,
		int candidateCount,
		int documentCount,
		int indexedCount,
		int failedCount,
		String leaseOwner,
		Instant leaseExpiresAt,
		String lastError,
		Instant createdAt,
		Instant updatedAt
	) {
		public LawMissingEmbeddingRepairOperation toOperation() {
			return new LawMissingEmbeddingRepairOperation(
				new Request(operationId, idempotencyKey, normalizedRequest, requestHash, target, runtimeInstanceId,
					candidateCount, documentCount, createdAt),
				new Progress(trustedIndexRevision, status, indexedCount, failedCount, leaseOwner, leaseExpiresAt, lastError, updatedAt)
			);
		}
	}

	public record Item(
		String operationId,
		int ordinal,
		long chunkId,
		long documentId,
		String expectedContentHash,
		ItemState state,
		String leaseOwner,
		Instant leaseExpiresAt,
		String beforeIndexRevision,
		String afterIndexRevision,
		String detail,
		Instant createdAt,
		Instant updatedAt
	) {
	}

	public static List<Item> immutableItems(List<Item> items) {
		return List.copyOf(items);
	}
}
