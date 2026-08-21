package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiCoverageAwareProperties;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.config.LawAiRrfProperties;
import com.kaces.pandora.semantic.lexical.CoverageAwareFusion;
import com.kaces.pandora.semantic.lexical.ReciprocalRankFusion;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LawAiAnswerServiceCoverageAwareTests {

	private final LawChunkMapper mapper = mock(LawChunkMapper.class);
	private final OpenAiEmbeddingClient embeddings = mock(OpenAiEmbeddingClient.class);
	private final QdrantClient qdrant = mock(QdrantClient.class);
	private LawAiAnswerService service;

	@AfterEach
	void tearDown() {
		if (service != null) {
			service.shutdownExecutors();
		}
	}

	@Test
	void computesCoverageShadowAfterHydrationWithoutChangingControlOrderOrCallingInfrastructure() {
		service = service(new LawAiCoverageAwareProperties(true, 1, 1, 30));
		List<ReciprocalRankFusion.RrfHit> fused = new ArrayList<>();
		Map<String, LawSemanticChunkRow> hydrated = new HashMap<>();
		for (int index = 1; index <= 31; index++) {
			long documentId = index == 1 || index == 31 ? 100 : 1_000 + index;
			Integer vectorRank = index == 31 ? 28 : index;
			Integer lexicalRank = index == 1 ? 1 : null;
			fused.add(new ReciprocalRankFusion.RrfHit(
				"law:" + index, "law", index, 1.0 / index,
				vectorRank, lexicalRank, index == 31 ? 28 : index
			));
			hydrated.put("law:" + index, chunk(index, documentId));
		}

		CoverageAwareFusion.Result coverage = service.coverageAwareRerank(fused, hydrated);
		List<LawSemanticChunkRow> control = List.of(chunk(900, 900));
		List<LawSemanticChunkRow> selected = LawAiAnswerService.selectCandidateOrder(
			control,
			coverage.ranking().stream().map(hit -> hydrated.get(hit.candidateKey())).toList(),
			false
		);

		assertThat(coverage.status()).isEqualTo(CoverageAwareFusion.Status.APPLIED);
		assertThat(coverage.ranking()).extracting(ReciprocalRankFusion.RrfHit::candidateKey)
			.contains("law:31")
			.doesNotContain("law:30");
		assertThat(selected).containsExactlyElementsOf(control);
		verifyNoInteractions(mapper, embeddings, qdrant);
	}

	private LawAiAnswerService service(LawAiCoverageAwareProperties coverage) {
		return new LawAiAnswerService(
			mapper, null, embeddings, qdrant, null, new EvidenceJudge(), new AnswerGuard(),
			new ClaimVerifier(), new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier()),
			new ParentContextAssembler(), new EvidenceCandidateDiversifier(), new FailureLoggingService(null),
			null, new LawAiProperties(null, null, null, null), null, null, null, null,
			new ReciprocalRankFusion(), null,
			new LawAiRrfProperties(true, false, 60, 1.0, 1.0, 100), null,
			new CoverageAwareFusion(), coverage
		);
	}

	private LawSemanticChunkRow chunk(long chunkId, long documentId) {
		return new LawSemanticChunkRow(
			chunkId, documentId, "law", "external-" + chunkId, "title", "agency", "category",
			"2026-01-01", "CURRENT", "제1조", "chunk", "text", 1, "path", "url", 1,
			"hash-" + chunkId, "parent", "ARTICLE"
		);
	}
}
