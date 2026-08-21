package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LawIndexIntegrityServiceTests {

	@Test
	void auditUsesStoredVectorPointIdRatherThanChunkId() {
		AtomicReference<Set<Long>> requestedPointIds = new AtomicReference<>();
		LawIndexIntegrityService service = new LawIndexIntegrityService(
			mapper(row(10, true, "current", "current", "INDEXED", "900")),
			ids -> {
				requestedPointIds.set(Set.copyOf(ids));
				return Set.of(900L);
			}
		);

		assertThat(service.audit("law", 100).issues()).isEmpty();
		assertThat(requestedPointIds).hasValue(Set.of(900L));
	}

	@Test
	void auditCapsMapperLimitAtTenThousand() {
		AtomicReference<Integer> mapperLimit = new AtomicReference<>();
		LawChunkMapper mapper = (LawChunkMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { LawChunkMapper.class },
			(proxy, method, args) -> {
				if ("findLawIndexIntegrityRows".equals(method.getName())) {
					mapperLimit.set((Integer) args[3]);
					return List.of();
				}
				return null;
			}
		);
		LawIndexIntegrityService service = new LawIndexIntegrityService(mapper, ids -> Set.of());

		service.audit("law", 99_999);

		assertThat(mapperLimit).hasValue(10_000);
	}

	@Test
	void auditForwardsCursorAndReturnsScannedPageProgress() {
		AtomicReference<Object[]> mapperArguments = new AtomicReference<>();
		LawChunkMapper mapper = (LawChunkMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { LawChunkMapper.class },
			(proxy, method, args) -> {
				if ("findLawIndexIntegrityRows".equals(method.getName())) {
					mapperArguments.set(args);
					return List.of(
						row(11, true, "current", "current", "INDEXED"),
						row(12, true, "current", "current", "INDEXED")
					);
				}
				return null;
			}
		);
		LawIndexIntegrityService service = new LawIndexIntegrityService(mapper, ids -> Set.of(11L, 12L));

		LawIndexIntegrityReport report = service.audit("law", 10_000, 10L);

		assertThat(mapperArguments).hasValueSatisfying(args -> assertThat(args[4]).isEqualTo(10L));
		assertThat(report.scannedRows()).isEqualTo(2);
		assertThat(report.lastScannedChunkId()).isEqualTo(12L);
	}

	@Test
	void exactIdAuditReportsNoScannedRowsWhenTheRequestedChunkIsNoLongerReturned() {
		LawChunkMapper mapper = (LawChunkMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { LawChunkMapper.class },
			(proxy, method, args) -> "findLawIndexIntegrityRowsByIds".equals(method.getName()) ? List.of() : null
		);
		LawIndexIntegrityService service = new LawIndexIntegrityService(mapper, ids -> Set.of());

		LawIndexIntegrityReport report = service.auditByChunkIds("law", List.of(101L));

		assertThat(report.scannedRows()).isZero();
		assertThat(report.issues()).isEmpty();
	}

	@Test
	void auditMarksMissingAndNonNumericStoredPointIdsAsMissingPoints() {
		LawIndexIntegrityService service = new LawIndexIntegrityService(
			mapper(
				row(10, true, "current", "current", "INDEXED", null),
				row(11, true, "current", "current", "INDEXED", "not-a-number")
			),
			ids -> Set.of()
		);

		assertThat(service.audit("law", 100).issues()).extracting(LawIndexIntegrityIssue::cause)
			.containsExactly(
				LawIndexIntegrityIssue.Cause.QDRANT_POINT_MISSING,
				LawIndexIntegrityIssue.Cause.QDRANT_POINT_MISSING
			);
	}

	@Test
	void errorStatusTakesRetryableFailurePrecedenceOverHashAndPointProblems() {
		LawIndexIntegrityService service = new LawIndexIntegrityService(
			mapper(row(10, true, "current", "stale", "ERROR", null)),
			ids -> Set.of()
		);

		assertThat(service.audit("law", 100).issues()).extracting(LawIndexIntegrityIssue::cause)
			.containsExactly(LawIndexIntegrityIssue.Cause.RETRYABLE_EMBEDDING_FAILURE);
	}

	@Test
	void previewRepairRejectsStaleEmbeddingHash() {
		LawIndexIntegrityService service = new LawIndexIntegrityService(
			mapper(row(10, true, "chunk", "embedding-current", "INDEXED", "10")),
			ids -> Set.of()
		);

		LawIndexIntegrityService.RepairPreview preview = service.previewRepair(
			"law",
			LawIndexIntegrityIssue.Cause.QDRANT_POINT_MISSING,
			List.of(new LawIndexIntegrityService.RepairCandidate(10, "chunk", "embedding-stale"))
		);

		assertThat(preview.acceptedIssueIds()).isEmpty();
		assertThat(preview.rejectedIssueIds()).containsExactly(10L);
	}

	@Test
	void auditRejectsInvalidTargetsAndCanonicalizesAll() {
		LawIndexIntegrityService service = new LawIndexIntegrityService(mapper(), ids -> Set.of());

		assertThatThrownBy(() -> service.audit("official_doc", 100))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("target");
		assertThat(service.audit(" ALL ", 100).target()).isEmpty();
		assertThat(service.audit(" LAW ", 100).target()).isEqualTo("law");
	}

	@Test
	void auditClassifiesEachIntegrityCauseInRequiredPrecedenceOrder() {
		LawIndexIntegrityService service = new LawIndexIntegrityService(
			mapper(
				row(1, true, "chunk-1", null, null),
				row(2, true, "chunk-2", "chunk-2", "FAILED"),
				row(3, true, "chunk-3", "old-hash", "INDEXED"),
				row(4, true, "chunk-4", "chunk-4", "INDEXED"),
				row(5, true, "chunk-5", "chunk-5", "PENDING"),
				row(6, false, "chunk-6", "chunk-6", "INDEXED")
			),
			ids -> Set.of(5L, 6L)
		);

		LawIndexIntegrityReport report = service.audit("law", 100);

		assertThat(report.issues()).extracting(issue -> issue.cause())
			.containsExactly(
				LawIndexIntegrityIssue.Cause.MISSING_EMBEDDING_ROW,
				LawIndexIntegrityIssue.Cause.RETRYABLE_EMBEDDING_FAILURE,
				LawIndexIntegrityIssue.Cause.CONTENT_HASH_MISMATCH,
				LawIndexIntegrityIssue.Cause.QDRANT_POINT_MISSING,
				LawIndexIntegrityIssue.Cause.STALE_DATABASE_STATUS,
				LawIndexIntegrityIssue.Cause.INACTIVE_CHUNK_COUNTED
			);
	}

	@Test
	void auditDoesNotReportCurrentIndexedRowWithExistingPoint() {
		LawIndexIntegrityService service = new LawIndexIntegrityService(
			mapper(row(10, true, "current", "current", "INDEXED")),
			ids -> Set.of(10L)
		);

		assertThat(service.audit("law", 100).issues()).isEmpty();
	}

	private LawChunkMapper mapper(LawIndexIntegrityRow... rows) {
		return (LawChunkMapper) Proxy.newProxyInstance(
			getClass().getClassLoader(),
			new Class<?>[] { LawChunkMapper.class },
			(proxy, method, args) -> "findLawIndexIntegrityRows".equals(method.getName()) ? List.of(rows) : null
		);
	}

	private LawIndexIntegrityRow row(
		long chunkId,
		boolean active,
		String chunkContentHash,
		String embeddingContentHash,
		String embeddingStatus
	) {
		return row(chunkId, active, chunkContentHash, embeddingContentHash, embeddingStatus, String.valueOf(chunkId));
	}

	private LawIndexIntegrityRow row(
		long chunkId,
		boolean active,
		String chunkContentHash,
		String embeddingContentHash,
		String embeddingStatus,
		String vectorPointId
	) {
		return new LawIndexIntegrityRow(chunkId, active, chunkContentHash, embeddingContentHash, embeddingStatus, vectorPointId);
	}
}
