package com.kaces.pandora.infra.qdrant;


import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.ObjectMapper;

@Component
public class QdrantClient {
	private static final long RAG_POINT_ID_OFFSET = 9_000_000_000_000_000L;
	private static final List<String> SEARCH_PAYLOAD_FIELDS = List.of("target", "chunkId");
	private static final Logger log = LoggerFactory.getLogger(QdrantClient.class);

	private final LawAiProperties properties;
	private final RestClient restClient;
	private final RestClient healthRestClient;
	private final ExecutorService searchExecutor;
	private final ObjectMapper objectMapper;
	private final AtomicLong searchFailureCount = new AtomicLong();

	// 메소드 설명: QdrantClient 처리 흐름을 수행합니다.
	public QdrantClient(LawAiProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofMinutes(2));
		this.restClient = RestClient.builder()
			.baseUrl(properties.qdrant().baseUrl())
			.requestFactory(requestFactory)
			.build();
		SimpleClientHttpRequestFactory healthRequestFactory = new SimpleClientHttpRequestFactory();
		healthRequestFactory.setConnectTimeout(Duration.ofSeconds(2));
		healthRequestFactory.setReadTimeout(Duration.ofSeconds(2));
		this.healthRestClient = RestClient.builder()
			.baseUrl(properties.qdrant().baseUrl())
			.requestFactory(healthRequestFactory)
			.build();
		this.searchExecutor = Executors.newFixedThreadPool(6, namedThreadFactory("qdrant-search-"));
	}

	@PreDestroy
	public void shutdownExecutor() {
		searchExecutor.shutdownNow();
	}

	public long searchFailureCount() {
		return searchFailureCount.get();
	}

	public Set<Long> findExistingLawPointIds(List<Long> pointIds) {
		List<Long> ids = pointIds == null ? List.of() : pointIds.stream()
			.filter(id -> id != null && id > 0)
			.distinct()
			.toList();
		if (ids.isEmpty()) {
			return Set.of();
		}
		if (ids.size() > 256) {
			throw new IllegalArgumentException("Law point lookup is limited to 256 IDs.");
		}
		byte[] response = restClient.post()
			.uri("/collections/{collection}/points", properties.qdrant().collection())
			.body(Map.of("ids", ids, "with_payload", false, "with_vector", false))
			.retrieve()
			.body(byte[].class);
		if (response == null || response.length == 0) {
			throw new IllegalStateException("Qdrant point lookup response was empty.");
		}
		try {
			Map<?, ?> envelope = objectMapper.readValue(response, Map.class);
			if (!(envelope.get("result") instanceof List<?> result)) {
				throw new IllegalStateException("Qdrant point lookup response did not contain a result list.");
			}
			Set<Long> existing = new LinkedHashSet<>();
			for (Object item : result) {
				if (!(item instanceof Map<?, ?> point) || !(point.get("id") instanceof Number id)) {
					throw new IllegalStateException("Qdrant point lookup response contained a malformed point.");
				}
				existing.add(id.longValue());
			}
			return Set.copyOf(existing);
		} catch (RuntimeException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new IllegalStateException("Qdrant point lookup response was not valid JSON.", exception);
		}
	}

	public boolean isSearchReady() {
		String lawCollection = properties.qdrant().collection();
		String ragCollection = ragCollection();
		if (lawCollection == null || lawCollection.isBlank() || ragCollection == null || ragCollection.isBlank()) {
			return false;
		}
		List<String> collections = List.of(lawCollection, ragCollection).stream()
			.distinct()
			.toList();
		for (String collection : collections) {
			try {
				byte[] response = healthRestClient.get()
					.uri("/collections/{collection}", collection)
					.retrieve()
					.body(byte[].class);
				if (!isCollectionSearchReady(response)) {
					log.warn("Qdrant collection is not search-ready. collection={}", collection);
					return false;
				}
			} catch (RuntimeException exception) {
				log.warn("Qdrant readiness check failed. collection={} failureType={}",
					collection,
					exception.getClass().getSimpleName()
				);
				return false;
			}
		}
		return true;
	}

	public Optional<QdrantIndexSnapshot> indexSnapshot(String collection) {
		if (collection == null || collection.isBlank() || objectMapper == null) {
			return Optional.empty();
		}
		try {
			byte[] infoResponse = healthRestClient.get()
				.uri("/collections/{collection}", collection)
				.retrieve()
				.body(byte[].class);
			Optional<CollectionIndexInfo> info = collectionIndexInfo(infoResponse);
			if (info.isEmpty() || !info.get().isStable(properties.qdrant().vectorSize())) {
				return Optional.empty();
			}
			byte[] countResponse = healthRestClient.post()
				.uri("/collections/{collection}/points/count", collection)
				.body(Map.of("exact", true))
				.retrieve()
				.body(byte[].class);
			Long exactCount = exactCount(countResponse);
			if (exactCount == null || exactCount <= 0) {
				return Optional.empty();
			}
			CollectionIndexInfo stable = info.get();
			return Optional.of(new QdrantIndexSnapshot(
				collection,
				stable.status(),
				stable.updateQueueLength(),
				exactCount,
				stable.vectorSize(),
				stable.distance(),
				stable.indexedVectorsCount(),
				stable.segmentsCount()
			));
		} catch (RuntimeException exception) {
			log.warn("Qdrant index snapshot is unavailable. collection={} failureType={}",
				collection,
				exception.getClass().getSimpleName()
			);
			return Optional.empty();
		}
	}

	private boolean isCollectionSearchReady(byte[] response) {
		if (response == null || response.length == 0 || objectMapper == null) {
			return false;
		}
		try {
			Map<?, ?> envelope = objectMapper.readValue(response, Map.class);
			Map<?, ?> result = envelope.get("result") instanceof Map<?, ?> value ? value : Map.of();
			if (!"green".equalsIgnoreCase(String.valueOf(result.get("status")))) {
				return false;
			}
			if (!(result.get("points_count") instanceof Number pointsCount) || pointsCount.longValue() <= 0) {
				return false;
			}
			Long updateQueueLength = updateQueueLength(result);
			if (updateQueueLength == null || updateQueueLength != 0) {
				return false;
			}
			Map<?, ?> config = result.get("config") instanceof Map<?, ?> value ? value : Map.of();
			Map<?, ?> params = config.get("params") instanceof Map<?, ?> value ? value : Map.of();
			Map<?, ?> vectors = params.get("vectors") instanceof Map<?, ?> value ? value : Map.of();
			if (!(vectors.get("size") instanceof Number vectorSize)
				|| vectorSize.intValue() != properties.qdrant().vectorSize()) {
				return false;
			}
			return "Cosine".equalsIgnoreCase(String.valueOf(vectors.get("distance")));
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private Optional<CollectionIndexInfo> collectionIndexInfo(byte[] response) {
		if (response == null || response.length == 0) {
			return Optional.empty();
		}
		try {
			Map<?, ?> envelope = objectMapper.readValue(response, Map.class);
			if (!(envelope.get("result") instanceof Map<?, ?> result)) {
				return Optional.empty();
			}
			Long updateQueueLength = updateQueueLength(result);
			if (updateQueueLength == null) {
				return Optional.empty();
			}
			Map<?, ?> config = result.get("config") instanceof Map<?, ?> value ? value : Map.of();
			Map<?, ?> params = config.get("params") instanceof Map<?, ?> value ? value : Map.of();
			Map<?, ?> vectors = params.get("vectors") instanceof Map<?, ?> value ? value : Map.of();
			if (!(vectors.get("size") instanceof Number vectorSize)) {
				return Optional.empty();
			}
			return Optional.of(new CollectionIndexInfo(
				String.valueOf(result.get("status")),
				updateQueueLength,
				vectorSize.intValue(),
				String.valueOf(vectors.get("distance")),
				longValueOrDefault(result.get("indexed_vectors_count"), -1L),
				(int) longValueOrDefault(result.get("segments_count"), -1L)
			));
		} catch (RuntimeException exception) {
			return Optional.empty();
		}
	}

	private Long updateQueueLength(Map<?, ?> result) {
		Object updateQueue = result.get("update_queue");
		if (updateQueue == null) {
			return null;
		}
		if (!(updateQueue instanceof Map<?, ?> queue)
			|| !(queue.get("length") instanceof Number length)
			|| length.longValue() < 0) {
			return null;
		}
		return length.longValue();
	}

	private Long exactCount(byte[] response) {
		if (response == null || response.length == 0) {
			return null;
		}
		try {
			Map<?, ?> envelope = objectMapper.readValue(response, Map.class);
			Map<?, ?> result = envelope.get("result") instanceof Map<?, ?> value ? value : Map.of();
			return result.get("count") instanceof Number count ? count.longValue() : null;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private long longValueOrDefault(Object value, long defaultValue) {
		return value instanceof Number number ? number.longValue() : defaultValue;
	}

	private record CollectionIndexInfo(
		String status,
		long updateQueueLength,
		int vectorSize,
		String distance,
		long indexedVectorsCount,
		int segmentsCount
	) {
		private boolean isStable(int expectedVectorSize) {
			return "green".equalsIgnoreCase(status)
				&& updateQueueLength == 0
				&& vectorSize == expectedVectorSize
				&& "Cosine".equalsIgnoreCase(distance);
		}
	}

	// 메소드 설명: ensureCollection 처리 흐름을 수행합니다.
	public void ensureCollection() {
		ensureCollection(properties.qdrant().collection());
	}

	public void ensureRagCollection() {
		ensureCollection(ragCollection());
	}

	private void ensureCollection(String collection) {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			restClient.get()
				.uri("/collections/{collection}", collection)
				.retrieve()
				.toBodilessEntity();
			return;
		} catch (HttpClientErrorException exception) {
			if (exception.getStatusCode() != HttpStatus.NOT_FOUND) {
				throw exception;
			}
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		restClient.put()
			.uri("/collections/{collection}", collection)
			.body(Map.of(
				"vectors", Map.of(
					"size", properties.qdrant().vectorSize(),
					"distance", "Cosine"
				)
			))
			.retrieve()
			.toBodilessEntity();
	}

	// 메소드 설명: upsert 처리 흐름을 수행합니다.
	public void upsert(List<LawSemanticChunkRow> chunks, List<List<Double>> vectors) {
		upsert(chunks, vectors, false);
	}

	// 메소드 설명: upsertRag 처리 흐름을 수행합니다.
	public void upsertRag(List<LawSemanticChunkRow> chunks, List<List<Double>> vectors) {
		ensureRagCollection();
		upsert(chunks, vectors, true, ragCollection());
	}

	// 메소드 설명: upsert 처리 흐름을 수행합니다.
	private void upsert(List<LawSemanticChunkRow> chunks, List<List<Double>> vectors, boolean stringPointId) {
		upsert(chunks, vectors, stringPointId, properties.qdrant().collection());
	}

	private void upsert(List<LawSemanticChunkRow> chunks, List<List<Double>> vectors, boolean stringPointId, String collection) {
		if (chunks.size() != vectors.size()) {
			throw new IllegalArgumentException("Chunk and vector counts must match.");
		}
		if (chunks.isEmpty()) {
			return;
		}
		List<Map<String, Object>> points = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			LawSemanticChunkRow chunk = chunks.get(i);
			Map<String, Object> payload = new LinkedHashMap<>();
			String agencyName = blankIfNull(chunk.agencyName());
			payload.put("chunkId", chunk.chunkId());
			payload.put("documentId", chunk.documentId());
			payload.put("target", chunk.target());
			payload.put("title", blankIfNull(chunk.title()));
			payload.put("sourceOrg", agencyName);
			payload.put("agencyName", agencyName);
			payload.put("sourceDate", blankIfNull(chunk.sourceDate()));
			payload.put("effectiveStatus", blankIfNull(chunk.effectiveStatus()));
			payload.put("chunkNo", blankIfNull(chunk.chunkNo()));
			payload.put("chunkVersion", chunkVersion(chunk));
			payload.put("sourcePath", blankIfNull(chunk.sourcePath()));
			points.add(Map.of(
				"id", stringPointId ? ragPointId(chunk.chunkId()) : chunk.chunkId(),
				"vector", vectors.get(i),
				"payload", payload
			));
		}

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		restClient.put()
			.uri("/collections/{collection}/points?wait=true", collection)
			.body(Map.of("points", points))
			.retrieve()
			.toBodilessEntity();
	}

	// 메소드 설명: ragPointId 처리 흐름을 수행합니다.
	public static long ragPointId(long chunkId) {
		return RAG_POINT_ID_OFFSET + chunkId;
	}

	private int chunkVersion(LawSemanticChunkRow chunk) {
		String sourcePath = chunk.sourcePath() == null ? "" : chunk.sourcePath();
		if (sourcePath.contains("$.v4.")) {
			return 4;
		}
		if (sourcePath.contains("$.v3.")) {
			return 3;
		}
		return sourcePath.contains("$.v2.") ? 2 : 1;
	}

	private static String blankIfNull(String value) {
		return value == null ? "" : value;
	}

	// 메소드 설명: upsertVectors 처리 흐름을 수행합니다.
	public void upsertVectors(List<LawSemanticChunkRow> chunks, Map<Long, List<Double>> vectorsByChunkId) {
		List<LawSemanticChunkRow> orderedChunks = chunks.stream()
			.filter(chunk -> vectorsByChunkId.containsKey(chunk.chunkId()))
			.toList();
		List<List<Double>> vectors = orderedChunks.stream()
			.map(chunk -> vectorsByChunkId.get(chunk.chunkId()))
			.toList();
		upsert(orderedChunks, vectors);
	}

	public void deleteLawPoints(List<Long> chunkIds) {
		deletePoints(chunkIds);
	}

	public void deleteLawPointsBestEffort(List<Long> chunkIds) {
		deletePointsBestEffort(chunkIds, "law");
	}

	public void deleteRagPointsBestEffort(List<Long> chunkIds) {
		List<Long> pointIds = chunkIds == null ? List.of() : chunkIds.stream()
			.filter(id -> id != null && id > 0)
			.map(QdrantClient::ragPointId)
			.toList();
		deletePointsBestEffort(pointIds, "rag", ragCollection());
		if (!ragCollection().equals(properties.qdrant().collection())) {
			deletePointsBestEffort(pointIds, "rag-legacy", properties.qdrant().collection());
		}
	}

	private void deletePointsBestEffort(List<Long> pointIds, String label) {
		deletePointsBestEffort(pointIds, label, properties.qdrant().collection());
	}

	private void deletePointsBestEffort(List<Long> pointIds, String label, String collection) {
		try {
			deletePoints(pointIds, collection);
		} catch (RestClientException exception) {
			log.warn("Qdrant {} stale point cleanup failed. It can be retried later. count={} message={}",
				label,
				pointIds == null ? 0 : pointIds.size(),
				exception.getMessage()
			);
		}
	}

	private void deletePoints(List<Long> pointIds) {
		deletePoints(pointIds, properties.qdrant().collection());
	}

	private void deletePoints(List<Long> pointIds, String collection) {
		List<Long> ids = pointIds == null ? List.of() : pointIds.stream()
			.filter(id -> id != null && id > 0)
			.distinct()
			.toList();
		if (ids.isEmpty()) {
			return;
		}
		int batchSize = 512;
		for (int start = 0; start < ids.size(); start += batchSize) {
			List<Long> batch = ids.subList(start, Math.min(start + batchSize, ids.size()));
			restClient.post()
				.uri("/collections/{collection}/points/delete?wait=true", collection)
				.body(Map.of("points", batch))
				.retrieve()
				.toBodilessEntity();
		}
	}

	// 메소드 설명: search 처리 흐름을 수행합니다.
	public List<QdrantSearchHit> search(List<Double> vector, String target, int limit) {
		return search(vector, List.of(target), limit);
	}

	// 메소드 설명: search 처리 흐름을 수행합니다.
	public List<QdrantSearchHit> search(List<Double> vector, List<String> targets, int limit) {
		List<String> normalizedTargets = targets == null ? List.of() : targets.stream()
			.filter(target -> target != null && !target.isBlank())
			.distinct()
			.toList();
		if (normalizedTargets.size() > 1) {
			return searchTargetsInParallel(vector, normalizedTargets, limit).stream()
				.sorted(Comparator.comparing(QdrantSearchHit::score).reversed())
				.limit(limit)
				.toList();
		}
		String target = normalizedTargets.isEmpty() ? "" : normalizedTargets.get(0);
		return searchSingleTarget(vector, target, limit);
	}

	public List<QdrantSearchHit> searchBalanced(List<Double> vector, List<String> targets, int perTargetLimit, int maxTotalLimit) {
		List<String> normalizedTargets = targets == null ? List.of() : targets.stream()
			.filter(target -> target != null && !target.isBlank())
			.distinct()
			.toList();
		int safePerTargetLimit = Math.max(1, perTargetLimit);
		int safeMaxTotalLimit = Math.max(safePerTargetLimit, maxTotalLimit);
		if (normalizedTargets.size() <= 1) {
			String target = normalizedTargets.isEmpty() ? "" : normalizedTargets.get(0);
			return searchSingleTarget(vector, target, safeMaxTotalLimit);
		}
		return searchTargetsInParallel(vector, normalizedTargets, safePerTargetLimit).stream()
			.collect(java.util.stream.Collectors.toMap(
				hit -> hit.target() + ":" + hit.chunkId(),
				hit -> hit,
				(first, second) -> first.score() >= second.score() ? first : second,
				java.util.LinkedHashMap::new
			))
			.values()
			.stream()
			.sorted(Comparator.comparing(QdrantSearchHit::score).reversed())
			.limit(safeMaxTotalLimit)
			.toList();
	}

	private List<QdrantSearchHit> searchTargetsInParallel(List<Double> vector, List<String> targets, int limit) {
		List<CompletableFuture<List<QdrantSearchHit>>> futures = targets.stream()
			.map(target -> CompletableFuture.supplyAsync(() -> searchSingleTarget(vector, target, limit), searchExecutor))
			.toList();
		List<QdrantSearchHit> hits = new ArrayList<>();
		for (CompletableFuture<List<QdrantSearchHit>> future : futures) {
			hits.addAll(joinFuture(future));
		}
		return hits;
	}

	private <T> T joinFuture(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Qdrant search task failed.", cause);
		}
	}

	// 메소드 설명: searchSingleTarget 처리 흐름을 수행합니다.
	private List<QdrantSearchHit> searchSingleTarget(List<Double> vector, String target, int limit) {
		String collection = isRagTarget(target) ? ragCollection() : properties.qdrant().collection();
		Map<String, Object> body = target == null || target.isBlank()
			? Map.of(
				"vector", vector,
				"limit", limit,
				"with_payload", SEARCH_PAYLOAD_FIELDS,
				"with_vector", false
			)
			: Map.of(
			"vector", vector,
			"limit", limit,
			"with_payload", SEARCH_PAYLOAD_FIELDS,
			"with_vector", false,
			"filter", Map.of(
				"must", List.of(Map.of(
					"key", "target",
					"match", Map.of("value", target)
				))
			)
		);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response = qdrantPostForMap(
			"/collections/{collection}/points/search",
			collection,
			body
		);

		Object resultObject = response == null ? null : response.get("result");
		List<?> result = resultObject instanceof List<?> resultList ? resultList : List.of();
		return result.stream()
			.map(this::toHit)
			.sorted(Comparator.comparing(QdrantSearchHit::score).reversed())
			.toList();
	}

	private Map<String, Object> qdrantPostForMap(String uri, String collection, Map<String, Object> body) {
		RuntimeException lastException = null;
		for (int attempt = 1; attempt <= 2; attempt++) {
			try {
				return qdrantPostForMapOnce(uri, collection, body);
			} catch (RestClientException | IllegalStateException exception) {
				lastException = exception;
				log.warn("Qdrant search request failed. attempt={} collection={} message={}",
					attempt,
					collection,
					exception.getMessage()
				);
				sleepBeforeRetry(attempt);
			}
		}
		log.warn("Qdrant search request abandoned after retry. collection={} message={}",
			collection,
			lastException == null ? "" : lastException.getMessage()
		);
		searchFailureCount.incrementAndGet();
		return Map.of();
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> qdrantPostForMapOnce(String uri, String collection, Map<String, Object> body) {
		byte[] response = restClient.post()
			.uri(uri, collection)
			.body(body)
			.retrieve()
			.body(byte[].class);
		if (response == null || response.length == 0) {
			throw new IllegalStateException("Qdrant search response was empty.");
		}
		Map<String, Object> parsed;
		try {
			parsed = objectMapper.readValue(response, Map.class);
		} catch (RuntimeException exception) {
			String snippet = new String(response, StandardCharsets.UTF_8);
			if (snippet.length() > 300) {
				snippet = snippet.substring(0, 300) + "...";
			}
			throw new IllegalStateException("Qdrant response was not valid JSON. body=" + snippet, exception);
		}
		if (parsed == null || !(parsed.get("result") instanceof List<?> result)) {
			throw new IllegalStateException("Qdrant search response did not contain a result list.");
		}
		try {
			for (Object item : result) {
				toHit(item);
			}
		} catch (RuntimeException exception) {
			throw new IllegalStateException("Qdrant search response contained a malformed result item.", exception);
		}
		return parsed;
	}

	// 메소드 설명: toHit 처리 흐름을 수행합니다.
	private void sleepBeforeRetry(int attempt) {
		try {
			Thread.sleep(Math.min(500L, 150L * Math.max(1, attempt)));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
		}
	}

	private QdrantSearchHit toHit(Object item) {
		Map<?, ?> itemMap = (Map<?, ?>) item;
		Number score = (Number) itemMap.get("score");
		Object payloadObject = itemMap.get("payload");
		Map<?, ?> payload = payloadObject instanceof Map<?, ?> payloadMap ? payloadMap : Map.of();
		Object targetObject = payload.get("target");
		Object chunkIdObject = payload.get("chunkId");
		Number chunkId = chunkIdObject instanceof Number number ? number : (Number) itemMap.get("id");
		return new QdrantSearchHit(
			targetObject == null ? "" : String.valueOf(targetObject),
			chunkId.longValue(),
			score.doubleValue()
		);
	}

	private String ragCollection() {
		return properties.qdrant().ragCollection();
	}

	private boolean isRagTarget(String target) {
		return "official_doc".equals(target) || "internal_doc".equals(target) || "reference_doc".equals(target);
	}

	private static ThreadFactory namedThreadFactory(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}
}
