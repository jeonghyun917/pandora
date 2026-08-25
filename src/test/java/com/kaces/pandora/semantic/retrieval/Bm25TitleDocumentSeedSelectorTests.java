package com.kaces.pandora.semantic.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.semantic.lexical.LexicalSearchHit;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Bm25TitleDocumentSeedSelectorTests {

	private final Bm25TitleDocumentSeedSelector selector = new Bm25TitleDocumentSeedSelector();

	@Test
	void selectsOnlyDocumentsWithTwoDistinctBm25TermsInTheDocumentTitle() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(
				hit("official_doc", 101, 10, 9.0, 1, "사전협의", "정보화사업"),
				hit("official_doc", 201, 20, 8.0, 2, "사전협의", "절차")
			),
			List.of(
				chunk(101, 10, "official_doc", "정보화사업 사전협의 지침", ""),
				chunk(201, 20, "official_doc", "일반 행정 지침", "사전협의 절차")
			),
			List.of("정보화사업", "사전협의", "절차"),
			List.of("official_doc"),
			policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.APPLIED);
		assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId).containsExactly(10L);
		assertThat(result.seeds().get(0).matchedTitleTerms()).containsExactly("사전협의", "정보화사업");
	}

	@Test
	void rejectsBodyChunkTitleAndParentTitleOnlyMatches() {
		LawSemanticChunkRow row = new LawSemanticChunkRow(
			101, 10, "official_doc", "ext-10", "일반 행정 지침", "기관", "분류", "2026-01-01",
			"ACTIVE", "1", "정보화사업", "사전협의 절차", 1, "path", "url", 1, "hash", "사전협의",
			"BODY", "PASS"
		);

		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(hit("official_doc", 101, 10, 9.0, 1, "사전협의", "정보화사업")),
			List.of(row),
			List.of("사전협의", "정보화사업"),
			List.of("official_doc"),
			policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.NO_MATCH);
		assertThat(result.seeds()).isEmpty();
	}

	@Test
	void ignoresWeakTermsWhenCountingTitleMatches() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(hit("law", 101, 10, 9.0, 1, "사전협의", "어떻게")),
			List.of(chunk(101, 10, "law", "사전협의 어떻게 안내", "")),
			List.of("사전협의", "어떻게"),
			List.of("law"),
			policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.NO_MATCH);
	}

	@Test
	void rejectsNonFiniteOrNonPositiveScores() {
		for (double score : List.of(Double.NaN, Double.POSITIVE_INFINITY, 0.0, -1.0)) {
			Bm25TitleDocumentSeedSelector.Selection result = selector.select(
				List.of(hit("law", 101, 10, score, 1, "정보화사업", "사전협의")),
				List.of(chunk(101, 10, "law", "정보화사업 사전협의 지침", "")),
				List.of("정보화사업", "사전협의"),
				List.of("law"),
				policy(3)
			);

			assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.INVALID_INPUT);
		}
	}

	@Test
	void rejectsMissingHydrationAndTargetMismatch() {
		LexicalSearchHit hit = hit("law", 101, 10, 9.0, 1, "정보화사업", "사전협의");

		assertThat(selector.select(
			List.of(hit), List.of(), List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		).status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.INVALID_INPUT);

		assertThat(selector.select(
			List.of(hit),
			List.of(chunk(101, 10, "official_doc", "정보화사업 사전협의 지침", "")),
			List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		).status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.INVALID_INPUT);
	}

	@Test
	void returnsNoMatchWhenBm25HasNoHits() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(),
			List.of(),
			List.of("정보화사업", "사전협의"),
			List.of("law"),
			policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.NO_MATCH);
	}

	@Test
	void ignoresUnhydratedHitWhenLaterDocumentHasAValidTitleSeed() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(
				hit("law", 999, 99, 10.0, 1, "정보화사업", "사전협의"),
				hit("law", 101, 10, 9.0, 2, "정보화사업", "사전협의")
			),
			List.of(chunk(101, 10, "law", "정보화사업 사전협의 지침", "")),
			List.of("정보화사업", "사전협의"),
			List.of("law"),
			policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.APPLIED);
		assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId).containsExactly(10L);
	}

	@Test
	void ignoresBlankTitleRowWhenLaterDocumentHasAValidTitleSeed() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(
				hit("law", 999, 99, 10.0, 1, "정보화사업", "사전협의"),
				hit("law", 101, 10, 9.0, 2, "정보화사업", "사전협의")
			),
			List.of(
				chunk(999, 99, "law", "", ""),
				chunk(101, 10, "law", "정보화사업 사전협의 지침", "")
			),
			List.of("정보화사업", "사전협의"),
			List.of("law"),
			policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.APPLIED);
		assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId).containsExactly(10L);
	}

	@Test
	void aggregatesDuplicateChunkHitsForOneDocumentUsingBestScoreAndRank() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(
				hit("law", 102, 10, 8.0, 3, "정보화사업"),
				hit("law", 101, 10, 9.0, 1, "사전협의")
			),
			List.of(
				chunk(101, 10, "law", "정보화사업 사전협의 지침", ""),
				chunk(102, 10, "law", "정보화사업 사전협의 지침", "")
			),
			List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		);

		assertThat(result.seeds()).hasSize(1);
		assertThat(result.seeds().get(0).bm25Score()).isEqualTo(9.0);
		assertThat(result.seeds().get(0).bm25Rank()).isEqualTo(1);
		assertThat(result.seeds().get(0).matchedTitleTerms()).containsExactly("정보화사업", "사전협의");
	}

	@Test
	void ordersByTermCountThenScoreThenRank() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(
				hit("law", 301, 30, 8.0, 3, "정보화사업", "사전협의", "지침"),
				hit("law", 201, 20, 10.0, 2, "정보화사업", "사전협의"),
				hit("law", 101, 10, 10.0, 1, "정보화사업", "사전협의")
			),
			List.of(
				chunk(301, 30, "law", "정보화사업 사전협의 지침", ""),
				chunk(201, 20, "law", "정보화사업 사전협의", ""),
				chunk(101, 10, "law", "정보화사업 사전협의", "")
			),
			List.of("정보화사업", "사전협의", "지침"), List.of("law"), policy(3)
		);

		assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId)
			.containsExactly(30L, 10L, 20L);
	}

	@Test
	void breaksExactScoreAndRankTiesByDocumentIdentity() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			List.of(
				hit("law", 301, 30, 9.0, 1, "정보화사업", "사전협의"),
				hit("law", 201, 20, 9.0, 1, "정보화사업", "사전협의"),
				hit("law", 101, 10, 9.0, 1, "정보화사업", "사전협의")
			),
			List.of(
				chunk(301, 30, "law", "정보화사업 사전협의", ""),
				chunk(201, 20, "law", "정보화사업 사전협의", ""),
				chunk(101, 10, "law", "정보화사업 사전협의", "")
			),
			List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		);

		assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId)
			.containsExactly(10L, 20L, 30L);
	}

	@Test
	void inspectsAtMostOneHundredBm25Hits() {
		List<LexicalSearchHit> hits = new ArrayList<>();
		List<LawSemanticChunkRow> rows = new ArrayList<>();
		for (int index = 1; index <= 100; index++) {
			hits.add(hit("law", index, index, 200.0 - index, index, "사전협의"));
			rows.add(chunk(index, index, "law", "사전협의 지침", ""));
		}
		hits.add(hit("law", 101, 101, 1.0, 101, "정보화사업", "사전협의"));
		rows.add(chunk(101, 101, "law", "정보화사업 사전협의 지침", ""));

		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			hits, rows, List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.NO_MATCH);
	}

	@Test
	void limitsSeedsToThreeDocumentsWhenBoundaryIsNotAmbiguous() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			fourHits(10.0, 9.0, 8.0, 7.59), fourRows(),
			List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.APPLIED);
		assertThat(result.seeds()).extracting(DocumentExpansionSeed::documentId).containsExactly(10L, 20L, 30L);
	}

	@Test
	void failsClosedWhenFirstExcludedDocumentIsWithinFivePercentOfBoundary() {
		Bm25TitleDocumentSeedSelector.Selection result = selector.select(
			fourHits(10.0, 9.0, 8.0, 7.6), fourRows(),
			List.of("정보화사업", "사전협의"), List.of("law"), policy(3)
		);

		assertThat(result.status()).isEqualTo(Bm25TitleDocumentSeedSelector.Status.AMBIGUOUS);
		assertThat(result.seeds()).isEmpty();
	}

	private List<LexicalSearchHit> fourHits(double first, double second, double third, double fourth) {
		return List.of(
			hit("law", 101, 10, first, 1, "정보화사업", "사전협의"),
			hit("law", 201, 20, second, 2, "정보화사업", "사전협의"),
			hit("law", 301, 30, third, 3, "정보화사업", "사전협의"),
			hit("law", 401, 40, fourth, 4, "정보화사업", "사전협의")
		);
	}

	private List<LawSemanticChunkRow> fourRows() {
		return List.of(
			chunk(101, 10, "law", "정보화사업 사전협의 지침 10", ""),
			chunk(201, 20, "law", "정보화사업 사전협의 지침 20", ""),
			chunk(301, 30, "law", "정보화사업 사전협의 지침 30", ""),
			chunk(401, 40, "law", "정보화사업 사전협의 지침 40", "")
		);
	}

	private Bm25TitleDocumentSeedSelector.Policy policy(int maxDocuments) {
		return new Bm25TitleDocumentSeedSelector.Policy(true, 100, 2, 0.05, maxDocuments);
	}

	private LexicalSearchHit hit(
		String target, long chunkId, long documentId, double score, int rank, String... terms
	) {
		return new LexicalSearchHit(target, chunkId, documentId, score, rank, List.of(terms));
	}

	private LawSemanticChunkRow chunk(
		long chunkId, long documentId, String target, String title, String text
	) {
		return new LawSemanticChunkRow(
			chunkId, documentId, target, "ext-" + documentId, title, "기관", "분류", "2026-01-01",
			"ACTIVE", "1", "제1조", text, 1, "path", "url", 1, "hash", "장", "BODY", "PASS"
		);
	}
}
