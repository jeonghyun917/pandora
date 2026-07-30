package com.kaces.pandora.app.admin;

import com.kaces.pandora.app.admin.AdminOverviewResponse.AdminBatchStatus;
import com.kaces.pandora.app.admin.AdminOverviewResponse.AdminImportStatus;
import com.kaces.pandora.app.admin.AdminOverviewResponse.AdminMetric;
import com.kaces.pandora.app.admin.AdminOverviewResponse.AdminPipelineBreakdown;
import com.kaces.pandora.app.admin.AdminOverviewResponse.AdminPipelineStatus;
import com.kaces.pandora.app.admin.AdminOverviewResponse.AdminSourceStatus;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AdminOverviewService {
	private static final Logger log = LoggerFactory.getLogger(AdminOverviewService.class);
	private static final DateTimeFormatter GENERATED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final long OVERVIEW_CACHE_NANOS = Duration.ofSeconds(30).toNanos();
	private static final long EVAL_GATE_TARGET_CASES = 500;
	private static final Duration QDRANT_ADMIN_TIMEOUT = Duration.ofSeconds(2);
	private final JdbcTemplate jdbcTemplate;
	private final LawAiProperties properties;
	private final ObjectMapper objectMapper;
	private final RestClient qdrantAdminClient;
	private volatile CachedAdminOverview pipelineCache;
	private volatile CachedAdminOverview operationsCache;
	private final AtomicBoolean pipelineRefreshRunning = new AtomicBoolean(false);
	private final AtomicBoolean operationsRefreshRunning = new AtomicBoolean(false);

	public AdminOverviewService(JdbcTemplate jdbcTemplate, LawAiProperties properties, ObjectMapper objectMapper) {
		this.jdbcTemplate = jdbcTemplate;
		this.properties = properties;
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(QDRANT_ADMIN_TIMEOUT);
		requestFactory.setReadTimeout(QDRANT_ADMIN_TIMEOUT);
		this.qdrantAdminClient = RestClient.builder()
			.baseUrl(properties.qdrant().baseUrl())
			.requestFactory(requestFactory)
			.build();
	}

	@EventListener(ApplicationReadyEvent.class)
	public void warmOverviewCaches() {
		refreshPipelineCacheAsync();
		refreshOperationsCacheAsync();
	}

	public AdminOverviewResponse overview() {
		List<AdminPipelineStatus> pipelines = pipelines();
		List<AdminSourceStatus> sources = sources();
		List<AdminBatchStatus> batches = recentBatches();
		List<AdminImportStatus> imports = recentImports();
		return new AdminOverviewResponse(
			LocalDateTime.now().format(GENERATED_FORMAT),
			metrics(pipelines, sources, batches, imports),
			pipelines,
			sources,
			batches,
			imports
		);
	}

	public AdminOverviewResponse pipelineOverview(boolean refresh) {
		CachedAdminOverview cached = pipelineCache;
		long now = System.nanoTime();
		if (!refresh && cached != null) {
			if (!cached.isFresh(now)) {
				refreshPipelineCacheAsync();
			}
			return cached.response();
		}
		return refreshPipelineCacheSync();
	}

	public AdminOverviewResponse operationsOverview(boolean refresh) {
		CachedAdminOverview cached = operationsCache;
		long now = System.nanoTime();
		if (!refresh && cached != null) {
			if (!cached.isFresh(now)) {
				refreshOperationsCacheAsync();
			}
			return cached.response();
		}
		return refreshOperationsCacheSync();
	}

	private AdminOverviewResponse refreshPipelineCacheSync() {
		long now = System.nanoTime();
		List<AdminPipelineStatus> pipelines = pipelines();
		AdminOverviewResponse response = new AdminOverviewResponse(
			LocalDateTime.now().format(GENERATED_FORMAT),
			qualityMetrics(),
			pipelines,
			List.of(),
			List.of(),
			List.of()
		);
		pipelineCache = new CachedAdminOverview(response, now);
		return response;
	}

	private AdminOverviewResponse refreshOperationsCacheSync() {
		long now = System.nanoTime();
		List<AdminSourceStatus> sources = sources();
		List<AdminBatchStatus> batches = recentBatches();
		List<AdminImportStatus> imports = recentImports();
		AdminOverviewResponse response = new AdminOverviewResponse(
			LocalDateTime.now().format(GENERATED_FORMAT),
			qualityMetrics(),
			List.of(),
			sources,
			batches,
			imports
		);
		operationsCache = new CachedAdminOverview(response, now);
		return response;
	}

	private void refreshPipelineCacheAsync() {
		if (!pipelineRefreshRunning.compareAndSet(false, true)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				refreshPipelineCacheSync();
			} catch (RuntimeException e) {
				log.warn("Failed to refresh admin pipeline cache", e);
			} finally {
				pipelineRefreshRunning.set(false);
			}
		});
	}

	private void refreshOperationsCacheAsync() {
		if (!operationsRefreshRunning.compareAndSet(false, true)) {
			return;
		}
		CompletableFuture.runAsync(() -> {
			try {
				refreshOperationsCacheSync();
			} catch (RuntimeException e) {
				log.warn("Failed to refresh admin operations cache", e);
			} finally {
				operationsRefreshRunning.set(false);
			}
		});
	}

	private List<AdminMetric> metrics(
		List<AdminPipelineStatus> pipelines,
		List<AdminSourceStatus> sources,
		List<AdminBatchStatus> batches,
		List<AdminImportStatus> imports
	) {
		long documents = pipelines.stream().mapToLong(AdminPipelineStatus::documents).sum();
		long chunks = pipelines.stream().mapToLong(AdminPipelineStatus::chunks).sum();
		long indexed = pipelines.stream().mapToLong(AdminPipelineStatus::indexedChunks).sum();
		long activeBatches = pipelines.stream().mapToLong(AdminPipelineStatus::activeBatches).sum();
		long sourceWarnings = sources.stream().filter(source -> !"정상".equals(source.status())).count();
		long failedImports = imports.stream().filter(importJob -> "FAILED".equals(importJob.status())).count();
		return List.of(
			new AdminMetric("documents", "전체 문서", documents, "법령/행정규칙/RAG 문서 합계", "normal"),
			new AdminMetric("chunks", "청크 생성", chunks, "검색 후보로 분해된 본문 청크", "normal"),
			new AdminMetric("indexed", "임베딩 완료", indexed, "Qdrant 인덱싱 완료 기준", "good"),
			new AdminMetric("activeBatches", "진행 Batch", activeBatches, "OpenAI Batch 진행/인입 대기", activeBatches > 0 ? "warn" : "normal"),
			new AdminMetric("sourceWarnings", "수집 점검", sourceWarnings, "오류 또는 미확인 수집원", sourceWarnings > 0 ? "warn" : "good"),
			new AdminMetric("failedImports", "최근 실패", failedImports, "최근 import 실패 작업", failedImports > 0 ? "bad" : "good")
		);
	}

	private List<AdminMetric> qualityMetrics() {
		long residualTiny = queryLong("""
			SELECT COUNT(*)
			FROM law_api_document_chunks c
			JOIN law_api_documents d ON d.document_id = c.document_id
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND CHAR_LENGTH(c.chunk_text) < 80
			""");
		long lowSignalTiny = queryLong("""
			SELECT COUNT(*)
			FROM law_api_document_chunks c
			JOIN law_api_documents d ON d.document_id = c.document_id
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND CHAR_LENGTH(c.chunk_text) < 80
			  AND (
			    LOWER(c.chunk_text) LIKE '%<img%'
			    OR c.chunk_text LIKE '%상단%'
			    OR c.chunk_text LIKE '%첨부%'
			    OR c.chunk_text LIKE '%메뉴%'
			    OR c.chunk_text LIKE '%다운로드%'
			  )
			""");
		long ragShortChunks = queryLong("""
			SELECT COUNT(*)
			FROM rag_document_chunks c
			JOIN rag_documents d ON d.document_id = c.document_id
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND c.chunk_version = (
			    SELECT MAX(c2.chunk_version)
			    FROM rag_document_chunks c2
			    WHERE c2.document_id = c.document_id
			      AND c2.use_yn = 'Y'
			  )
			  AND CHAR_LENGTH(c.chunk_text) < 120
			""");
		long lawEmbeddingBacklog = queryLong("""
			SELECT COUNT(*)
			FROM law_api_document_chunks c
			JOIN law_api_documents d ON d.document_id = c.document_id
			LEFT JOIN law_api_chunk_embeddings e
			  ON e.chunk_id = c.chunk_id
			 AND e.embedding_model = 'text-embedding-3-small'
			 AND e.vector_store = 'law_chunks'
			 AND e.status = 'INDEXED'
			 AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND d.target IN ('law', 'admrul')
			  AND e.chunk_id IS NULL
			""");
		long ragEmbeddingBacklog = queryLong("""
			SELECT COUNT(*)
			FROM rag_document_chunks c
			JOIN rag_documents d ON d.document_id = c.document_id
			LEFT JOIN rag_chunk_embeddings e
			  ON e.chunk_id = c.chunk_id
			 AND e.embedding_model = 'text-embedding-3-small'
			 AND e.vector_store = 'rag_chunks_v4'
			 AND e.status = 'INDEXED'
			 AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND d.document_type = 'official_doc'
			  AND c.chunk_version = (
			    SELECT MAX(c2.chunk_version)
			    FROM rag_document_chunks c2
			    WHERE c2.document_id = c.document_id
			      AND c2.use_yn = 'Y'
			  )
			  AND e.chunk_id IS NULL
			""");
		long embeddingBacklog = lawEmbeddingBacklog + ragEmbeddingBacklog;
		long openFailureCandidates = queryLong("""
			SELECT COUNT(*)
			FROM law_ai_search_failure_logs
			WHERE review_status = 'OPEN'
			  AND eval_candidate = 1
			  AND created_at >= DATE_SUB(NOW(), INTERVAL 14 DAY)
			""");
		QdrantDeltaSummary qdrantDelta = qdrantDeltaSummary();
		EvalGateSummary evalGate = latestEvalGateSummary().orElse(EvalGateSummary.missing());
		RagShortAuditSummary ragShortAudit = latestRagShortAuditSummary().orElse(RagShortAuditSummary.missing());
		ResidualTinyAuditSummary residualTinyAudit = latestResidualTinyAuditSummary().orElse(ResidualTinyAuditSummary.missing());
		return List.of(
			new AdminMetric("qualityEmbeddingBacklog", "임베딩 대기", embeddingBacklog, "현행 법령/행정규칙/공식문서 청크 중 현재 기준 임베딩이 없는 건", embeddingBacklog == 0 ? "good" : "bad"),
			new AdminMetric("qualityQdrantDelta", "Qdrant 정합성", qdrantDelta.value(), qdrantDelta.detail(), qdrantDelta.tone()),
			new AdminMetric("qualityEvalGate", "평가셋 통과율", evalGate.passRatePercent(), evalGate.detail(), evalGate.tone()),
			new AdminMetric("qualityOpenFailures", "검토 대기 질문", openFailureCandidates, "최근 14일 내 평가셋 후보로 남은 미처리 실패 질문", openFailureCandidates == 0 ? "good" : "warn"),
			new AdminMetric("qualityResidualTiny", "짧은 법령 청크", residualTiny, "현행 법령/행정규칙 청크 중 80자 미만인 건", residualTiny == 0 ? "good" : "warn"),
			new AdminMetric("qualityResidualReview", "짧은 법령 검토", residualTinyAudit.manualReview(), residualTinyAudit.detail(), residualTinyAudit.tone()),
			new AdminMetric("qualityLowSignalTiny", "저품질 짧은 청크", lowSignalTiny, "이미지/메뉴/첨부/다운로드성 짧은 법령 청크", lowSignalTiny == 0 ? "good" : "warn"),
			new AdminMetric("qualityRagShort", "짧은 RAG 청크", ragShortChunks, "최신 RAG 문서 청크 중 120자 미만인 건", ragShortChunks == 0 ? "good" : "warn"),
			new AdminMetric("qualityRagShortNoisy", "정리 대상 RAG 청크", ragShortAudit.noisyCandidates(), ragShortAudit.noisyDetail(), ragShortAudit.tone()),
			new AdminMetric("qualityRagShortReview", "RAG 수동 검토", ragShortAudit.manualReview(), ragShortAudit.reviewDetail(), ragShortAudit.reviewTone())
		);
	}

	private QdrantDeltaSummary qdrantDeltaSummary() {
		Optional<Long> lawCount = qdrantExactCount(properties.qdrant().collection(), "law");
		Optional<Long> admrulCount = qdrantExactCount(properties.qdrant().collection(), "admrul");
		Optional<Long> officialCount = qdrantExactCount(properties.qdrant().ragCollection(), "official_doc");
		if (lawCount.isEmpty() || admrulCount.isEmpty() || officialCount.isEmpty()) {
			return new QdrantDeltaSummary(
				-1,
				"Qdrant 정확 집계를 읽지 못했습니다. 6333 포트와 진단 리포트를 확인하세요.",
				"bad"
			);
		}
		long lawDelta = lawCount.get() - indexedLawChunks("law");
		long admrulDelta = admrulCount.get() - indexedLawChunks("admrul");
		long officialDelta = officialCount.get() - indexedOfficialChunks();
		long absoluteDelta = Math.abs(lawDelta) + Math.abs(admrulDelta) + Math.abs(officialDelta);
		String detail = "법령 " + lawDelta + ", 행정규칙 " + admrulDelta + ", 공식문서 " + officialDelta;
		return new QdrantDeltaSummary(absoluteDelta, detail, absoluteDelta == 0 ? "good" : "bad");
	}

	@SuppressWarnings("unchecked")
	private Optional<Long> qdrantExactCount(String collection, String target) {
		try {
			Map<String, Object> body = Map.of(
				"exact", true,
				"filter", Map.of(
					"must", List.of(Map.of(
						"key", "target",
						"match", Map.of("value", target)
					))
				)
			);
			Map<String, Object> response = qdrantAdminClient.post()
				.uri("/collections/{collection}/points/count", collection)
				.body(body)
				.retrieve()
				.body(Map.class);
			Object result = response == null ? null : response.get("result");
			if (!(result instanceof Map<?, ?> resultMap)) {
				return Optional.empty();
			}
			Object count = resultMap.get("count");
			if (count instanceof Number number) {
				return Optional.of(number.longValue());
			}
			if (count != null) {
				return Optional.of(Long.parseLong(String.valueOf(count)));
			}
			return Optional.empty();
		} catch (RuntimeException exception) {
			log.warn("Failed to read Qdrant exact count. collection={} target={} message={}",
				collection,
				target,
				exception.getMessage()
			);
			return Optional.empty();
		}
	}

	private long indexedLawChunks(String target) {
		return queryLong("""
			SELECT COUNT(*)
			FROM law_api_chunk_embeddings e
			JOIN law_api_document_chunks c ON c.chunk_id = e.chunk_id
			JOIN law_api_documents d ON d.document_id = c.document_id
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND d.target = ?
			  AND e.embedding_model = 'text-embedding-3-small'
			  AND e.vector_store = 'law_chunks'
			  AND e.status = 'INDEXED'
			  AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			""", target);
	}

	private long indexedOfficialChunks() {
		return queryLong("""
			SELECT COUNT(*)
			FROM rag_chunk_embeddings e
			JOIN rag_document_chunks c ON c.chunk_id = e.chunk_id
			JOIN rag_documents d ON d.document_id = c.document_id
			WHERE d.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			  AND d.document_type = 'official_doc'
			  AND c.chunk_version = (
			    SELECT MAX(c2.chunk_version)
			    FROM rag_document_chunks c2
			    WHERE c2.document_id = c.document_id
			      AND c2.use_yn = 'Y'
			  )
			  AND e.embedding_model = 'text-embedding-3-small'
			  AND e.vector_store = 'rag_chunks_v4'
			  AND e.status = 'INDEXED'
			  AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			""");
	}

	private Optional<EvalGateSummary> latestEvalGateSummary() {
		Path logDir = Path.of("logs");
		if (!Files.isDirectory(logDir)) {
			return Optional.empty();
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(logDir, "rag-eval-gate*.json")) {
			List<EvalGateCandidate> candidates = new ArrayList<>();
			for (Path path : stream) {
				parseEvalGate(path).ifPresent(candidates::add);
			}
			return candidates.stream()
				.max(Comparator
					.comparingLong(EvalGateCandidate::total)
					.thenComparingLong(EvalGateCandidate::modifiedAtMillis))
				.map(EvalGateCandidate::summary);
		} catch (IOException exception) {
			log.warn("Failed to scan eval gate logs", exception);
			return Optional.empty();
		}
	}

	private Optional<EvalGateCandidate> parseEvalGate(Path path) {
		try {
			String fileName = path.getFileName().toString();
			if (fileName.contains("checkpoint")) {
				return Optional.empty();
			}
			JsonNode root = objectMapper.readTree(Files.readString(path));
			String runScope = root.path("provenance").path("runScope").asText("");
			boolean legacyFullArtifact = runScope.isBlank() && fileName.startsWith("rag-eval-gate-full-");
			if (!("full".equals(runScope) || legacyFullArtifact)) {
				return Optional.empty();
			}
			long total = root.path("total").asLong(-1L);
			long passed = root.path("passed").asLong(-1L);
			long failed = root.path("failed").asLong(-1L);
			boolean gatePassed = root.path("gatePassed").asBoolean(false);
			if (total < 0 || passed < 0 || failed < 0) {
				return Optional.empty();
			}
			String gitCommit = root.path("provenance").path("gitCommit").asText("");
			String datasetHash = root.path("provenance").path("datasetHash").asText("");
			String indexVersion = root.path("provenance").path("indexVersion").asText("");
			String generatedAt = root.path("provenance").path("generatedAt").asText("");
			long curatedTotal = root.path("breakdown").path("curated").path("total").asLong(0L);
			long curatedPassed = root.path("breakdown").path("curated").path("passed").asLong(0L);
			long generatedTotal = root.path("breakdown").path("generated").path("total").asLong(0L);
			long generatedPassed = root.path("breakdown").path("generated").path("passed").asLong(0L);
			long answerRequired = root.path("breakdown").path("answerVerification").path("required").asLong(0L);
			long answerPassed = root.path("breakdown").path("answerVerification").path("passed").asLong(0L);
			long modifiedAt = Files.getLastModifiedTime(path).toMillis();
			return Optional.of(new EvalGateCandidate(total, modifiedAt, new EvalGateSummary(
				total,
				passed,
				failed,
				gatePassed,
				path,
				gitCommit,
				datasetHash,
				indexVersion,
				generatedAt,
				curatedTotal,
				curatedPassed,
				generatedTotal,
				generatedPassed,
				answerRequired,
				answerPassed
			)));
		} catch (IOException exception) {
			log.warn("Failed to read eval gate log: {}", path, exception);
			return Optional.empty();
		}
	}

	private Optional<RagShortAuditSummary> latestRagShortAuditSummary() {
		Path path = Path.of("logs", "rag-short-chunk-audit-latest.json");
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try {
			JsonNode root = objectMapper.readTree(Files.readString(path));
			long total = root.path("total").asLong(-1L);
			if (total < 0) {
				return Optional.empty();
			}
			long keep = 0;
			long noisyCandidates = 0;
			long contextOnly = 0;
			long manualReview = 0;
			for (JsonNode row : root.path("summary")) {
				long chunks = row.path("chunks").asLong(0L);
				String action = row.path("action").asText("");
				if ("keep".equals(action)) {
					keep += chunks;
				} else if ("manual_review".equals(action)) {
					manualReview += chunks;
				} else if ("downrank_context_only".equals(action)) {
					contextOnly += chunks;
				} else if ("suppress_or_downrank".equals(action)
					|| "suppress_or_rechunk".equals(action)
					|| "merge_or_downrank".equals(action)) {
					noisyCandidates += chunks;
				}
			}
			return Optional.of(new RagShortAuditSummary(total, keep, noisyCandidates, contextOnly, manualReview, path));
		} catch (IOException exception) {
			log.warn("Failed to read RAG short audit log: {}", path, exception);
			return Optional.empty();
		}
	}

	private Optional<ResidualTinyAuditSummary> latestResidualTinyAuditSummary() {
		Path path = Path.of("logs", "residual-tiny-audit-latest.json");
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try {
			JsonNode root = objectMapper.readTree(Files.readString(path));
			long total = root.path("total").asLong(-1L);
			if (total < 0) {
				return Optional.empty();
			}
			long keep = 0;
			long lowSignal = 0;
			long manualReview = 0;
			for (JsonNode row : root.path("summary")) {
				long chunks = row.path("chunks").asLong(0L);
				String action = row.path("action").asText("");
				if ("keep".equals(action)) {
					keep += chunks;
				} else if ("manual_review".equals(action)) {
					manualReview += chunks;
				} else if ("suppress_or_downrank".equals(action)) {
					lowSignal += chunks;
				}
			}
			return Optional.of(new ResidualTinyAuditSummary(total, keep, lowSignal, manualReview, path));
		} catch (IOException exception) {
			log.warn("Failed to read residual tiny audit log: {}", path, exception);
			return Optional.empty();
		}
	}

	private long queryLong(String sql) {
		try {
			Long value = jdbcTemplate.queryForObject(sql, Long.class);
			return value == null ? 0L : value;
		} catch (RuntimeException e) {
			log.warn("Failed to read admin quality metric", e);
			return 0L;
		}
	}

	private long queryLong(String sql, Object... args) {
		try {
			Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
			return value == null ? 0L : value;
		} catch (RuntimeException e) {
			log.warn("Failed to read admin quality metric", e);
			return 0L;
		}
	}

	private List<AdminPipelineStatus> pipelines() {
		List<AdminPipelineStatus> rows = new ArrayList<>();
		Map<String, PipelineAggregate> aggregates = new HashMap<>();
		aggregates.putAll(lawAggregates());
		aggregates.putAll(ragAggregates());
		Map<String, BatchAggregate> batches = batchAggregates();
		List<AdminPipelineBreakdown> officialGuideBreakdowns = officialGuideBreakdowns();
		rows.add(pipeline("law", "법령 OpenAPI", "국가법령정보센터 API", "법령 목록/상세 OpenAPI + 상세 본문 청킹", aggregates, batches, List.of()));
		rows.add(pipeline("admrul", "행정규칙 OpenAPI", "국가법령정보센터 API", "행정규칙 목록/상세 OpenAPI + 상세 본문 청킹", aggregates, batches, List.of()));
		rows.add(pipeline("official_doc", "공식 가이드 문서", "RSS/API/폴더", "기관 RSS·첨부 다운로드 + data/rag-upload 공식문서 스캔", aggregates, batches, officialGuideBreakdowns));
		rows.add(pipeline("internal_doc", "내부 지침/업무 매뉴얼", "폴더 스캔", "data/rag-upload 내부문서 스캔", aggregates, batches, List.of()));
		rows.add(pipeline("reference_doc", "참고자료", "폴더 스캔", "data/rag-upload 참고자료 스캔", aggregates, batches, List.of()));
		return rows;
	}

	private AdminPipelineStatus pipeline(
		String target,
		String pageName,
		String sourceType,
		String fetchMethod,
		Map<String, PipelineAggregate> aggregates,
		Map<String, BatchAggregate> batches,
		List<AdminPipelineBreakdown> breakdowns
	) {
		PipelineAggregate aggregate = aggregates.getOrDefault(target, PipelineAggregate.empty());
		BatchAggregate batch = batches.getOrDefault(target, BatchAggregate.empty());
		boolean useBatchCounts = aggregate.chunks() == 0 && batch.submittedRequests() > 0;
		long chunks = useBatchCounts ? batch.submittedRequests() : aggregate.chunks();
		long indexedChunks = useBatchCounts ? batch.ingestedRequests() : aggregate.indexedChunks();
		long pendingChunks = useBatchCounts
			? Math.max(0, batch.submittedRequests() - batch.ingestedRequests())
			: Math.max(0, aggregate.chunks() - aggregate.indexedChunks());
		long chunkedDocuments = useBatchCounts && aggregate.documents() > 0
			? aggregate.documents()
			: aggregate.chunkedDocuments();
		return new AdminPipelineStatus(
			target,
			pageName,
			sourceType,
			fetchMethod,
			target,
			aggregate.documents(),
			chunkedDocuments,
			chunks,
			indexedChunks,
			pendingChunks,
			aggregate.failedEmbeddings(),
			batch.activeJobs(),
			batch.totalJobs(),
			batch.ingestedJobs(),
			aggregate.lastUpdatedAt(),
			pipelineStatus(chunks, indexedChunks, aggregate.failedEmbeddings(), batch.activeJobs()),
			breakdowns
		);
	}

	private Map<String, PipelineAggregate> lawAggregates() {
		Map<String, PipelineAggregate> result = new HashMap<>();
		jdbcTemplate.query("""
			SELECT
			  doc.target,
			  COUNT(*) AS documents,
			  DATE_FORMAT(MAX(doc.updated_at), '%Y-%m-%d %H:%i:%s') AS last_updated_at
			FROM law_api_documents doc
			WHERE doc.target IN ('law', 'admrul')
			  AND doc.use_yn = 'Y'
			GROUP BY doc.target
			""", rs -> {
			result.put(rs.getString("target"), new PipelineAggregate(
				rs.getLong("documents"),
				0,
				0,
				0,
				0,
				blankToDash(rs.getString("last_updated_at"))
			));
		});
		jdbcTemplate.query("""
			SELECT
			  doc.target,
			  COUNT(DISTINCT c.document_id) AS chunked_documents,
			  COUNT(*) AS chunks,
			  SUM(CASE WHEN c.index_status = 'INDEXED' THEN 1 ELSE 0 END) AS indexed_chunks,
			  SUM(CASE WHEN c.index_status IN ('FAILED', 'ERROR') THEN 1 ELSE 0 END) AS failed_embeddings,
			  DATE_FORMAT(MAX(c.updated_at), '%Y-%m-%d %H:%i:%s') AS last_updated_at
			FROM law_api_document_chunks c
			JOIN law_api_documents doc ON doc.document_id = c.document_id
			WHERE doc.target IN ('law', 'admrul')
			  AND doc.use_yn = 'Y'
			  AND c.use_yn = 'Y'
			GROUP BY doc.target
			""", rs -> {
			String target = rs.getString("target");
			PipelineAggregate existing = result.getOrDefault(target, PipelineAggregate.empty());
			result.put(target, new PipelineAggregate(
				existing.documents(),
				rs.getLong("chunked_documents"),
				rs.getLong("chunks"),
				rs.getLong("indexed_chunks"),
				rs.getLong("failed_embeddings"),
				blankToDash(rs.getString("last_updated_at"))
			));
		});
		return result;
	}

	private Map<String, PipelineAggregate> ragAggregates() {
		Map<String, PipelineAggregate> result = new HashMap<>();
		jdbcTemplate.query("""
			SELECT
			  doc.document_type,
			  COUNT(DISTINCT doc.document_id) AS documents,
			  COUNT(DISTINCT c.document_id) AS chunked_documents,
			  COUNT(DISTINCT c.chunk_id) AS chunks,
			  COUNT(DISTINCT CASE
			    WHEN e.status = 'INDEXED'
			      AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			    THEN c.chunk_id
			  END) AS indexed_chunks,
			  COUNT(DISTINCT CASE
			    WHEN e.status IN ('FAILED', 'ERROR')
			      AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			    THEN c.chunk_id
			  END) AS failed_embeddings,
			  DATE_FORMAT(MAX(doc.updated_at), '%Y-%m-%d %H:%i:%s') AS last_updated_at
			FROM rag_documents doc
			LEFT JOIN (
			  SELECT document_id, MAX(chunk_version) AS latest_version
			  FROM rag_document_chunks
			  WHERE use_yn = 'Y'
			  GROUP BY document_id
			) latest
			  ON latest.document_id = doc.document_id
			LEFT JOIN rag_document_chunks c
			  ON c.document_id = doc.document_id
			  AND c.use_yn = 'Y'
			  AND c.chunk_version = latest.latest_version
			LEFT JOIN rag_chunk_embeddings e
			  ON e.chunk_id = c.chunk_id
			  AND e.embedding_model = 'text-embedding-3-small'
			  AND e.vector_store = 'rag_chunks_v4'
			WHERE doc.document_type IN ('official_doc', 'internal_doc', 'reference_doc')
			  AND doc.use_yn = 'Y'
			GROUP BY doc.document_type
			""", rs -> {
			result.put(rs.getString("document_type"), new PipelineAggregate(
				rs.getLong("documents"),
				rs.getLong("chunked_documents"),
				rs.getLong("chunks"),
				rs.getLong("indexed_chunks"),
				rs.getLong("failed_embeddings"),
				blankToDash(rs.getString("last_updated_at"))
			));
		});
		return result;
	}

	private List<AdminPipelineBreakdown> officialGuideBreakdowns() {
		String publicDataCondition = """
			doc.source_org LIKE '%공공데이터%'
			OR doc.title LIKE '%공공데이터%'
			OR doc.file_name LIKE '%공공데이터%'
			OR COALESCE(doc.document_topic, '') LIKE '%공공데이터%'
			OR COALESCE(doc.document_category, '') LIKE '%공공데이터%'
			OR LOWER(COALESCE(doc.source_org, '')) LIKE '%data.go.kr%'
			""";
		String sql = """
			SELECT
			  labeled.agency_label,
			  labeled.agency_sort_order,
			  COUNT(DISTINCT doc.document_id) AS documents,
			  COUNT(DISTINCT c.chunk_id) AS chunks,
			  COUNT(DISTINCT CASE
			    WHEN e.status = 'INDEXED'
			      AND COALESCE(e.content_hash, '') = COALESCE(c.content_hash, '')
			    THEN c.chunk_id
			  END) AS indexed_chunks
			FROM (
			  SELECT
			    doc.document_id,
			    CASE
			      WHEN doc.source_org LIKE '%문화체육관광부%' OR doc.source_org LIKE '%문체부%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%mcst%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%ministry of culture%' THEN '문화체육관광부 공식 가이드'
			      WHEN doc.source_org LIKE '%행정안전부%' OR doc.source_org LIKE '%행안부%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%mois%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%ministry of the interior%' THEN '행정안전부 공식 가이드'
			      WHEN __PUBLIC_DATA_CONDITION__ THEN '공공데이터 공식 가이드'
			      WHEN doc.source_org LIKE '%과학기술정보통신부%' OR doc.source_org LIKE '%과기정통부%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%msit%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%ministry of science%' THEN '과학기술정보통신부 공식 가이드'
			      WHEN doc.source_org LIKE '%개인정보보호위원회%' OR doc.source_org LIKE '%개인정보위%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%pipc%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%personal information protection%' THEN '개인정보보호위원회 공식 가이드'
			      ELSE '공식 가이드 문서'
			    END AS agency_label,
			    CASE
			      WHEN doc.source_org LIKE '%문화체육관광부%' OR doc.source_org LIKE '%문체부%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%mcst%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%ministry of culture%' THEN 1
			      WHEN doc.source_org LIKE '%행정안전부%' OR doc.source_org LIKE '%행안부%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%mois%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%ministry of the interior%' THEN 2
			      WHEN __PUBLIC_DATA_CONDITION__ THEN 3
			      WHEN doc.source_org LIKE '%과학기술정보통신부%' OR doc.source_org LIKE '%과기정통부%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%msit%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%ministry of science%' THEN 4
			      WHEN doc.source_org LIKE '%개인정보보호위원회%' OR doc.source_org LIKE '%개인정보위%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%pipc%' OR LOWER(COALESCE(doc.source_org, '')) LIKE '%personal information protection%' THEN 5
			      ELSE 6
			    END AS agency_sort_order
			  FROM rag_documents doc
			  WHERE doc.document_type = 'official_doc'
			    AND doc.use_yn = 'Y'
			) labeled
			JOIN rag_documents doc
			  ON doc.document_id = labeled.document_id
			LEFT JOIN (
			  SELECT document_id, MAX(chunk_version) AS latest_version
			  FROM rag_document_chunks
			  WHERE use_yn = 'Y'
			  GROUP BY document_id
			) latest
			  ON latest.document_id = doc.document_id
			LEFT JOIN rag_document_chunks c
			  ON c.document_id = doc.document_id
			  AND c.use_yn = 'Y'
			  AND c.chunk_version = latest.latest_version
			LEFT JOIN rag_chunk_embeddings e
			  ON e.chunk_id = c.chunk_id
			  AND e.embedding_model = 'text-embedding-3-small'
			  AND e.vector_store = 'rag_chunks_v4'
			GROUP BY labeled.agency_label, labeled.agency_sort_order
			ORDER BY labeled.agency_sort_order, labeled.agency_label
			""".replace("__PUBLIC_DATA_CONDITION__", publicDataCondition);
		return jdbcTemplate.query(sql, (rs, rowNum) -> new AdminPipelineBreakdown(
			rs.getString("agency_label"),
			rs.getLong("documents"),
			rs.getLong("chunks"),
			rs.getLong("indexed_chunks")
		));
	}

	private Map<String, BatchAggregate> batchAggregates() {
		Map<String, BatchAggregate> result = new HashMap<>();
		jdbcTemplate.query("""
			SELECT
			  target,
			  SUM(CASE
			    WHEN status IN ('validating', 'in_progress', 'finalizing')
			      OR (status = 'completed' AND ingested_count = 0)
			    THEN 1 ELSE 0
			  END) AS active_jobs,
			  COUNT(*) AS total_jobs,
			  SUM(CASE WHEN status = 'INGESTED' THEN 1 ELSE 0 END) AS ingested_jobs,
			  COALESCE(SUM(submitted_count), 0) AS submitted_requests,
			  COALESCE(SUM(completed_count), 0) AS completed_requests,
			  COALESCE(SUM(failed_count), 0) AS failed_requests,
			  COALESCE(SUM(ingested_count), 0) AS ingested_requests
			FROM semantic_batch_jobs
			GROUP BY target
			""", rs -> {
			result.put(rs.getString("target"), new BatchAggregate(
				rs.getLong("active_jobs"),
				rs.getLong("total_jobs"),
				rs.getLong("ingested_jobs"),
				rs.getLong("submitted_requests"),
				rs.getLong("completed_requests"),
				rs.getLong("failed_requests"),
				rs.getLong("ingested_requests")
			));
		});
		return result;
	}

	private List<AdminSourceStatus> sources() {
		return jdbcTemplate.query("""
			SELECT
			  s.source_key,
			  s.source_type,
			  s.agency_code,
			  s.agency_name,
			  s.source_url,
			  s.enabled,
			  COUNT(DISTINCT a.article_id) AS articles,
			  SUM(CASE WHEN a.status = 'IMPORTED' THEN 1 ELSE 0 END) AS imported_articles,
			  COUNT(DISTINCT att.attachment_id) AS attachments,
			  SUM(CASE WHEN att.status = 'IMPORTED' THEN 1 ELSE 0 END) AS imported_attachments,
			  DATE_FORMAT(s.last_checked_at, '%Y-%m-%d %H:%i:%s') AS last_checked_at,
			  DATE_FORMAT(s.last_success_at, '%Y-%m-%d %H:%i:%s') AS last_success_at,
			  CASE
			    WHEN s.enabled != 'Y' THEN '비활성'
			    WHEN s.last_error_message IS NOT NULL AND s.last_error_message != '' THEN '오류'
			    WHEN s.last_checked_at IS NULL THEN '미확인'
			    ELSE '정상'
			  END AS status
			FROM rag_collection_sources s
			LEFT JOIN rag_source_articles a ON a.source_id = s.source_id
			LEFT JOIN rag_source_attachments att ON att.article_id = a.article_id
			GROUP BY s.source_id
			ORDER BY s.agency_code, s.source_key
			""", (rs, rowNum) -> new AdminSourceStatus(
			rs.getString("source_key"),
			rs.getString("source_type"),
			rs.getString("agency_code"),
			rs.getString("agency_name"),
			rs.getString("source_url"),
			rs.getString("enabled"),
			rs.getLong("articles"),
			rs.getLong("imported_articles"),
			rs.getLong("attachments"),
			rs.getLong("imported_attachments"),
			rs.getString("last_checked_at"),
			rs.getString("last_success_at"),
			rs.getString("status")
		));
	}

	private List<AdminBatchStatus> recentBatches() {
		return jdbcTemplate.query("""
			SELECT
			  batch_job_id,
			  target,
			  status,
			  submitted_count,
			  completed_count,
			  failed_count,
			  ingested_count,
			  DATE_FORMAT(submitted_at, '%Y-%m-%d %H:%i:%s') AS submitted_at,
			  DATE_FORMAT(completed_at, '%Y-%m-%d %H:%i:%s') AS completed_at,
			  DATE_FORMAT(ingested_at, '%Y-%m-%d %H:%i:%s') AS ingested_at
			FROM semantic_batch_jobs
			ORDER BY batch_job_id DESC
			LIMIT 8
			""", (rs, rowNum) -> new AdminBatchStatus(
			rs.getLong("batch_job_id"),
			rs.getString("target"),
			rs.getString("status"),
			rs.getInt("submitted_count"),
			rs.getInt("completed_count"),
			rs.getInt("failed_count"),
			rs.getInt("ingested_count"),
			rs.getString("submitted_at"),
			rs.getString("completed_at"),
			rs.getString("ingested_at")
		));
	}

	private List<AdminImportStatus> recentImports() {
		return jdbcTemplate.query("""
			SELECT
			  import_job_id,
			  document_type,
			  status,
			  discovered_count,
			  imported_count,
			  skipped_count,
			  failed_count,
			  indexed_count,
			  DATE_FORMAT(started_at, '%Y-%m-%d %H:%i:%s') AS started_at,
			  DATE_FORMAT(finished_at, '%Y-%m-%d %H:%i:%s') AS finished_at
			FROM rag_import_jobs
			ORDER BY import_job_id DESC
			LIMIT 8
			""", (rs, rowNum) -> new AdminImportStatus(
			rs.getLong("import_job_id"),
			rs.getString("document_type"),
			rs.getString("status"),
			rs.getInt("discovered_count"),
			rs.getInt("imported_count"),
			rs.getInt("skipped_count"),
			rs.getInt("failed_count"),
			rs.getInt("indexed_count"),
			rs.getString("started_at"),
			rs.getString("finished_at")
		));
	}

	private String pipelineStatus(long chunks, long indexedChunks, long failedEmbeddings, long activeBatches) {
		if (failedEmbeddings > 0) {
			return "오류 확인";
		}
		if (activeBatches > 0) {
			return "Batch 진행";
		}
		if (chunks == 0) {
			return "대기 없음";
		}
		if (indexedChunks >= chunks) {
			return "완료";
		}
		return "임베딩 대기";
	}

	private long count(String sql, Object... args) {
		Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
		return value == null ? 0L : value;
	}

	private String blankToDash(String value) {
		return value == null || value.isBlank() ? "-" : value;
	}

	private record PipelineAggregate(
		long documents,
		long chunkedDocuments,
		long chunks,
		long indexedChunks,
		long failedEmbeddings,
		String lastUpdatedAt
	) {
		private static PipelineAggregate empty() {
			return new PipelineAggregate(0, 0, 0, 0, 0, "-");
		}
	}

	private record BatchAggregate(
		long activeJobs,
		long totalJobs,
		long ingestedJobs,
		long submittedRequests,
		long completedRequests,
		long failedRequests,
		long ingestedRequests
	) {
		private static BatchAggregate empty() {
			return new BatchAggregate(0, 0, 0, 0, 0, 0, 0);
		}
	}

	private record CachedAdminOverview(AdminOverviewResponse response, long createdNanos) {
		private boolean isFresh(long nowNanos) {
			return nowNanos - createdNanos < OVERVIEW_CACHE_NANOS;
		}
	}

	private record QdrantDeltaSummary(long value, String detail, String tone) {
	}

	private record EvalGateCandidate(long total, long modifiedAtMillis, EvalGateSummary summary) {
	}

	private record EvalGateSummary(
		long total,
		long passed,
		long failed,
		boolean gatePassed,
		Path path,
		String gitCommit,
		String datasetHash,
		String indexVersion,
		String generatedAt,
		long curatedTotal,
		long curatedPassed,
		long generatedTotal,
		long generatedPassed,
		long answerRequired,
		long answerPassed
	) {
		private static EvalGateSummary missing() {
			return new EvalGateSummary(0, 0, 0, false, null, "", "", "", "", 0, 0, 0, 0, 0, 0);
		}

		private long passRatePercent() {
			if (total <= 0) {
				return -1;
			}
			return Math.round((passed * 100.0) / total);
		}

		private String detail() {
			if (total <= 0) {
				return "평가셋 실행 로그가 없습니다. rag-eval-gate를 실행하세요.";
			}
			String fileName = path == null ? "unknown" : path.getFileName().toString();
			String provenance = gitCommit == null || gitCommit.isBlank()
				? "provenance 없음"
				: "commit " + gitCommit.substring(0, Math.min(8, gitCommit.length())) + ", index " + indexVersion;
			String breakdown = curatedTotal <= 0 && generatedTotal <= 0
				? "세부 점수 없음"
				: "수동 " + curatedPassed + "/" + curatedTotal
					+ ", 생성 " + generatedPassed + "/" + generatedTotal
					+ ", 답변검증 " + answerPassed + "/" + answerRequired;
			return total + "건 중 " + passed + "건 통과, 실패 " + failed + "건, " + breakdown + ", " + provenance + ", 파일 " + fileName;
		}

		private String tone() {
			if (total <= 0 || !gatePassed || failed > 0) {
				return "bad";
			}
			if (gitCommit == null || gitCommit.isBlank() || datasetHash == null || datasetHash.isBlank()) {
				return "warn";
			}
			return total >= EVAL_GATE_TARGET_CASES ? "good" : "warn";
		}
	}

	private record RagShortAuditSummary(
		long total,
		long keep,
		long noisyCandidates,
		long contextOnly,
		long manualReview,
		Path path
	) {
		private static RagShortAuditSummary missing() {
			return new RagShortAuditSummary(-1, 0, -1, 0, -1, null);
		}

		private String noisyDetail() {
			if (total < 0) {
				return "RAG 짧은 청크 감사 로그가 없습니다. rag-short-chunk-audit를 실행하세요.";
			}
			return "정리/병합/재청킹 " + noisyCandidates
				+ ", 문맥용 " + contextOnly
				+ ", 유지 " + keep
				+ ", 파일 " + fileName(path);
		}

		private String reviewDetail() {
			if (total < 0) {
				return "RAG 짧은 청크 감사 로그가 없습니다. rag-short-chunk-audit를 실행하세요.";
			}
			return "수동 검토 " + manualReview
				+ " / " + total
				+ ", 파일 " + fileName(path);
		}

		private String tone() {
			if (total < 0) {
				return "bad";
			}
			return noisyCandidates == 0 && contextOnly == 0 ? "good" : "warn";
		}

		private String reviewTone() {
			if (total < 0) {
				return "bad";
			}
			return manualReview == 0 ? "good" : "warn";
		}
	}

	private record ResidualTinyAuditSummary(long total, long keep, long lowSignal, long manualReview, Path path) {
		private static ResidualTinyAuditSummary missing() {
			return new ResidualTinyAuditSummary(-1, 0, 0, -1, null);
		}

		private String detail() {
			if (total < 0) {
				return "짧은 법령 청크 감사 로그가 없습니다. residual-tiny-audit를 실행하세요.";
			}
			return "수동 검토 " + manualReview
				+ ", 저품질 " + lowSignal
				+ ", 유지 " + keep
				+ ", 파일 " + fileName(path);
		}

		private String tone() {
			if (total < 0) {
				return "bad";
			}
			return manualReview == 0 ? "good" : "warn";
		}
	}

	private static String fileName(Path path) {
		return path == null ? "unknown" : path.getFileName().toString();
	}
}
