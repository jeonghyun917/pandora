package com.kaces.pandora.ai.answer;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;
import com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot;
import com.kaces.pandora.semantic.provenance.IndexContentSnapshot;
import com.kaces.pandora.semantic.provenance.IndexRevisionCalculator;
import com.kaces.pandora.semantic.provenance.IndexRevisionCollection;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

final class SemanticVectorSearchService {

	private final QdrantClient qdrantClient;

	SemanticVectorSearchService(QdrantClient qdrantClient) {
		this.qdrantClient = qdrantClient;
	}

	boolean isReady() {
		return qdrantClient != null && qdrantClient.isSearchReady();
	}

	long searchFailureCount() {
		return qdrantClient == null ? 0L : qdrantClient.searchFailureCount();
	}

	QdrantIndexSnapshot indexSnapshot(String collection) {
		return qdrantClient == null ? null : qdrantClient.indexSnapshot(collection).orElse(null);
	}

	String indexRevision(
		String embeddingModel,
		String lawCollection,
		IndexContentSnapshot lawDatabase,
		String ragCollection,
		IndexContentSnapshot ragDatabase
	) {
		if (qdrantClient == null) {
			return null;
		}
		QdrantIndexSnapshot lawQdrant = indexSnapshot(lawCollection);
		QdrantIndexSnapshot ragQdrant = indexSnapshot(ragCollection);
		return indexRevision(embeddingModel, lawCollection, lawDatabase, lawQdrant, ragCollection, ragDatabase, ragQdrant);
	}

	String indexRevision(
		String embeddingModel,
		String lawCollection,
		IndexContentSnapshot lawDatabase,
		QdrantIndexSnapshot lawQdrant,
		String ragCollection,
		IndexContentSnapshot ragDatabase,
		QdrantIndexSnapshot ragQdrant
	) {
		if (qdrantClient == null) {
			return null;
		}
		return IndexRevisionCalculator.calculate(
			embeddingModel,
			List.of(
				new IndexRevisionCollection("law", lawCollection, lawDatabase, lawQdrant),
				new IndexRevisionCollection("rag", ragCollection, ragDatabase, ragQdrant)
			)
		);
	}

	List<QdrantSearchHit> search(
		List<List<Double>> queryVectors,
		List<String> targets,
		int candidateLimit,
		Executor executor
	) {
		return searchInternal(queryVectors, targets, candidateLimit, executor, null);
	}

	List<QdrantSearchHit> search(
		List<List<Double>> queryVectors, List<String> targets, int candidateLimit,
		Executor executor, RecoveryRoutingSnapshot routing
	) {
		if (routing == null) throw new IllegalArgumentException("Captured recovery routing required");
		return searchInternal(queryVectors, targets, candidateLimit, executor, routing);
	}

	private List<QdrantSearchHit> searchInternal(
		List<List<Double>> queryVectors, List<String> targets, int candidateLimit,
		Executor executor, RecoveryRoutingSnapshot routing
	) {
		if (queryVectors == null || queryVectors.isEmpty()) {
			return List.of();
		}
		List<List<Double>> usableVectors = queryVectors.stream()
			.filter(queryVector -> queryVector != null && !queryVector.isEmpty())
			.toList();
		if (usableVectors.isEmpty()) {
			return List.of();
		}
		int safeLimit = Math.max(1, candidateLimit);
		if (usableVectors.size() == 1) {
			return merge(searchVector(
				usableVectors.get(0),
				targets,
				safeLimit,
				safeLimit * 2, routing
			), safeLimit * 2);
		}
		List<CompletableFuture<List<QdrantSearchHit>>> futures = usableVectors.stream()
			.map(queryVector -> CompletableFuture.supplyAsync(
				() -> searchVector(queryVector, targets, safeLimit, safeLimit * 2, routing),
				executor
			))
			.toList();
		List<QdrantSearchHit> hits = new ArrayList<>();
		for (CompletableFuture<List<QdrantSearchHit>> future : futures) {
			hits.addAll(join(future));
		}
		return merge(hits, safeLimit * 2);
	}

	private List<QdrantSearchHit> searchVector(List<Double> vector, List<String> targets,
		int perTargetLimit, int totalLimit, RecoveryRoutingSnapshot routing) {
		return routing == null
			? qdrantClient.searchBalanced(vector, targets, perTargetLimit, totalLimit)
			: qdrantClient.searchBalanced(vector, targets, perTargetLimit, totalLimit, routing);
	}

	List<QdrantSearchHit> merge(List<QdrantSearchHit> hits, int limit) {
		if (hits == null || hits.isEmpty()) {
			return List.of();
		}
		Map<String, QdrantSearchHit> bestByChunk = new LinkedHashMap<>();
		for (QdrantSearchHit hit : hits) {
			if (hit == null) {
				continue;
			}
			String key = hit.target() + ":" + hit.chunkId();
			QdrantSearchHit previous = bestByChunk.get(key);
			if (previous == null || hit.score() > previous.score()) {
				bestByChunk.put(key, hit);
			}
		}
		return bestByChunk.values().stream()
			.sorted(Comparator.comparingDouble(QdrantSearchHit::score).reversed()
				.thenComparing(QdrantSearchHit::target, Comparator.nullsFirst(String::compareTo))
				.thenComparingLong(QdrantSearchHit::chunkId))
			.limit(Math.max(1, limit))
			.toList();
	}

	private <T> T join(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Async vector search task failed.", cause);
		}
	}
}
