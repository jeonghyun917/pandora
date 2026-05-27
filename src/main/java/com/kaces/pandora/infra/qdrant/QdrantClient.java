package com.kaces.pandora.infra.qdrant;


import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

@Component
public class QdrantClient {
	private static final long RAG_POINT_ID_OFFSET = 9_000_000_000_000_000L;

	private final LawAiProperties properties;
	private final RestClient restClient;

	// 메소드 설명: QdrantClient 처리 흐름을 수행합니다.
	public QdrantClient(LawAiProperties properties) {
		this.properties = properties;
		SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
		requestFactory.setConnectTimeout(Duration.ofSeconds(5));
		requestFactory.setReadTimeout(Duration.ofMinutes(2));
		this.restClient = RestClient.builder()
			.baseUrl(properties.qdrant().baseUrl())
			.requestFactory(requestFactory)
			.build();
	}

	// 메소드 설명: ensureCollection 처리 흐름을 수행합니다.
	public void ensureCollection() {
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			restClient.get()
				.uri("/collections/{collection}", properties.qdrant().collection())
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
			.uri("/collections/{collection}", properties.qdrant().collection())
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
		upsert(chunks, vectors, true);
	}

	// 메소드 설명: upsert 처리 흐름을 수행합니다.
	private void upsert(List<LawSemanticChunkRow> chunks, List<List<Double>> vectors, boolean stringPointId) {
		if (chunks.size() != vectors.size()) {
			throw new IllegalArgumentException("Chunk and vector counts must match.");
		}
		if (chunks.isEmpty()) {
			return;
		}
		List<Map<String, Object>> points = new ArrayList<>(chunks.size());
		for (int i = 0; i < chunks.size(); i++) {
			LawSemanticChunkRow chunk = chunks.get(i);
			points.add(Map.of(
				"id", stringPointId ? ragPointId(chunk.chunkId()) : chunk.chunkId(),
				"vector", vectors.get(i),
				"payload", Map.of(
					"chunkId", chunk.chunkId(),
					"documentId", chunk.documentId(),
					"target", chunk.target(),
					"title", chunk.title(),
					"chunkNo", chunk.chunkNo() == null ? "" : chunk.chunkNo(),
					"sourcePath", chunk.sourcePath() == null ? "" : chunk.sourcePath()
				)
			));
		}

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		restClient.put()
			.uri("/collections/{collection}/points?wait=true", properties.qdrant().collection())
			.body(Map.of("points", points))
			.retrieve()
			.toBodilessEntity();
	}

	// 메소드 설명: ragPointId 처리 흐름을 수행합니다.
	public static long ragPointId(long chunkId) {
		return RAG_POINT_ID_OFFSET + chunkId;
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
			return normalizedTargets.stream()
				.flatMap(target -> searchSingleTarget(vector, target, limit).stream())
				.sorted(Comparator.comparing(QdrantSearchHit::score).reversed())
				.limit(limit)
				.toList();
		}
		String target = normalizedTargets.isEmpty() ? "" : normalizedTargets.get(0);
		return searchSingleTarget(vector, target, limit);
	}

	// 메소드 설명: searchSingleTarget 처리 흐름을 수행합니다.
	private List<QdrantSearchHit> searchSingleTarget(List<Double> vector, String target, int limit) {
		Map<String, Object> body = target == null || target.isBlank()
			? Map.of(
				"vector", vector,
				"limit", limit,
				"with_payload", true
			)
			: Map.of(
			"vector", vector,
			"limit", limit,
			"with_payload", true,
			"filter", Map.of(
				"must", List.of(Map.of(
					"key", "target",
					"match", Map.of("value", target)
				))
			)
		);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Map<?, ?> response = restClient.post()
			.uri("/collections/{collection}/points/search", properties.qdrant().collection())
			.body(body)
			.retrieve()
			.body(Map.class);

		Object resultObject = response == null ? null : response.get("result");
		List<?> result = resultObject instanceof List<?> resultList ? resultList : List.of();
		return result.stream()
			.map(this::toHit)
			.sorted(Comparator.comparing(QdrantSearchHit::score).reversed())
			.toList();
	}

	// 메소드 설명: toHit 처리 흐름을 수행합니다.
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
}
