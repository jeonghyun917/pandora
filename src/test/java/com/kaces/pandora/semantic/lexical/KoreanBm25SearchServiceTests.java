package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class KoreanBm25SearchServiceTests {

	@Test
	void ranksTheDirectProvisionAboveTitleOnlyAndCommonNoiseMatches() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findReadyRevision()).thenReturn("revision-a");
		when(mapper.findBm25TermMatches(eq("revision-a"), anyList(), eq(List.of("law"))))
			.thenReturn(List.of(
				match("law", 10L, 110L, "검사", 5.0, 2, 100, 100.0, 100),
				match("law", 20L, 120L, "검사", 8.0, 50, 100, 100.0, 100),
				match("law", 30L, 130L, "검사", 10.0, 99, 100, 100.0, 100)
			));
		KoreanBm25SearchService service = service(mapper);

		List<LexicalSearchHit> hits = service.search("검사", List.of("law"), 10);

		assertThat(hits).extracting(LexicalSearchHit::chunkId)
			.containsExactly(10L, 20L, 30L);
		assertThat(hits).extracting(LexicalSearchHit::rank)
			.containsExactly(1, 2, 3);
		assertThat(hits).allSatisfy(hit -> assertThat(hit.matchedTerms()).containsExactly("검사"));
	}

	@Test
	void returnsNoShadowResultsWhenNoReadyRevisionExists() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findReadyRevision()).thenReturn(null);

		assertThat(service(mapper).search("국가계약법", List.of("law"), 10)).isEmpty();
		verify(mapper, never()).findBm25TermMatches(anyString(), anyList(), anyList());
	}

	@Test
	void boundsQueryTermsAndResultCountByConfiguration() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findReadyRevision()).thenReturn("revision-b");
		List<SemanticLexicalMapper.Bm25TermMatchRow> matches = new ArrayList<>();
		for (long chunkId = 1; chunkId <= 120; chunkId++) {
			matches.add(match("law", chunkId, 1_000 + chunkId, "term1", 1.0, 2, 200, 50.0, 50));
		}
		when(mapper.findBm25TermMatches(eq("revision-b"), anyList(), eq(List.of("law"))))
			.thenReturn(matches);
		StringBuilder query = new StringBuilder();
		for (int index = 1; index <= 30; index++) {
			query.append("term").append(index).append(' ');
		}

		List<LexicalSearchHit> hits = service(mapper).search(query.toString(), List.of("law"), 500);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<String>> terms = ArgumentCaptor.forClass(List.class);
		verify(mapper).findBm25TermMatches(eq("revision-b"), terms.capture(), eq(List.of("law")));
		assertThat(terms.getValue()).hasSize(24);
		assertThat(hits).hasSize(100);
	}

	@Test
	void failsShadowSearchClosedWhenTheBoundedMapperReadFails() {
		SemanticLexicalMapper mapper = mock(SemanticLexicalMapper.class);
		when(mapper.findReadyRevision()).thenReturn("revision-c");
		when(mapper.findBm25TermMatches(eq("revision-c"), anyList(), anyList()))
			.thenThrow(new IllegalStateException("query timeout"));

		assertThat(service(mapper).search("검사", List.of("law"), 10)).isEmpty();
	}

	private KoreanBm25SearchService service(SemanticLexicalMapper mapper) {
		return new KoreanBm25SearchService(
			mapper,
			new KoreanLexicalTokenizer(),
			new LawAiLexicalProperties(0, -1, 0, 0, 0, 0, 0, 0)
		);
	}

	private SemanticLexicalMapper.Bm25TermMatchRow match(
		String target,
		long chunkId,
		long documentId,
		String term,
		double weightedTermFrequency,
		int documentFrequency,
		int activeChunkCount,
		double averageWeightedLength,
		int weightedLength
	) {
		return new SemanticLexicalMapper.Bm25TermMatchRow(
			target, chunkId, documentId, term, weightedTermFrequency, documentFrequency,
			activeChunkCount, averageWeightedLength, weightedLength
		);
	}
}
