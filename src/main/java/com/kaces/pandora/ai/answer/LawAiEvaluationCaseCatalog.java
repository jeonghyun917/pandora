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
import java.util.List;
import java.util.Map;

final class LawAiEvaluationCaseCatalog {

	private static final List<String> RESOURCE_PATHS = List.of(
		"/rag-evaluation-cases.tsv",
		"/rag-evaluation-cases.generated.tsv"
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
			return cases.isEmpty() ? fallbackCases() : List.copyOf(cases.values());
		} catch (IOException exception) {
			return fallbackCases();
		}
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
