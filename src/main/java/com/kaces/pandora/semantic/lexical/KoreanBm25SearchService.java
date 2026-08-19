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
	private static final int MAX_POSTING_DOCUMENT_BUDGET = 12_000;
	private static final int MAX_POSTING_QUERY_TERMS = 8;

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
		long started = System.nanoTime();
		String revision = readyRevision();
		List<String> terms = queryTerms(query);
		if (revision == null || terms.isEmpty()) {
			return List.of();
		}
		List<String> targetFilter = normalizedTargets(targets);
		try {
			List<String> postingTerms = selectPostingTerms(revision, terms);
			if (postingTerms.isEmpty()) {
				return List.of();
			}
			List<SemanticLexicalMapper.Bm25TermMatchRow> rows = mapper.findBm25TermMatches(
				revision,
				postingTerms,
				targetFilter
			);
			List<LexicalSearchHit> results = rank(rows, boundedLimit(limit));
			log.info(
				"Korean BM25 shadow completed. revision={} termCount={} postingTermCount={} resultCount={} elapsedMs={}",
				revision, terms.size(), postingTerms.size(), results.size(), elapsedMillis(started)
			);
			return results;
		} catch (RuntimeException exception) {
			log.warn(
				"Korean BM25 shadow failed closed. revision={} termCount={} elapsedMs={} failureType={}",
				revision, terms.size(), elapsedMillis(started), exception.getClass().getSimpleName()
			);
			return List.of();
		}
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

	private String readyRevision() {
		try {
			String revision = mapper.findReadyRevision();
			return revision == null || revision.isBlank() ? null : revision;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private List<String> queryTerms(String query) {
		Set<String> terms = new LinkedHashSet<>();
		for (String base : tokenizer.tokenize(query).keySet()) {
			for (String expansion : KoreanQueryNormalizer.expandSearchKeywords(base)) {
				terms.addAll(tokenizer.tokenize(expansion).keySet());
				if (terms.size() >= properties.maxQueryTerms()) {
					return terms.stream().limit(properties.maxQueryTerms()).toList();
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
