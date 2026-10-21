package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticVectorSearchServiceTests {

	private final SemanticVectorSearchService service = new SemanticVectorSearchService(null);

	@Test
	void routedParallelSearchKeepsCapturedGenerationAndPropagatesUnavailableAssets() {
		var client = mock(QdrantClient.class);
		var routed = new SemanticVectorSearchService(client);
		var snapshot = new RecoveryRoutingSnapshot(0, Map.of(), Map.of(), Set.of(), null, null);
		var targets = List.of("official_doc");
		when(client.searchBalanced(List.of(1.0), targets, 2, 4, snapshot))
			.thenReturn(List.of(new QdrantSearchHit("official_doc", 101, .7)));
		when(client.searchBalanced(List.of(2.0), targets, 2, 4, snapshot))
			.thenReturn(List.of(new QdrantSearchHit("official_doc", 101, .9), new QdrantSearchHit("official_doc", 102, .8)));
		assertThat(routed.search(List.of(List.of(1.0), List.of(2.0)), targets, 2, Runnable::run, snapshot))
			.containsExactly(new QdrantSearchHit("official_doc", 101, .9), new QdrantSearchHit("official_doc", 102, .8));
		when(client.searchBalanced(List.of(2.0), targets, 2, 4, snapshot))
			.thenThrow(new IllegalStateException("active assets unavailable"));
		assertThatThrownBy(() -> routed.search(List.of(List.of(1.0), List.of(2.0)), targets, 2, Runnable::run, snapshot))
			.isInstanceOf(IllegalStateException.class).hasMessage("active assets unavailable");
		assertThatThrownBy(() -> routed.search(List.of(), targets, 2, Runnable::run, null))
			.isInstanceOf(IllegalArgumentException.class);
	}

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
