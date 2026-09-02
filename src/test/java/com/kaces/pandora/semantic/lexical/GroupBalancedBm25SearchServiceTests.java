package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.text.QuestionSearchPlan;
import com.kaces.pandora.semantic.config.LawAiLexicalVariantProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class GroupBalancedBm25SearchServiceTests {

	@Test
	void disabledShadowDoesNotExecuteBm25Search() {
		KoreanBm25SearchService bm25 = mock(KoreanBm25SearchService.class);
		var service = service(bm25, new LawAiLexicalVariantProperties(false, false, 4, 60.0));

		GroupBalancedBm25SearchService.Result result = service.search(
			QuestionSearchPlan.from("공공데이터 미제공 시 제재는?"),
			List.of("law"),
			20
		);

		assertThat(result.status()).isEqualTo(GroupBalancedBm25SearchService.Status.DISABLED);
		assertThat(result.fusedHits()).isEmpty();
		verify(bm25, never()).searchStrict(anyString(), anyList(), anyList(), eq(20));
	}

	@Test
	void executesEachVariantWithAnIndependentBm25BudgetAndFusesResults() {
		KoreanBm25SearchService bm25 = mock(KoreanBm25SearchService.class);
		QuestionSearchPlan plan = QuestionSearchPlan.from(
			"공공기관이 공공데이터를 제공하지 않으면 어떤 불이익이 있어?"
		);
		when(bm25.searchStrict(anyString(), anyList(), eq(List.of("law")), eq(20)))
			.thenAnswer(invocation -> {
				String query = invocation.getArgument(0);
				int rankSeed = plan.bm25Variants().stream()
					.map(QuestionSearchPlan.LexicalVariant::query)
					.toList()
					.indexOf(query) + 1;
				return List.of(new LexicalSearchHit(
					"law", 100L + rankSeed, 200L + rankSeed, 10.0 - rankSeed, 1, List.of(query)
				));
			});
		var service = service(bm25, new LawAiLexicalVariantProperties(true, false, 4, 60.0));

		GroupBalancedBm25SearchService.Result result = service.search(plan, List.of("law"), 20);

		assertThat(result.status()).isEqualTo(GroupBalancedBm25SearchService.Status.APPLIED);
		assertThat(result.variantHashes()).containsExactlyElementsOf(
			plan.bm25Variants().stream().map(QuestionSearchPlan.LexicalVariant::tokenSetHash).toList()
		);
		assertThat(result.variantHitCounts().values()).containsOnly(1);
		assertThat(result.fusedHits()).hasSize(plan.bm25Variants().size());

		ArgumentCaptor<String> queryCaptor = ArgumentCaptor.forClass(String.class);
		verify(bm25, org.mockito.Mockito.times(plan.bm25Variants().size()))
			.searchStrict(queryCaptor.capture(), anyList(), eq(List.of("law")), eq(20));
		assertThat(queryCaptor.getAllValues())
			.containsExactlyElementsOf(plan.bm25Variants().stream().map(QuestionSearchPlan.LexicalVariant::query).toList());
	}

	@Test
	void oneVariantFailureRejectsTheEntireShadowCandidate() {
		KoreanBm25SearchService bm25 = mock(KoreanBm25SearchService.class);
		when(bm25.searchStrict(anyString(), anyList(), anyList(), eq(20)))
			.thenReturn(List.of(new LexicalSearchHit("law", 10, 100, 5.0, 1, List.of("공공데이터"))))
			.thenThrow(new IllegalStateException("database unavailable"));
		var service = service(bm25, new LawAiLexicalVariantProperties(true, false, 4, 60.0));

		GroupBalancedBm25SearchService.Result result = service.search(
			QuestionSearchPlan.from("공공기관이 공공데이터를 제공하지 않으면 어떤 불이익이 있어?"),
			List.of("law"),
			20
		);

		assertThat(result.status()).isEqualTo(GroupBalancedBm25SearchService.Status.FAILED);
		assertThat(result.reasonCodes()).containsExactly("VARIANT_SEARCH_FAILED");
		assertThat(result.fusedHits()).isEmpty();
	}

	@Test
	void invalidBoundsDisableCandidateAndAuthorityDefaultsStayOff() {
		KoreanBm25SearchService bm25 = mock(KoreanBm25SearchService.class);
		var properties = new LawAiLexicalVariantProperties(true, false, 5, 0.0);
		var service = service(bm25, properties);

		GroupBalancedBm25SearchService.Result result = service.search(
			QuestionSearchPlan.from("사전협의 대상은?"),
			List.of("law"),
			20
		);

		assertThat(properties.authoritative()).isFalse();
		assertThat(result.status()).isEqualTo(GroupBalancedBm25SearchService.Status.INVALID_CONFIG);
		assertThat(result.reasonCodes()).containsExactly("INVALID_VARIANT_CONFIG");
		verify(bm25, never()).searchStrict(anyString(), anyList(), anyList(), eq(20));
	}

	private GroupBalancedBm25SearchService service(
		KoreanBm25SearchService bm25,
		LawAiLexicalVariantProperties properties
	) {
		return new GroupBalancedBm25SearchService(bm25, new LexicalVariantFusion(), properties);
	}
}
