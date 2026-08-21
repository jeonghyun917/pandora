package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RetrievalTraceCollectorTests {

	@Test
	void preservesSourceRanksEnteredStagesAndTheFirstLossReason() {
		RetrievalTraceCollector collector = new RetrievalTraceCollector(100);
		collector.source("law:55", "law", 55L, "vector", 4);
		collector.enter("law:55", "merged");
		collector.enter("law:55", "reranked");
		collector.enter("law:55", "intent");
		collector.lose("law:55", "judge", "JUDGE_NOT_DIRECT");
		collector.lose("law:55", "answerContext", "ANSWER_CONTEXT_NOT_SELECTED");

		RetrievalCandidateTrace trace = collector.finish("law:55");

		assertThat(trace.sourceRanks()).containsEntry("vector", 4);
		assertThat(trace.enteredStages()).contains("merged", "reranked", "intent");
		assertThat(trace.firstLossStage()).isEqualTo("judge");
		assertThat(trace.reasonCodes()).containsExactly("JUDGE_NOT_DIRECT");
	}

	@Test
	void selectedCandidatesHaveNoLossStage() {
		RetrievalTraceCollector collector = new RetrievalTraceCollector(100);
		collector.source("law:8", "law", 8L, "bm25", 1);
		collector.enter("law:8", "merged");
		collector.enter("law:8", "selected");
		collector.select("law:8");

		RetrievalCandidateTrace trace = collector.finish("law:8");

		assertThat(trace.selected()).isTrue();
		assertThat(trace.firstLossStage()).isNull();
		assertThat(trace.reasonCodes()).isEmpty();
	}

	@Test
	void selectedCandidatesCanRetainNonLossPolicyNotes() {
		RetrievalTraceCollector collector = new RetrievalTraceCollector(100);
		collector.source("law:8", "law", 8L, "bm25", 1);
		collector.note("law:8", "directEvidencePolicy", "DIRECT_ATOM_PRESERVED");
		collector.select("law:8");

		RetrievalCandidateTrace trace = collector.finish("law:8");

		assertThat(trace.selected()).isTrue();
		assertThat(trace.firstLossStage()).isNull();
		assertThat(trace.enteredStages()).contains("directEvidencePolicy");
		assertThat(trace.reasonCodes()).containsExactly("DIRECT_ATOM_PRESERVED");
	}

	@Test
	void boundsDebugTracesWithoutIncludingChunkText() {
		RetrievalTraceCollector collector = new RetrievalTraceCollector(2);
		collector.source("law:1", "law", 1L, "vector", 1);
		collector.source("law:2", "law", 2L, "vector", 2);
		collector.source("law:3", "law", 3L, "vector", 3);

		List<RetrievalCandidateTrace> traces = collector.finishAll();

		assertThat(traces).extracting(RetrievalCandidateTrace::candidateKey)
			.containsExactly("law:1", "law:2");
		assertThat(RetrievalCandidateTrace.class.getRecordComponents())
			.extracting(java.lang.reflect.RecordComponent::getName)
			.doesNotContain("chunkText", "body", "snippet");
	}

	@Test
	void boundedTraceKeepsTheBestRankedCandidatesAcrossRetrievalSources() {
		RetrievalTraceCollector collector = new RetrievalTraceCollector(2);
		collector.source("law:1", "law", 1L, "vector", 1);
		collector.source("law:2", "law", 2L, "vector", 40);

		collector.source("official_doc:3", "official_doc", 3L, "bm25", 23);

		assertThat(collector.finishAll())
			.extracting(RetrievalCandidateTrace::candidateKey)
			.containsExactly("law:1", "official_doc:3");
	}
}
