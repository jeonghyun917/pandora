package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticVectorSearchServiceTests {

	private final SemanticVectorSearchService service = new SemanticVectorSearchService(null);

	@Test
	void mergeKeepsHighestScorePerChunkAndSortsDescending() {
		List<QdrantSearchHit> merged = service.merge(List.of(
			new QdrantSearchHit("law", 10, 0.51),
			new QdrantSearchHit("official_doc", 20, 0.82),
			new QdrantSearchHit("law", 10, 0.91),
			new QdrantSearchHit("law", 30, 0.73)
		), 2);

		assertThat(merged).containsExactly(
			new QdrantSearchHit("law", 10, 0.91),
			new QdrantSearchHit("official_doc", 20, 0.82)
		);
	}

	@Test
	void mergeBreaksEqualScoreTiesByStableCandidateIdentity() {
		List<QdrantSearchHit> first = service.merge(List.of(
			new QdrantSearchHit("official_doc", 20, 0.82),
			new QdrantSearchHit("law", 30, 0.82),
			new QdrantSearchHit("law", 10, 0.82)
		), 3);
		List<QdrantSearchHit> reversed = service.merge(List.of(
			new QdrantSearchHit("law", 10, 0.82),
			new QdrantSearchHit("law", 30, 0.82),
			new QdrantSearchHit("official_doc", 20, 0.82)
		), 3);

		assertThat(first).containsExactly(
			new QdrantSearchHit("law", 10, 0.82),
			new QdrantSearchHit("law", 30, 0.82),
			new QdrantSearchHit("official_doc", 20, 0.82)
		);
		assertThat(reversed).containsExactlyElementsOf(first);
	}
}
