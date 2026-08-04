package com.kaces.pandora.semantic.integrity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexResult;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexService;
import java.util.List;
import org.junit.jupiter.api.Test;

class LawMissingEmbeddingRepairServiceTests {

	@Test
	void previewAcceptsOnlyExactCurrentMissingEmbeddingRowsWithoutIndexing() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawIndexIntegrityService integrity = mock(LawIndexIntegrityService.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawSemanticChunkRow chunk = chunk(101L, 11L, "a".repeat(64));
		when(mapper.findSemanticChunksByIdsForIndexing(List.of(101L))).thenReturn(List.of(chunk));
		when(integrity.auditByChunkIds("law", List.of(101L))).thenReturn(report(issue(101L, 11L, "a".repeat(64))));
		LawMissingEmbeddingRepairService service = service(mapper, integrity, indexer, runtime("instance-a", "revision-a"));

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request(false, List.of(11L), candidate(101L, "a".repeat(64))));

		assertThat(result.applied()).isFalse();
		assertThat(result.complete()).isFalse();
		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(LawMissingEmbeddingRepairService.RepairState.READY);
		verify(indexer, never()).indexExactChunks(List.of(chunk));
	}

	@Test
	void applyIndexesAnExactCandidateAndVerifiesItsFinalIntegrityState() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawIndexIntegrityService integrity = mock(LawIndexIntegrityService.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawSemanticChunkRow chunk = chunk(101L, 11L, "a".repeat(64));
		when(mapper.findSemanticChunksByIdsForIndexing(List.of(101L))).thenReturn(List.of(chunk));
		when(integrity.auditByChunkIds("law", List.of(101L)))
			.thenReturn(report(issue(101L, 11L, "a".repeat(64))), new LawIndexIntegrityReport("law", 1, 1, 101L, List.of()));
		when(indexer.indexExactChunks(List.of(chunk))).thenReturn(new LawSemanticIndexResult("law_chunks", "text-embedding-3-small", 1, 1));
		LawMissingEmbeddingRepairService service = service(mapper, integrity, indexer, runtime("instance-a", "revision-a"));

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request(true, List.of(11L), candidate(101L, "a".repeat(64))));

		assertThat(result.applied()).isTrue();
		assertThat(result.complete()).isTrue();
		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(LawMissingEmbeddingRepairService.RepairState.INDEXED);
		verify(indexer).indexExactChunks(List.of(chunk));
	}

	@Test
	void applyRejectsAnyCandidateWhoseCurrentClassifierCauseOrHashHasDrifted() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawIndexIntegrityService integrity = mock(LawIndexIntegrityService.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawSemanticChunkRow chunk = chunk(101L, 11L, "a".repeat(64));
		when(mapper.findSemanticChunksByIdsForIndexing(List.of(101L))).thenReturn(List.of(chunk));
		when(integrity.auditByChunkIds("law", List.of(101L))).thenReturn(report(new LawIndexIntegrityIssue(
			101L, 11L, LawIndexIntegrityIssue.Cause.CONTENT_HASH_MISMATCH, "a".repeat(64), "b".repeat(64), "INDEXED", "101"
		)));
		LawMissingEmbeddingRepairService service = service(mapper, integrity, indexer, runtime("instance-a", "revision-a"));

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request(true, List.of(11L), candidate(101L, "a".repeat(64))));

		assertThat(result.applied()).isFalse();
		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(LawMissingEmbeddingRepairService.RepairState.REJECTED_CLASSIFICATION_DRIFT);
		verify(indexer, never()).indexExactChunks(List.of(chunk));
	}

	@Test
	void rejectsTheWholeWaveBeforeAnyReadWhenTheExpectedRuntimeFenceDoesNotMatch() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawMissingEmbeddingRepairService service = service(mapper, mock(LawIndexIntegrityService.class), indexer, runtime("instance-b", "revision-a"));

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request(true, List.of(11L), candidate(101L, "a".repeat(64))));

		assertThat(result.applied()).isFalse();
		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(LawMissingEmbeddingRepairService.RepairState.REJECTED_RUNTIME_FENCE);
		verify(indexer, never()).indexExactChunks(org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void rejectsBeforeIndexingWhenTheIndexRevisionChangesDuringPreflight() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawIndexIntegrityService integrity = mock(LawIndexIntegrityService.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawSemanticChunkRow chunk = chunk(101L, 11L, "a".repeat(64));
		when(mapper.findSemanticChunksByIdsForIndexing(List.of(101L))).thenReturn(List.of(chunk));
		when(integrity.auditByChunkIds("law", List.of(101L))).thenReturn(report(issue(101L, 11L, "a".repeat(64))));
		LawMissingEmbeddingRepairService service = service(mapper, integrity, indexer,
			new SequencedRuntimeInfoProvider("instance-a", "revision-a", "instance-a", "revision-b"));

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request(true, List.of(11L), candidate(101L, "a".repeat(64))));

		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(LawMissingEmbeddingRepairService.RepairState.REJECTED_RUNTIME_FENCE);
		verify(indexer, never()).indexExactChunks(org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void rejectsARequestThatClaimsMoreThanFiftyDocumentOwnersBeforeAnyIndexing() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawMissingEmbeddingRepairService service = service(mapper, mock(LawIndexIntegrityService.class), indexer, runtime("instance-a", "revision-a"));
		List<Long> documentIds = java.util.stream.LongStream.rangeClosed(1L, 51L).boxed().toList();

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request(true, documentIds, candidate(101L, "a".repeat(64))));

		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(LawMissingEmbeddingRepairService.RepairState.REJECTED_REQUEST);
		verify(indexer, never()).indexExactChunks(org.mockito.ArgumentMatchers.anyList());
	}

	@Test
	void reportsEveryRemainingIdAsNotAttemptedAfterPostIndexVerificationFailure() {
		LawChunkMapper mapper = mock(LawChunkMapper.class);
		LawIndexIntegrityService integrity = mock(LawIndexIntegrityService.class);
		LawSemanticIndexService indexer = mock(LawSemanticIndexService.class);
		LawSemanticChunkRow first = chunk(101L, 11L, "a".repeat(64));
		LawSemanticChunkRow second = chunk(102L, 11L, "b".repeat(64));
		when(mapper.findSemanticChunksByIdsForIndexing(List.of(101L, 102L))).thenReturn(List.of(first, second));
		when(integrity.auditByChunkIds("law", List.of(101L, 102L))).thenReturn(report(
			issue(101L, 11L, "a".repeat(64)), issue(102L, 11L, "b".repeat(64))
		));
		when(integrity.auditByChunkIds("law", List.of(101L))).thenReturn(report(issue(101L, 11L, "a".repeat(64))));
		when(indexer.indexExactChunks(List.of(first))).thenReturn(new LawSemanticIndexResult("law_chunks", "text-embedding-3-small", 1, 1));
		LawMissingEmbeddingRepairService service = service(mapper, integrity, indexer, runtime("instance-a", "revision-a"));
		LawMissingEmbeddingRepairService.RepairRequest request = new LawMissingEmbeddingRepairService.RepairRequest(
			"law", "instance-a", "revision-a", List.of(11L),
			List.of(candidate(101L, "a".repeat(64)), candidate(102L, "b".repeat(64))), true
		);

		LawMissingEmbeddingRepairService.RepairResult result = service.repair(request);

		assertThat(result.outcomes()).extracting(LawMissingEmbeddingRepairService.RepairOutcome::state)
			.containsExactly(
				LawMissingEmbeddingRepairService.RepairState.VERIFICATION_FAILED,
				LawMissingEmbeddingRepairService.RepairState.NOT_ATTEMPTED
			);
		verify(indexer, never()).indexExactChunks(List.of(second));
	}

	private LawMissingEmbeddingRepairService service(
		LawChunkMapper mapper,
		LawIndexIntegrityService integrity,
		LawSemanticIndexService indexer,
		LawIndexIntegrityRuntimeInfoProvider runtime
	) {
		return new LawMissingEmbeddingRepairService(mapper, integrity, indexer, runtime);
	}

	private LawMissingEmbeddingRepairService.RepairRequest request(
		boolean apply, List<Long> documentIds, LawMissingEmbeddingRepairService.RepairCandidate candidate
	) {
		return new LawMissingEmbeddingRepairService.RepairRequest(
			"law", "instance-a", "revision-a", documentIds, List.of(candidate), apply
		);
	}

	private LawMissingEmbeddingRepairService.RepairCandidate candidate(long chunkId, String hash) {
		return new LawMissingEmbeddingRepairService.RepairCandidate(chunkId, hash);
	}

	private LawIndexIntegrityIssue issue(long chunkId, long documentId, String hash) {
		return new LawIndexIntegrityIssue(
			chunkId, documentId, LawIndexIntegrityIssue.Cause.MISSING_EMBEDDING_ROW, hash, null, null, null
		);
	}

	private LawIndexIntegrityReport report(LawIndexIntegrityIssue... issues) {
		return new LawIndexIntegrityReport("law", issues.length, issues.length, issues.length, List.of(issues));
	}

	private LawIndexIntegrityRuntimeInfoProvider runtime(String instance, String revision) {
		return () -> new LawIndexIntegrityRuntimeInfo(instance, revision);
	}

	private static final class SequencedRuntimeInfoProvider implements LawIndexIntegrityRuntimeInfoProvider {
		private final List<LawIndexIntegrityRuntimeInfo> values;
		private int index;

		private SequencedRuntimeInfoProvider(String firstInstance, String firstRevision, String secondInstance, String secondRevision) {
			values = List.of(
				new LawIndexIntegrityRuntimeInfo(firstInstance, firstRevision),
				new LawIndexIntegrityRuntimeInfo(secondInstance, secondRevision)
			);
		}

		@Override
		public LawIndexIntegrityRuntimeInfo current() {
			return values.get(Math.min(index++, values.size() - 1));
		}
	}

	private LawSemanticChunkRow chunk(long chunkId, long documentId, String hash) {
		return new LawSemanticChunkRow(chunkId, documentId, "law", "law-1", "Title", "", "", "", "CURRENT",
			"Article 1", "Scope", "Text", null, "$.article", "", 0, hash, "Scope", "provision", "PASS");
	}
}
