package com.kaces.pandora.semantic.integrity;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class LawMissingEmbeddingRepairService {
	static final int MAX_CANDIDATES = 1_000;
	static final int MAX_DOCUMENTS = 50;

	private final LawChunkMapper lawChunkMapper;
	private final LawIndexIntegrityService integrityService;
	private final LawSemanticIndexService indexService;
	private final LawIndexIntegrityRuntimeInfoProvider runtimeInfoProvider;

	public LawMissingEmbeddingRepairService(
		LawChunkMapper lawChunkMapper,
		LawIndexIntegrityService integrityService,
		LawSemanticIndexService indexService,
		LawIndexIntegrityRuntimeInfoProvider runtimeInfoProvider
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.integrityService = integrityService;
		this.indexService = indexService;
		this.runtimeInfoProvider = runtimeInfoProvider;
	}

	public RepairResult repair(RepairRequest request) {
		return repair(request, () -> { });
	}

	private RepairResult repair(RepairRequest request, RepairCheckpoint repairCheckpoint) {
		RepairCheckpoint checkpoint = repairCheckpoint == null ? () -> { } : repairCheckpoint;
		List<RepairCandidate> candidates = request == null || request.candidates() == null ? List.of() : request.candidates();
		checkpoint.verifyOwnership();
		LawIndexIntegrityRuntimeInfo initialRuntime = currentRuntime();
		if (!matchesExpectedRuntime(request, initialRuntime)) {
			return rejected(request, candidates, RepairState.REJECTED_RUNTIME_FENCE, "Runtime instance or index revision changed.");
		}
		if (!isValidRequest(request, candidates)) {
			return rejected(request, candidates, RepairState.REJECTED_REQUEST, "The repair request was invalid or exceeded its bound.");
		}
		List<Long> chunkIds = candidates.stream().map(RepairCandidate::chunkId).toList();
		Map<Long, LawSemanticChunkRow> chunksById = new LinkedHashMap<>();
		checkpoint.verifyOwnership();
		for (LawSemanticChunkRow chunk : lawChunkMapper.findSemanticChunksByIdsForIndexing(chunkIds)) {
			chunksById.put(chunk.chunkId(), chunk);
		}
		Map<Long, LawIndexIntegrityIssue> issuesById = new LinkedHashMap<>();
		checkpoint.verifyOwnership();
		for (LawIndexIntegrityIssue issue : integrityService.auditByChunkIds("law", chunkIds).issues()) {
			issuesById.put(issue.chunkId(), issue);
		}
		List<RepairOutcome> outcomes = new ArrayList<>();
		Set<Long> actualDocumentIds = new LinkedHashSet<>();
		for (RepairCandidate candidate : candidates) {
			LawSemanticChunkRow chunk = chunksById.get(candidate.chunkId());
			LawIndexIntegrityIssue issue = issuesById.get(candidate.chunkId());
			RepairState state = stateBeforeIndex(candidate, chunk, issue);
			if (chunk != null) {
				actualDocumentIds.add(chunk.documentId());
			}
			outcomes.add(new RepairOutcome(candidate.chunkId(), chunk == null ? 0L : chunk.documentId(), state, detail(state)));
		}
		if (!actualDocumentIds.equals(new LinkedHashSet<>(request.expectedDocumentIds())) || actualDocumentIds.size() > MAX_DOCUMENTS) {
			return rejected(request, candidates, RepairState.REJECTED_DOCUMENT_WAVE, "The candidate document set did not match the bounded wave.");
		}
		checkpoint.verifyOwnership();
		LawIndexIntegrityRuntimeInfo trustedRuntime = currentRuntime();
		if (!matchesExpectedRuntime(request, trustedRuntime)) {
			return rejected(request, candidates, RepairState.REJECTED_RUNTIME_FENCE, "Runtime instance or index revision changed during preflight.");
		}
		if (outcomes.stream().anyMatch(outcome -> outcome.state() != RepairState.READY)) {
			return new RepairResult(false, false, currentRuntime(), List.copyOf(outcomes));
		}
		if (!request.apply()) {
			return new RepairResult(false, false, currentRuntime(), List.copyOf(outcomes));
		}

		List<RepairOutcome> applied = new ArrayList<>();
		for (int index = 0; index < candidates.size(); index++) {
			RepairCandidate candidate = candidates.get(index);
			LawSemanticChunkRow chunk = chunksById.get(candidate.chunkId());
			checkpoint.verifyOwnership();
			LawIndexIntegrityRuntimeInfo runtime = currentRuntime();
			if (!sameRuntime(trustedRuntime, runtime)) {
				applied.add(new RepairOutcome(candidate.chunkId(), chunk.documentId(), RepairState.REJECTED_RUNTIME_FENCE, "Runtime instance or index revision changed during repair."));
				for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
					RepairCandidate pending = candidates.get(remaining);
					applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(), RepairState.NOT_ATTEMPTED, "Not attempted after runtime drift."));
				}
				return new RepairResult(true, false, runtime, List.copyOf(applied));
			}
			try {
				LawIndexIntegrityRuntimeInfo trustedBeforeWrite = trustedRuntime;
				indexService.indexExactChunks(List.of(chunk), checkpoint::verifyOwnership, () -> {
					checkpoint.verifyOwnership();
					if (!sameRuntime(trustedBeforeWrite, currentRuntime())) {
						throw new RuntimeFenceChangedException();
					}
				});
				checkpoint.verifyOwnership();
				LawIndexIntegrityReport verified = integrityService.auditByChunkIds("law", List.of(candidate.chunkId()));
				RepairState state = verified.scannedRows() == 1 && verified.issues().isEmpty()
					? RepairState.INDEXED : RepairState.VERIFICATION_FAILED;
				applied.add(new RepairOutcome(candidate.chunkId(), chunk.documentId(), state, detail(state)));
				if (state != RepairState.INDEXED) {
					for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
						RepairCandidate pending = candidates.get(remaining);
						applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(), RepairState.NOT_ATTEMPTED, "Not attempted after verification failure."));
					}
					return new RepairResult(true, false, currentRuntime(), List.copyOf(applied));
				}
				checkpoint.verifyOwnership();
				LawIndexIntegrityRuntimeInfo runtimeAfterVerifiedWrite = currentRuntime();
				if (!sameInstance(trustedRuntime.runtimeInstanceId(), runtimeAfterVerifiedWrite)) {
					for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
						RepairCandidate pending = candidates.get(remaining);
						applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(), RepairState.NOT_ATTEMPTED, "Not attempted after runtime drift."));
					}
					return new RepairResult(true, false, runtimeAfterVerifiedWrite, List.copyOf(applied));
				}
				trustedRuntime = runtimeAfterVerifiedWrite;
			} catch (RuntimeFenceChangedException exception) {
				applied.add(new RepairOutcome(candidate.chunkId(), chunk.documentId(), RepairState.REJECTED_RUNTIME_FENCE,
					"Runtime instance or index revision changed before exact index mutation."));
				for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
					RepairCandidate pending = candidates.get(remaining);
					applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(),
						RepairState.NOT_ATTEMPTED, "Not attempted after runtime drift."));
				}
				return new RepairResult(true, false, currentRuntime(), List.copyOf(applied));
			} catch (RuntimeException exception) {
				applied.add(new RepairOutcome(candidate.chunkId(), chunk.documentId(), RepairState.INDEX_FAILED, "Direct indexing failed."));
				for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
					RepairCandidate pending = candidates.get(remaining);
					applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(), RepairState.NOT_ATTEMPTED, "Not attempted after indexing failure."));
				}
				return new RepairResult(true, false, currentRuntime(), List.copyOf(applied));
			}
		}
		checkpoint.verifyOwnership();
		LawIndexIntegrityRuntimeInfo runtimeAfter = currentRuntime();
		return new RepairResult(true, sameRuntime(trustedRuntime, runtimeAfter), runtimeAfter, List.copyOf(applied));
	}

	/**
	 * Performs the exact bounded repair preflight without indexing. Durable operation registration
	 * uses this method so its active-chunk, hash, document-wave, and classifier checks cannot drift
	 * from the legacy synchronous endpoint.
	 */
	RepairResult preflight(RepairRequest request) {
		if (request == null) {
			return repair(null);
		}
		return repair(new RepairRequest(
			request.target(), request.expectedRuntimeInstanceId(), request.expectedIndexRevision(),
			request.expectedDocumentIds(), request.candidates(), false
		));
	}

	/** Executes the existing repair and post-write exact audit for one durable item only. */
	RepairResult repairExact(RepairRequest request) {
		return repairExact(request, () -> { });
	}

	RepairResult repairExact(RepairRequest request, RepairCheckpoint checkpoint) {
		List<RepairCandidate> candidates = request == null || request.candidates() == null ? List.of() : request.candidates();
		if (request == null || candidates.size() != 1 || request.expectedDocumentIds() == null
			|| request.expectedDocumentIds().size() != 1) {
			return rejected(request, candidates, RepairState.REJECTED_REQUEST, "Exact repair requires one chunk and one document.");
		}
		return repair(request, checkpoint);
	}

	@FunctionalInterface
	interface RepairCheckpoint {
		void verifyOwnership();
	}

	/**
	 * Reclassifies one exact candidate without mutation. Recovery uses this before deciding whether
	 * an expired PROCESSING item was already committed, is still safely retryable, or is ambiguous.
	 */
	ExactInspection inspectExactCandidate(RepairCandidate candidate, Long expectedDocumentId) {
		LawIndexIntegrityRuntimeInfo runtime = currentRuntime();
		if (candidate == null || expectedDocumentId == null || expectedDocumentId <= 0
			|| candidate.chunkId() <= 0 || !isHash(candidate.expectedChunkContentHash())) {
			return new ExactInspection(runtime, RepairState.REJECTED_REQUEST, 0L);
		}
		List<LawSemanticChunkRow> chunks = lawChunkMapper.findSemanticChunksByIdsForIndexing(List.of(candidate.chunkId()));
		LawSemanticChunkRow chunk = chunks.size() == 1 ? chunks.get(0) : null;
		if (chunk == null || chunk.chunkId() != candidate.chunkId() || chunk.documentId() != expectedDocumentId
			|| !"law".equals(chunk.target()) || !candidate.expectedChunkContentHash().equals(chunk.contentHash())) {
			return new ExactInspection(runtime, RepairState.REJECTED_CHUNK_DRIFT, chunk == null ? 0L : chunk.documentId());
		}
		LawIndexIntegrityReport report = integrityService.auditByChunkIds("law", List.of(candidate.chunkId()));
		if (report.scannedRows() == 1 && report.issues().isEmpty()) {
			return new ExactInspection(currentRuntime(), RepairState.INDEXED, chunk.documentId());
		}
		if (report.scannedRows() == 1 && report.issues().size() == 1
			&& stateBeforeIndex(candidate, chunk, report.issues().get(0)) == RepairState.READY) {
			return new ExactInspection(currentRuntime(), RepairState.READY, chunk.documentId());
		}
		return new ExactInspection(currentRuntime(), RepairState.REJECTED_CLASSIFICATION_DRIFT, chunk.documentId());
	}

	LawIndexIntegrityRuntimeInfo currentRuntimeSnapshot() {
		return currentRuntime();
	}

	static boolean isValidRequest(RepairRequest request, List<RepairCandidate> candidates) {
		if (request == null || !"law".equals(request.target()) || candidates.isEmpty() || candidates.size() > MAX_CANDIDATES
			|| request.expectedDocumentIds() == null || request.expectedDocumentIds().isEmpty() || request.expectedDocumentIds().size() > MAX_DOCUMENTS) {
			return false;
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (RepairCandidate candidate : candidates) {
			if (candidate == null || candidate.chunkId() <= 0 || !isHash(candidate.expectedChunkContentHash()) || !ids.add(candidate.chunkId())) {
				return false;
			}
		}
		return request.expectedDocumentIds().stream().allMatch(documentId -> documentId != null && documentId > 0)
			&& new LinkedHashSet<>(request.expectedDocumentIds()).size() == request.expectedDocumentIds().size();
	}

	private RepairState stateBeforeIndex(RepairCandidate candidate, LawSemanticChunkRow chunk, LawIndexIntegrityIssue issue) {
		if (chunk == null || !"law".equals(chunk.target()) || !candidate.expectedChunkContentHash().equals(chunk.contentHash())) {
			return RepairState.REJECTED_CHUNK_DRIFT;
		}
		if (issue == null || issue.cause() != LawIndexIntegrityIssue.Cause.MISSING_EMBEDDING_ROW
			|| !candidate.expectedChunkContentHash().equals(issue.chunkContentHash()) || issue.documentId() != chunk.documentId()) {
			return RepairState.REJECTED_CLASSIFICATION_DRIFT;
		}
		return RepairState.READY;
	}

	private RepairResult rejected(RepairRequest request, List<RepairCandidate> candidates, RepairState state, String detail) {
		return new RepairResult(false, false, currentRuntime(), candidates.stream()
			.map(candidate -> new RepairOutcome(candidate == null ? 0L : candidate.chunkId(), 0L, state, detail)).toList());
	}

	private boolean matchesExpectedRuntime(RepairRequest request, LawIndexIntegrityRuntimeInfo runtime) {
		return request != null && runtime != null && runtime.isComplete()
			&& request.expectedRuntimeInstanceId() != null && !request.expectedRuntimeInstanceId().isBlank()
			&& request.expectedIndexRevision() != null && !request.expectedIndexRevision().isBlank()
			&& request.expectedRuntimeInstanceId().equals(runtime.runtimeInstanceId())
			&& request.expectedIndexRevision().equals(runtime.indexRevision());
	}

	private LawIndexIntegrityRuntimeInfo currentRuntime() {
		LawIndexIntegrityRuntimeInfo runtime = runtimeInfoProvider.current();
		if (runtime == null || !runtime.isComplete()) {
			throw new IllegalStateException("Law missing-embedding repair runtime identity is unavailable.");
		}
		return runtime;
	}

	private boolean sameInstance(String expectedInstanceId, LawIndexIntegrityRuntimeInfo runtime) {
		return runtime != null && runtime.isComplete() && expectedInstanceId.equals(runtime.runtimeInstanceId());
	}

	private boolean sameRuntime(LawIndexIntegrityRuntimeInfo expected, LawIndexIntegrityRuntimeInfo actual) {
		return expected != null && expected.isComplete() && actual != null && actual.isComplete()
			&& expected.runtimeInstanceId().equals(actual.runtimeInstanceId())
			&& expected.indexRevision().equals(actual.indexRevision());
	}

	static boolean isHash(String value) {
		return value != null && value.matches("[0-9a-fA-F]{64}");
	}

	private String detail(RepairState state) {
		return switch (state) {
			case READY -> "Current active chunk is classified MISSING_EMBEDDING_ROW.";
			case INDEXED -> "Embedding row, hash, status, and Qdrant point verified.";
			case VERIFICATION_FAILED -> "Post-index integrity verification failed.";
			default -> state.name();
		};
	}

	public record RepairRequest(
		String target,
		String expectedRuntimeInstanceId,
		String expectedIndexRevision,
		List<Long> expectedDocumentIds,
		List<RepairCandidate> candidates,
		boolean apply
	) {
	}

	public record RepairCandidate(long chunkId, String expectedChunkContentHash) {
	}

	public record RepairOutcome(long chunkId, long documentId, RepairState state, String detail) {
	}

	public record RepairResult(boolean applied, boolean complete, LawIndexIntegrityRuntimeInfo runtime, List<RepairOutcome> outcomes) {
	}

	record ExactInspection(LawIndexIntegrityRuntimeInfo runtime, RepairState state, long documentId) {
	}

	public enum RepairState {
		READY,
		INDEXED,
		REJECTED_RUNTIME_FENCE,
		REJECTED_REQUEST,
		REJECTED_CHUNK_DRIFT,
		REJECTED_CLASSIFICATION_DRIFT,
		REJECTED_DOCUMENT_WAVE,
		INDEX_FAILED,
		VERIFICATION_FAILED,
		NOT_ATTEMPTED
	}

	private static final class RuntimeFenceChangedException extends IllegalStateException {
		private RuntimeFenceChangedException() {
			super("Runtime fence changed.");
		}
	}
}
