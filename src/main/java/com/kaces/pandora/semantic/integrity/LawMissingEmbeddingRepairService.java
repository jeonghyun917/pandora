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
	private static final int MAX_CANDIDATES = 1_000;
	private static final int MAX_DOCUMENTS = 50;

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
		List<RepairCandidate> candidates = request == null || request.candidates() == null ? List.of() : request.candidates();
		if (!matchesExpectedRuntime(request, currentRuntime())) {
			return rejected(request, candidates, RepairState.REJECTED_RUNTIME_FENCE, "Runtime instance or index revision changed.");
		}
		if (!isValidRequest(request, candidates)) {
			return rejected(request, candidates, RepairState.REJECTED_REQUEST, "The repair request was invalid or exceeded its bound.");
		}
		List<Long> chunkIds = candidates.stream().map(RepairCandidate::chunkId).toList();
		Map<Long, LawSemanticChunkRow> chunksById = new LinkedHashMap<>();
		for (LawSemanticChunkRow chunk : lawChunkMapper.findSemanticChunksByIdsForIndexing(chunkIds)) {
			chunksById.put(chunk.chunkId(), chunk);
		}
		Map<Long, LawIndexIntegrityIssue> issuesById = new LinkedHashMap<>();
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
		if (!matchesExpectedRuntime(request, currentRuntime())) {
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
			LawIndexIntegrityRuntimeInfo runtime = currentRuntime();
			if (!sameInstance(request.expectedRuntimeInstanceId(), runtime)) {
				applied.add(new RepairOutcome(candidate.chunkId(), chunk.documentId(), RepairState.REJECTED_RUNTIME_FENCE, "Runtime instance changed during repair."));
				for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
					RepairCandidate pending = candidates.get(remaining);
					applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(), RepairState.NOT_ATTEMPTED, "Not attempted after runtime drift."));
				}
				return new RepairResult(true, false, runtime, List.copyOf(applied));
			}
			try {
				indexService.indexExactChunks(List.of(chunk));
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
			} catch (RuntimeException exception) {
				applied.add(new RepairOutcome(candidate.chunkId(), chunk.documentId(), RepairState.INDEX_FAILED, "Direct indexing failed."));
				for (int remaining = index + 1; remaining < candidates.size(); remaining++) {
					RepairCandidate pending = candidates.get(remaining);
					applied.add(new RepairOutcome(pending.chunkId(), chunksById.get(pending.chunkId()).documentId(), RepairState.NOT_ATTEMPTED, "Not attempted after indexing failure."));
				}
				return new RepairResult(true, false, currentRuntime(), List.copyOf(applied));
			}
		}
		LawIndexIntegrityRuntimeInfo runtimeAfter = currentRuntime();
		return new RepairResult(true, sameInstance(request.expectedRuntimeInstanceId(), runtimeAfter), runtimeAfter, List.copyOf(applied));
	}

	private boolean isValidRequest(RepairRequest request, List<RepairCandidate> candidates) {
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

	private boolean isHash(String value) {
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
}
