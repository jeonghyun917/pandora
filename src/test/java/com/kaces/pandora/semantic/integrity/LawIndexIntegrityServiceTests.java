package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LawIndexIntegrityServiceTests {

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
		return new LawIndexIntegrityRow(chunkId, active, chunkContentHash, embeddingContentHash, embeddingStatus, String.valueOf(chunkId));
	}
}
