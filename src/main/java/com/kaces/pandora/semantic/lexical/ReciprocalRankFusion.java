package com.kaces.pandora.semantic.lexical;

import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ReciprocalRankFusion {

	private static final int DEFAULT_LIMIT = 100;

	public List<RrfHit> fuse(
		List<QdrantSearchHit> vectorHits,
		List<LexicalSearchHit> lexicalHits,
		int k,
		double vectorWeight,
		double lexicalWeight
	) {
		return fuse(vectorHits, lexicalHits, k, vectorWeight, lexicalWeight, DEFAULT_LIMIT);
	}

	public List<RrfHit> fuse(
		List<QdrantSearchHit> vectorHits,
		List<LexicalSearchHit> lexicalHits,
		int k,
		double vectorWeight,
		double lexicalWeight,
		int limit
	) {
		int safeK = Math.max(1, k);
		Map<String, Accumulator> candidates = new LinkedHashMap<>();
		addVectorRanks(candidates, vectorHits, safeK, Math.max(0.0, vectorWeight));
		addLexicalRanks(candidates, lexicalHits, safeK, Math.max(0.0, lexicalWeight));
		return candidates.values().stream()
			.map(Accumulator::toHit)
			.sorted(Comparator.comparingDouble(RrfHit::score).reversed()
				.thenComparingInt(RrfHit::bestSourceRank)
				.thenComparing(RrfHit::target)
				.thenComparingLong(RrfHit::chunkId))
			.limit(Math.max(1, limit))
			.toList();
	}

	private void addVectorRanks(
		Map<String, Accumulator> candidates,
		List<QdrantSearchHit> hits,
		int k,
		double weight
	) {
		if (hits == null || weight == 0) {
			return;
		}
		for (int index = 0; index < hits.size(); index++) {
			QdrantSearchHit hit = hits.get(index);
			if (hit == null) {
				continue;
			}
			int rank = index + 1;
			Accumulator candidate = candidate(candidates, hit.target(), hit.chunkId());
			if (candidate.vectorRank == null) {
				candidate.vectorRank = rank;
				candidate.score += weight / (k + (double) rank);
			}
		}
	}

	private void addLexicalRanks(
		Map<String, Accumulator> candidates,
		List<LexicalSearchHit> hits,
		int k,
		double weight
	) {
		if (hits == null || weight == 0) {
			return;
		}
		for (int index = 0; index < hits.size(); index++) {
			LexicalSearchHit hit = hits.get(index);
			if (hit == null) {
				continue;
			}
			int rank = hit.rank() > 0 ? hit.rank() : index + 1;
			Accumulator candidate = candidate(candidates, hit.target(), hit.chunkId());
			if (candidate.lexicalRank == null || rank < candidate.lexicalRank) {
				if (candidate.lexicalRank != null) {
					candidate.score -= weight / (k + (double) candidate.lexicalRank);
				}
				candidate.lexicalRank = rank;
				candidate.score += weight / (k + (double) rank);
			}
		}
	}

	private Accumulator candidate(Map<String, Accumulator> candidates, String target, long chunkId) {
		String safeTarget = target == null ? "" : target;
		return candidates.computeIfAbsent(
			safeTarget + ':' + chunkId,
			ignored -> new Accumulator(safeTarget, chunkId)
		);
	}

	public record RrfHit(
		String candidateKey,
		String target,
		long chunkId,
		double score,
		Integer vectorRank,
		Integer lexicalRank,
		int bestSourceRank
	) {
	}

	private static final class Accumulator {
		private final String target;
		private final long chunkId;
		private Integer vectorRank;
		private Integer lexicalRank;
		private double score;

		private Accumulator(String target, long chunkId) {
			this.target = target;
			this.chunkId = chunkId;
		}

		private RrfHit toHit() {
			int bestRank = Math.min(
				vectorRank == null ? Integer.MAX_VALUE : vectorRank,
				lexicalRank == null ? Integer.MAX_VALUE : lexicalRank
			);
			return new RrfHit(
				target + ':' + chunkId,
				target,
				chunkId,
				score,
				vectorRank,
				lexicalRank,
				bestRank
			);
		}
	}
}
