package com.kaces.pandora.semantic.lexical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

@Component
public final class LexicalVariantFusion {

	private static final int MAX_VARIANTS = 4;
	private static final int MAX_LIMIT = 100;

	public enum Status {
		APPLIED,
		EMPTY,
		INVALID_INPUT
	}

	public record VariantHits(String variantId, List<LexicalSearchHit> hits) {
		public VariantHits {
			hits = hits == null ? null : List.copyOf(hits);
		}
	}

	public record Hit(
		String target,
		long chunkId,
		long documentId,
		double score,
		int bestVariantRank,
		List<String> matchedTerms,
		Map<String, Integer> variantRanks
	) {
		public Hit {
			matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
			variantRanks = variantRanks == null ? Map.of() : Map.copyOf(variantRanks);
		}

		public String candidateKey() {
			return target + ':' + chunkId;
		}
	}

	public record Result(Status status, String reasonCode, List<Hit> hits) {
		public Result {
			hits = hits == null ? List.of() : List.copyOf(hits);
		}
	}

	public Result fuse(List<VariantHits> variants, double rrfK, int limit) {
		if (!Double.isFinite(rrfK) || rrfK <= 0 || limit <= 0 || limit > MAX_LIMIT) {
			return invalid("INVALID_FUSION_BOUNDS");
		}
		if (variants == null || variants.isEmpty()) {
			return new Result(Status.EMPTY, "NO_VARIANTS", List.of());
		}
		if (variants.size() > MAX_VARIANTS) {
			return invalid("TOO_MANY_VARIANTS");
		}

		Set<String> variantIds = new TreeSet<>();
		Map<String, Accumulator> candidates = new LinkedHashMap<>();
		for (VariantHits variant : variants) {
			if (variant == null || variant.variantId() == null || variant.variantId().isBlank()) {
				return invalid("INVALID_VARIANT_ID");
			}
			String variantId = variant.variantId().trim();
			if (!variantIds.add(variantId)) {
				return invalid("DUPLICATE_VARIANT_ID");
			}
			if (variant.hits() == null || variant.hits().size() > limit) {
				return invalid("INVALID_VARIANT_HITS");
			}
			Set<String> variantCandidates = new TreeSet<>();
			for (int index = 0; index < variant.hits().size(); index++) {
				LexicalSearchHit hit = variant.hits().get(index);
				String invalidReason = validateHit(hit, index + 1);
				if (invalidReason != null) {
					return invalid(invalidReason);
				}
				String candidateKey = hit.target().trim() + ':' + hit.chunkId();
				if (!variantCandidates.add(candidateKey)) {
					return invalid("DUPLICATE_VARIANT_CANDIDATE");
				}
				Accumulator candidate = candidates.get(candidateKey);
				if (candidate != null && candidate.documentId != hit.documentId()) {
					return invalid("CONFLICTING_DOCUMENT_ID");
				}
				if (candidate == null) {
					candidate = new Accumulator(hit.target().trim(), hit.chunkId(), hit.documentId());
					candidates.put(candidateKey, candidate);
				}
				candidate.score += 1.0 / (rrfK + hit.rank());
				candidate.bestVariantRank = Math.min(candidate.bestVariantRank, hit.rank());
				candidate.variantRanks.put(variantId, hit.rank());
				for (String matchedTerm : hit.matchedTerms()) {
					if (matchedTerm != null && !matchedTerm.isBlank()) {
						candidate.matchedTerms.add(matchedTerm.trim());
					}
				}
			}
		}
		if (candidates.isEmpty()) {
			return new Result(Status.EMPTY, "NO_HITS", List.of());
		}

		List<Hit> hits = candidates.values().stream()
			.filter(candidate -> Double.isFinite(candidate.score) && candidate.score > 0)
			.sorted(Comparator.comparingDouble(Accumulator::score).reversed()
				.thenComparingInt(Accumulator::bestVariantRank)
				.thenComparing(Accumulator::target)
				.thenComparingLong(Accumulator::chunkId)
				.thenComparingLong(Accumulator::documentId))
			.limit(limit)
			.map(Accumulator::toHit)
			.toList();
		return hits.isEmpty()
			? invalid("INVALID_FUSED_SCORE")
			: new Result(Status.APPLIED, "", hits);
	}

	private String validateHit(LexicalSearchHit hit, int expectedRank) {
		if (hit == null || hit.target() == null || hit.target().isBlank()
			|| hit.chunkId() <= 0 || hit.documentId() <= 0
			|| hit.rank() != expectedRank
			|| !Double.isFinite(hit.score()) || hit.score() <= 0) {
			return "MALFORMED_VARIANT_HIT";
		}
		return null;
	}

	private Result invalid(String reasonCode) {
		return new Result(Status.INVALID_INPUT, reasonCode, List.of());
	}

	private static final class Accumulator {
		private final String target;
		private final long chunkId;
		private final long documentId;
		private final TreeSet<String> matchedTerms = new TreeSet<>();
		private final LinkedHashMap<String, Integer> variantRanks = new LinkedHashMap<>();
		private double score;
		private int bestVariantRank = Integer.MAX_VALUE;

		private Accumulator(String target, long chunkId, long documentId) {
			this.target = target;
			this.chunkId = chunkId;
			this.documentId = documentId;
		}

		private String target() {
			return target;
		}

		private long chunkId() {
			return chunkId;
		}

		private long documentId() {
			return documentId;
		}

		private double score() {
			return score;
		}

		private int bestVariantRank() {
			return bestVariantRank;
		}

		private Hit toHit() {
			return new Hit(
				target,
				chunkId,
				documentId,
				score,
				bestVariantRank,
				List.copyOf(matchedTerms),
				Map.copyOf(variantRanks)
			);
		}
	}
}
