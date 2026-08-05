package com.kaces.pandora.semantic.integrity;

import java.time.Instant;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** SQL compare-and-set boundary for durable missing-embedding repair operations. */
@Mapper
public interface LawMissingEmbeddingRepairOperationMapper {

	int insertOperation(@Param("operation") LawMissingEmbeddingRepairOperation.OperationRow operation);

	int insertItems(
		@Param("operationId") String operationId,
		@Param("items") List<LawMissingEmbeddingRepairOperation.Item> items
	);

	LawMissingEmbeddingRepairOperation.OperationRow findOperationById(@Param("operationId") String operationId);

	LawMissingEmbeddingRepairOperation.OperationRow findOperationByIdempotencyKey(@Param("idempotencyKey") String idempotencyKey);

	List<LawMissingEmbeddingRepairOperation.Item> findItemsByOperationId(@Param("operationId") String operationId);

	LawMissingEmbeddingRepairOperation.Item findItemByOrdinal(
		@Param("operationId") String operationId,
		@Param("ordinal") int ordinal
	);

	int claimReadyItem(
		@Param("operationId") String operationId,
		@Param("ordinal") int ordinal,
		@Param("owner") String owner,
		@Param("runtimeInstanceId") String runtimeInstanceId,
		@Param("trustedIndexRevision") String trustedIndexRevision,
		@Param("leaseExpiresAt") Instant leaseExpiresAt
	);

	int claimExpiredItem(
		@Param("operationId") String operationId,
		@Param("ordinal") int ordinal,
		@Param("owner") String owner,
		@Param("runtimeInstanceId") String runtimeInstanceId,
		@Param("trustedIndexRevision") String trustedIndexRevision,
		@Param("leaseExpiresAt") Instant leaseExpiresAt
	);

	int renewItemLease(
		@Param("operationId") String operationId,
		@Param("ordinal") int ordinal,
		@Param("owner") String owner,
		@Param("leaseExpiresAt") Instant leaseExpiresAt
	);

	int completeClaimedItemAndAdvanceRevision(
		@Param("operationId") String operationId,
		@Param("ordinal") int ordinal,
		@Param("owner") String owner,
		@Param("runtimeInstanceId") String runtimeInstanceId,
		@Param("trustedIndexRevision") String trustedIndexRevision,
		@Param("afterIndexRevision") String afterIndexRevision,
		@Param("detail") String detail
	);

	int failClaimedItemAndOperation(
		@Param("operationId") String operationId,
		@Param("ordinal") int ordinal,
		@Param("owner") String owner,
		@Param("lastError") String lastError,
		@Param("detail") String detail
	);

	int markReadyItemsNotAttempted(@Param("operationId") String operationId);

	int markOperationIndexingComplete(@Param("operationId") String operationId);
}
