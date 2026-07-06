package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LawAiEvaluationCaseCatalogTests {

	@Test
	void loadsEnoughDefaultCasesForRegressionGate() {
		List<LawAiEvalRequest.EvalCase> cases = LawAiEvaluationCaseCatalog.loadDefaultCases();

		assertThat(cases).hasSizeGreaterThanOrEqualTo(25);
	}

	@Test
	void defaultCasesHaveStableIdsAndExpectations() {
		List<LawAiEvalRequest.EvalCase> cases = LawAiEvaluationCaseCatalog.loadDefaultCases();
		Set<String> ids = new HashSet<>();

		for (LawAiEvalRequest.EvalCase evalCase : cases) {
			assertThat(evalCase.id()).isNotBlank();
			assertThat(ids.add(evalCase.id())).as("duplicate evaluation id: " + evalCase.id()).isTrue();
			assertThat(evalCase.question()).isNotBlank();
			assertThat(evalCase.targets()).as(evalCase.id()).isNotEmpty();
			assertThat(hasAnyExpectation(evalCase)).as(evalCase.id() + " has no expected evidence signal").isTrue();
			assertThat(evalCase.answerDirection()).as(evalCase.id()).isNotBlank();
		}
	}

	private boolean hasAnyExpectation(LawAiEvalRequest.EvalCase evalCase) {
		return !empty(evalCase.expectedTerms())
			|| !empty(evalCase.expectedTitleTerms())
			|| !empty(evalCase.expectedSectionTypes())
			|| !empty(evalCase.expectedDocumentTerms())
			|| !empty(evalCase.expectedPageNumbers())
			|| !empty(evalCase.expectedParentTerms())
			|| !empty(evalCase.expectedResultMsgs());
	}

	private boolean empty(List<String> values) {
		return values == null || values.isEmpty();
	}
}
