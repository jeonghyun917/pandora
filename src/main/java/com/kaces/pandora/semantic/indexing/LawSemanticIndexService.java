package com.kaces.pandora.semantic.indexing;


import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.common.json.LawJsonWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LawSemanticIndexService {

	private static final int EMBEDDING_BATCH_SIZE = 100;
	private static final int MAX_DIRECT_INDEX_LIMIT = 10_000;
	private static final int MAX_OPENAI_BATCH_INPUTS = 50_000;
	private static final int STATUS_UPDATE_ATTEMPTS = 3;

	private final LawChunkMapper lawChunkMapper;
	private final LawAiProperties properties;
	private final OpenAiEmbeddingClient embeddingClient;
	private final QdrantClient qdrantClient;
	private final LawJsonWriter jsonWriter;

	public LawSemanticIndexService(
		LawChunkMapper lawChunkMapper,
		LawAiProperties properties,
		OpenAiEmbeddingClient embeddingClient,
		QdrantClient qdrantClient,
		LawJsonWriter jsonWriter
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.properties = properties;
		this.embeddingClient = embeddingClient;
		this.qdrantClient = qdrantClient;
		this.jsonWriter = jsonWriter;
	}

	// 메소드 설명: ensureCollection 처리 흐름을 수행합니다.
	public void ensureCollection() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		qdrantClient.ensureCollection();
	}

	// 메소드 설명: indexSample 처리 흐름을 수행합니다.
	public LawSemanticIndexResult indexSample(String target, String query, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_DIRECT_INDEX_LIMIT));
		String normalizedTarget = target == null ? "" : target.trim();
		String normalizedQuery = query == null ? "" : query.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<LawSemanticChunkRow> chunks = lawChunkMapper.findSemanticIndexCandidates(
			normalizedTarget,
			normalizedQuery,
			model,
			vectorStore,
			safeLimit
		);

		return indexChunks(chunks, model, vectorStore, safeLimit);
	}

	public LawSemanticIndexResult indexDocuments(String target, List<Long> documentIds, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_DIRECT_INDEX_LIMIT));
		String normalizedTarget = target == null ? "" : target.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		List<Long> safeDocumentIds = documentIds == null ? List.of() : documentIds.stream()
			.filter(id -> id != null && id > 0)
			.distinct()
			.toList();
		if (safeDocumentIds.isEmpty()) {
			return new LawSemanticIndexResult(vectorStore, model, 0, 0);
		}
		List<LawSemanticChunkRow> chunks = lawChunkMapper.findSemanticIndexCandidatesByDocumentIds(
			normalizedTarget,
			safeDocumentIds,
			model,
			vectorStore,
			safeLimit
		);
		return indexChunks(chunks, model, vectorStore, chunks.size());
	}

	public LawSemanticIndexResult indexCandidate(String target, long documentId, int candidateVersion, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_DIRECT_INDEX_LIMIT));
		if (documentId <= 0 || candidateVersion <= 0) {
			return new LawSemanticIndexResult(properties.qdrant().collection(), properties.openai().embeddingModel(), 0, 0);
		}
		String normalizedTarget = target == null ? "" : target.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		List<LawSemanticChunkRow> chunks = lawChunkMapper.findSemanticIndexCandidatesByDocumentIdAndVersion(
			normalizedTarget, documentId, candidateVersion, model, vectorStore, safeLimit);
		return indexCandidateChunks(chunks, model, vectorStore, chunks.size());
	}

	public LawSemanticIndexResult indexExactChunks(List<LawSemanticChunkRow> chunks) {
		return indexExactChunks(chunks, () -> { });
	}

	/** Exact repair hook used to fence every remote or durable mutation phase. */
	public LawSemanticIndexResult indexExactChunks(List<LawSemanticChunkRow> chunks, Runnable ownershipCheckpoint) {
		List<LawSemanticChunkRow> safeChunks = chunks == null ? List.of() : List.copyOf(chunks);
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		return indexChunks(safeChunks, model, vectorStore, safeChunks.size(), ownershipCheckpoint);
	}

	private LawSemanticIndexResult indexCandidateChunks(
		List<LawSemanticChunkRow> chunks, String model, String vectorStore, int requested
	) {
		int indexed = 0;
		for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
			int end = Math.min(chunks.size(), start + EMBEDDING_BATCH_SIZE);
			List<LawSemanticChunkRow> batch = chunks.subList(start, end);
			List<List<Double>> vectors = embeddingClient.embed(batch.stream().map(LawSemanticChunkRow::embeddingInput).toList());
			qdrantClient.upsertLawCandidates(batch, vectors);
			for (LawSemanticChunkRow chunk : batch) {
				markChunkIndexed(chunk, model, vectorStore);
			}
			indexed += batch.size();
		}
		return new LawSemanticIndexResult(qdrantClient.lawCandidateCollection(), model, requested, indexed);
	}

	private LawSemanticIndexResult indexChunks(
		List<LawSemanticChunkRow> chunks,
		String model,
		String vectorStore,
		int requested
	) {
		return indexChunks(chunks, model, vectorStore, requested, () -> { });
	}

	private LawSemanticIndexResult indexChunks(
		List<LawSemanticChunkRow> chunks,
		String model,
		String vectorStore,
		int requested,
		Runnable ownershipCheckpoint
	) {
		Runnable checkpoint = ownershipCheckpoint == null ? () -> { } : ownershipCheckpoint;
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		checkpoint.run();
		qdrantClient.ensureCollection();
		int indexed = 0;
		for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
			int end = Math.min(chunks.size(), start + EMBEDDING_BATCH_SIZE);
			List<LawSemanticChunkRow> batch = chunks.subList(start, end);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			checkpoint.run();
			List<List<Double>> vectors = embeddingClient.embed(batch.stream().map(LawSemanticChunkRow::embeddingInput).toList());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			checkpoint.run();
			qdrantClient.upsert(batch, vectors);
			for (LawSemanticChunkRow chunk : batch) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				checkpoint.run();
				markChunkIndexed(chunk, model, vectorStore, checkpoint);
			}
			indexed += batch.size();
		}

		return new LawSemanticIndexResult(vectorStore, model, requested, indexed);
	}

	private void markChunkIndexed(LawSemanticChunkRow chunk, String model, String vectorStore) {
		markChunkIndexed(chunk, model, vectorStore, () -> { });
	}

	private void markChunkIndexed(
		LawSemanticChunkRow chunk, String model, String vectorStore, Runnable ownershipCheckpoint
	) {
		RuntimeException lastException = null;
		for (int attempt = 1; attempt <= STATUS_UPDATE_ATTEMPTS; attempt++) {
			try {
				ownershipCheckpoint.run();
				lawChunkMapper.upsertEmbeddingStatus(
					chunk.chunkId(),
					model,
					vectorStore,
					String.valueOf(chunk.chunkId()),
					chunk.contentHash(),
					"INDEXED",
					null
				);
				ownershipCheckpoint.run();
				lawChunkMapper.updateChunkIndexStatus(chunk.chunkId(), "INDEXED", null);
				return;
			} catch (RuntimeException exception) {
				lastException = exception;
				if (attempt == STATUS_UPDATE_ATTEMPTS || !isConcurrentStatusUpdate(exception)) {
					throw exception;
				}
				sleepBeforeStatusRetry(attempt);
			}
		}
		throw lastException;
	}

	private boolean isConcurrentStatusUpdate(RuntimeException exception) {
		String message = String.valueOf(exception.getMessage()).toLowerCase();
		return message.contains("record has changed since last read")
			|| message.contains("deadlock")
			|| message.contains("lock wait timeout");
	}

	private void sleepBeforeStatusRetry(int attempt) {
		try {
			Thread.sleep(150L * attempt);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Interrupted while retrying semantic index status update.", exception);
		}
	}

	// 메소드 설명: createBatchFile 처리 흐름을 수행합니다.
	public LawSemanticBatchFileResult createBatchFile(String target, String query, int limit) {
		int safeLimit = Math.max(1, Math.min(limit, MAX_OPENAI_BATCH_INPUTS));
		String normalizedTarget = target == null ? "" : target.trim();
		String normalizedQuery = query == null ? "" : query.trim();
		String model = properties.openai().embeddingModel();
		String vectorStore = properties.qdrant().collection();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<LawSemanticChunkRow> chunks = lawChunkMapper.findSemanticIndexCandidates(
			normalizedTarget,
			normalizedQuery,
			model,
			vectorStore,
			safeLimit
		);
		Path directory = Path.of("target", "openai-batches");
		String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
		Path file = directory.resolve("embedding-" + (normalizedTarget.isBlank() ? "all" : normalizedTarget) + "-" + timestamp + ".jsonl");
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
			throw new IllegalStateException("OpenAI Batch 입력 파일 생성에 실패했습니다.", exception);
		}
		return new LawSemanticBatchFileResult(file.toAbsolutePath().toString(), model, normalizedTarget, safeLimit, chunks.size());
	}
}
