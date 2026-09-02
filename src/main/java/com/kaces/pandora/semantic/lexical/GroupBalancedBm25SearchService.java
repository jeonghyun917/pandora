package com.kaces.pandora.semantic.lexical;

import com.kaces.pandora.common.text.QuestionSearchPlan;
import com.kaces.pandora.semantic.config.LawAiLexicalVariantProperties;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GroupBalancedBm25SearchService {

	private static final Logger log = LoggerFactory.getLogger(GroupBalancedBm25SearchService.class);

	public enum Status {
		DISABLED,
		APPLIED,
		EMPTY,
		FAILED,
		INVALID_CONFIG
	}

	public record Result(
		Status status,
		List<String> reasonCodes,
		List<String> variantHashes,
		Map<String, Integer> variantHitCounts,
		List<LexicalVariantFusion.Hit> fusedHits,
		long planningMillis,
		long searchMillis,
		long fusionMillis
	) {
		public Result {
			reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
			variantHashes = variantHashes == null ? List.of() : List.copyOf(variantHashes);
			variantHitCounts = variantHitCounts == null ? Map.of() : Map.copyOf(variantHitCounts);
			fusedHits = fusedHits == null ? List.of() : List.copyOf(fusedHits);
		}
	}

	private final KoreanBm25SearchService bm25SearchService;
	private final LexicalVariantFusion fusion;
	private final LawAiLexicalVariantProperties properties;

	public GroupBalancedBm25SearchService(
		KoreanBm25SearchService bm25SearchService,
		LexicalVariantFusion fusion,
		LawAiLexicalVariantProperties properties
	) {
		this.bm25SearchService = bm25SearchService;
		this.fusion = fusion;
		this.properties = properties;
	}

	public Result search(QuestionSearchPlan plan, List<String> targets, int limit) {
		if (!properties.shadowEnabled()) {
			return result(Status.DISABLED, List.of(), List.of(), Map.of(), List.of(), 0, 0, 0);
		}
		if (!properties.valid()) {
			return result(
				Status.INVALID_CONFIG,
				List.of("INVALID_VARIANT_CONFIG"),
				List.of(), Map.of(), List.of(), 0, 0, 0
			);
		}
		if (plan == null || limit <= 0) {
			return result(
				Status.FAILED,
				List.of("INVALID_VARIANT_REQUEST"),
				List.of(), Map.of(), List.of(), 0, 0, 0
			);
		}

		long planningStarted = System.nanoTime();
		List<QuestionSearchPlan.LexicalVariant> variants = plan.bm25Variants().stream()
			.limit(properties.maxVariants())
			.toList();
		long planningMillis = elapsedMillis(planningStarted);
		List<String> hashes = variants.stream().map(QuestionSearchPlan.LexicalVariant::tokenSetHash).toList();
		if (variants.isEmpty()) {
			return result(Status.EMPTY, List.of("NO_VARIANTS"), hashes, Map.of(), List.of(), planningMillis, 0, 0);
		}

		long searchStarted = System.nanoTime();
		List<LexicalVariantFusion.VariantHits> variantHits = new ArrayList<>();
		Map<String, Integer> hitCounts = new LinkedHashMap<>();
		try {
			for (QuestionSearchPlan.LexicalVariant variant : variants) {
				List<LexicalSearchHit> hits = bm25SearchService.searchStrict(
					variant.query(),
					variant.plannedKeywords(),
					targets,
					limit
				);
				variantHits.add(new LexicalVariantFusion.VariantHits(variant.id(), hits));
				hitCounts.put(variant.id(), hits == null ? 0 : hits.size());
			}
		} catch (RuntimeException exception) {
			log.warn("Group-balanced BM25 shadow failed closed. failureType={}", exception.getClass().getSimpleName());
			return result(
				Status.FAILED,
				List.of("VARIANT_SEARCH_FAILED"),
				hashes, hitCounts, List.of(), planningMillis, elapsedMillis(searchStarted), 0
			);
		}
		long searchMillis = elapsedMillis(searchStarted);

		long fusionStarted = System.nanoTime();
		LexicalVariantFusion.Result fused = fusion.fuse(variantHits, properties.rrfK(), limit);
		long fusionMillis = elapsedMillis(fusionStarted);
		if (fused.status() == LexicalVariantFusion.Status.INVALID_INPUT) {
			return result(
				Status.FAILED,
				List.of("VARIANT_FUSION_" + fused.reasonCode()),
				hashes, hitCounts, List.of(), planningMillis, searchMillis, fusionMillis
			);
		}
		Status status = fused.status() == LexicalVariantFusion.Status.APPLIED ? Status.APPLIED : Status.EMPTY;
		List<String> reasons = fused.reasonCode().isBlank() ? List.of() : List.of(fused.reasonCode());
		return result(
			status, reasons, hashes, hitCounts, fused.hits(), planningMillis, searchMillis, fusionMillis
		);
	}

	private Result result(
		Status status,
		List<String> reasons,
		List<String> hashes,
		Map<String, Integer> hitCounts,
		List<LexicalVariantFusion.Hit> hits,
		long planningMillis,
		long searchMillis,
		long fusionMillis
	) {
		return new Result(
			status,
			reasons,
			hashes,
			hitCounts,
			hits,
			planningMillis,
			searchMillis,
			fusionMillis
		);
	}

	private long elapsedMillis(long started) {
		return (System.nanoTime() - started) / 1_000_000L;
	}
}
