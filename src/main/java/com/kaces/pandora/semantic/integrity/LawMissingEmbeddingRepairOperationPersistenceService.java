package com.kaces.pandora.semantic.integrity;

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
}
