package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.ai.answer.RuntimeConfigurationIdentity;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

/**
 * A document has one durable activation operation. Its owner and phase are the only authority to
 * mutate Qdrant or advance database state, while its point snapshots prevent later reads from
 * broadening cleanup to another version.
 */
@Component
class LawChunkActivationSaga {
	static final String PREPARING = "PREPARING";
	static final String QDRANT_ACTIVATING = "QDRANT_ACTIVATING";
	static final String RECOVERY_REQUIRED = "RECOVERY_REQUIRED";
	static final String DB_ACTIVE_CLEANUP_PENDING = "DB_ACTIVE_CLEANUP_PENDING";
	static final String DONE = "DONE";
	private static final Duration LEASE = Duration.ofMinutes(10);

	private final LawChunkMapper mapper;
	private final QdrantClient qdrant;
	private final LawAiProperties properties;
	private final LawActivationTransactionExecutor transactions;
	private final String runtimeInstanceId;

	@Autowired
	LawChunkActivationSaga(LawChunkMapper mapper, QdrantClient qdrant, LawAiProperties properties, LawActivationTransactionExecutor transactions) {
		this(mapper, qdrant, properties, transactions, RuntimeConfigurationIdentity.instanceId());
	}

	LawChunkActivationSaga(LawChunkMapper mapper, QdrantClient qdrant, LawAiProperties properties, LawActivationTransactionExecutor transactions, String runtimeInstanceId) {
		this.mapper = mapper;
		this.qdrant = qdrant;
		this.properties = properties;
		this.transactions = transactions;
		this.runtimeInstanceId = runtimeInstanceId;
	}

	ChunkActivationResult activate(long documentId, int candidateVersion) {
		ActivationContext context = transactions.inTransaction(() -> prepare(documentId, candidateVersion, UUID.randomUUID().toString(), Instant.now()));
		if (context.blockedReason != null) return ChunkActivationResult.blocked(documentId, candidateVersion, context.blockedReason);
		if (context.cleanupOnly) return finalizeCleanup(context);
		if (context.recoveryRequired) return recoverPreFlip(context);
		if (!promoteAndVerify(context)) return failBeforeDatabaseFlip(context, "Candidate Qdrant activation failed; retry is safe.");
		try {
			transactions.inTransaction(() -> {
				flip(context);
				return null;
			});
		} catch (RuntimeException exception) {
			return failBeforeDatabaseFlip(context, "Database activation was not confirmed; retry is safe.");
		}
		return finalizeCleanup(context);
	}

	private ActivationContext prepare(long documentId, int candidateVersion, String owner, Instant now) {
		DocumentActivationOperation existing = mapper.findActivationOperation(documentId);
		if (existing == null || DONE.equals(existing.phase())) return claimNewOperation(documentId, candidateVersion, owner, now, existing != null);
		if (!existing.leaseExpired(now)) return ActivationContext.blocked("Document activation is owned by another request.");
		if (QDRANT_ACTIVATING.equals(existing.phase()) && sameOrUnknownRuntime(existing.runtimeInstanceId())) {
			return ActivationContext.blocked("Expired Qdrant activation belongs to this runtime and may still resume.");
		}

		String reclaimedPhase = DB_ACTIVE_CLEANUP_PENDING.equals(existing.phase()) ? DB_ACTIVE_CLEANUP_PENDING : RECOVERY_REQUIRED;
		if (mapper.reclaimActivationOperation(documentId, owner, runtimeInstanceId, now.plus(LEASE), existing.phase(), reclaimedPhase, existing.lastError()) != 1) {
			return ActivationContext.blocked("Document activation ownership changed before reclaim.");
		}
		return fromOperation(existing, owner, reclaimedPhase);
	}

	private ActivationContext claimNewOperation(long documentId, int candidateVersion, String owner, Instant now, boolean replaceDone) {
		if (!"CANDIDATE".equals(mapper.findChunkVersionStatus(documentId, candidateVersion))) {
			return ActivationContext.blocked("Candidate version is not activatable.");
		}
		List<Long> candidateIds = mapper.findChunkIdsByDocumentIdAndVersion(documentId, candidateVersion);
		LawChunkVersionVerification verification = mapper.findChunkVersionVerification(
			documentId, candidateVersion, properties.openai().embeddingModel(), properties.qdrant().collection());
		if (verification == null || !verification.databaseGatesPass() || candidateIds.isEmpty()
			|| !qdrant.findExistingLawCandidatePointIds(candidateIds).containsAll(candidateIds)) {
			return ActivationContext.blocked("Candidate database or staging verification failed.");
		}

		List<Long> priorIds = mapper.findActiveChunkIdsByDocumentId(documentId);
		int priorVersion = mapper.findActiveChunkVersion(documentId);
		DocumentActivationOperation operation = new DocumentActivationOperation(
			documentId, candidateVersion, owner, runtimeInstanceId, now.plus(LEASE), PREPARING, priorVersion,
			encodeIds(priorIds), encodeIds(candidateIds), null);
		int claimed;
		try {
			claimed = replaceDone ? mapper.replaceCompletedActivationOperation(operation) : mapper.insertActivationOperation(operation);
		} catch (DuplicateKeyException exception) {
			return ActivationContext.blocked("Document activation is owned by another request.");
		}
		if (claimed != 1) return ActivationContext.blocked("Document activation is owned by another request.");
		if (mapper.markCandidateActivatingForOperation(documentId, candidateVersion, owner) != 1
			|| mapper.transitionActivationOperation(documentId, owner, PREPARING, QDRANT_ACTIVATING, null) != 1) {
			throw new IllegalStateException("Activation operation was not atomically prepared.");
		}
		return new ActivationContext(documentId, candidateVersion, priorVersion, candidateIds, priorIds, owner, QDRANT_ACTIVATING, false, false, null);
	}

	private ActivationContext fromOperation(DocumentActivationOperation operation, String owner, String phase) {
		return new ActivationContext(operation.documentId(), operation.candidateVersion(), operation.priorActiveVersion(),
			decodeIds(operation.candidatePointIdsJson()), decodeIds(operation.priorPointIdsJson()), owner, phase,
			DB_ACTIVE_CLEANUP_PENDING.equals(phase), RECOVERY_REQUIRED.equals(phase), null);
	}

	private boolean promoteAndVerify(ActivationContext context) {
		try {
			if (!renewLease(context, QDRANT_ACTIVATING)) return false;
			qdrant.promoteLawCandidatePoints(context.candidateIds);
			qdrant.markLawPointsActive(context.candidateIds);
			return qdrant.findLawPointIdsWithActivationStatus(context.candidateIds, "ACTIVE").containsAll(context.candidateIds);
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private void flip(ActivationContext context) {
		if (!renewLease(context, QDRANT_ACTIVATING)) throw new IllegalStateException("Activation ownership changed before database flip.");
		if (!context.priorIds.isEmpty() && mapper.retireChunkIdsForOperation(context.documentId, context.priorIds, context.owner) != context.priorIds.size()) {
			throw new IllegalStateException("Prior chunk retirement was rejected.");
		}
		if (mapper.activateCandidateChunksForOperation(context.documentId, context.candidateVersion, context.owner) != context.candidateIds.size()
			|| mapper.markCandidateVersionCleanupPendingForOperation(context.documentId, context.candidateVersion, context.owner) != 1
			|| (context.priorVersion > 0 && mapper.retirePriorVersionForOperation(context.documentId, context.priorVersion, context.owner) != 1)
			|| mapper.transitionActivationOperation(context.documentId, context.owner, QDRANT_ACTIVATING, DB_ACTIVE_CLEANUP_PENDING, null) != 1) {
			throw new IllegalStateException("Activation ownership changed before database flip.");
		}
	}

	private ChunkActivationResult finalizeCleanup(ActivationContext context) {
		if (!renewLease(context, DB_ACTIVE_CLEANUP_PENDING)) {
			return new ChunkActivationResult(context.documentId, context.candidateVersion, true, "ACTIVATED_CLEANUP_PENDING", context.priorIds);
		}
		try {
			qdrant.markLawPointsRetired(context.priorIds);
			qdrant.deleteLawPoints(context.priorIds);
			transactions.inTransaction(() -> {
				if (mapper.completeCandidateCleanupForOperation(context.documentId, context.candidateVersion, context.owner) != 1
					|| mapper.transitionActivationOperation(context.documentId, context.owner, DB_ACTIVE_CLEANUP_PENDING, DONE, null) != 1) {
					throw new IllegalStateException("Cleanup ownership changed.");
				}
				return null;
			});
			return new ChunkActivationResult(context.documentId, context.candidateVersion, true, "ACTIVATED", context.priorIds);
		} catch (RuntimeException exception) {
			return new ChunkActivationResult(context.documentId, context.candidateVersion, true, "ACTIVATED_CLEANUP_PENDING", context.priorIds);
		}
	}

	private ChunkActivationResult recoverPreFlip(ActivationContext context) {
		if (!demoteAndRelease(context, RECOVERY_REQUIRED)) {
			return ChunkActivationResult.blocked(context.documentId, context.candidateVersion, "Candidate recovery is ambiguous; operation remains RECOVERY_REQUIRED.");
		}
		return ChunkActivationResult.blocked(context.documentId, context.candidateVersion, "Expired pre-flip activation was recovered; retry is safe.");
	}

	private ChunkActivationResult failBeforeDatabaseFlip(ActivationContext context, String reason) {
		if (!demoteAndRelease(context, QDRANT_ACTIVATING)) {
			return ChunkActivationResult.blocked(context.documentId, context.candidateVersion, "Candidate demotion was not verified; operation remains RECOVERY_REQUIRED.");
		}
		return ChunkActivationResult.blocked(context.documentId, context.candidateVersion, reason);
	}

	private boolean demoteAndRelease(ActivationContext context, String expectedPhase) {
		try {
			if (!renewLease(context, expectedPhase)) return false;
			qdrant.markLawPointsCandidate(context.candidateIds);
			if (!qdrant.findLawPointIdsWithActivationStatus(context.candidateIds, "CANDIDATE").containsAll(context.candidateIds)) {
				throw new IllegalStateException("Candidate demotion could not be verified.");
			}
			transactions.inTransaction(() -> {
				if (mapper.resetCandidateForOperation(context.documentId, context.candidateVersion, context.owner, expectedPhase) != 1
					|| mapper.transitionActivationOperation(context.documentId, context.owner, expectedPhase, DONE, null) != 1) {
					throw new IllegalStateException("Activation ownership changed during candidate demotion.");
				}
				return null;
			});
			return true;
		} catch (RuntimeException exception) {
			markRecoveryRequired(context, expectedPhase, "Candidate demotion failed: " + exception.getMessage());
			return false;
		}
	}

	private boolean renewLease(ActivationContext context, String expectedPhase) {
		return mapper.renewActivationOperationLease(context.documentId, context.owner, expectedPhase, Instant.now().plus(LEASE)) == 1;
	}

	private boolean sameOrUnknownRuntime(String operationRuntimeInstanceId) {
		return operationRuntimeInstanceId == null || operationRuntimeInstanceId.isBlank()
			|| runtimeInstanceId.equals(operationRuntimeInstanceId);
	}

	private void markRecoveryRequired(ActivationContext context, String expectedPhase, String error) {
		try {
			transactions.inTransaction(() -> mapper.transitionActivationOperation(context.documentId, context.owner, expectedPhase, RECOVERY_REQUIRED, error));
		} catch (RuntimeException ignored) {
			// The operation remains owned until its lease expires; never release an ambiguous candidate.
		}
	}

	private static String encodeIds(List<Long> ids) {
		return "[" + ids.stream().map(String::valueOf).reduce((left, right) -> left + "," + right).orElse("") + "]";
	}

	private static List<Long> decodeIds(String json) {
		if (json == null || json.length() < 3) return List.of();
		List<Long> ids = new ArrayList<>();
		for (String part : json.substring(1, json.length() - 1).split(",")) {
			if (!part.isBlank()) ids.add(Long.parseLong(part.trim()));
		}
		return List.copyOf(ids);
	}

	private record ActivationContext(long documentId, int candidateVersion, int priorVersion, List<Long> candidateIds, List<Long> priorIds,
		String owner, String phase, boolean cleanupOnly, boolean recoveryRequired, String blockedReason) {
		static ActivationContext blocked(String reason) {
			return new ActivationContext(0, 0, 0, List.of(), List.of(), "", "", false, false, reason);
		}
	}
}
