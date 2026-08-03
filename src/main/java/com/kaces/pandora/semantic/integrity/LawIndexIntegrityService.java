package com.kaces.pandora.semantic.integrity;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class LawIndexIntegrityService {
	private static final int MAX_AUDIT_LIMIT = 10_000;
	private static final int POINT_LOOKUP_BATCH_SIZE = 256;

	private final LawChunkMapper lawChunkMapper;
	private final PointLookup pointLookup;
	private final String model;
	private final String vectorStore;

	@Autowired
	public LawIndexIntegrityService(
		LawChunkMapper lawChunkMapper,
		QdrantClient qdrantClient,
		LawAiProperties properties
	) {
		this(lawChunkMapper, ids -> qdrantClient.findExistingLawPointIds(new ArrayList<>(ids)),
			properties.openai().embeddingModel(), properties.qdrant().collection());
	}

	LawIndexIntegrityService(LawChunkMapper lawChunkMapper, PointLookup pointLookup) {
		this(lawChunkMapper, pointLookup, "text-embedding-3-small", "law_chunks");
	}

	private LawIndexIntegrityService(
		LawChunkMapper lawChunkMapper,
		PointLookup pointLookup,
		String model,
		String vectorStore
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.pointLookup = pointLookup;
		this.model = model;
		this.vectorStore = vectorStore;
	}

	public LawIndexIntegrityReport audit(String target, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_AUDIT_LIMIT));
		List<LawIndexIntegrityRow> rows = lawChunkMapper.findLawIndexIntegrityRows(
			normalizeTarget(target), model, vectorStore, safeLimit
		);
		Set<Long> existingPointIds = findExistingPointIds(rows);
		List<LawIndexIntegrityIssue> issues = new ArrayList<>();
		for (LawIndexIntegrityRow row : rows) {
			classify(row, existingPointIds).ifPresent(issues::add);
		}
		return new LawIndexIntegrityReport(normalizeTarget(target), safeLimit, issues);
	}

	public RepairPreview previewRepair(String target, LawIndexIntegrityIssue.Cause cause, List<RepairCandidate> candidates) {
		if (cause == null) {
			throw new IllegalArgumentException("A repair cause is required.");
		}
		LawIndexIntegrityReport current = audit(target, MAX_AUDIT_LIMIT);
		Set<RepairCandidate> requested = candidates == null ? Set.of() : Set.copyOf(candidates);
		List<Long> accepted = new ArrayList<>();
		List<Long> rejected = new ArrayList<>();
		for (RepairCandidate candidate : requested) {
			boolean matchesCurrentAudit = current.issues().stream().anyMatch(issue ->
				issue.chunkId() == candidate.chunkId()
					&& issue.cause() == cause
					&& equalsHash(issue.chunkContentHash(), candidate.chunkContentHash())
			);
			(matchesCurrentAudit ? accepted : rejected).add(candidate.chunkId());
		}
		return new RepairPreview(cause, List.copyOf(accepted), List.copyOf(rejected));
	}

	private Set<Long> findExistingPointIds(List<LawIndexIntegrityRow> rows) {
		List<Long> pointIds = rows.stream()
			.filter(this::requiresPointCheck)
			.map(LawIndexIntegrityRow::chunkId)
			.distinct()
			.toList();
		Set<Long> existing = new LinkedHashSet<>();
		for (int start = 0; start < pointIds.size(); start += POINT_LOOKUP_BATCH_SIZE) {
			existing.addAll(pointLookup.findExisting(pointIds.subList(start, Math.min(start + POINT_LOOKUP_BATCH_SIZE, pointIds.size()))));
		}
		return Set.copyOf(existing);
	}

	private boolean requiresPointCheck(LawIndexIntegrityRow row) {
		return hasText(row.embeddingStatus())
			&& !isRetryableFailure(row.embeddingStatus())
			&& equalsHash(row.chunkContentHash(), row.embeddingContentHash());
	}

	private java.util.Optional<LawIndexIntegrityIssue> classify(LawIndexIntegrityRow row, Set<Long> existingPointIds) {
		LawIndexIntegrityIssue.Cause cause = null;
		if (!hasText(row.embeddingStatus())) {
			cause = LawIndexIntegrityIssue.Cause.MISSING_EMBEDDING_ROW;
		} else if (isRetryableFailure(row.embeddingStatus())) {
			cause = LawIndexIntegrityIssue.Cause.RETRYABLE_EMBEDDING_FAILURE;
		} else if (!equalsHash(row.chunkContentHash(), row.embeddingContentHash())) {
			cause = LawIndexIntegrityIssue.Cause.CONTENT_HASH_MISMATCH;
		} else if (!existingPointIds.contains(row.chunkId())) {
			cause = LawIndexIntegrityIssue.Cause.QDRANT_POINT_MISSING;
		} else if (!"INDEXED".equalsIgnoreCase(row.embeddingStatus())) {
			cause = LawIndexIntegrityIssue.Cause.STALE_DATABASE_STATUS;
		} else if (!row.active()) {
			cause = LawIndexIntegrityIssue.Cause.INACTIVE_CHUNK_COUNTED;
		}
		return cause == null ? java.util.Optional.empty() : java.util.Optional.of(new LawIndexIntegrityIssue(
			row.chunkId(), cause, row.chunkContentHash(), row.embeddingContentHash(), row.embeddingStatus(), row.vectorPointId()
		));
	}

	private boolean isRetryableFailure(String status) {
		return "FAILED".equalsIgnoreCase(status);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static boolean equalsHash(String left, String right) {
		return hasText(left) && left.equals(right);
	}

	private static String normalizeTarget(String target) {
		return target == null ? "" : target.trim();
	}

	@FunctionalInterface
	interface PointLookup {
		Set<Long> findExisting(Collection<Long> pointIds);
	}

	public record RepairCandidate(long chunkId, String chunkContentHash) {
	}

	public record RepairPreview(LawIndexIntegrityIssue.Cause cause, List<Long> acceptedIssueIds, List<Long> rejectedIssueIds) {
	}
}
