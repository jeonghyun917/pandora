package com.kaces.pandora.ai.answer;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.infra.qdrant.QdrantIndexSnapshot;
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
		QdrantIndexSnapshot lawQdrant = qdrantClient.indexSnapshot(lawCollection).orElse(null);
		QdrantIndexSnapshot ragQdrant = qdrantClient.indexSnapshot(ragCollection).orElse(null);
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
			return merge(qdrantClient.searchBalanced(
				usableVectors.get(0),
				targets,
				safeLimit,
				safeLimit * 2
			), safeLimit * 2);
		}
		List<CompletableFuture<List<QdrantSearchHit>>> futures = usableVectors.stream()
			.map(queryVector -> CompletableFuture.supplyAsync(
				() -> qdrantClient.searchBalanced(queryVector, targets, safeLimit, safeLimit * 2),
				executor
			))
			.toList();
		List<QdrantSearchHit> hits = new ArrayList<>();
		for (CompletableFuture<List<QdrantSearchHit>> future : futures) {
			hits.addAll(join(future));
		}
		return merge(hits, safeLimit * 2);
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
			.sorted(Comparator.comparingDouble(QdrantSearchHit::score).reversed())
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
