package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReciprocalRankFusionTests {

	@Test
	void fusesVectorAndLexicalRanksDeterministically() {
		List<QdrantSearchHit> vectorHits = List.of(
			new QdrantSearchHit("law", 10L, 0.99),
			new QdrantSearchHit("law", 20L, 0.95),
			new QdrantSearchHit("admrul", 30L, 0.90)
		);
		List<LexicalSearchHit> lexicalHits = List.of(
			lexical("law", 20L, 1),
			lexical("admrul", 30L, 2),
			lexical("law", 10L, 3)
		);

		List<ReciprocalRankFusion.RrfHit> fused = new ReciprocalRankFusion()
			.fuse(vectorHits, lexicalHits, 60, 1.0, 1.0);

		assertThat(fused).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.containsExactly("law:20", "law:10", "admrul:30");
		assertThat(fused.get(0).vectorRank()).isEqualTo(2);
		assertThat(fused.get(0).lexicalRank()).isEqualTo(1);
	}

	@Test
	void breaksExactScoreAndBestRankTiesByTargetThenNumericChunkId() {
		List<LexicalSearchHit> lexicalHits = List.of(
			lexical("law", 20L, 1),
			lexical("admrul", 30L, 1),
			lexical("law", 10L, 1)
		);

		List<ReciprocalRankFusion.RrfHit> fused = new ReciprocalRankFusion()
			.fuse(List.of(), lexicalHits, 60, 1.0, 1.0);

		assertThat(fused).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.containsExactly("admrul:30", "law:10", "law:20");
	}

	@Test
	void deduplicatesCandidatesWithinEachSourceAndBoundsTheResult() {
		List<QdrantSearchHit> vectorHits = List.of(
			new QdrantSearchHit("law", 1L, 0.9),
			new QdrantSearchHit("law", 1L, 0.8),
			new QdrantSearchHit("law", 2L, 0.7)
		);

		List<ReciprocalRankFusion.RrfHit> fused = new ReciprocalRankFusion()
			.fuse(vectorHits, List.of(), 60, 1.0, 1.0, 1);

		assertThat(fused).singleElement()
			.extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.isEqualTo("law:1");
	}

	private LexicalSearchHit lexical(String target, long chunkId, int rank) {
		return new LexicalSearchHit(target, chunkId, 1_000 + chunkId, 10.0 / rank, rank, List.of("검사"));
	}
}
