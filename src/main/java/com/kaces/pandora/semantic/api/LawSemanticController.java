package com.kaces.pandora.semantic.api;


import com.kaces.pandora.semantic.batch.LawSemanticBatchJobResponse;
import com.kaces.pandora.semantic.batch.LawSemanticBatchJobService;
import com.kaces.pandora.semantic.batch.LawSemanticBatchSchedulerStatus;
import com.kaces.pandora.semantic.indexing.LawSemanticBatchFileResult;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexResult;
import com.kaces.pandora.semantic.indexing.LawSemanticIndexService;
import com.kaces.pandora.semantic.search.LawSemanticSearchResponse;
import com.kaces.pandora.semantic.search.LawSemanticSearchService;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/law-data/semantic")
public class LawSemanticController {

	private final LawSemanticIndexService indexService;
	private final LawSemanticSearchService searchService;
	private final LawSemanticBatchJobService batchJobService;

	public LawSemanticController(
		LawSemanticIndexService indexService,
		LawSemanticSearchService searchService,
		LawSemanticBatchJobService batchJobService
	) {
		this.indexService = indexService;
		this.searchService = searchService;
		this.batchJobService = batchJobService;
	}

	@PostMapping("/collection")
	// 메소드 설명: ensureCollection 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, String>> ensureCollection() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		indexService.ensureCollection();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(Map.of("result", "OK"));
	}

	@PostMapping("/index-sample")
	public ResponseEntity<LawSemanticIndexResult> indexSample(
		@RequestParam(defaultValue = "") String target,
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "10000") int limit
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(indexService.indexSample(target, query, limit));
	}

	@PostMapping("/index-documents")
	public ResponseEntity<LawSemanticIndexResult> indexDocuments(
		@RequestParam(defaultValue = "") String target,
		@RequestParam List<Long> documentIds,
		@RequestParam(defaultValue = "10000") int limit
	) {
		return ResponseEntity.ok(indexService.indexDocuments(target, documentIds, limit));
	}

	@PostMapping("/batch-file")
	public ResponseEntity<LawSemanticBatchFileResult> createBatchFile(
		@RequestParam(defaultValue = "") String target,
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "10000") int limit
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(indexService.createBatchFile(target, query, limit));
	}

	@PostMapping("/batches/submit-next")
	public ResponseEntity<LawSemanticBatchJobResponse> submitNextBatch(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "50000") int limit
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.submitNextBatch(target, query, limit));
	}

	@PostMapping("/batches/submit-documents")
	public ResponseEntity<LawSemanticBatchJobResponse> submitDocumentBatch(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam List<Long> documentIds,
		@RequestParam(defaultValue = "50000") int limit
	) {
		return ResponseEntity.ok(batchJobService.submitDocumentBatch(target, documentIds, limit));
	}

	@PostMapping("/batches/register")
	public ResponseEntity<LawSemanticBatchJobResponse> registerBatch(
		@RequestParam String batchId,
		@RequestParam String inputFileId,
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "") String query,
		@RequestParam String inputFilePath,
		@RequestParam(defaultValue = "50000") int requestedCount,
		@RequestParam(defaultValue = "50000") int submittedCount
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.registerExistingBatch(
			batchId,
			inputFileId,
			target,
			query,
			inputFilePath,
			requestedCount,
			submittedCount
		));
	}

	@GetMapping("/batches/{batchId}")
	public ResponseEntity<LawSemanticBatchJobResponse> getBatch(
		@org.springframework.web.bind.annotation.PathVariable String batchId
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.pollJob(batchId));
	}

	@PostMapping("/batches/poll")
	// 메소드 설명: pollBatches 처리 흐름을 수행합니다.
	public ResponseEntity<java.util.List<LawSemanticBatchJobResponse>> pollBatches() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.pollActiveJobs());
	}

	@GetMapping("/batches/scheduler-status")
	// 메소드 설명: batchSchedulerStatus 처리 흐름을 수행합니다.
	public ResponseEntity<LawSemanticBatchSchedulerStatus> batchSchedulerStatus() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.schedulerStatus());
	}

	@PostMapping("/batches/{batchId}/ingest")
	public ResponseEntity<LawSemanticBatchJobResponse> ingestBatch(
		@org.springframework.web.bind.annotation.PathVariable String batchId
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.ingestJob(batchId));
	}

	@PostMapping("/batches/{batchId}/backfill-chunks")
	public ResponseEntity<Map<String, Integer>> backfillBatchChunks(
		@org.springframework.web.bind.annotation.PathVariable String batchId
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.backfillJobChunks(batchId));
	}

	@PostMapping("/batches/backfill-chunks")
	// 메소드 설명: backfillAllBatchChunks 처리 흐름을 수행합니다.
	public ResponseEntity<Map<String, Integer>> backfillAllBatchChunks() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.backfillAllJobChunks());
	}

	@PostMapping("/batches/fill-queue")
	public ResponseEntity<java.util.List<LawSemanticBatchJobResponse>> fillBatchQueue(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam(defaultValue = "") String query,
		@RequestParam(defaultValue = "50000") int limit,
		@RequestParam(defaultValue = "0") int maxActiveJobs
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.fillQueue(target, query, limit, maxActiveJobs));
	}

	@PostMapping("/batches/recover-stale")
	public ResponseEntity<Map<String, Integer>> recoverStaleSubmitted(
		@RequestParam(defaultValue = "") String target
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(batchJobService.recoverStaleSubmittedEmbeddings(target));
	}

	@GetMapping("/search")
	public ResponseEntity<Map<String, LawSemanticSearchResponse>> search(
		@RequestParam(defaultValue = "law") String target,
		@RequestParam String query,
		@RequestParam(defaultValue = "10") int limit,
		@RequestParam(defaultValue = "true") boolean includeFuture
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			.body(searchService.search(target, query, limit, includeFuture));
	}
}
