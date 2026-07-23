package com.kaces.pandora.ai.answer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class LawAiEvaluationCaseCatalog {

	private static final List<String> RESOURCE_PATHS = List.of(
		"/rag-evaluation-cases.tsv",
		"/rag-evaluation-cases.generated.tsv"
	);
	private static final String ANSWER_ORACLE_RESOURCE_PATH = "/rag-answer-evaluation-oracles.tsv";
	private static final Set<String> REQUIRED_ANSWER_ORACLE_IDS = Set.of(
		"project-review-target",
		"project-review-simple-software",
		"project-review-hardware-exclusion",
		"project-review-sns-operation",
		"project-review-pre-consultation-relation",
		"pre-consultation-target",
		"pre-consultation-when",
		"pre-consultation-exception",
		"security-review-target",
		"security-review-exception",
		"security-review-procedure",
		"it-compliance-penalty",
		"egov-preliminary-review-target",
		"rfp-required-items",
		"rfp-tech-score-table",
		"public-data-db-standard",
		"procurement-catalog-contract",
		"commercial-sw-direct-purchase",
		"performance-measure-when",
		"irm-faithfulness",
		"whistleblower-protection-scope",
		"traffic-crosswalk-stop",
		"video-cctv-guide",
		"personal-info-purpose",
		"official-doc-title",
		"noise-unification-white-paper-header",
		"privacy-integrated-guide-purpose",
		"privacy-consent-notice-items",
		"public-data-custom-support",
		"public-data-preprocessing",
		"mois-autonomy-preconsultation-target",
		"mois-autonomy-preconsultation-procedure",
		"mcst-tourism-dure-support",
		"pipc-cctv-public-place-exception",
		"pipc-cctv-retention-period",
		"pipc-pseudonym-additional-info",
		"public-data-portal-standard-scope",
		"mcst-tourism-dure-period",
		"msit-tving-investigation",
		"project-review-all-sw-projects",
		"project-review-exclusion-hardware",
		"pre-consultation-public-agency",
		"pre-consultation-plan-stage",
		"security-review-sensitive-info",
		"security-review-notice-result",
		"rfp-requirement-evaluation",
		"commercial-sw-direct-buy-exception",
		"procurement-digital-service-mall",
		"privacy-consent-refusal",
		"cctv-public-place-rule",
		"cctv-retention-not-fixed-30",
		"ai-law-enforcement-date",
		"traffic-right-turn-pedestrian",
		"whistleblower-disadvantage",
		"irm-faithfulness-meaning",
		"mois-autonomy-document-confusion",
		"project-review-maintenance-check",
		"project-review-scope-change",
		"pre-consultation-central-agency",
		"pre-consultation-excluded-project",
		"security-review-major-infra",
		"security-review-skip-condition",
		"rfp-requirement-method",
		"commercial-sw-direct-buy-target",
		"procurement-catalog-vs-contract",
		"public-data-portal-manual-application",
		"privacy-consent-items-law",
		"privacy-processing-principle",
		"pseudonym-extra-info-separate",
		"traffic-right-turn-stop-rule",
		"whistleblower-protection-action",
		"irm-measure-period",
		"mois-autonomy-request-docs",
		"privacy-retention-notice",
		"privacy-minimum-collection",
		"privacy-destruction-principle",
		"cctv-install-purpose-limit",
		"cctv-retention-period",
		"public-data-open-format",
		"public-data-meta-management",
		"mois-national-safety-plan",
		"law-effective-date-check",
		"admrul-notice-exception",
		"no-unrelated-privacy-for-sw",
		"public-data-obligation-system"
	);
	private static final Path EXTERNAL_FAILURE_CASE_PATH = Path.of(
		System.getProperty("pandora.rag.eval.failure-cases", "data/rag-evaluation/failure-cases.tsv")
	);
	private static final int MIN_COLUMN_COUNT = 8;

	private LawAiEvaluationCaseCatalog() {
	}

	static List<LawAiEvalRequest.EvalCase> loadDefaultCases() {
		try {
			Map<String, LawAiEvalRequest.EvalCase> cases = new LinkedHashMap<>();
			for (String resourcePath : RESOURCE_PATHS) {
				try (InputStream inputStream = LawAiEvaluationCaseCatalog.class.getResourceAsStream(resourcePath)) {
					if (inputStream == null) {
						continue;
					}
					for (LawAiEvalRequest.EvalCase evalCase : parse(inputStream)) {
						cases.put(evalCase.id(), evalCase);
					}
				}
			}
			loadExternalCases(cases);
			try (InputStream oracleStream = LawAiEvaluationCaseCatalog.class.getResourceAsStream(ANSWER_ORACLE_RESOURCE_PATH)) {
				if (oracleStream == null) {
					throw new IllegalStateException("Missing bundled answer oracle resource: " + ANSWER_ORACLE_RESOURCE_PATH);
				}
				return mergeAnswerOracles(List.copyOf(cases.values()), oracleStream, REQUIRED_ANSWER_ORACLE_IDS);
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Failed to load evaluation cases and answer oracles.", exception);
		}
	}

	static List<LawAiEvalRequest.EvalCase> mergeAnswerOracles(
		List<LawAiEvalRequest.EvalCase> baseCases,
		InputStream inputStream,
		Set<String> requiredOracleIds
	) throws IOException {
		Map<String, LawAiEvalRequest.EvalCase> casesById = new LinkedHashMap<>();
		for (LawAiEvalRequest.EvalCase evalCase : baseCases == null ? List.<LawAiEvalRequest.EvalCase>of() : baseCases) {
			casesById.put(evalCase.id(), evalCase);
		}
		Map<String, AnswerOracle> oracles = parseAnswerOracles(inputStream);
		for (String oracleId : oracles.keySet()) {
			if (!casesById.containsKey(oracleId)) {
				throw new IllegalArgumentException("orphan oracle ID: " + oracleId);
			}
		}
		Set<String> requiredIds = requiredOracleIds == null ? Set.of() : Set.copyOf(requiredOracleIds);
		Set<String> missingIds = new LinkedHashSet<>(requiredIds);
		missingIds.removeAll(oracles.keySet());
		if (!missingIds.isEmpty()) {
			throw new IllegalArgumentException("missing oracle IDs: " + String.join(", ", missingIds));
		}
		Set<String> unexpectedIds = new LinkedHashSet<>(oracles.keySet());
		unexpectedIds.removeAll(requiredIds);
		if (!unexpectedIds.isEmpty()) {
			throw new IllegalArgumentException("unexpected oracle IDs: " + String.join(", ", unexpectedIds));
		}
		if (oracles.size() != requiredIds.size()) {
			throw new IllegalArgumentException(
				"bundled answer oracle count must be " + requiredIds.size() + ", got " + oracles.size()
			);
		}
		for (AnswerOracle oracle : oracles.values()) {
			LawAiEvalRequest.EvalCase baseCase = casesById.get(oracle.id());
			casesById.put(oracle.id(), withOracle(baseCase, oracle));
		}
		return List.copyOf(casesById.values());
	}

	private static Map<String, AnswerOracle> parseAnswerOracles(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			throw new IllegalArgumentException("Answer oracle input is required.");
		}
		Map<String, AnswerOracle> oracles = new LinkedHashMap<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			int lineNumber = 0;
			while ((line = reader.readLine()) != null) {
				lineNumber++;
				if (lineNumber == 1 && line.startsWith("\uFEFF")) {
					line = line.substring(1);
				}
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] columns = line.split("\t", -1);
				if ("id".equalsIgnoreCase(columns[0].trim())) {
					continue;
				}
				if (columns.length != 4) {
					throw new IllegalArgumentException(
						"answer oracle line " + lineNumber + " must have exactly 4 columns"
					);
				}
				String id = columns[0].trim();
				if (id.isBlank()) {
					throw new IllegalArgumentException("answer oracle line " + lineNumber + " has an empty ID");
				}
				if (oracles.containsKey(id)) {
					throw new IllegalArgumentException("duplicate oracle ID: " + id);
				}
				List<List<String>> propositions = parseGroups(columns[1], id, "proposition", false);
				List<List<String>> conditions = parseGroups(columns[2], id, "condition", true);
				List<String> forbidden = parseForbiddenExpressions(columns[3], id);
				oracles.put(id, new AnswerOracle(id, propositions, conditions, forbidden));
			}
		}
		return Map.copyOf(oracles);
	}

	private static List<List<String>> parseGroups(String value, String id, String kind, boolean allowNone) {
		String trimmed = value == null ? "" : value.trim();
		if (allowNone && "-".equals(trimmed)) {
			return List.of();
		}
		if (trimmed.isBlank() || "-".equals(trimmed)) {
			throw new IllegalArgumentException("malformed " + kind + " groups for oracle ID " + id);
		}
		List<List<String>> groups = new ArrayList<>();
		for (String rawGroup : trimmed.split(";", -1)) {
			if (rawGroup.isBlank() || rawGroup.contains("-") && "-".equals(rawGroup.trim())) {
				throw new IllegalArgumentException("malformed " + kind + " groups for oracle ID " + id);
			}
			List<String> aliases = new ArrayList<>();
			for (String rawAlias : rawGroup.split("\\|", -1)) {
				String alias = rawAlias.trim();
				if (alias.isBlank()) {
					throw new IllegalArgumentException("malformed " + kind + " group for oracle ID " + id);
				}
				aliases.add(alias);
			}
			groups.add(List.copyOf(aliases));
		}
		return List.copyOf(groups);
	}

	private static List<String> parseForbiddenExpressions(String value, String id) {
		String trimmed = value == null ? "" : value.trim();
		if (trimmed.isBlank() || "-".equals(trimmed)) {
			throw new IllegalArgumentException("missing forbidden answer expression for oracle ID " + id);
		}
		List<String> expressions = new ArrayList<>();
		for (String rawExpression : trimmed.split("\\|", -1)) {
			String expression = rawExpression.trim();
			if (expression.isBlank()) {
				throw new IllegalArgumentException("malformed forbidden answer expression for oracle ID " + id);
			}
			expressions.add(expression);
		}
		return List.copyOf(expressions);
	}

	private static LawAiEvalRequest.EvalCase withOracle(
		LawAiEvalRequest.EvalCase baseCase,
		AnswerOracle oracle
	) {
		return new LawAiEvalRequest.EvalCase(
			baseCase.id(),
			baseCase.question(),
			baseCase.targets(),
			baseCase.expectedTerms(),
			baseCase.requiredMatches(),
			baseCase.expectedTitleTerms(),
			baseCase.expectedSectionTypes(),
			baseCase.forbiddenTerms(),
			baseCase.expectedDocumentTerms(),
			baseCase.expectedPageNumbers(),
			baseCase.expectedParentTerms(),
			baseCase.answerDirection(),
			baseCase.expectedResultMsgs(),
			true,
			baseCase.expectedAnswerTerms(),
			oracle.forbiddenAnswerExpressions(),
			oracle.requiredPropositionGroups(),
			oracle.requiredConditionGroups()
		);
	}

	private record AnswerOracle(
		String id,
		List<List<String>> requiredPropositionGroups,
		List<List<String>> requiredConditionGroups,
		List<String> forbiddenAnswerExpressions
	) {
	}

	static Path externalFailureCasePath() {
		return EXTERNAL_FAILURE_CASE_PATH;
	}

	static boolean caseIdExists(String caseId) {
		if (caseId == null || caseId.isBlank()) {
			return false;
		}
		String normalized = caseId.trim();
		return loadDefaultCases().stream()
			.anyMatch(evalCase -> normalized.equals(evalCase.id()));
	}

	static void appendExternalFailureCase(LawAiEvalRequest.EvalCase evalCase) throws IOException {
		if (evalCase == null || evalCase.id() == null || evalCase.id().isBlank()) {
			throw new IllegalArgumentException("Evaluation case id is required.");
		}
		Path path = externalFailureCasePath();
		if (path.getParent() != null) {
			Files.createDirectories(path.getParent());
		}
		boolean writeHeader = !Files.exists(path) || Files.size(path) == 0;
		StringBuilder builder = new StringBuilder();
		if (writeHeader) {
			builder.append("id\tquestion\ttargets\texpectedTerms\trequiredMatches\texpectedTitleTerms\texpectedSectionTypes\tforbiddenTerms\texpectedDocumentTerms\texpectedPageNumbers\texpectedParentTerms\tanswerDirection\texpectedResultMsgs\tanswerVerificationRequired\texpectedAnswerTerms\tforbiddenAnswerTerms\n");
		}
		builder.append(toTsvLine(evalCase)).append("\n");
		Files.writeString(path, builder.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
	}

	private static void loadExternalCases(Map<String, LawAiEvalRequest.EvalCase> cases) throws IOException {
		Path path = externalFailureCasePath();
		if (!Files.isRegularFile(path)) {
			return;
		}
		try (InputStream inputStream = Files.newInputStream(path)) {
			for (LawAiEvalRequest.EvalCase evalCase : parse(inputStream)) {
				cases.put(evalCase.id(), evalCase);
			}
		}
	}

	private static List<LawAiEvalRequest.EvalCase> parse(InputStream inputStream) throws IOException {
		List<LawAiEvalRequest.EvalCase> cases = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank() || line.startsWith("#")) {
					continue;
				}
				String[] columns = line.split("\t", -1);
				if (columns.length < MIN_COLUMN_COUNT || "id".equalsIgnoreCase(columns[0].trim())) {
					continue;
				}
				cases.add(new LawAiEvalRequest.EvalCase(
					columns[0].trim(),
					columns[1].trim(),
					splitList(columns[2]),
					splitList(columns[3]),
					parseRequiredMatches(columns[4]),
					splitList(columns[5]),
					splitList(columns[6]),
					splitList(columns[7]),
					splitList(column(columns, 8)),
					splitList(column(columns, 9)),
					splitList(column(columns, 10)),
					column(columns, 11).trim(),
					expectedResultMsgs(columns[0], column(columns, 12)),
					parseBoolean(column(columns, 13)),
					splitList(column(columns, 14)),
					splitList(column(columns, 15))
				));
			}
		}
		return List.copyOf(cases);
	}

	private static Boolean parseBoolean(String value) {
		if (value == null || value.isBlank() || "-".equals(value.trim())) {
			return null;
		}
		String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
		if ("true".equals(normalized) || "1".equals(normalized) || "yes".equals(normalized) || "y".equals(normalized)) {
			return true;
		}
		if ("false".equals(normalized) || "0".equals(normalized) || "no".equals(normalized) || "n".equals(normalized)) {
			return false;
		}
		return null;
	}

	private static String column(String[] columns, int index) {
		return columns.length > index ? columns[index] : "";
	}

	private static List<String> expectedResultMsgs(String id, String value) {
		List<String> explicitValues = splitList(value);
		if (!explicitValues.isEmpty()) {
			return explicitValues;
		}
		if (id != null && id.trim().startsWith("no-")) {
			return List.of("NO_GROUNDS");
		}
		return List.of();
	}

	private static Integer parseRequiredMatches(String value) {
		if (value == null || value.isBlank()) {
			return null;
		}
		try {
			return Math.max(0, Integer.parseInt(value.trim()));
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private static List<String> splitList(String value) {
		if (value == null || value.isBlank() || "-".equals(value.trim())) {
			return List.of();
		}
		return java.util.Arrays.stream(value.split("\\|"))
			.map(String::trim)
			.filter(term -> !term.isBlank())
			.toList();
	}

	private static String toTsvLine(LawAiEvalRequest.EvalCase evalCase) {
		return String.join("\t",
			tsv(evalCase.id()),
			tsv(evalCase.question()),
			tsv(join(evalCase.targets())),
			tsv(join(evalCase.expectedTerms())),
			tsv(evalCase.requiredMatches() == null ? "" : String.valueOf(evalCase.requiredMatches())),
			tsv(join(evalCase.expectedTitleTerms())),
			tsv(join(evalCase.expectedSectionTypes())),
			tsv(join(evalCase.forbiddenTerms())),
			tsv(join(evalCase.expectedDocumentTerms())),
			tsv(join(evalCase.expectedPageNumbers())),
			tsv(join(evalCase.expectedParentTerms())),
			tsv(evalCase.answerDirection()),
			tsv(join(evalCase.expectedResultMsgs())),
			tsv(evalCase.answerVerificationRequired() == null ? "" : String.valueOf(evalCase.answerVerificationRequired())),
			tsv(join(evalCase.expectedAnswerTerms())),
			tsv(join(evalCase.forbiddenAnswerTerms()))
		);
	}

	private static String join(List<String> values) {
		if (values == null || values.isEmpty()) {
			return "";
		}
		return String.join("|", values);
	}

	private static String tsv(String value) {
		return String.valueOf(value == null ? "" : value)
			.replace('\t', ' ')
			.replace('\r', ' ')
			.replace('\n', ' ')
			.trim();
	}

	private static List<LawAiEvalRequest.EvalCase> fallbackCases() {
		return List.of(
			new LawAiEvalRequest.EvalCase(
				"project-review-target",
				"과업심의 대상은?",
				List.of("official_doc", "internal_doc"),
				List.of("적용 대상 사업", "국가기관 등이 발주하는 모든 SW사업", "소프트웨어사업"),
				2,
				List.of("과업심의"),
				List.of("target_scope"),
				List.of("간소화"),
				List.of("과업심의"),
				List.of(),
				List.of("적용 대상 사업"),
				"대상 사업과 제외 대상을 먼저 답한다"
			),
			new LawAiEvalRequest.EvalCase(
				"security-review-target",
				"보안성검토 대상 시스템은?",
				List.of("official_doc", "internal_doc", "admrul", "law"),
				List.of("보안성 검토", "정보시스템", "민감정보"),
				2,
				List.of("보안성 검토"),
				List.of("target_scope"),
				List.of("발주정보등록"),
				List.of("보안성 검토"),
				List.of(),
				List.of("대상"),
				"검토 대상 시스템 범위를 먼저 답한다"
			)
		);
	}
}
