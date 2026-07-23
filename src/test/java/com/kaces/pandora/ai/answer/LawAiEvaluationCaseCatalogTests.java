package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
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
			List.of("기관별 사업금액 기준 미만인 사업은 제외", "기관별 기준금액 미만 사업은 사전협의 제외")
		);
		assertThat(preConsultation.requiredConditionGroups()).containsExactly(
			List.of("신규 사업은 금액 기준 미만이어도 사전협의 대상", "신규로 추진하는 사업은 대상에 포함")
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
					if (ExplicitOracleTermMatcher.matches(allowedAlias, forbidden)) {
						collisions.add(evalCase.id() + ": " + allowedAlias + " <> " + forbidden);
					}
				}
			}
			for (String forbidden : evalCase.forbiddenAnswerTerms()) {
				if (ExplicitOracleTermMatcher.matches(representativeAllowedAnswer, forbidden)) {
					collisions.add(evalCase.id() + " representative answer <> " + forbidden);
				}
			}
		}
		assertThat(collisions).isEmpty();
	}

	@Test
	void everyBundledOracleAcceptsItsDirectRepresentativeAnswer() {
		List<LawAiEvalRequest.EvalCase> oracleCases = LawAiEvaluationCaseCatalog.loadDefaultCases().stream()
			.filter(evalCase -> !evalCase.requiredPropositionGroups().isEmpty())
			.toList();

		for (LawAiEvalRequest.EvalCase evalCase : oracleCases) {
			String representativeAnswer = java.util.stream.Stream.concat(
				evalCase.requiredPropositionGroups().stream(),
				evalCase.requiredConditionGroups().stream()
			).map(group -> group.get(0)).collect(java.util.stream.Collectors.joining(". "));
			AnswerOracleMatcher.Result result = AnswerOracleMatcher.evaluate(representativeAnswer, evalCase);

			assertThat(result.passed())
				.as(evalCase.id() + ": " + result.message())
				.isTrue();
		}
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

	@Test
	void oracleParserFailsClosedForQuotedFieldsIncludingQuotedTabsAndNewlines() {
		List<LawAiEvalRequest.EvalCase> baseCases = List.of(baseCase("a"), baseCase("b"));
		Set<String> requiredIds = Set.of("a", "b");

		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"\"a\"\tanswer\t-\twrong\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("quoted fields are not supported");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\t\"answer\talias\"\t-\twrong\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("quoted fields are not supported");
		assertThatThrownBy(() -> merge(baseCases, requiredIds,
			"a\t\"answer\ncontinued\"\t-\twrong\n" +
			"b\tanswer\t-\twrong\n"))
			.hasMessageContaining("quoted fields are not supported");
	}

	@Test
	void correctedSemanticOraclesRequireDirectFactsInsteadOfCircularRestatements() {
		List<LawAiEvalRequest.EvalCase> cases = LawAiEvaluationCaseCatalog.loadDefaultCases();
		Map<String, String> directAnswers = Map.of(
			"pre-consultation-exception",
			"기관별 사업금액 기준 미만인 사업은 사전협의에서 제외되지만 신규 사업은 금액 기준 미만이어도 대상입니다.",
			"security-review-exception",
			"DB 구축이나 콘텐츠 제작 용역에서 참여 인력이 시스템에 접근하지 않으면 보안성검토 대상에서 제외됩니다. 참여 인력이 시스템에 접근하면 보안성검토 대상입니다.",
			"security-review-skip-condition",
			"DB 구축이나 콘텐츠 제작 용역에서 참여 인력이 시스템에 접근하지 않으면 보안성검토를 생략할 수 있습니다. 참여 인력이 시스템에 접근하면 보안성검토 대상입니다.",
			"performance-measure-when",
			"IRM 평가기간은 2025년 12월 17일부터 2026년 10월 31일까지이며 평가기간 동안 요청 수와 완료 수를 합산합니다.",
			"irm-measure-period",
			"IRM 평가기간은 2025년 12월 17일부터 2026년 10월 31일까지이며 평가기간 동안 요청 수와 완료 수를 합산합니다.",
			"security-review-notice-result",
			"검토기관이 보안성검토 결과를 검토 요청기관에 통보합니다.",
			"commercial-sw-direct-buy-target",
			"조달청 종합쇼핑몰 또는 디지털서비스몰에 등록된 상용소프트웨어는 직접구매 대상입니다. 품질 인증 소프트웨어는 가격이 5천만원 이상인 경우 직접구매 대상입니다."
		);
		Map<String, String> circularAnswers = Map.of(
			"pre-consultation-exception", "사전협의 제외 대상에는 정해진 조건이 있으며 공식 기준을 확인해야 합니다.",
			"security-review-exception", "보안성검토 생략 가능한 경우에는 정해진 조건이 있습니다.",
			"security-review-skip-condition", "보안성검토를 생략할 수 있는 조건을 확인해야 합니다.",
			"performance-measure-when", "IRM 성과측정은 정해진 평가기간에 하고 공식 일정을 확인합니다.",
			"irm-measure-period", "정보자원관리 성과측정은 정해진 평가기간에 하고 공식 일정을 확인합니다.",
			"security-review-notice-result", "보안성검토 결과 통보 절차를 따릅니다.",
			"commercial-sw-direct-buy-target", "상용소프트웨어 직접구매 대상이면 직접구매하고 정해진 기준을 충족해야 합니다."
		);

		for (String id : directAnswers.keySet()) {
			AnswerOracleMatcher.Result directResult = AnswerOracleMatcher.evaluate(directAnswers.get(id), find(cases, id));
			assertThat(directResult.passed())
				.as(id + " direct answer: " + directResult.message())
				.isTrue();
			assertThat(AnswerOracleMatcher.evaluate(circularAnswers.get(id), find(cases, id)).passed())
				.as(id + " circular answer")
				.isFalse();
		}
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
