package com.kaces.pandora.semantic.retrieval;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.semantic.lexical.LexicalSearchHit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class Bm25TitleDocumentSeedSelector {

	private static final String ANCHOR_TYPE = "BM25_TITLE";
	private static final String REASON = "BM25_TITLE_SEED";

	public Selection select(
		List<LexicalSearchHit> hits,
		List<LawSemanticChunkRow> hydratedRows,
		List<String> plannedTerms,
		List<String> allowedTargets,
		Policy policy
	) {
		if (!validInputs(hits, hydratedRows, plannedTerms, allowedTargets, policy)) {
			return Selection.empty(Status.INVALID_INPUT);
		}
		if (!policy.enabled()) {
			return Selection.empty(Status.DISABLED);
		}

		Set<String> allowed = normalizeTargets(allowedTargets);
		Set<String> planned = normalizeTerms(plannedTerms);
		if (allowed.isEmpty() || planned.size() < policy.minimumDistinctTitleTerms()) {
			return Selection.empty(Status.NO_MATCH);
		}

		Map<String, LawSemanticChunkRow> rowByCandidate = new LinkedHashMap<>();
		for (LawSemanticChunkRow row : hydratedRows) {
			if (!validRow(row)) {
				return Selection.empty(Status.INVALID_INPUT);
			}
			String key = candidateKey(row.target(), row.chunkId());
			if (rowByCandidate.putIfAbsent(key, row) != null) {
				return Selection.empty(Status.INVALID_INPUT);
			}
		}

		Map<String, MutableSeed> byDocument = new LinkedHashMap<>();
		int inspected = Math.min(hits.size(), policy.maxBm25HitsInspected());
		for (int index = 0; index < inspected; index++) {
			LexicalSearchHit hit = hits.get(index);
			if (!validHit(hit)) {
				return Selection.empty(Status.INVALID_INPUT);
			}
			String target = normalizeTarget(hit.target());
			if (!allowed.contains(target)) {
				return Selection.empty(Status.INVALID_INPUT);
			}
			LawSemanticChunkRow row = rowByCandidate.get(candidateKey(target, hit.chunkId()));
			if (row == null || row.documentId() != hit.documentId() || !target.equals(normalizeTarget(row.target()))) {
				return Selection.empty(Status.INVALID_INPUT);
			}

			String normalizedTitle = KoreanQueryNormalizer.normalizeForMatch(row.title());
			String documentKey = documentKey(target, hit.documentId());
			MutableSeed seed = byDocument.get(documentKey);
			if (seed != null && !seed.normalizedTitle.equals(normalizedTitle)) {
				return Selection.empty(Status.INVALID_INPUT);
			}
			if (seed == null) {
				seed = new MutableSeed(target, hit.documentId(), row.title(), normalizedTitle);
				byDocument.put(documentKey, seed);
			}
			for (String rawTerm : hit.matchedTerms()) {
				String term = KoreanQueryNormalizer.normalizeForMatch(rawTerm);
				if (!term.isBlank()
					&& planned.contains(term)
					&& !KoreanQueryNormalizer.isWeakQuestionTerm(term)
					&& normalizedTitle.contains(term)) {
					seed.matchedTerms.add(term);
				}
			}
			seed.bestScore = Math.max(seed.bestScore, hit.score());
			seed.bestRank = Math.min(seed.bestRank, hit.rank());
		}

		List<DocumentExpansionSeed> eligible = byDocument.values().stream()
			.filter(seed -> seed.matchedTerms.size() >= policy.minimumDistinctTitleTerms())
			.map(MutableSeed::toSeed)
			.sorted(seedComparator())
			.toList();
		if (eligible.isEmpty()) {
			return Selection.empty(Status.NO_MATCH);
		}
		if (eligible.size() > policy.maxDocuments()
			&& ambiguousBoundary(eligible.get(policy.maxDocuments() - 1), eligible.get(policy.maxDocuments()), policy)) {
			return Selection.empty(Status.AMBIGUOUS);
		}
		return new Selection(Status.APPLIED, eligible.stream().limit(policy.maxDocuments()).toList());
	}

	private boolean validInputs(
		List<LexicalSearchHit> hits,
		List<LawSemanticChunkRow> rows,
		List<String> plannedTerms,
		List<String> targets,
		Policy policy
	) {
		return hits != null && rows != null && plannedTerms != null && targets != null && policy != null
			&& policy.validBounds();
	}

	private boolean validHit(LexicalSearchHit hit) {
		return hit != null
			&& hit.chunkId() > 0
			&& hit.documentId() > 0
			&& hit.rank() > 0
			&& Double.isFinite(hit.score())
			&& hit.score() > 0.0
			&& !normalizeTarget(hit.target()).isBlank();
	}

	private boolean validRow(LawSemanticChunkRow row) {
		return row != null
			&& row.chunkId() > 0
			&& row.documentId() > 0
			&& !normalizeTarget(row.target()).isBlank()
			&& !KoreanQueryNormalizer.normalizeForMatch(row.title()).isBlank();
	}

	private Set<String> normalizeTargets(List<String> targets) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String target : targets) {
			String value = normalizeTarget(target);
			if (!value.isBlank()) {
				normalized.add(value);
			}
		}
		return normalized;
	}

	private Set<String> normalizeTerms(List<String> terms) {
		Set<String> normalized = new LinkedHashSet<>();
		for (String term : terms) {
			String value = KoreanQueryNormalizer.normalizeForMatch(term);
			if (!value.isBlank() && !KoreanQueryNormalizer.isWeakQuestionTerm(value)) {
				normalized.add(value);
			}
		}
		return normalized;
	}

	private Comparator<DocumentExpansionSeed> seedComparator() {
		return Comparator
			.<DocumentExpansionSeed>comparingInt(seed -> seed.matchedTitleTerms().size()).reversed()
			.thenComparing(Comparator.comparingDouble(DocumentExpansionSeed::bm25Score).reversed())
			.thenComparingInt(DocumentExpansionSeed::bm25Rank)
			.thenComparing(DocumentExpansionSeed::target)
			.thenComparingLong(DocumentExpansionSeed::documentId);
	}

	private boolean ambiguousBoundary(DocumentExpansionSeed included, DocumentExpansionSeed excluded, Policy policy) {
		if (included.matchedTitleTerms().size() != excluded.matchedTitleTerms().size()) {
			return false;
		}
		double ratio = (included.bm25Score() - excluded.bm25Score()) / included.bm25Score();
		return ratio <= policy.ambiguityScoreRatio() + 1.0e-12;
	}

	private String normalizeTarget(String target) {
		return target == null ? "" : target.trim().toLowerCase(Locale.ROOT);
	}

	private String candidateKey(String target, long chunkId) {
		return normalizeTarget(target) + ':' + chunkId;
	}

	private String documentKey(String target, long documentId) {
		return normalizeTarget(target) + ':' + documentId;
	}

	public enum Status {
		DISABLED,
		APPLIED,
		NO_MATCH,
		AMBIGUOUS,
		INVALID_INPUT
	}

	public record Selection(Status status, List<DocumentExpansionSeed> seeds) {
		public Selection {
			status = status == null ? Status.INVALID_INPUT : status;
			seeds = seeds == null ? List.of() : List.copyOf(seeds);
		}

		private static Selection empty(Status status) {
			return new Selection(status, List.of());
		}
	}

	public record Policy(
		boolean enabled,
		int maxBm25HitsInspected,
		int minimumDistinctTitleTerms,
		double ambiguityScoreRatio,
		int maxDocuments
	) {
		public boolean validBounds() {
			return maxBm25HitsInspected >= 1 && maxBm25HitsInspected <= 100
				&& minimumDistinctTitleTerms >= 2 && minimumDistinctTitleTerms <= 6
				&& Double.isFinite(ambiguityScoreRatio)
				&& ambiguityScoreRatio >= 0.0 && ambiguityScoreRatio <= 0.25
				&& maxDocuments >= 1 && maxDocuments <= 3;
		}
	}

	private static final class MutableSeed {
		private final String target;
		private final long documentId;
		private final String title;
		private final String normalizedTitle;
		private final LinkedHashSet<String> matchedTerms = new LinkedHashSet<>();
		private double bestScore = Double.NEGATIVE_INFINITY;
		private int bestRank = Integer.MAX_VALUE;

		private MutableSeed(String target, long documentId, String title, String normalizedTitle) {
			this.target = target;
			this.documentId = documentId;
			this.title = title;
			this.normalizedTitle = normalizedTitle;
		}

		private DocumentExpansionSeed toSeed() {
			return new DocumentExpansionSeed(
				target, documentId, title, new ArrayList<>(matchedTerms), bestScore, bestRank, ANCHOR_TYPE, REASON
			);
		}
	}
}
