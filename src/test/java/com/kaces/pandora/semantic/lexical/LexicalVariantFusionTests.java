package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LexicalVariantFusionTests {

	private final LexicalVariantFusion fusion = new LexicalVariantFusion();

	@Test
	void fusesVariantRanksWithDeterministicTieBreaksAndTermUnion() {
		var first = new LexicalVariantFusion.VariantHits("entity-intent", List.of(
			hit("law", 20, 200, 4.0, 1, "공공데이터"),
			hit("law", 10, 100, 3.0, 2, "제재")
		));
		var second = new LexicalVariantFusion.VariantHits("direct-evidence", List.of(
			hit("law", 10, 100, 8.0, 1, "벌칙"),
			hit("law", 20, 200, 7.0, 2, "과태료"),
			hit("rag", 30, 300, 2.0, 3, "처벌")
		));

		LexicalVariantFusion.Result result = fusion.fuse(List.of(first, second), 60.0, 10);

		assertThat(result.status()).isEqualTo(LexicalVariantFusion.Status.APPLIED);
		assertThat(result.hits()).extracting(LexicalVariantFusion.Hit::candidateKey)
			.containsExactly("law:10", "law:20", "rag:30");
		assertThat(result.hits().get(0).score()).isEqualTo(1.0 / 62.0 + 1.0 / 61.0);
		assertThat(result.hits().get(0).bestVariantRank()).isEqualTo(1);
		assertThat(result.hits().get(0).variantRanks()).isEqualTo(Map.of(
			"entity-intent", 2,
			"direct-evidence", 1
		));
		assertThat(result.hits().get(0).matchedTerms()).containsExactly("벌칙", "제재");
	}

	@Test
	void rejectsEntireCandidateWhenOneVariantContainsDuplicateIdentity() {
		var malformed = new LexicalVariantFusion.VariantHits("original-focused", List.of(
			hit("law", 10, 100, 5.0, 1, "공공데이터"),
			hit("law", 10, 100, 4.0, 2, "제재")
		));

		LexicalVariantFusion.Result result = fusion.fuse(List.of(malformed), 60.0, 10);

		assertThat(result.status()).isEqualTo(LexicalVariantFusion.Status.INVALID_INPUT);
		assertThat(result.hits()).isEmpty();
		assertThat(result.reasonCode()).isEqualTo("DUPLICATE_VARIANT_CANDIDATE");
	}

	@Test
	void rejectsUnboundedOrMalformedInputsWithoutPartialFusion() {
		var valid = new LexicalVariantFusion.VariantHits("v1", List.of(
			hit("law", 10, 100, 5.0, 1, "공공데이터")
		));
		var invalidScore = new LexicalVariantFusion.VariantHits("v2", List.of(
			new LexicalSearchHit("law", 20, 200, Double.NaN, 1, List.of("제재"))
		));

		assertThat(fusion.fuse(List.of(valid, invalidScore), 60.0, 10).status())
			.isEqualTo(LexicalVariantFusion.Status.INVALID_INPUT);
		assertThat(fusion.fuse(List.of(valid), 0.0, 10).status())
			.isEqualTo(LexicalVariantFusion.Status.INVALID_INPUT);
		assertThat(fusion.fuse(List.of(valid, valid, valid, valid, valid), 60.0, 10).status())
			.isEqualTo(LexicalVariantFusion.Status.INVALID_INPUT);
	}

	private LexicalSearchHit hit(
		String target,
		long chunkId,
		long documentId,
		double score,
		int rank,
		String... matchedTerms
	) {
		return new LexicalSearchHit(target, chunkId, documentId, score, rank, List.of(matchedTerms));
	}
}
