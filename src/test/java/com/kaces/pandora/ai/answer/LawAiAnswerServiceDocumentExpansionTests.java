package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.text.DocumentSearchAnchor;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.openai.OpenAiAnswerClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiCoverageAwareProperties;
import com.kaces.pandora.semantic.config.LawAiDocumentExpansionProperties;
import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.config.LawAiRrfProperties;
import com.kaces.pandora.semantic.config.LawAiSemanticSelectionProperties;
import com.kaces.pandora.semantic.lexical.CoverageAwareFusion;
import com.kaces.pandora.semantic.lexical.KoreanBm25SearchService;
import com.kaces.pandora.semantic.lexical.LexicalSearchHit;
import com.kaces.pandora.semantic.lexical.ReciprocalRankFusion;
import com.kaces.pandora.semantic.retrieval.DocumentCandidateExpansion;
import com.kaces.pandora.semantic.retrieval.DocumentExpansionSearchService;
import com.kaces.pandora.semantic.retrieval.Bm25TitleDocumentSeedSelector;
import com.kaces.pandora.semantic.retrieval.DocumentExpansionSeed;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LawAiAnswerServiceDocumentExpansionTests {

	private static final String QUESTION = "「전자정부법」이란 무엇인가?";
	private static final String SECRET_TEXT = "PRODUCTION-CHUNK-TEXT-MUST-NOT-BECOME-METADATA";
	private static final LawSemanticChunkRow VECTOR_CHUNK = chunk(101, 10, 1, "전자정부법의 정의와 목적");
	private static final LawSemanticChunkRow BM25_CHUNK = chunk(201, 20, 2, "전자정부법의 정의와 목적");
	private static final LawSemanticChunkRow LEXICAL_CHUNK = chunk(301, 30, 3, "전자정부법의 정의와 목적");
	private static final List<QdrantSearchHit> VECTOR_HITS = List.of(new QdrantSearchHit("law", 101, 0.91));
	private static final List<LexicalSearchHit> BM25_HITS = List.of(
		new LexicalSearchHit("law", 201, 20, 4.2, 1, List.of("전자정부법"))
	);

	@Test
	void bm25TitleFallbackRemainsShadowOnlyAndAddsNoExternalRequest() {
		DocumentCandidateExpansion.Result seeded = new DocumentCandidateExpansion.Result(
			List.of(chunk(901, 90, 1, "BM25 title sibling")),
			List.of(new DocumentCandidateExpansion.Hit(
				"law:901", 1, "BM25_TITLE", false, "BM25_TITLE_SEED", 2, 9.0, 1
			)),
			DocumentCandidateExpansion.Status.BM25_TITLE_APPLIED,
			List.of()
		);
		try (
			Harness baseline = Harness.mocked(properties(false, false), true, true, disabled(), false);
			Harness shadow = Harness.bm25TitleFallback(seeded, true)
		) {
			LawAiDebugResponse baselineResult = baseline.debug("정보화사업 사전협의는 언제 해야 해?");
			LawAiDebugResponse result = shadow.debug("정보화사업 사전협의는 언제 해야 해?");

			assertThat(result.documentExpansionStatus()).isEqualTo("BM25_TITLE_APPLIED");
			assertThat(ids(result.documentExpansionHits())).containsExactly(901L);
			assertThat(ids(result.merged())).containsExactlyElementsOf(ids(baselineResult.merged()));
			assertThat(ids(result.selected())).containsExactlyElementsOf(ids(baselineResult.selected()));
			assertThat(shadow.bm25TitleSelectorRequestCount()).isEqualTo(1);
			assertThat(shadow.bm25SeededSearchRequestCount()).isEqualTo(1);
			assertThat(shadow.embeddingRequestCount()).isEqualTo(1);
			assertThat(shadow.qdrantSearchRequestCount()).isEqualTo(1);
			assertThat(shadow.answerRequestCount()).isZero();

			LawAiDebugResponse.Item hit = result.documentExpansionHits().get(0);
			assertThat(hit.documentExpansionAnchorType()).isEqualTo("BM25_TITLE");
			assertThat(hit.documentExpansionReason()).isEqualTo("BM25_TITLE_SEED");
			assertThat(hit.documentExpansionSeedTermCount()).isEqualTo(2);
			assertThat(hit.documentExpansionSeedBm25Score()).isEqualTo(9.0);
			assertThat(hit.documentExpansionSeedBm25Rank()).isEqualTo(1);
		}
	}

	@Test
	void strongAnchorAppliedPreventsBm25TitleFallback() {
		DocumentCandidateExpansion.Result strong = appliedExpansion(
			List.of(chunk(901, 90, 1, "strong anchor sibling")),
			List.of(new DocumentCandidateExpansion.Hit(
				"law:901", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
			))
		);
		try (Harness harness = Harness.strongAnchorWithBm25Available(strong)) {
			LawAiDebugResponse result = harness.debug(QUESTION);

			assertThat(result.documentExpansionStatus()).isEqualTo("APPLIED");
			assertThat(ids(result.documentExpansionHits())).containsExactly(901L);
			assertThat(harness.bm25TitleSelectorRequestCount()).isZero();
			assertThat(harness.bm25SeededSearchRequestCount()).isZero();
			assertThat(result.documentExpansionHits().get(0).documentExpansionSeedTermCount()).isNull();
			assertThat(result.documentExpansionHits().get(0).documentExpansionSeedBm25Score()).isNull();
			assertThat(result.documentExpansionHits().get(0).documentExpansionSeedBm25Rank()).isNull();
		}
	}

	@Test
	void computesExpansionInParallelWithoutChangingAnyShadowControlOrderOrExternalRequestCount() throws Exception {
		DocumentCandidateExpansion.Result expansion = appliedExpansion(
			List.of(
				chunk(901, 90, 1, SECRET_TEXT),
				chunk(902, 91, 2, "전자정부법의 다른 조문")
			),
			List.of(
				new DocumentCandidateExpansion.Hit("law:901", 1, "EXPLICIT_TITLE", true, "EVIDENCE_TERMS"),
				new DocumentCandidateExpansion.Hit("law:902", 2, "EXPLICIT_TITLE", true, "DOCUMENT_ORDER")
			),
			List.of(RetrievalCandidateTrace.DOCUMENT_LIMIT, RetrievalCandidateTrace.DOCUMENT_CHUNK_LIMIT)
		);
		try (
			Harness baseline = Harness.mocked(properties(false, false), true, true, disabled(), false);
			Harness shadow = Harness.mocked(properties(true, false), true, true, expansion, true)
		) {
			LawAiDebugResponse baselineResult = baseline.debug(QUESTION);
			LawAiDebugResponse result = shadow.debug(QUESTION);

			assertThat(ids(result.vectorHits())).containsExactlyElementsOf(ids(baselineResult.vectorHits()));
			assertThat(ids(result.lexicalHits())).containsExactlyElementsOf(ids(baselineResult.lexicalHits()));
			assertThat(ids(result.bm25Hits())).containsExactlyElementsOf(ids(baselineResult.bm25Hits()));
			assertThat(ids(result.fused())).containsExactlyElementsOf(ids(baselineResult.fused()));
			assertThat(ids(result.coverageFused())).containsExactlyElementsOf(ids(baselineResult.coverageFused()));
			assertThat(ids(result.merged())).containsExactlyElementsOf(ids(baselineResult.merged()));
			assertThat(ids(result.reranked())).containsExactlyElementsOf(ids(baselineResult.reranked()));
			assertThat(ids(result.intentFiltered())).containsExactlyElementsOf(ids(baselineResult.intentFiltered()));
			assertThat(ids(result.judgeCandidates())).containsExactlyElementsOf(ids(baselineResult.judgeCandidates()));
			assertThat(ids(result.judged())).containsExactlyElementsOf(ids(baselineResult.judged()));
			assertThat(ids(result.selected())).containsExactlyElementsOf(ids(baselineResult.selected()));
			assertThat(result.fused()).extracting(LawAiDebugResponse.Item::rrfScore)
				.containsExactlyElementsOf(baselineResult.fused().stream().map(LawAiDebugResponse.Item::rrfScore).toList());

			assertThat(ids(result.merged())).containsExactly(101L, 201L, 301L);
			assertThat(ids(result.documentExpansionHits())).containsExactly(901L, 902L);
			assertThat(ids(result.documentExpansionFused())).containsExactly(101L, 201L, 901L, 902L);
			assertThat(result.documentExpansionStatus()).isEqualTo(DocumentCandidateExpansion.Status.APPLIED.name());
			assertThat(result.documentExpansionReasonCodes()).containsExactly(
				RetrievalCandidateTrace.DOCUMENT_LIMIT,
				RetrievalCandidateTrace.DOCUMENT_CHUNK_LIMIT
			);
			assertThat(baseline.embeddingRequestCount()).isEqualTo(1);
			assertThat(shadow.embeddingRequestCount()).isEqualTo(1);
			assertThat(baseline.qdrantSearchRequestCount()).isEqualTo(1);
			assertThat(shadow.qdrantSearchRequestCount()).isEqualTo(1);
			assertThat(shadow.expansionInputCandidateKeys()).isEmpty();
			assertThat(shadow.embeddingObservedExpansionStart()).isTrue();

			LawAiDebugResponse.Item firstExpansion = result.documentExpansionHits().get(0);
			assertThat(firstExpansion.documentExpansionRank()).isEqualTo(1);
			assertThat(firstExpansion.documentExpansionAnchorType()).isEqualTo("EXPLICIT_TITLE");
			assertThat(firstExpansion.documentExpansionReason()).isEqualTo("EVIDENCE_TERMS");
			assertThat(firstExpansion.documentExpansionOverlap()).isFalse();
			assertThat(List.of(
				String.valueOf(firstExpansion.documentExpansionRank()),
				firstExpansion.documentExpansionAnchorType(),
				firstExpansion.documentExpansionReason(),
				String.valueOf(firstExpansion.documentExpansionOverlap())
			).toString()).doesNotContain(SECRET_TEXT);
			assertThat(firstExpansion.snippet()).hasSizeLessThanOrEqualTo(360);

			RetrievalCandidateTrace trace = result.candidateTraces().stream()
				.filter(candidate -> candidate.candidateKey().equals("law:901"))
				.findFirst()
				.orElseThrow();
			assertThat(trace.sourceRanks()).containsEntry(RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE, 1);
			assertThat(trace.enteredStages()).contains(RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE);
			assertThat(trace.reasonCodes()).contains(
				RetrievalCandidateTrace.DOCUMENT_LIMIT,
				RetrievalCandidateTrace.DOCUMENT_CHUNK_LIMIT
			);
			assertThat(stage(result, RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE).description())
				.contains(RetrievalCandidateTrace.DOCUMENT_LIMIT, RetrievalCandidateTrace.DOCUMENT_CHUNK_LIMIT);
		}
	}

	@Test
	void reannotatesEveryImmutableExpansionHitAgainstTheFinalControlSourceUnion() {
		List<LawSemanticChunkRow> chunks = List.of(
			chunk(101, 10, 1, "vector overlap"),
			chunk(201, 20, 2, "BM25 overlap"),
			chunk(301, 30, 3, "lexical overlap"),
			chunk(901, 30, 4, "new expansion")
		);
		List<DocumentCandidateExpansion.Hit> deliberatelyWrongFlags = List.of(
			new DocumentCandidateExpansion.Hit("law:101", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"),
			new DocumentCandidateExpansion.Hit("law:201", 2, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"),
			new DocumentCandidateExpansion.Hit("law:301", 3, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"),
			new DocumentCandidateExpansion.Hit("law:901", 4, "EXPLICIT_TITLE", true, "DOCUMENT_ORDER")
		);
		try (Harness harness = Harness.mocked(
			properties(true, false), false, false, appliedExpansion(chunks, deliberatelyWrongFlags), false
		)) {
			LawAiDebugResponse result = harness.debug(QUESTION);
			Set<String> finalSourceUnion = new LinkedHashSet<>();
			result.vectorHits().forEach(item -> finalSourceUnion.add(key(item)));
			result.lexicalHits().forEach(item -> finalSourceUnion.add(key(item)));
			result.bm25Hits().forEach(item -> finalSourceUnion.add(key(item)));

			assertThat(result.documentExpansionHits()).hasSize(4);
			for (LawAiDebugResponse.Item item : result.documentExpansionHits()) {
				assertThat(item.documentExpansionOverlap())
					.as("final-union overlap for %s", key(item))
					.isEqualTo(finalSourceUnion.contains(key(item)));
			}
			assertThat(result.documentExpansionHits())
				.extracting(LawAiDebugResponse.Item::documentExpansionOverlap)
				.containsExactly(true, true, true, false);
		}
	}

	@Test
	void shadowFusionRetainsRawVectorAndBm25ContributionsBeyondThePureRrfLimit() {
		List<QdrantSearchHit> vectorHits = List.of(
			new QdrantSearchHit("law", 101, 0.93),
			new QdrantSearchHit("law", 201, 0.92),
			new QdrantSearchHit("law", 901, 0.91)
		);
		List<LexicalSearchHit> bm25Hits = List.of(
			new LexicalSearchHit("law", 101, 10, 9.0, 1, List.of("전자정부법")),
			new LexicalSearchHit("law", 201, 20, 8.0, 2, List.of("전자정부법")),
			new LexicalSearchHit("law", 901, 90, 7.0, 3, List.of("전자정부법"))
		);
		DocumentCandidateExpansion.Result expansion = appliedExpansion(
			List.of(chunk(901, 90, 1, "raw-source contribution candidate")),
			List.of(new DocumentCandidateExpansion.Hit(
				"law:901", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
			))
		);
		try (Harness harness = Harness.mockedWithSources(
			properties(true, false), expansion, vectorHits, bm25Hits, 2
		)) {
			LawAiDebugResponse result = harness.debug(QUESTION);

			assertThat(ids(result.fused())).containsExactly(101L, 201L);
			assertThat(ids(result.documentExpansionFused())).containsExactly(901L, 101L);
			LawAiDebugResponse.Item candidate = result.documentExpansionFused().get(0);
			assertThat(candidate.vectorRank()).isEqualTo(3);
			assertThat(candidate.lexicalRank()).isEqualTo(3);
			assertThat(candidate.documentExpansionRank()).isEqualTo(1);
			assertThat(candidate.rrfScore()).isCloseTo(0.0481394743689826, within(1.0e-12));
		}
	}

	@Test
	void genericQuestionPerformsNoDocumentExpansionDatabaseQuery() {
		try (Harness harness = Harness.real(properties(true, false), false, false)) {
			LawAiDebugResponse result = harness.debug("사전협의는 언제 하나요?");

			assertThat(stage(result, RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE).description())
				.contains(
					DocumentCandidateExpansion.Status.NO_STRONG_ANCHOR.name(),
					RetrievalCandidateTrace.DOCUMENT_NOT_ANCHORED
				);
			assertThat(result.documentExpansionHits()).isEmpty();
			assertThat(result.documentExpansionStatus())
				.isEqualTo(DocumentCandidateExpansion.Status.NO_STRONG_ANCHOR.name());
			assertThat(result.documentExpansionReasonCodes())
				.containsExactly(RetrievalCandidateTrace.DOCUMENT_NOT_ANCHORED);
			assertThat(result.documentExpansionFused()).extracting(LawAiDebugResponse.Item::chunkId)
				.containsExactly(101L, 201L);
			verify(harness.lawMapper(), never()).findDocumentExpansionDocuments(
				anyList(), anyList(), anyList(), anyBoolean(), anyInt()
			);
			verify(harness.lawMapper(), never()).findDocumentExpansionChunks(
				anyList(), anyList(), anyList(), anyList(), anyBoolean(), anyInt(), anyInt()
			);
		}
	}

	@Test
	void databaseFallbackReturnsTheBaselineUnchanged() {
		DocumentCandidateExpansion.Result fallback = new DocumentCandidateExpansion.Result(
			List.of(), List.of(), DocumentCandidateExpansion.Status.DB_FALLBACK_BASELINE,
			List.of("DOCUMENT_EXPANSION_DB_FAILURE")
		);
		assertFailClosedBaseline(fallback, DocumentCandidateExpansion.Status.DB_FALLBACK_BASELINE);
	}

	@Test
	void timeoutReturnsTheBaselineUnchanged() {
		CountDownLatch neverCompletes = new CountDownLatch(1);
		Supplier<DocumentCandidateExpansion.Result> blocked = () -> {
			try {
				neverCompletes.await();
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
			return appliedExpansion(
				List.of(chunk(901, 90, 1, "late result")),
				List.of(new DocumentCandidateExpansion.Hit(
					"law:901", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
				))
			);
		};
		try (Harness harness = Harness.mocked(properties(true, false), false, false, blocked, false)) {
			LawAiDebugResponse result = harness.debug(QUESTION);

			assertThat(ids(result.merged())).containsExactly(301L, 101L);
			assertThat(result.documentExpansionHits()).isEmpty();
			assertThat(result.documentExpansionFused()).extracting(LawAiDebugResponse.Item::chunkId)
				.containsExactly(101L, 201L);
			assertThat(stage(result, RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE).description())
				.contains(DocumentCandidateExpansion.Status.FALLBACK_BASELINE.name());
			assertThat(harness.embeddingRequestCount()).isEqualTo(1);
			assertThat(harness.qdrantSearchRequestCount()).isEqualTo(1);
		}
	}

	@Test
	void malformedExpansionIdentityReturnsTheBaselineUnchanged() {
		DocumentCandidateExpansion.Result malformed = appliedExpansion(
			List.of(chunk(901, 0, 1, "invalid document identity")),
			List.of(new DocumentCandidateExpansion.Hit(
				"law:901", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
			))
		);
		assertFailClosedBaseline(malformed, DocumentCandidateExpansion.Status.FALLBACK_BASELINE);
	}

	@Test
	void duplicateExpansionIdentityReturnsTheBaselineUnchanged() {
		LawSemanticChunkRow duplicate = chunk(901, 90, 1, "duplicate identity");
		DocumentCandidateExpansion.Result malformed = appliedExpansion(
			List.of(duplicate, duplicate),
			List.of(
				new DocumentCandidateExpansion.Hit(
					"law:901", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
				),
				new DocumentCandidateExpansion.Hit(
					"law:901", 2, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
				)
			)
		);
		assertFailClosedBaseline(malformed, DocumentCandidateExpansion.Status.FALLBACK_BASELINE);
	}

	@Test
	void ambiguousDocumentMatchPublishesItsBoundedReasonAndLeavesTheBaselineUnchanged() {
		DocumentCandidateExpansion.Result ambiguous = new DocumentCandidateExpansion.Result(
			List.of(),
			List.of(),
			DocumentCandidateExpansion.Status.DOCUMENT_MATCH_AMBIGUOUS,
			List.of(RetrievalCandidateTrace.DOCUMENT_MATCH_AMBIGUOUS)
		);
		try (Harness harness = Harness.mocked(properties(true, false), false, false, ambiguous, false)) {
			LawAiDebugResponse result = harness.debug(QUESTION);

			assertThat(ids(result.merged())).containsExactly(301L, 101L);
			assertThat(result.documentExpansionHits()).isEmpty();
			assertThat(stage(result, RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE).description())
				.contains(
					DocumentCandidateExpansion.Status.DOCUMENT_MATCH_AMBIGUOUS.name(),
					RetrievalCandidateTrace.DOCUMENT_MATCH_AMBIGUOUS
				);
		}
	}

	@Test
	void expansionThatExceedsThePerDocumentBoundFailsClosed() {
		List<LawSemanticChunkRow> chunks = new ArrayList<>();
		List<DocumentCandidateExpansion.Hit> hits = new ArrayList<>();
		for (int index = 0; index < 9; index++) {
			long chunkId = 901L + index;
			chunks.add(chunk(chunkId, 90, index + 1, "invalid per-document bound " + index));
			hits.add(new DocumentCandidateExpansion.Hit(
				"law:" + chunkId, index + 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
			));
		}
		assertFailClosedBaseline(
			appliedExpansion(chunks, hits),
			DocumentCandidateExpansion.Status.FALLBACK_BASELINE
		);
	}

	@Test
	void expansionThatExceedsTheTotalBoundFailsClosed() {
		List<LawSemanticChunkRow> chunks = new ArrayList<>();
		List<DocumentCandidateExpansion.Hit> hits = new ArrayList<>();
		for (int index = 0; index < 25; index++) {
			long chunkId = 901L + index;
			chunks.add(chunk(chunkId, 90 + (index / 8), index + 1, "bounded expansion " + index));
			hits.add(new DocumentCandidateExpansion.Hit(
				"law:" + chunkId, index + 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
			));
		}
		assertFailClosedBaseline(
			appliedExpansion(chunks, hits),
			DocumentCandidateExpansion.Status.FALLBACK_BASELINE
		);
	}

	@Test
	void expansionThatExceedsTheDocumentBoundFailsClosed() {
		List<LawSemanticChunkRow> chunks = new ArrayList<>();
		List<DocumentCandidateExpansion.Hit> hits = new ArrayList<>();
		for (int index = 0; index < 4; index++) {
			long chunkId = 901L + index;
			chunks.add(chunk(chunkId, 90 + index, index + 1, "invalid document bound " + index));
			hits.add(new DocumentCandidateExpansion.Hit(
				"law:" + chunkId, index + 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"
			));
		}
		assertFailClosedBaseline(
			appliedExpansion(chunks, hits),
			DocumentCandidateExpansion.Status.FALLBACK_BASELINE
		);
	}

	@ParameterizedTest(name = "documentAuthority={0}, rrfAuthority={1}, semanticAuthority={2}")
	@CsvSource({
		"false, true,  true,  '101,201,301'",
		"true,  false, true,  '301,101'",
		"true,  true,  false, '101,201,301'",
		"true,  true,  true,  '101,201,901,902,301'"
	})
	void expansionEntersTheAnswerCandidatePathOnlyWhenEveryAuthorityPrerequisiteIsTrue(
		boolean documentAuthority,
		boolean rrfAuthority,
		boolean semanticAuthority,
		String expectedIds
	) {
		DocumentCandidateExpansion.Result expansion = appliedExpansion(
			List.of(
				chunk(901, 90, 1, "expanded first"),
				chunk(902, 91, 2, "expanded second")
			),
			List.of(
				new DocumentCandidateExpansion.Hit("law:901", 1, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER"),
				new DocumentCandidateExpansion.Hit("law:902", 2, "EXPLICIT_TITLE", false, "DOCUMENT_ORDER")
			)
		);
		try (Harness harness = Harness.mocked(
			properties(true, documentAuthority), rrfAuthority, semanticAuthority, expansion, false
		)) {
			LawAiDebugResponse result = harness.debug(QUESTION);
			List<Long> expected = java.util.Arrays.stream(expectedIds.split(","))
				.map(String::trim)
				.map(Long::valueOf)
				.toList();

			assertThat(ids(result.merged())).containsExactlyElementsOf(expected);
			assertThat(harness.embeddingRequestCount()).isEqualTo(1);
			assertThat(harness.qdrantSearchRequestCount()).isEqualTo(1);
		}
	}

	private void assertFailClosedBaseline(
		DocumentCandidateExpansion.Result expansion,
		DocumentCandidateExpansion.Status status
	) {
		try (Harness harness = Harness.mocked(properties(true, false), false, false, expansion, false)) {
			LawAiDebugResponse result = harness.debug(QUESTION);

			assertThat(ids(result.merged())).containsExactly(301L, 101L);
			assertThat(result.documentExpansionHits()).isEmpty();
			assertThat(result.documentExpansionFused()).extracting(LawAiDebugResponse.Item::chunkId)
				.containsExactly(101L, 201L);
			assertThat(stage(result, RetrievalCandidateTrace.DOCUMENT_EXPANSION_STAGE).description())
				.contains(status.name());
			assertThat(harness.embeddingRequestCount()).isEqualTo(1);
			assertThat(harness.qdrantSearchRequestCount()).isEqualTo(1);
		}
	}

	private static DocumentCandidateExpansion.Result appliedExpansion(
		List<LawSemanticChunkRow> chunks,
		List<DocumentCandidateExpansion.Hit> hits
	) {
		return appliedExpansion(chunks, hits, List.of());
	}

	private static DocumentCandidateExpansion.Result appliedExpansion(
		List<LawSemanticChunkRow> chunks,
		List<DocumentCandidateExpansion.Hit> hits,
		List<String> reasonCodes
	) {
		return new DocumentCandidateExpansion.Result(
			chunks, hits, DocumentCandidateExpansion.Status.APPLIED, reasonCodes
		);
	}

	private static DocumentCandidateExpansion.Result disabled() {
		return new DocumentCandidateExpansion.Result(
			List.of(), List.of(), DocumentCandidateExpansion.Status.DISABLED, List.of()
		);
	}

	private static DocumentCandidateExpansion.Result noStrongAnchor() {
		return new DocumentCandidateExpansion.Result(
			List.of(), List.of(), DocumentCandidateExpansion.Status.NO_STRONG_ANCHOR,
			List.of(RetrievalCandidateTrace.DOCUMENT_NOT_ANCHORED)
		);
	}

	private static LawAiDocumentExpansionProperties properties(boolean enabled, boolean authoritative) {
		return new LawAiDocumentExpansionProperties(enabled, authoritative, enabled ? 3 : 0, enabled ? 8 : 0, enabled ? 24 : 0);
	}

	private static LawAiDocumentExpansionProperties bm25TitleProperties(boolean authoritative) {
		return new LawAiDocumentExpansionProperties(true, authoritative, 3, 8, 24, true, 100, 2, 0.05);
	}

	private static List<Long> ids(List<LawAiDebugResponse.Item> items) {
		return items.stream().map(LawAiDebugResponse.Item::chunkId).toList();
	}

	private static String key(LawAiDebugResponse.Item item) {
		return item.target() + ':' + item.chunkId();
	}

	private static LawAiDebugResponse.Stage stage(LawAiDebugResponse response, String name) {
		return response.stages().stream().filter(stage -> stage.name().equals(name)).findFirst().orElseThrow();
	}

	private static LawSemanticChunkRow chunk(long chunkId, long documentId, int sortOrder, String text) {
		return new LawSemanticChunkRow(
			chunkId,
			documentId,
			"law",
			"external-" + chunkId,
			"전자정부법",
			"행정안전부",
			"법률",
			"2026-01-01",
			"CURRENT",
			"제" + sortOrder + "조",
			"정의와 목적",
			text,
			1,
			"law/" + chunkId,
			"https://example.invalid/" + chunkId,
			sortOrder,
			"hash-" + chunkId,
			"총칙",
			"ARTICLE"
		);
	}

	private static final class Harness implements AutoCloseable {
		private final LawChunkMapper lawMapper;
		private final OpenAiEmbeddingClient embeddingClient;
		private final OpenAiAnswerClient answerClient;
		private final QdrantClient qdrantClient;
		private final AtomicInteger embeddingRequests = new AtomicInteger();
		private final AtomicInteger answerRequests = new AtomicInteger();
		private final AtomicInteger qdrantSearchRequests = new AtomicInteger();
		private final AtomicInteger bm25TitleSelectorRequests = new AtomicInteger();
		private final AtomicInteger bm25SeededSearchRequests = new AtomicInteger();
		private final AtomicReference<Set<String>> expansionInputCandidateKeys = new AtomicReference<>(Set.of());
		private final CountDownLatch expansionStarted = new CountDownLatch(1);
		private final AtomicBoolean embeddingObservedExpansionStart = new AtomicBoolean();
		private final LawAiAnswerService service;

		private static Harness mocked(
			LawAiDocumentExpansionProperties properties,
			boolean rrfAuthority,
			boolean semanticAuthority,
			DocumentCandidateExpansion.Result result,
			boolean requireParallelStart
		) {
			return mocked(properties, rrfAuthority, semanticAuthority, () -> result, requireParallelStart);
		}

		private static Harness mocked(
			LawAiDocumentExpansionProperties properties,
			boolean rrfAuthority,
			boolean semanticAuthority,
			Supplier<DocumentCandidateExpansion.Result> result,
			boolean requireParallelStart
		) {
			return new Harness(
				properties, rrfAuthority, semanticAuthority, result, requireParallelStart, false,
				VECTOR_HITS, BM25_HITS, 100
			);
		}

		private static Harness mockedWithSources(
			LawAiDocumentExpansionProperties properties,
			DocumentCandidateExpansion.Result result,
			List<QdrantSearchHit> vectorHits,
			List<LexicalSearchHit> bm25Hits,
			int rrfFusedLimit
		) {
			return new Harness(
				properties, false, false, () -> result, false, false,
				vectorHits, bm25Hits, rrfFusedLimit
			);
		}

		private static Harness real(
			LawAiDocumentExpansionProperties properties,
			boolean rrfAuthority,
			boolean semanticAuthority
		) {
			return new Harness(
				properties,
				rrfAuthority,
				semanticAuthority,
				LawAiAnswerServiceDocumentExpansionTests::disabled,
				false,
				true,
				VECTOR_HITS,
				BM25_HITS,
				100
			);
		}

		private static Harness bm25TitleFallback(
			DocumentCandidateExpansion.Result seededResult,
			boolean allAuthorityFlagsEnabled
		) {
			return new Harness(
				bm25TitleProperties(allAuthorityFlagsEnabled),
				allAuthorityFlagsEnabled,
				allAuthorityFlagsEnabled,
				LawAiAnswerServiceDocumentExpansionTests::noStrongAnchor,
				false,
				false,
				VECTOR_HITS,
				BM25_HITS,
				100,
				appliedSeedSelection(),
				() -> seededResult
			);
		}

		private static Harness strongAnchorWithBm25Available(DocumentCandidateExpansion.Result strongResult) {
			return new Harness(
				bm25TitleProperties(false), false, false, () -> strongResult, false, false,
				VECTOR_HITS, BM25_HITS, 100, appliedSeedSelection(), LawAiAnswerServiceDocumentExpansionTests::noStrongAnchor
			);
		}

		private static Bm25TitleDocumentSeedSelector.Selection appliedSeedSelection() {
			return new Bm25TitleDocumentSeedSelector.Selection(
				Bm25TitleDocumentSeedSelector.Status.APPLIED,
				List.of(new DocumentExpansionSeed(
					"law", 20, "정보화사업 사전협의 지침", List.of("정보화사업", "사전협의"),
					9.0, 1, "BM25_TITLE", "BM25_TITLE_SEED"
				))
			);
		}

		@SuppressWarnings("unchecked")
		private Harness(
			LawAiDocumentExpansionProperties documentExpansionProperties,
			boolean rrfAuthority,
			boolean semanticAuthority,
			Supplier<DocumentCandidateExpansion.Result> expansionResult,
			boolean requireParallelStart,
			boolean realExpansionService,
			List<QdrantSearchHit> vectorHits,
			List<LexicalSearchHit> bm25Hits,
			int rrfFusedLimit
		) {
			this(
				documentExpansionProperties, rrfAuthority, semanticAuthority, expansionResult,
				requireParallelStart, realExpansionService, vectorHits, bm25Hits, rrfFusedLimit,
				new Bm25TitleDocumentSeedSelector.Selection(Bm25TitleDocumentSeedSelector.Status.NO_MATCH, List.of()),
				LawAiAnswerServiceDocumentExpansionTests::noStrongAnchor
			);
		}

		@SuppressWarnings("unchecked")
		private Harness(
			LawAiDocumentExpansionProperties documentExpansionProperties,
			boolean rrfAuthority,
			boolean semanticAuthority,
			Supplier<DocumentCandidateExpansion.Result> expansionResult,
			boolean requireParallelStart,
			boolean realExpansionService,
			List<QdrantSearchHit> vectorHits,
			List<LexicalSearchHit> bm25Hits,
			int rrfFusedLimit,
			Bm25TitleDocumentSeedSelector.Selection seedSelection,
			Supplier<DocumentCandidateExpansion.Result> seededExpansionResult
		) {
			lawMapper = mock(LawChunkMapper.class, invocation -> {
				String method = invocation.getMethod().getName();
				if ("findSemanticChunksByIds".equals(method)) {
					List<Long> requestedIds = invocation.getArgument(0);
					return List.of(VECTOR_CHUNK, BM25_CHUNK).stream()
						.filter(chunk -> requestedIds.contains(chunk.chunkId()))
						.toList();
				}
				if ("findSemanticContextChunks".equals(method)) {
					return List.of();
				}
				if (method.startsWith("findSemanticChunks")) {
					return List.of(LEXICAL_CHUNK);
				}
				if (List.class.isAssignableFrom(invocation.getMethod().getReturnType())) {
					return List.of();
				}
				if (invocation.getMethod().getReturnType() == int.class) {
					return 0;
				}
				if (invocation.getMethod().getReturnType() == long.class) {
					return 0L;
				}
				if (invocation.getMethod().getReturnType() == boolean.class) {
					return false;
				}
				return null;
			});
			embeddingClient = mock(OpenAiEmbeddingClient.class);
			when(embeddingClient.embed(anyList())).thenAnswer(invocation -> {
				embeddingRequests.incrementAndGet();
				if (requireParallelStart) {
					embeddingObservedExpansionStart.set(expansionStarted.await(2, TimeUnit.SECONDS));
				}
				return List.of(List.of(0.1, 0.2));
			});
			answerClient = mock(OpenAiAnswerClient.class, invocation -> {
				answerRequests.incrementAndGet();
				return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
			});
			qdrantClient = mock(QdrantClient.class);
			when(qdrantClient.searchBalanced(anyList(), anyList(), anyInt(), anyInt())).thenAnswer(invocation -> {
				qdrantSearchRequests.incrementAndGet();
				return vectorHits;
			});
			KoreanBm25SearchService bm25 = mock(KoreanBm25SearchService.class);
			when(bm25.search(anyString(), anyList(), anyList(), anyInt())).thenReturn(bm25Hits);
			DocumentExpansionSearchService expansionService;
			if (realExpansionService) {
				expansionService = new DocumentExpansionSearchService(
					lawMapper,
					null,
					new DocumentCandidateExpansion(),
					documentExpansionProperties
				);
			} else {
				expansionService = mock(DocumentExpansionSearchService.class);
				when(expansionService.search(
					any(DocumentSearchAnchor.class), anyList(), anyBoolean(), anySet()
				)).thenAnswer(invocation -> {
					expansionInputCandidateKeys.set(Set.copyOf(invocation.getArgument(3)));
					expansionStarted.countDown();
					return expansionResult.get();
				});
				when(expansionService.searchBm25Seeded(
					any(DocumentSearchAnchor.class), anyList(), anyBoolean(), anySet()
				)).thenAnswer(invocation -> {
					bm25SeededSearchRequests.incrementAndGet();
					return seededExpansionResult.get();
				});
			}
			Bm25TitleDocumentSeedSelector seedSelector = mock(Bm25TitleDocumentSeedSelector.class);
			when(seedSelector.select(anyList(), anyList(), anyList(), anyList(), any()))
				.thenAnswer(invocation -> {
					bm25TitleSelectorRequests.incrementAndGet();
					return seedSelection;
				});
			EvidenceJudge judge = mock(EvidenceJudge.class);
			when(judge.judge(anyString(), anyList(), anyMap(), anyInt())).thenAnswer(invocation -> {
				List<LawSemanticChunkRow> candidates = invocation.getArgument(1);
				Map<String, Double> scores = candidates.stream().collect(java.util.stream.Collectors.toMap(
					chunk -> chunk.target() + ':' + chunk.chunkId(),
					chunk -> 1.0,
					(left, right) -> left
				));
				return new EvidenceJudge.Result(
					candidates, scores, false, false, false, false,
					candidates.size(), candidates.size(), 0, "test_direct"
				);
			});
			LawAiProperties properties = new LawAiProperties(
				new LawAiProperties.OpenAi("test", "embedding", "answer", "low", "low", 700),
				new LawAiProperties.Qdrant("http://127.0.0.1:6333", "law", "rag", 2),
				null,
				null
			);
			service = new LawAiAnswerService(
				lawMapper,
				null,
				embeddingClient,
				qdrantClient,
				answerClient,
				judge,
				new AnswerGuard(),
				new ClaimVerifier(),
				new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier()),
				new ParentContextAssembler(),
				new EvidenceCandidateDiversifier(),
				new FailureLoggingService(null),
				null,
				properties,
				null,
				null,
				null,
				bm25,
				new ReciprocalRankFusion(),
				new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100),
				new LawAiRrfProperties(true, rrfAuthority, 60, 1.0, 1.0, rrfFusedLimit),
				new LawAiSemanticSelectionProperties(true, semanticAuthority, 4),
				new CoverageAwareFusion(),
				new LawAiCoverageAwareProperties(false, 0, 1, 30),
				expansionService,
				documentExpansionProperties,
				seedSelector
			);
		}

		private LawAiDebugResponse debug(String question) {
			return service.debug(new LawAiDebugRequest(
				"law", List.of("law"), question, 8, true, List.of()
			));
		}

		private int embeddingRequestCount() {
			return embeddingRequests.get();
		}

		private int qdrantSearchRequestCount() {
			return qdrantSearchRequests.get();
		}

		private int answerRequestCount() {
			return answerRequests.get();
		}

		private int bm25TitleSelectorRequestCount() {
			return bm25TitleSelectorRequests.get();
		}

		private int bm25SeededSearchRequestCount() {
			return bm25SeededSearchRequests.get();
		}

		private Set<String> expansionInputCandidateKeys() {
			return expansionInputCandidateKeys.get();
		}

		private boolean embeddingObservedExpansionStart() {
			return embeddingObservedExpansionStart.get();
		}

		private LawChunkMapper lawMapper() {
			return lawMapper;
		}

		@Override
		public void close() {
			service.shutdownExecutors();
		}
	}
}
