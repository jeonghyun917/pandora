package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
class LawChunkActivationSaga {
	private final LawChunkMapper mapper;
	private final QdrantClient qdrant;
	private final LawAiProperties properties;
	private final LawActivationTransactionExecutor transactions;

	LawChunkActivationSaga(LawChunkMapper mapper, QdrantClient qdrant, LawAiProperties properties, LawActivationTransactionExecutor transactions) {
		this.mapper = mapper;
		this.qdrant = qdrant;
		this.properties = properties;
		this.transactions = transactions;
	}

	ChunkActivationResult activate(long documentId, int candidateVersion) {
		ActivationContext context = transactions.inTransaction(() -> prepare(documentId, candidateVersion, UUID.randomUUID().toString()));
		if (context.blockedReason() != null) return ChunkActivationResult.blocked(documentId, candidateVersion, context.blockedReason());
		if (context.cleanupOnly()) return finalizeCleanup(context);
		try {
			qdrant.promoteLawCandidatePoints(context.candidateIds());
			qdrant.markLawPointsActive(context.candidateIds());
			if (!qdrant.findLawPointIdsWithActivationStatus(context.candidateIds(), "ACTIVE").containsAll(context.candidateIds())) {
				compensate(context);
				return ChunkActivationResult.blocked(documentId, candidateVersion, "Candidate Qdrant activation verification failed; retry is safe.");
			}
		} catch (RuntimeException exception) {
			compensate(context);
			return ChunkActivationResult.blocked(documentId, candidateVersion, "Candidate Qdrant activation failed; retry is safe.");
		}
		try {
			transactions.inTransaction(() -> { flip(context); return null; });
		} catch (RuntimeException exception) {
			compensate(context);
			return ChunkActivationResult.blocked(documentId, candidateVersion, "Database activation failed; recovery remains ACTIVATING.");
		}
		return finalizeCleanup(context);
	}

	private ActivationContext prepare(long documentId, int candidateVersion, String owner) {
		String status = mapper.findChunkVersionStatus(documentId, candidateVersion);
		if (!Set.of("CANDIDATE", "ACTIVE_CLEANUP_PENDING").contains(status)) {
			return ActivationContext.blocked("Candidate version is not activatable.");
		}
		List<Long> candidateIds = mapper.findChunkIdsByDocumentIdAndVersion(documentId, candidateVersion);
		List<Long> retiredIds = mapper.findActiveChunkIdsByDocumentId(documentId).stream().filter(id -> !candidateIds.contains(id)).toList();
		if (status.equals("ACTIVE_CLEANUP_PENDING")) return new ActivationContext(documentId, candidateVersion, candidateIds, retiredIds, null, true, owner);
		LawChunkVersionVerification verification = mapper.findChunkVersionVerification(documentId, candidateVersion, properties.openai().embeddingModel(), properties.qdrant().collection());
		if (verification == null || !verification.databaseGatesPass() || candidateIds.isEmpty()
			|| !qdrant.findExistingLawCandidatePointIds(candidateIds).containsAll(candidateIds)) {
			return ActivationContext.blocked("Candidate database or staging verification failed.");
		}
		if (mapper.claimCandidateActivation(documentId, candidateVersion, owner) != 1) return ActivationContext.blocked("Candidate activation is owned by another request.");
		return new ActivationContext(documentId, candidateVersion, candidateIds, retiredIds, null, false, owner);
	}

	private void flip(ActivationContext context) {
		mapper.retireOtherChunkVersions(context.documentId(), context.candidateVersion());
		mapper.activateChunkVersion(context.documentId(), context.candidateVersion());
		mapper.retireOtherActiveChunkVersionStates(context.documentId(), context.candidateVersion());
		if (mapper.completeCandidateActivation(context.documentId(), context.candidateVersion(), context.owner(), "ACTIVE_CLEANUP_PENDING") != 1) throw new IllegalStateException("Activation ownership changed before database flip.");
	}

	private ChunkActivationResult finalizeCleanup(ActivationContext context) {
		try {
			qdrant.markLawPointsRetired(context.retiredIds());
			qdrant.deleteLawPoints(context.retiredIds());
			transactions.inTransaction(() -> { mapper.updateChunkVersionStatus(context.documentId(), context.candidateVersion(), "ACTIVE"); return null; });
			return new ChunkActivationResult(context.documentId(), context.candidateVersion(), true, "ACTIVATED", context.retiredIds());
		} catch (RuntimeException exception) {
			return new ChunkActivationResult(context.documentId(), context.candidateVersion(), true, "ACTIVATED_CLEANUP_PENDING", context.retiredIds());
		}
	}

	private void compensate(ActivationContext context) {
		try { qdrant.markLawPointsCandidate(context.candidateIds()); } catch (RuntimeException ignored) { }
		try { transactions.inTransaction(() -> mapper.releaseCandidateActivation(context.documentId(), context.candidateVersion(), context.owner())); } catch (RuntimeException ignored) { }
	}

	private record ActivationContext(long documentId, int candidateVersion, List<Long> candidateIds, List<Long> retiredIds, String blockedReason, boolean cleanupOnly, String owner) {
		static ActivationContext blocked(String reason) { return new ActivationContext(0, 0, List.of(), List.of(), reason, false, ""); }
	}
}
