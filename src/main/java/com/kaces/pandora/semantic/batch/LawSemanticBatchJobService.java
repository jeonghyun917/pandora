package com.kaces.pandora.semantic.batch;


import com.kaces.pandora.infra.openai.OpenAiBatchClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.batch.persistence.LawSemanticBatchJobChunkMapper;
import com.kaces.pandora.semantic.batch.persistence.LawSemanticBatchJobChunkRow;
import com.kaces.pandora.semantic.batch.persistence.LawSemanticBatchJobMapper;
import com.kaces.pandora.semantic.batch.persistence.LawSemanticBatchJobRow;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LawSemanticBatchJobService {

	private static final int MAX_OPENAI_BATCH_INPUTS = 50_000;
	private static final int QDRANT_UPSERT_BATCH_SIZE = 500;
	private static final int DB_WRITE_BATCH_SIZE = 500;

	private final LawChunkMapper lawChunkMapper;
	private final RagDocumentMapper ragDocumentMapper;
	private final LawSemanticBatchJobMapper batchJobMapper;
	private final LawSemanticBatchJobChunkMapper batchJobChunkMapper;
	private final LawAiProperties properties;
	private final LawJsonWriter jsonWriter;
	private final OpenAiBatchClient batchClient;
	private final QdrantClient qdrantClient;
	private final ObjectMapper objectMapper;
	private final TransactionTemplate transactionTemplate;
	// 메소드 설명: idle 처리 흐름을 수행합니다.
	private volatile LawSemanticBatchSchedulerStatus schedulerStatus = LawSemanticBatchSchedulerStatus.idle();

	public LawSemanticBatchJobService(
		LawChunkMapper lawChunkMapper,
		RagDocumentMapper ragDocumentMapper,
		LawSemanticBatchJobMapper batchJobMapper,
		LawSemanticBatchJobChunkMapper batchJobChunkMapper,
		LawAiProperties properties,
		LawJsonWriter jsonWriter,
		OpenAiBatchClient batchClient,
		QdrantClient qdrantClient,
		ObjectMapper objectMapper,
		TransactionTemplate transactionTemplate
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.ragDocumentMapper = ragDocumentMapper;
		this.batchJobMapper = batchJobMapper;
		this.batchJobChunkMapper = batchJobChunkMapper;
		this.properties = properties;
		this.jsonWriter = jsonWriter;
		this.batchClient = batchClient;
		this.qdrantClient = qdrantClient;
		this.objectMapper = objectMapper;
		this.transactionTemplate = transactionTemplate;
	}

	// 메소드 설명: submitNextBatch 처리 흐름을 수행합니다.
	public LawSemanticBatchJobResponse submitNextBatch(String target, String query, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_OPENAI_BATCH_INPUTS));
		String normalizedTarget = target == null ? "" : target.trim();
		String normalizedQuery = query == null ? "" : query.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		List<LawSemanticChunkRow> chunks = findSemanticIndexCandidates(
			normalizedTarget,
			normalizedQuery,
			model,
			vectorStore,
			safeLimit
		);
		if (chunks.isEmpty()) {
			return new LawSemanticBatchJobResponse(0, null, "NO_CANDIDATES", null, null, null, normalizedTarget, normalizedQuery, null, null, safeLimit, 0, 0, 0, 0);
		}

		Path inputFile = writeInputFile(normalizedTarget, normalizedQuery, model, chunks);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String inputFileId = batchClient.uploadBatchFile(inputFile);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		OpenAiBatchStatus status = batchClient.createEmbeddingBatch(inputFileId, normalizedTarget, chunks.size());
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		batchJobMapper.insertJob(
			status.id(),
			inputFileId,
			status.status(),
			normalizedTarget,
			normalizedQuery,
			model,
			vectorStore,
			inputFile.toAbsolutePath().toString(),
			safeLimit,
			chunks.size()
		);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		long jobId = batchJobMapper.lastInsertId();
		insertJobChunks(jobId, status.id(), normalizedTarget, chunks);
		markSubmitted(chunks, model, vectorStore);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return toResponse(batchJobMapper.findByOpenaiBatchId(status.id()));
	}

	public LawSemanticBatchJobResponse registerExistingBatch(
		String batchId,
		String inputFileId,
		String target,
		String query,
		String inputFilePath,
		int requestedCount,
		int submittedCount
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		OpenAiBatchStatus status = batchClient.retrieveBatch(batchId);
		String normalizedTarget = target == null ? "" : target.trim();
		String normalizedQuery = query == null ? "" : query.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		LawSemanticBatchJobRow existing = batchJobMapper.findByOpenaiBatchId(batchId);
		if (existing == null) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchJobMapper.insertJob(
				batchId,
				inputFileId,
				status.status(),
				normalizedTarget,
				normalizedQuery,
				model,
				vectorStore,
				inputFilePath,
				requestedCount,
				submittedCount
			);
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		batchJobMapper.updateStatus(batchId, status.status(), status.outputFileId(), status.errorFileId(), status.completed(), status.failed());
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		backfillJobChunks(batchJobMapper.findByOpenaiBatchId(batchId));
		markSubmittedFromInputFile(Path.of(inputFilePath), model, vectorStore);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return toResponse(batchJobMapper.findByOpenaiBatchId(batchId));
	}

	// 메소드 설명: backfillJobChunks 처리 흐름을 수행합니다.
	public Map<String, Integer> backfillJobChunks(String batchId) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		LawSemanticBatchJobRow job = batchJobMapper.findByOpenaiBatchId(batchId);
		if (job == null) {
			throw new IllegalArgumentException("Unknown batch id: " + batchId);
		}
		int inserted = backfillJobChunks(job);
		return Map.of("backfilled", inserted);
	}

	// 메소드 설명: backfillAllJobChunks 처리 흐름을 수행합니다.
	public Map<String, Integer> backfillAllJobChunks() {
		int jobs = 0;
		int chunks = 0;
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		for (LawSemanticBatchJobRow job : batchJobMapper.findAllJobs()) {
			int inserted = backfillJobChunks(job);
			if (inserted > 0) {
				jobs++;
				chunks += inserted;
			}
		}
		return Map.of("jobs", jobs, "backfilled", chunks);
	}

	// 메소드 설명: pollActiveJobs 처리 흐름을 수행합니다.
	public List<LawSemanticBatchJobResponse> pollActiveJobs() {
		List<LawSemanticBatchJobResponse> responses = new ArrayList<>();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		for (LawSemanticBatchJobRow job : batchJobMapper.findActiveJobs()) {
			responses.add(pollJob(job));
		}
		return responses;
	}

	// 메소드 설명: pollJob 처리 흐름을 수행합니다.
	public LawSemanticBatchJobResponse pollJob(String batchId) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		LawSemanticBatchJobRow job = batchJobMapper.findByOpenaiBatchId(batchId);
		if (job == null) {
			throw new IllegalArgumentException("Unknown batch id: " + batchId);
		}
		return pollJob(job);
	}

	// 메소드 설명: ingestJob 처리 흐름을 수행합니다.
	public LawSemanticBatchJobResponse ingestJob(String batchId) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		LawSemanticBatchJobRow job = batchJobMapper.findByOpenaiBatchId(batchId);
		if (job == null) {
			throw new IllegalArgumentException("Unknown batch id: " + batchId);
		}
		if (job.outputFileId() == null || job.outputFileId().isBlank()) {
			if (job.completedCount() == 0 && job.failedCount() > 0 && job.errorFileId() != null && !job.errorFileId().isBlank()) {
				ingestErrorOnly(job);
				return toResponse(batchJobMapper.findByOpenaiBatchId(batchId));
			}
			throw new IllegalStateException("Batch output file is not ready.");
		}
		int ingested = ingestOutput(job);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return toResponse(batchJobMapper.findByOpenaiBatchId(batchId));
	}

	// 메소드 설명: fillQueue 처리 흐름을 수행합니다.
	public List<LawSemanticBatchJobResponse> fillQueue(String target, String query, int limit, int maxActiveJobs) {
		int safeMaxActive = maxActiveJobs <= 0 ? properties.batch().maxActiveJobs() : maxActiveJobs;
		safeMaxActive = Math.max(1, safeMaxActive);
		List<LawSemanticBatchJobResponse> responses = new ArrayList<>(pollActiveJobs());
		recoverStaleSubmittedEmbeddings(target);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		long activeCount = batchJobMapper.findActiveJobs().stream()
			.filter(job -> List.of("validating", "in_progress", "finalizing").contains(job.status()))
			.count();
		while (activeCount < safeMaxActive) {
			LawSemanticBatchJobResponse submitted = submitNextBatch(target, query, limit);
			responses.add(submitted);
			if ("NO_CANDIDATES".equals(submitted.status())) {
				break;
			}
			activeCount++;
		}
		return responses;
	}

	@Scheduled(fixedDelayString = "${law-ai.batch.poll-delay-millis:60000}")
	// 메소드 설명: scheduledPoll 처리 흐름을 수행합니다.
	public void scheduledPoll() {
		LocalDateTime startedAt = LocalDateTime.now();
		schedulerStatus = new LawSemanticBatchSchedulerStatus(startedAt, schedulerStatus.lastFinishedAt(), true, "RUNNING", null);
		try {
			pollActiveJobs();
			recoverStaleSubmittedEmbeddings(properties.batch().autoTarget());
			if (properties.batch().autoEnabled()) {
				fillQueue(
					properties.batch().autoTarget(),
					properties.batch().autoQuery(),
					properties.batch().submitLimit(),
					properties.batch().maxActiveJobs()
				);
			}
			schedulerStatus = new LawSemanticBatchSchedulerStatus(startedAt, LocalDateTime.now(), false, "OK", null);
		} catch (Exception exception) {
			schedulerStatus = new LawSemanticBatchSchedulerStatus(startedAt, LocalDateTime.now(), false, "ERROR", exception.getMessage());
			throw exception;
		}
	}

	// 메소드 설명: schedulerStatus 처리 흐름을 수행합니다.
	public LawSemanticBatchSchedulerStatus schedulerStatus() {
		return schedulerStatus;
	}

	// 메소드 설명: recoverStaleSubmittedEmbeddings 처리 흐름을 수행합니다.
	public Map<String, Integer> recoverStaleSubmittedEmbeddings(String target) {
		String normalizedTarget = target == null ? "" : target.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		if (isRagTarget(normalizedTarget)) {
			return Map.of(
				"law", 0,
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				"rag", ragDocumentMapper.recoverStaleSubmittedEmbeddings(normalizedTarget, model, vectorStore, staleSubmittedMinutes())
			);
		}
		if ("law".equals(normalizedTarget) || "admrul".equals(normalizedTarget)) {
			return Map.of(
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				"law", lawChunkMapper.recoverStaleSubmittedEmbeddings(normalizedTarget, model, vectorStore, staleSubmittedMinutes()),
				"rag", 0
			);
		}
		return Map.of(
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			"law", lawChunkMapper.recoverStaleSubmittedEmbeddings("", model, vectorStore, staleSubmittedMinutes()),
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			"rag", ragDocumentMapper.recoverStaleSubmittedEmbeddings("", model, vectorStore, staleSubmittedMinutes())
		);
	}

	// 메소드 설명: pollJob 처리 흐름을 수행합니다.
	private LawSemanticBatchJobResponse pollJob(LawSemanticBatchJobRow job) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			OpenAiBatchStatus status = batchClient.retrieveBatch(job.openaiBatchId());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchJobMapper.updateStatus(job.openaiBatchId(), status.status(), status.outputFileId(), status.errorFileId(), status.completed(), status.failed());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			LawSemanticBatchJobRow updated = batchJobMapper.findByOpenaiBatchId(job.openaiBatchId());
			if (properties.batch().autoEnabled() && "completed".equals(status.status()) && updated.ingestedCount() == 0 && status.outputFileId() != null) {
				ingestOutput(updated);
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				updated = batchJobMapper.findByOpenaiBatchId(job.openaiBatchId());
			}
			return toResponse(updated);
		} catch (Exception exception) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchJobMapper.recordLocalError(job.openaiBatchId(), exception.getMessage());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return toResponse(batchJobMapper.findByOpenaiBatchId(job.openaiBatchId()));
		}
	}

	// 메소드 설명: ingestOutput 처리 흐름을 수행합니다.
	private int ingestOutput(LawSemanticBatchJobRow job) {
		Path outputFile = Path.of("target", "openai-batches", job.openaiBatchId() + "-output.jsonl");
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.createDirectories(outputFile.getParent());
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to create batch output directory.", exception);
		}
		ensureDownloadedOutput(job, outputFile);

		int ingested = 0;
		Map<Long, List<Double>> vectors = new HashMap<>();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Set<Long> indexedChunkIds = new HashSet<>(batchJobChunkMapper.findChunkIdsByStatus(job.batchJobId(), "INDEXED"));
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (BufferedReader reader = Files.newBufferedReader(outputFile, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				BatchEmbeddingResult embedding = parseEmbeddingLine(line);
				if (embedding == null) {
					continue;
				}
				if (!embedding.success()) {
					transactionTemplate.executeWithoutResult(status ->
						// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
						batchJobChunkMapper.markFailed(job.batchJobId(), embedding.customId(), embedding.errorCode(), embedding.errorMessage())
					);
					continue;
				}
				if (indexedChunkIds.contains(embedding.chunkId())) {
					continue;
				}
				vectors.put(embedding.chunkId(), embedding.vector());
				if (vectors.size() >= QDRANT_UPSERT_BATCH_SIZE) {
					ingested += flushVectors(vectors, job, indexedChunkIds);
					vectors.clear();
				}
			}
			if (!vectors.isEmpty()) {
				ingested += flushVectors(vectors, job, indexedChunkIds);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to ingest OpenAI Batch output.", exception);
		}
		transactionTemplate.executeWithoutResult(status -> {
			markFailuresFromErrorFile(job);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			int indexedCount = batchJobChunkMapper.countByStatus(job.batchJobId(), "INDEXED");
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchJobMapper.markIngested(job.openaiBatchId(), outputFile.toAbsolutePath().toString(), indexedCount);
			if (isRagTarget(job.target())) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				ragDocumentMapper.markFullyIndexedDocuments(job.target(), job.embeddingModel(), job.vectorStore());
			}
		});
		return ingested;
	}

	private void ingestErrorOnly(LawSemanticBatchJobRow job) {
		ensureDownloadedError(job);
		transactionTemplate.executeWithoutResult(status -> {
			markFailuresFromErrorFile(job);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			int indexedCount = batchJobChunkMapper.countByStatus(job.batchJobId(), "INDEXED");
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchJobMapper.markIngested(job.openaiBatchId(), null, indexedCount);
		});
	}

	// 메소드 설명: ensureDownloadedOutput 처리 흐름을 수행합니다.
	private void ensureDownloadedOutput(LawSemanticBatchJobRow job, Path outputFile) {
		ensureDownloadedError(job);
		if (hasDownloadedOutput(job, outputFile)) {
			return;
		}
		Path partialOutput = outputFile.resolveSibling(outputFile.getFileName() + ".part");
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.deleteIfExists(partialOutput);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchClient.downloadFile(job.outputFileId(), partialOutput);
			if (!hasDownloadedOutput(job, partialOutput)) {
				throw new IllegalStateException("Downloaded batch output did not pass validation.");
			}
			moveDownloadedOutput(partialOutput, outputFile);
		} catch (Exception exception) {
			throw new IllegalStateException("OpenAI Batch output download failed.", exception);
		}
	}

	// 메소드 설명: ensureDownloadedError 처리 흐름을 수행합니다.
	private void ensureDownloadedError(LawSemanticBatchJobRow job) {
		if (job.errorFileId() == null || job.errorFileId().isBlank()) {
			return;
		}
		Path errorFile = errorOutputFile(job);
		if (hasDownloadedError(errorFile)) {
			return;
		}
		Path partialError = errorFile.resolveSibling(errorFile.getFileName() + ".part");
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.deleteIfExists(partialError);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchClient.downloadFile(job.errorFileId(), partialError);
			if (!hasDownloadedError(partialError)) {
				throw new IllegalStateException("Downloaded batch error output did not pass validation.");
			}
			moveDownloadedOutput(partialError, errorFile);
		} catch (Exception exception) {
			throw new IllegalStateException("OpenAI Batch error output download failed.", exception);
		}
	}

	// 메소드 설명: moveDownloadedOutput 처리 흐름을 수행합니다.
	private void moveDownloadedOutput(Path partialOutput, Path outputFile) throws java.io.IOException {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.move(partialOutput, outputFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (java.nio.file.AtomicMoveNotSupportedException exception) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.move(partialOutput, outputFile, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// 메소드 설명: hasDownloadedOutput 처리 흐름을 수행합니다.
	private boolean hasDownloadedOutput(LawSemanticBatchJobRow job, Path outputFile) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			if (!Files.exists(outputFile) || Files.size(outputFile) == 0) {
				return false;
			}
			long expectedLines = expectedOutputLines(job);
			LineStats outputStats = lineStats(outputFile);
			if (!outputStats.hasJsonLine()) {
				return false;
			}
			if (expectedLines <= 0 || outputStats.lineCount() == expectedLines) {
				return true;
			}
			LineStats errorStats = lineStats(errorOutputFile(job));
			return errorStats.hasJsonLine() && outputStats.lineCount() + errorStats.lineCount() == expectedLines;
		} catch (Exception exception) {
			return false;
		}
	}

	// 메소드 설명: hasDownloadedError 처리 흐름을 수행합니다.
	private boolean hasDownloadedError(Path errorFile) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return Files.exists(errorFile) && Files.size(errorFile) > 0 && lineStats(errorFile).hasJsonLine();
		} catch (Exception exception) {
			return false;
		}
	}

	// 메소드 설명: lineStats 처리 흐름을 수행합니다.
	private LineStats lineStats(Path file) throws java.io.IOException {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.exists(file)) {
			return new LineStats(0, false);
		}
		long lineCount = 0;
		boolean hasJsonLine = false;
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (!line.isBlank()) {
					lineCount++;
					if (!hasJsonLine) {
						hasJsonLine = line.trim().startsWith("{");
					}
				}
			}
		}
		return new LineStats(lineCount, hasJsonLine);
	}

	// 메소드 설명: errorOutputFile 처리 흐름을 수행합니다.
	private Path errorOutputFile(LawSemanticBatchJobRow job) {
		return Path.of("target", "openai-batches", job.openaiBatchId() + "-error.jsonl");
	}

	// 메소드 설명: markFailuresFromErrorFile 처리 흐름을 수행합니다.
	private void markFailuresFromErrorFile(LawSemanticBatchJobRow job) {
		Path errorFile = errorOutputFile(job);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.exists(errorFile)) {
			return;
		}
		Map<Long, String> failedMessagesByChunkId = new LinkedHashMap<>();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (BufferedReader reader = Files.newBufferedReader(errorFile, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				BatchEmbeddingResult embedding = parseEmbeddingLine(line);
				if (embedding != null && !embedding.success()) {
					// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
					batchJobChunkMapper.markFailed(job.batchJobId(), embedding.customId(), embedding.errorCode(), embedding.errorMessage());
					Long chunkId = chunkIdFromCustomId(embedding.customId());
					if (chunkId != null) {
						failedMessagesByChunkId.put(chunkId, embedding.errorMessage());
					}
				}
			}
			if (!failedMessagesByChunkId.isEmpty()) {
				for (LawSemanticChunkRow chunk : findSemanticChunksByIds(job.target(), new ArrayList<>(failedMessagesByChunkId.keySet()))) {
					upsertEmbeddingStatus(
						job.target(),
						chunk.chunkId(),
						job.embeddingModel(),
						job.vectorStore(),
						vectorPointId(chunk.target(), chunk.chunkId()),
						chunk.contentHash(),
						"FAILED",
						failedMessagesByChunkId.get(chunk.chunkId())
					);
				}
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to ingest OpenAI Batch error output.", exception);
		}
	}

	private Long chunkIdFromCustomId(String customId) {
		if (customId == null || !customId.startsWith("chunk:")) {
			return null;
		}
		try {
			return Long.parseLong(customId.substring("chunk:".length()));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	// 메소드 설명: expectedOutputLines 처리 흐름을 수행합니다.
	private long expectedOutputLines(LawSemanticBatchJobRow job) {
		if (job.completedCount() > 0) {
			return job.completedCount();
		}
		return job.submittedCount();
	}

	// 메소드 설명: flushVectors 처리 흐름을 수행합니다.
	private int flushVectors(Map<Long, List<Double>> vectors, LawSemanticBatchJobRow job, Set<Long> indexedChunkIds) {
		List<Long> chunkIds = new ArrayList<>(vectors.keySet());
		List<LawSemanticChunkRow> chunks = findSemanticChunksByIds(job.target(), chunkIds);
		upsertVectors(job.target(), chunks, vectors);
		transactionTemplate.executeWithoutResult(status -> {
			for (LawSemanticChunkRow chunk : chunks) {
				if (vectors.containsKey(chunk.chunkId())) {
					upsertEmbeddingStatus(
						job.target(),
						chunk.chunkId(),
						job.embeddingModel(),
						job.vectorStore(),
						vectorPointId(job.target(), chunk.chunkId()),
						chunk.contentHash(),
						"INDEXED",
						null
					);
				}
			}
			if (!chunks.isEmpty()) {
				List<Long> indexedIds = chunks.stream().map(LawSemanticChunkRow::chunkId).toList();
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				batchJobChunkMapper.markIndexed(job.batchJobId(), indexedIds);
				indexedChunkIds.addAll(indexedIds);
			}
		});
		return chunks.size();
	}

	// 메소드 설명: parseEmbeddingLine 처리 흐름을 수행합니다.
	private BatchEmbeddingResult parseEmbeddingLine(String line) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			JsonNode root = objectMapper.readTree(line);
			String customId = root.get("custom_id").asText();
			if (!customId.startsWith("chunk:")) {
				return null;
			}
			JsonNode response = root.get("response");
			if (response == null || response.get("status_code").asInt() != 200) {
				return BatchEmbeddingResult.failed(
					customId,
					errorCode(root),
					errorMessage(root)
				);
			}
			JsonNode embeddingNode = response.get("body").get("data").get(0).get("embedding");
			List<Double> vector = embeddingNode.valueStream()
				.map(JsonNode::asDouble)
				.toList();
			return BatchEmbeddingResult.success(Long.parseLong(customId.substring("chunk:".length())), customId, vector);
		} catch (Exception exception) {
			return null;
		}
	}

	// 메소드 설명: writeInputFile 처리 흐름을 수행합니다.
	private Path writeInputFile(String target, String query, String model, List<LawSemanticChunkRow> chunks) {
		Path directory = Path.of("target", "openai-batches");
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		String queryPart = query.isBlank() ? "" : "-" + query.replaceAll("[^A-Za-z0-9가-힣_-]", "_");
		Path file = directory.resolve("embedding-" + (target.isBlank() ? "all" : target) + queryPart + "-" + timestamp + ".jsonl");
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.createDirectories(directory);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			List<String> lines = chunks.stream().map(chunk -> jsonWriter.write(Map.of(
				"custom_id", "chunk:" + chunk.chunkId(),
				"method", "POST",
				"url", "/v1/embeddings",
				"body", Map.of(
					"model", model,
					"input", chunk.embeddingInput()
				)
			))).toList();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.write(file, lines, StandardCharsets.UTF_8);
		} catch (Exception exception) {
			throw new IllegalStateException("OpenAI Batch input file creation failed.", exception);
		}
		return file;
	}

	// 메소드 설명: markSubmitted 처리 흐름을 수행합니다.
	private void markSubmitted(List<LawSemanticChunkRow> chunks, String model, String vectorStore) {
		for (LawSemanticChunkRow chunk : chunks) {
			upsertEmbeddingStatus(
				chunk.target(),
				chunk.chunkId(),
				model,
				vectorStore,
				vectorPointId(chunk.target(), chunk.chunkId()),
				chunk.contentHash(),
				"BATCH_SUBMITTED",
				null
			);
		}
	}

	// 메소드 설명: markSubmittedFromInputFile 처리 흐름을 수행합니다.
	private void markSubmittedFromInputFile(Path inputFile, String model, String vectorStore) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
			List<Long> chunkIds = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				JsonNode root = objectMapper.readTree(line);
				String customId = root.get("custom_id").asText();
				if (customId.startsWith("chunk:")) {
					chunkIds.add(Long.parseLong(customId.substring("chunk:".length())));
				}
				if (chunkIds.size() >= QDRANT_UPSERT_BATCH_SIZE) {
					markSubmittedIds(chunkIds, model, vectorStore);
					chunkIds.clear();
				}
			}
			if (!chunkIds.isEmpty()) {
				markSubmittedIds(chunkIds, model, vectorStore);
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to mark submitted chunks from batch input file.", exception);
		}
	}

	// 메소드 설명: markSubmittedIds 처리 흐름을 수행합니다.
	private void markSubmittedIds(List<Long> chunkIds, String model, String vectorStore) {
		for (LawSemanticChunkRow chunk : findSemanticChunksByIds("", chunkIds)) {
			upsertEmbeddingStatus(
				chunk.target(),
				chunk.chunkId(),
				model,
				vectorStore,
				vectorPointId(chunk.target(), chunk.chunkId()),
				chunk.contentHash(),
				"BATCH_SUBMITTED",
				null
			);
		}
	}

	private List<LawSemanticChunkRow> findSemanticIndexCandidates(
		String target,
		String query,
		String model,
		String vectorStore,
		int limit
	) {
		if (isRagTarget(target)) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return ragDocumentMapper.findSemanticIndexCandidates(target, query, model, vectorStore, limit);
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return lawChunkMapper.findSemanticIndexCandidates(target, query, model, vectorStore, limit);
	}

	// 메소드 설명: findSemanticChunksByIds 처리 흐름을 수행합니다.
	private List<LawSemanticChunkRow> findSemanticChunksByIds(String target, List<Long> chunkIds) {
		if (chunkIds == null || chunkIds.isEmpty()) {
			return List.of();
		}
		if (isRagTarget(target)) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return ragDocumentMapper.findSemanticChunksByIds(chunkIds);
		}
		if (target == null || target.isBlank()) {
			Map<String, LawSemanticChunkRow> combined = new LinkedHashMap<>();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			for (LawSemanticChunkRow chunk : lawChunkMapper.findSemanticChunksByIds(chunkIds)) {
				combined.put(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			}
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			for (LawSemanticChunkRow chunk : ragDocumentMapper.findSemanticChunksByIds(chunkIds)) {
				combined.putIfAbsent(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			}
			return new ArrayList<>(combined.values());
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return lawChunkMapper.findSemanticChunksByIds(chunkIds);
	}

	// 메소드 설명: staleSubmittedMinutes 처리 흐름을 수행합니다.
	private int staleSubmittedMinutes() {
		return properties.batch().staleSubmittedMinutes();
	}

	// 메소드 설명: scoreKey 처리 흐름을 수행합니다.
	private String scoreKey(String target, long chunkId) {
		return (target == null ? "" : target) + ":" + chunkId;
	}

	// 메소드 설명: upsertVectors 처리 흐름을 수행합니다.
	private void upsertVectors(String target, List<LawSemanticChunkRow> chunks, Map<Long, List<Double>> vectorsByChunkId) {
		if (isRagTarget(target)) {
			List<LawSemanticChunkRow> orderedChunks = chunks.stream()
				.filter(chunk -> vectorsByChunkId.containsKey(chunk.chunkId()))
				.toList();
			List<List<Double>> vectors = orderedChunks.stream()
				.map(chunk -> vectorsByChunkId.get(chunk.chunkId()))
				.toList();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			qdrantClient.upsertRag(orderedChunks, vectors);
			return;
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		qdrantClient.upsertVectors(chunks, vectorsByChunkId);
	}

	private void upsertEmbeddingStatus(
		String target,
		long chunkId,
		String model,
		String vectorStore,
		String vectorPointId,
		String contentHash,
		String status,
		String errorMessage
	) {
		if (isRagTarget(target)) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			ragDocumentMapper.upsertEmbeddingStatus(chunkId, model, vectorStore, vectorPointId, contentHash, status, errorMessage);
			return;
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawChunkMapper.upsertEmbeddingStatus(chunkId, model, vectorStore, vectorPointId, contentHash, status, errorMessage);
	}

	// 메소드 설명: vectorPointId 처리 흐름을 수행합니다.
	private String vectorPointId(String target, long chunkId) {
		return isRagTarget(target) ? String.valueOf(QdrantClient.ragPointId(chunkId)) : String.valueOf(chunkId);
	}

	// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
	private boolean isRagTarget(String target) {
		return "official_doc".equals(target) || "internal_doc".equals(target) || "reference_doc".equals(target);
	}

	private void insertJobChunks(
		long batchJobId,
		String openaiBatchId,
		String target,
		List<LawSemanticChunkRow> chunks
	) {
		List<LawSemanticBatchJobChunkRow> rows = new ArrayList<>();
		for (LawSemanticChunkRow chunk : chunks) {
			rows.add(LawSemanticBatchJobChunkRow.submitted(batchJobId, openaiBatchId, target, chunk.chunkId()));
			if (rows.size() >= DB_WRITE_BATCH_SIZE) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				batchJobChunkMapper.insertChunks(rows);
				rows.clear();
			}
		}
		if (!rows.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			batchJobChunkMapper.insertChunks(rows);
		}
	}

	// 메소드 설명: backfillJobChunks 처리 흐름을 수행합니다.
	private int backfillJobChunks(LawSemanticBatchJobRow job) {
		if (job == null || job.inputFilePath() == null || job.inputFilePath().isBlank()) {
			return 0;
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		int existingCount = batchJobChunkMapper.countByBatchJobId(job.batchJobId());
		if (existingCount >= job.submittedCount()) {
			return 0;
		}
		Path inputFile = Path.of(job.inputFilePath());
		int inserted = 0;
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (BufferedReader reader = Files.newBufferedReader(inputFile, StandardCharsets.UTF_8)) {
			List<LawSemanticBatchJobChunkRow> rows = new ArrayList<>();
			String line;
			while ((line = reader.readLine()) != null) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				JsonNode root = objectMapper.readTree(line);
				String customId = root.get("custom_id").asText();
				if (!customId.startsWith("chunk:")) {
					continue;
				}
				long chunkId = Long.parseLong(customId.substring("chunk:".length()));
				rows.add(LawSemanticBatchJobChunkRow.submitted(job.batchJobId(), job.openaiBatchId(), job.target(), chunkId));
				if (rows.size() >= DB_WRITE_BATCH_SIZE) {
					// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
					batchJobChunkMapper.insertChunks(rows);
					inserted += rows.size();
					rows.clear();
				}
			}
			if (!rows.isEmpty()) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				batchJobChunkMapper.insertChunks(rows);
				inserted += rows.size();
			}
			if (job.ingestedCount() > 0 || "INGESTED".equals(job.status())) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				batchJobChunkMapper.markIndexedFromEmbeddingStatus(job.batchJobId(), job.embeddingModel(), job.vectorStore());
			}
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to backfill batch job chunks from input file.", exception);
		}
		return inserted;
	}

	// 메소드 설명: errorCode 처리 흐름을 수행합니다.
	private String errorCode(JsonNode root) {
		JsonNode error = root.findValue("error");
		if (error != null && error.get("code") != null) {
			return error.get("code").asText();
		}
		JsonNode response = root.get("response");
		if (response != null && response.get("status_code") != null) {
			return "HTTP_" + response.get("status_code").asInt();
		}
		return "UNKNOWN";
	}

	// 메소드 설명: errorMessage 처리 흐름을 수행합니다.
	private String errorMessage(JsonNode root) {
		JsonNode error = root.findValue("error");
		if (error != null && error.get("message") != null) {
			return error.get("message").asText();
		}
		return "OpenAI Batch embedding request failed.";
	}

	// 메소드 설명: toResponse 처리 흐름을 수행합니다.
	private LawSemanticBatchJobResponse toResponse(LawSemanticBatchJobRow row) {
		if (row == null) {
			return null;
		}
		return new LawSemanticBatchJobResponse(
			row.batchJobId(),
			row.openaiBatchId(),
			row.status(),
			row.inputFileId(),
			row.outputFileId(),
			row.errorFileId(),
			row.target(),
			row.queryText(),
			row.inputFilePath(),
			row.outputFilePath(),
			row.requestedCount(),
			row.submittedCount(),
			row.completedCount(),
			row.failedCount(),
			row.ingestedCount()
		);
	}

	private record BatchEmbeddingResult(
		long chunkId,
		String customId,
		List<Double> vector,
		boolean success,
		String errorCode,
		String errorMessage
	) {
		// 메소드 설명: success 처리 흐름을 수행합니다.
		private static BatchEmbeddingResult success(long chunkId, String customId, List<Double> vector) {
			return new BatchEmbeddingResult(chunkId, customId, vector, true, null, null);
		}

		// 메소드 설명: failed 처리 흐름을 수행합니다.
		private static BatchEmbeddingResult failed(String customId, String errorCode, String errorMessage) {
			return new BatchEmbeddingResult(0, customId, List.of(), false, errorCode, errorMessage);
		}
	}

	// 메소드 설명: LineStats 처리 흐름을 수행합니다.
	private record LineStats(long lineCount, boolean hasJsonLine) {
	}
}
