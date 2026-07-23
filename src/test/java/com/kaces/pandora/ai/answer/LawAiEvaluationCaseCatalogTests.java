package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
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

	@Test
	void loadsExactlyEightyFiveCompleteExplicitAnswerOracles() {
		List<LawAiEvalRequest.EvalCase> oracleCases = LawAiEvaluationCaseCatalog.loadDefaultCases().stream()
			.filter(evalCase -> !evalCase.requiredPropositionGroups().isEmpty())
			.toList();

		assertThat(oracleCases).hasSize(85);
		for (LawAiEvalRequest.EvalCase evalCase : oracleCases) {
			assertThat(evalCase.answerVerificationRequired()).as(evalCase.id()).isTrue();
			assertThat(evalCase.requiredPropositionGroups()).as(evalCase.id()).allSatisfy(group ->
				assertThat(group).allSatisfy(alias -> assertThat(alias).isNotBlank())
			);
			assertThat(evalCase.requiredConditionGroups()).as(evalCase.id()).isNotNull();
			assertThat(evalCase.requiredConditionGroups()).as(evalCase.id()).allSatisfy(group ->
				assertThat(group).allSatisfy(alias -> assertThat(alias).isNotBlank())
			);
			assertThat(evalCase.forbiddenAnswerTerms()).as(evalCase.id()).allSatisfy(expression ->
				assertThat(expression).isNotBlank()
			);
			assertThat(evalCase.forbiddenAnswerTerms()).as(evalCase.id()).isNotEmpty();
		}
	}

	@Test
	void preservesRepresentativeExceptionAndPeriodOracleSemantics() {
		List<LawAiEvalRequest.EvalCase> cases = LawAiEvaluationCaseCatalog.loadDefaultCases();

		LawAiEvalRequest.EvalCase hardware = find(cases, "project-review-hardware-exclusion");
		assertThat(hardware.requiredPropositionGroups()).containsExactly(
			List.of("소프트웨어사업으로 볼 수 없는", "비대상")
		);
		assertThat(hardware.requiredConditionGroups()).isEmpty();
		assertThat(hardware.forbiddenAnswerTerms()).contains("하드웨어만 구매해도 심의한다");

		LawAiEvalRequest.EvalCase retention = find(cases, "cctv-retention-not-fixed-30");
		assertThat(retention.requiredPropositionGroups()).containsExactly(
			List.of("무조건 30일이 아니다", "반드시 30일은 아니다", "일률적으로 30일은 아니다")
		);
		assertThat(retention.requiredConditionGroups()).containsExactly(
			List.of("설치 목적", "목적에 필요한 기간")
		);
		assertThat(retention.forbiddenAnswerTerms()).contains("영상 종류별 차이를 무시한 획일 기준");

		LawAiEvalRequest.EvalCase preConsultation = find(cases, "pre-consultation-exception");
		assertThat(preConsultation.requiredPropositionGroups()).containsExactly(
			List.of("사전협의 제외 대상", "사전협의 제외")
		);
		assertThat(preConsultation.requiredConditionGroups()).containsExactly(
			List.of("제외 요건", "제외 조건", "대상 여부 확인")
		);
		assertThat(preConsultation.forbiddenAnswerTerms()).contains("제외사업 없음");
	}

	@Test
	void allowedAliasesDoNotMatchForbiddenExpressions() {
		List<LawAiEvalRequest.EvalCase> oracleCases = LawAiEvaluationCaseCatalog.loadDefaultCases().stream()
			.filter(evalCase -> !evalCase.requiredPropositionGroups().isEmpty())
			.toList();
		List<String> collisions = new java.util.ArrayList<>();

		for (LawAiEvalRequest.EvalCase evalCase : oracleCases) {
			List<String> allowedAliases = java.util.stream.Stream.concat(
				evalCase.requiredPropositionGroups().stream(),
				evalCase.requiredConditionGroups().stream()
			).flatMap(List::stream).toList();
			String representativeAllowedAnswer = java.util.stream.Stream.concat(
				evalCase.requiredPropositionGroups().stream(),
				evalCase.requiredConditionGroups().stream()
			).map(group -> group.get(0)).collect(java.util.stream.Collectors.joining(". "));
			for (String allowedAlias : allowedAliases) {
				for (String forbidden : evalCase.forbiddenAnswerTerms()) {
					if (EvaluationTermMatcher.matchesAnswerTerm(allowedAlias, forbidden)) {
						collisions.add(evalCase.id() + ": " + allowedAlias + " <> " + forbidden);
					}
				}
			}
			for (String forbidden : evalCase.forbiddenAnswerTerms()) {
				if (EvaluationTermMatcher.matchesAnswerTerm(representativeAllowedAnswer, forbidden)) {
					collisions.add(evalCase.id() + " representative answer <> " + forbidden);
				}
			}
		}
		assertThat(collisions).isEmpty();
	}

	@Test
	void oracleMergeRejectsDuplicateOrphanMissingAndMalformedRows() {
		List<LawAiEvalRequest.EvalCase> baseCases = List.of(baseCase("a"), baseCase("b"));
		Set<String> requiredIds = Set.of("a", "b");

		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\tanswer\t-\twrong\n" +
			"a\tanswer\t-\twrong\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("duplicate oracle ID")
			.hasMessageContaining("a");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\tanswer\t-\twrong\n" +
			"b\tanswer\t-\twrong\n" +
			"orphan\tanswer\t-\twrong\n"))
			.hasMessageContaining("orphan oracle ID")
			.hasMessageContaining("orphan");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\tanswer\t-\twrong\n"))
			.hasMessageContaining("missing oracle IDs")
			.hasMessageContaining("b");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\tanswer||alias\t-\twrong\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("malformed proposition group")
			.hasMessageContaining("a");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\tanswer\t-;condition\twrong\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("malformed condition groups")
			.hasMessageContaining("a");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\tanswer\t-\t-\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("forbidden answer expression")
			.hasMessageContaining("a");
	}

	private LawAiEvalRequest.EvalCase find(List<LawAiEvalRequest.EvalCase> cases, String id) {
		return cases.stream()
			.filter(evalCase -> id.equals(evalCase.id()))
			.findFirst()
			.orElseThrow();
	}

	private LawAiEvalRequest.EvalCase baseCase(String id) {
		return new LawAiEvalRequest.EvalCase(
			id,
			"question " + id,
			List.of("law"),
			List.of("expected"),
			1,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			"answer directly"
		);
	}

	private List<LawAiEvalRequest.EvalCase> merge(
		List<LawAiEvalRequest.EvalCase> baseCases,
		Set<String> requiredIds,
		String rows
	) throws Exception {
		String tsv = "id\trequiredPropositionGroups\trequiredConditionGroups\tforbiddenAnswerExpressions\n" + rows;
		return LawAiEvaluationCaseCatalog.mergeAnswerOracles(
			baseCases,
			new ByteArrayInputStream(tsv.getBytes(StandardCharsets.UTF_8)),
			requiredIds
		);
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
