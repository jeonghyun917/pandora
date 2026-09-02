package com.kaces.pandora.semantic.lexical;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class KoreanBm25SearchService {

	private static final Logger log = LoggerFactory.getLogger(KoreanBm25SearchService.class);
	private static final int MAX_POSTING_DOCUMENT_BUDGET = 4_000;
	private static final int MAX_POSTING_QUERY_TERMS = 6;

	private final SemanticLexicalMapper mapper;
	private final KoreanLexicalTokenizer tokenizer;
	private final LawAiLexicalProperties properties;

	public KoreanBm25SearchService(
		SemanticLexicalMapper mapper,
		KoreanLexicalTokenizer tokenizer,
		LawAiLexicalProperties properties
	) {
		this.mapper = mapper;
		this.tokenizer = tokenizer;
		this.properties = properties;
	}

	public List<LexicalSearchHit> search(String query, List<String> targets, int limit) {
		return search(query, List.of(), targets, limit);
	}

	public List<LexicalSearchHit> search(
		String query,
		List<String> plannedKeywords,
		List<String> targets,
		int limit
	) {
		long started = System.nanoTime();
		try {
			return searchStrict(query, plannedKeywords, targets, limit);
		} catch (RuntimeException exception) {
			log.warn(
				"Korean BM25 shadow failed closed. elapsedMs={} failureType={}",
				elapsedMillis(started), exception.getClass().getSimpleName()
			);
			return List.of();
		}
	}

	List<LexicalSearchHit> searchStrict(
		String query,
		List<String> plannedKeywords,
		List<String> targets,
		int limit
	) {
		long started = System.nanoTime();
		String foundRevision = mapper.findReadyRevision();
		String revision = foundRevision == null || foundRevision.isBlank() ? null : foundRevision;
		List<String> terms = queryTerms(query, plannedKeywords);
		if (revision == null || terms.isEmpty()) {
			return List.of();
		}
		List<String> postingTerms = selectPostingTerms(revision, terms);
		if (postingTerms.isEmpty()) {
			return List.of();
		}
		List<SemanticLexicalMapper.Bm25TermMatchRow> rows = mapper.findBm25TermMatches(
			revision,
			postingTerms,
			normalizedTargets(targets)
		);
		List<LexicalSearchHit> results = rank(rows, boundedLimit(limit));
		log.info(
			"Korean BM25 shadow completed. revision={} termCount={} postingTermCount={} resultCount={} elapsedMs={}",
			revision, terms.size(), postingTerms.size(), results.size(), elapsedMillis(started)
		);
		return results;
	}

	private List<String> selectPostingTerms(String revision, List<String> terms) {
		List<SemanticLexicalMapper.TermStatisticRow> statistics = mapper.findTermStatistics(revision, terms);
		if (statistics == null || statistics.isEmpty()) {
			return List.of();
		}
		List<String> selected = new ArrayList<>();
		long postingBudget = 0;
		for (SemanticLexicalMapper.TermStatisticRow statistic : statistics) {
			if (statistic == null || statistic.term() == null || statistic.term().isBlank()) {
				continue;
			}
			long frequency = Math.max(1, statistic.documentFrequency());
			if (!selected.isEmpty() && postingBudget + frequency > MAX_POSTING_DOCUMENT_BUDGET) {
				break;
			}
			selected.add(statistic.term());
			postingBudget += frequency;
			if (selected.size() >= MAX_POSTING_QUERY_TERMS) {
				break;
			}
		}
		return List.copyOf(selected);
	}

	private List<String> queryTerms(String query, List<String> plannedKeywords) {
		Set<String> terms = new LinkedHashSet<>();
		List<String> sources = new ArrayList<>();
		sources.add(query);
		if (plannedKeywords != null) {
			sources.addAll(plannedKeywords);
		}
		for (String source : sources) {
			if (source == null || source.isBlank()) {
				continue;
			}
			for (String base : tokenizer.tokenize(source).keySet()) {
				for (String expansion : KoreanQueryNormalizer.expandSearchKeywords(base)) {
					terms.addAll(tokenizer.tokenize(expansion).keySet());
					if (terms.size() >= properties.maxQueryTerms()) {
						return terms.stream().limit(properties.maxQueryTerms()).toList();
					}
				}
			}
		}
		return List.copyOf(terms);
	}

	private List<String> normalizedTargets(List<String> targets) {
		if (targets == null || targets.isEmpty()) {
			return List.of();
		}
		return targets.stream()
			.filter(target -> target != null && !target.isBlank())
			.map(String::trim)
			.distinct()
			.toList();
	}

	private List<LexicalSearchHit> rank(
		List<SemanticLexicalMapper.Bm25TermMatchRow> rows,
		int limit
	) {
		if (rows == null || rows.isEmpty()) {
			return List.of();
		}
		Map<String, Accumulator> scores = new LinkedHashMap<>();
		for (SemanticLexicalMapper.Bm25TermMatchRow row : rows) {
			if (row == null) {
				continue;
			}
			String key = row.target() + ':' + row.chunkId();
			Accumulator accumulator = scores.computeIfAbsent(
				key,
				ignored -> new Accumulator(row.target(), row.chunkId(), row.documentId())
			);
			accumulator.score += score(row);
			accumulator.matchedTerms.add(row.term());
		}
		List<Accumulator> ordered = scores.values().stream()
			.filter(candidate -> Double.isFinite(candidate.score) && candidate.score > 0)
			.sorted(Comparator.comparingDouble(Accumulator::score).reversed()
				.thenComparing(Accumulator::target)
				.thenComparingLong(Accumulator::chunkId))
			.limit(limit)
			.toList();
		List<LexicalSearchHit> hits = new ArrayList<>(ordered.size());
		for (int index = 0; index < ordered.size(); index++) {
			Accumulator candidate = ordered.get(index);
			hits.add(new LexicalSearchHit(
				candidate.target,
				candidate.chunkId,
				candidate.documentId,
				candidate.score,
				index + 1,
				candidate.matchedTerms.stream().sorted().toList()
			));
		}
		return List.copyOf(hits);
	}

	private double score(SemanticLexicalMapper.Bm25TermMatchRow row) {
		double total = Math.max(1, row.activeChunkCount());
		double documentFrequency = Math.max(1, Math.min(row.documentFrequency(), total));
		double averageLength = row.averageWeightedLength() > 0 ? row.averageWeightedLength() : 1.0;
		double length = Math.max(0, row.weightedLength());
		double weightedFrequency = Math.max(0, row.weightedTermFrequency());
		double idf = Math.log(1 + (total - documentFrequency + 0.5) / (documentFrequency + 0.5));
		double denominator = weightedFrequency + properties.k1()
			* (1 - properties.b() + properties.b() * length / averageLength);
		return denominator == 0
			? 0.0
			: idf * (weightedFrequency * (properties.k1() + 1)) / denominator;
	}

	private int boundedLimit(int requested) {
		return Math.max(1, Math.min(requested, properties.maxResultLimit()));
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}

	private static final class Accumulator {
		private final String target;
		private final long chunkId;
		private final long documentId;
		private final Set<String> matchedTerms = new LinkedHashSet<>();
		private double score;

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

		private double score() {
			return score;
		}
	}
}
