package com.kaces.pandora.semantic.integrity;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.Objects;
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
		return audit(target, limit, 0L);
	}

	public LawIndexIntegrityReport audit(String target, int limit, long afterChunkId) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_AUDIT_LIMIT));
		long safeAfterChunkId = Math.max(0L, afterChunkId);
		List<LawIndexIntegrityRow> rows = lawChunkMapper.findLawIndexIntegrityRows(
			normalizeTarget(target), model, vectorStore, safeLimit, safeAfterChunkId
		);
		Set<Long> existingPointIds = findExistingPointIds(rows);
		List<LawIndexIntegrityIssue> issues = new ArrayList<>();
		for (LawIndexIntegrityRow row : rows) {
			classify(row, existingPointIds).ifPresent(issues::add);
		}
		long lastScannedChunkId = rows.isEmpty() ? safeAfterChunkId : rows.get(rows.size() - 1).chunkId();
		return new LawIndexIntegrityReport(normalizeTarget(target), safeLimit, rows.size(), lastScannedChunkId, issues);
	}

	public LawIndexIntegrityReport auditByChunkIds(String target, List<Long> chunkIds) {
		String normalizedTarget = normalizeTarget(target);
		List<Long> safeChunkIds = chunkIds == null ? List.of() : chunkIds.stream()
			.filter(chunkId -> chunkId != null && chunkId > 0)
			.distinct()
			.toList();
		if (safeChunkIds.isEmpty()) {
			return new LawIndexIntegrityReport(normalizedTarget, 0, 0, 0L, List.of());
		}
		List<LawIndexIntegrityRow> rows = lawChunkMapper.findLawIndexIntegrityRowsByIds(
			normalizedTarget, model, vectorStore, safeChunkIds
		);
		Set<Long> existingPointIds = findExistingPointIds(rows);
		List<LawIndexIntegrityIssue> issues = new ArrayList<>();
		for (LawIndexIntegrityRow row : rows) {
			classify(row, existingPointIds).ifPresent(issues::add);
		}
		long lastScannedChunkId = rows.stream().mapToLong(LawIndexIntegrityRow::chunkId).max().orElse(0L);
		return new LawIndexIntegrityReport(normalizedTarget, safeChunkIds.size(), rows.size(), lastScannedChunkId, issues);
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
					&& Objects.equals(issue.chunkContentHash(), candidate.chunkContentHash())
					&& Objects.equals(issue.embeddingContentHash(), candidate.embeddingContentHash())
			);
			(matchesCurrentAudit ? accepted : rejected).add(candidate.chunkId());
		}
		return new RepairPreview(cause, List.copyOf(accepted), List.copyOf(rejected));
	}

	private Set<Long> findExistingPointIds(List<LawIndexIntegrityRow> rows) {
		List<Long> pointIds = rows.stream()
			.filter(this::requiresPointCheck)
			.map(row -> pointId(row).orElseThrow())
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
			&& equalsHash(row.chunkContentHash(), row.embeddingContentHash())
			&& pointId(row).isPresent();
	}

	private java.util.Optional<LawIndexIntegrityIssue> classify(LawIndexIntegrityRow row, Set<Long> existingPointIds) {
		LawIndexIntegrityIssue.Cause cause = null;
		if (!hasText(row.embeddingStatus())) {
			cause = LawIndexIntegrityIssue.Cause.MISSING_EMBEDDING_ROW;
		} else if (isRetryableFailure(row.embeddingStatus())) {
			cause = LawIndexIntegrityIssue.Cause.RETRYABLE_EMBEDDING_FAILURE;
		} else if (!equalsHash(row.chunkContentHash(), row.embeddingContentHash())) {
			cause = LawIndexIntegrityIssue.Cause.CONTENT_HASH_MISMATCH;
		} else if (pointId(row).isEmpty() || !existingPointIds.contains(pointId(row).getAsLong())) {
			cause = LawIndexIntegrityIssue.Cause.QDRANT_POINT_MISSING;
		} else if (!"INDEXED".equalsIgnoreCase(row.embeddingStatus())) {
			cause = LawIndexIntegrityIssue.Cause.STALE_DATABASE_STATUS;
		} else if (!row.active()) {
			cause = LawIndexIntegrityIssue.Cause.INACTIVE_CHUNK_COUNTED;
		}
		return cause == null ? java.util.Optional.empty() : java.util.Optional.of(new LawIndexIntegrityIssue(
			row.chunkId(), row.documentId(), cause, row.chunkContentHash(), row.embeddingContentHash(), row.embeddingStatus(), row.vectorPointId()
		));
	}

	private boolean isRetryableFailure(String status) {
		return "FAILED".equalsIgnoreCase(status) || "ERROR".equalsIgnoreCase(status);
	}

	private static boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private static boolean equalsHash(String left, String right) {
		return hasText(left) && left.equals(right);
	}

	private static String normalizeTarget(String target) {
		String normalized = target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
		if (normalized.isEmpty() || "all".equals(normalized)) {
			return "";
		}
		if ("law".equals(normalized) || "admrul".equals(normalized)) {
			return normalized;
		}
		throw new IllegalArgumentException("Unsupported law index integrity target: " + target);
	}

	private OptionalLong pointId(LawIndexIntegrityRow row) {
		String value = row.vectorPointId();
		if (value == null || !value.matches("[1-9]\\d*")) {
			return OptionalLong.empty();
		}
		try {
			return OptionalLong.of(Long.parseLong(value));
		} catch (NumberFormatException exception) {
			return OptionalLong.empty();
		}
	}

	@FunctionalInterface
	interface PointLookup {
		Set<Long> findExisting(Collection<Long> pointIds);
	}

	public record RepairCandidate(long chunkId, String chunkContentHash, String embeddingContentHash) {
	}

	public record RepairPreview(LawIndexIntegrityIssue.Cause cause, List<Long> acceptedIssueIds, List<Long> rejectedIssueIds) {
	}
}
