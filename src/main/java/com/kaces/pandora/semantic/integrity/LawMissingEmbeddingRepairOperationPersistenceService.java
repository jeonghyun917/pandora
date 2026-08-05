package com.kaces.pandora.semantic.integrity;

import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short transaction boundary for durable operation writes and post-rollback winner recovery. */
@Service
public class LawMissingEmbeddingRepairOperationPersistenceService {
	private final LawMissingEmbeddingRepairOperationMapper operationMapper;

	public LawMissingEmbeddingRepairOperationPersistenceService(LawMissingEmbeddingRepairOperationMapper operationMapper) {
		this.operationMapper = operationMapper;
	}

	@Transactional
	public void persist(LawMissingEmbeddingRepairOperation.OperationRow operation, List<LawMissingEmbeddingRepairOperation.Item> items) {
		if (operationMapper.insertOperation(operation) != 1 || operationMapper.insertItems(operation.operationId(), items) != items.size()) {
			throw new IllegalStateException("Unable to persist complete repair operation.");
		}
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
	public LawMissingEmbeddingRepairOperation.OperationRow findCommittedWinner(String requestHash) {
		return operationMapper.findOperationByIdempotencyKeyForUpdate(requestHash);
	}

	@Transactional
	public boolean claimReadyItem(
		String operationId, int ordinal, String owner, String runtimeInstanceId,
		String trustedIndexRevision, Instant leaseExpiresAt
	) {
		return operationMapper.claimReadyItem(operationId, ordinal, owner, runtimeInstanceId,
			trustedIndexRevision, leaseExpiresAt) > 0;
	}

	@Transactional
	public boolean claimExpiredItem(
		String operationId, int ordinal, String owner, String runtimeInstanceId,
		String trustedIndexRevision, Instant leaseExpiresAt
	) {
		return operationMapper.claimExpiredItem(operationId, ordinal, owner, runtimeInstanceId,
			trustedIndexRevision, leaseExpiresAt) > 0;
	}

	@Transactional
	public boolean completeClaimedItem(
		String operationId, int ordinal, String owner, String runtimeInstanceId,
		String trustedIndexRevision, String afterIndexRevision
	) {
		if (operationMapper.completeClaimedItemAndAdvanceRevision(
			operationId, ordinal, owner, runtimeInstanceId, trustedIndexRevision,
			afterIndexRevision, "INDEXED"
		) <= 0) {
			return false;
		}
		operationMapper.markOperationIndexingComplete(operationId);
		return true;
	}

	@Transactional
	public boolean failClaimedItem(String operationId, int ordinal, String owner, String reason) {
		if (operationMapper.failClaimedItemAndOperation(operationId, ordinal, owner, reason, reason) <= 0) {
			return false;
		}
		operationMapper.markReadyItemsNotAttempted(operationId);
		return true;
	}
}
