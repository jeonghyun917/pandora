package com.kaces.pandora.common.text;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record QuestionSearchPlan(
	String question,
	QuestionIntentProfile profile,
	String embeddingQuery,
	List<String> expandedQueries,
	List<String> clarificationQuestions,
	List<String> lexicalKeywords,
	List<String> focusedKeywords,
	List<String> excludedHints,
	List<String> answerFocusInstructions
) {
	private static final int MAX_EMBEDDING_KEYWORDS = 10;
	private static final int MAX_LEXICAL_KEYWORDS = 48;
	private static final int MAX_FOCUSED_KEYWORDS = 18;
	private static final int MAX_EXPANDED_QUERIES = 4;
	private static final int MAX_BM25_VARIANTS = 4;

	public record LexicalVariant(
		String id,
		String query,
		List<String> plannedKeywords,
		String tokenSetHash
	) {
		public LexicalVariant {
			plannedKeywords = plannedKeywords == null ? List.of() : List.copyOf(plannedKeywords);
		}
	}

	public List<String> bm25Keywords() {
		LinkedHashSet<String> prioritized = new LinkedHashSet<>();
		addAll(prioritized, focusedKeywords);
		addAll(prioritized, lexicalKeywords);
		return cleanKeywords(prioritized, MAX_LEXICAL_KEYWORDS);
	}

	public List<LexicalVariant> bm25Variants() {
		List<VariantDraft> drafts = new ArrayList<>();
		drafts.add(new VariantDraft("original-focused", question, focusedKeywords));

		List<String> intentTypes = prioritizedIntentTypes(question, profile.intentTypes());
		LinkedHashSet<String> entityIntentTerms = new LinkedHashSet<>();
		if (!profile.entities().isEmpty()) {
			QuestionEntity entity = profile.entities().get(0);
			addTerm(entityIntentTerms, entity.label());
			addLimitedTerms(entityIntentTerms, entity.focusedKeywords(), 3);
		} else {
			addFirstTerms(entityIntentTerms, profile.conceptGroups(), 2);
		}
		addIntentTerms(entityIntentTerms, intentTypes, 4);
		drafts.add(draft("entity-intent", entityIntentTerms));

		LinkedHashSet<String> directEvidenceTerms = new LinkedHashSet<>();
		addFirstTerms(directEvidenceTerms, profile.directEvidenceGroups(), 4);
		if (!profile.entities().isEmpty()) {
			addTerm(directEvidenceTerms, profile.entities().get(0).label());
		} else {
			addFirstTerms(directEvidenceTerms, profile.conceptGroups(), 2);
		}
		drafts.add(draft("direct-evidence", directEvidenceTerms));

		LinkedHashSet<String> synonymIntentTerms = new LinkedHashSet<>();
		addFirstTerms(synonymIntentTerms, profile.synonymGroups(), 4);
		addIntentTerms(synonymIntentTerms, intentTypes, 4);
		drafts.add(draft("synonym-intent", synonymIntentTerms));

		LinkedHashSet<String> hashes = new LinkedHashSet<>();
		List<LexicalVariant> variants = new ArrayList<>();
		for (VariantDraft draft : drafts) {
			TreeSet<String> tokens = substantiveTokens(draft.query(), draft.plannedKeywords());
			if (tokens.isEmpty()) {
				continue;
			}
			String hash = sha256(String.join("\n", tokens));
			if (!hashes.add(hash)) {
				continue;
			}
			variants.add(new LexicalVariant(draft.id(), draft.query(), draft.plannedKeywords(), hash));
			if (variants.size() >= MAX_BM25_VARIANTS) {
				break;
			}
		}
		return List.copyOf(variants);
	}

	public DocumentSearchAnchor documentSearchAnchor() {
		return DocumentSearchAnchorExtractor.extract(question, profile, lexicalKeywords, focusedKeywords);
	}

	public static QuestionSearchPlan from(String question) {
		String original = question == null ? "" : question.trim();
		QuestionIntentProfile profile = QuestionIntentProfile.from(original);
		LinkedHashSet<String> lexical = new LinkedHashSet<>();
		LinkedHashSet<String> focused = new LinkedHashSet<>();

		addAll(lexical, profile.lexicalKeywords());
		addAll(lexical, flatten(profile.conceptGroups()));
		addAll(lexical, flatten(profile.intentGroups()));
		addAll(lexical, flatten(profile.directEvidenceGroups()));
		addAll(focused, profile.focusedKeywords());
		addFirstTerms(focused, profile.directEvidenceGroups());
		addAll(focused, profile.preferredSectionTypes());
		addAll(lexical, focused);

		List<String> lexicalKeywords = cleanKeywords(lexical, MAX_LEXICAL_KEYWORDS);
		List<String> focusedKeywords = cleanKeywords(focused, MAX_FOCUSED_KEYWORDS);
		String embeddingQuery = buildEmbeddingQuery(original, profile, lexicalKeywords);
		return new QuestionSearchPlan(
			original,
			profile,
			embeddingQuery,
			buildExpandedQueries(original, profile, embeddingQuery),
			buildClarificationQuestions(original, profile),
			lexicalKeywords,
			focusedKeywords,
			excludedHints(profile),
			buildAnswerFocusInstructions(profile)
		);
	}

	private static String buildEmbeddingQuery(String question, QuestionIntentProfile profile, List<String> lexicalKeywords) {
		List<String> embeddingKeywords = lexicalKeywords.stream()
			.filter(value -> value.length() >= 3)
			.limit(MAX_EMBEDDING_KEYWORDS)
			.toList();
		String sectionTypes = String.join(", ", profile.preferredSectionTypes());
		StringBuilder builder = new StringBuilder(question == null ? "" : question.trim());
		if (!embeddingKeywords.isEmpty()) {
			builder.append("\n핵심 개념: ").append(String.join(", ", embeddingKeywords));
		}
		if (!sectionTypes.isBlank()) {
			builder.append("\n찾을 근거 유형: ").append(sectionTypes);
		}
		return builder.toString().trim();
	}

	private static List<String> buildExpandedQueries(String question, QuestionIntentProfile profile, String embeddingQuery) {
		LinkedHashSet<String> queries = new LinkedHashSet<>();
		if (embeddingQuery != null && !embeddingQuery.isBlank()) {
			queries.add(embeddingQuery);
		}
		List<String> intentTypes = prioritizedIntentTypes(question, profile.intentTypes());
		for (QuestionEntity entity : profile.entities()) {
			for (String intentType : intentTypes) {
				addAll(queries, QuestionIntentDictionary.values("multi_query." + entity.id() + "." + intentType, List.of()));
			}
		}
		addGeneratedEntityIntentQueries(queries, profile, intentTypes);
		addGeneratedSynonymIntentQueries(queries, profile, intentTypes);
		for (String intentType : intentTypes) {
			addAll(queries, QuestionIntentDictionary.values("multi_query." + intentType, List.of()));
		}
		if (queries.size() < 2 && !profile.entities().isEmpty() && !profile.intentTypes().isEmpty()) {
			addFallbackEntityIntentQueries(queries, profile, intentTypes);
		}
		if (queries.isEmpty() && question != null && !question.isBlank()) {
			queries.add(question.trim());
		}
		return queries.stream()
			.map(value -> value == null ? "" : value.replaceAll("\\s+", " ").trim())
			.filter(value -> value.length() >= 2)
			.distinct()
			.limit(MAX_EXPANDED_QUERIES)
			.toList();
	}

	private static void addGeneratedEntityIntentQueries(
		LinkedHashSet<String> queries,
		QuestionIntentProfile profile,
		List<String> intentTypes
	) {
		if (profile.entities().isEmpty() || intentTypes.isEmpty()) {
			return;
		}
		for (QuestionEntity entity : profile.entities()) {
			for (String intentType : intentTypes) {
				LinkedHashSet<String> terms = new LinkedHashSet<>();
				addTerm(terms, entity.label());
				addLimitedTerms(terms, entity.focusedKeywords(), 4);
				addFirstTerms(terms, entity.directEvidenceGroups(), 3);
				addLimitedTerms(terms, QuestionIntentDictionary.values("intent." + intentType + ".terms", List.of()), 4);
				addFirstTerms(terms, profile.directEvidenceGroups(), 2);
				String query = queryFromTerms(terms, 10);
				if (!query.isBlank()) {
					queries.add(query);
				}
			}
		}
	}

	private static void addIntentTerms(Set<String> target, List<String> intentTypes, int limit) {
		if (intentTypes == null || limit <= 0) {
			return;
		}
		for (String intentType : intentTypes) {
			addLimitedTerms(target, QuestionIntentDictionary.values("intent." + intentType + ".terms", List.of()), limit);
			if (target.size() >= limit) {
				return;
			}
		}
	}

	private static VariantDraft draft(String id, Set<String> terms) {
		List<String> keywords = terms == null ? List.of() : List.copyOf(terms);
		return new VariantDraft(id, queryFromTerms(terms, 10), keywords);
	}

	private static TreeSet<String> substantiveTokens(String query, List<String> plannedKeywords) {
		TreeSet<String> tokens = new TreeSet<>();
		List<String> sources = new ArrayList<>();
		sources.add(query);
		if (plannedKeywords != null) {
			sources.addAll(plannedKeywords);
		}
		for (String source : sources) {
			if (source == null || source.isBlank()) {
				continue;
			}
			for (String part : source.replaceAll("[^\\p{IsHangul}\\p{Alnum}]+", " ").trim().split("\\s+")) {
				String normalized = KoreanQueryNormalizer.normalizeQueryTerm(part);
				if (normalized.length() >= 2 && !KoreanQueryNormalizer.isWeakQuestionTerm(normalized)) {
					tokens.add(normalized);
				}
			}
		}
		return tokens;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
			);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 unavailable", exception);
		}
	}

	private static void addGeneratedSynonymIntentQueries(
		LinkedHashSet<String> queries,
		QuestionIntentProfile profile,
		List<String> intentTypes
	) {
		if (profile.synonymGroups().isEmpty() || intentTypes.isEmpty()) {
			return;
		}
		for (String intentType : intentTypes) {
			LinkedHashSet<String> terms = new LinkedHashSet<>();
			addFirstTerms(terms, profile.synonymGroups(), 4);
			addLimitedTerms(terms, QuestionIntentDictionary.values("intent." + intentType + ".terms", List.of()), 4);
			addLimitedTerms(terms, profile.focusedKeywords(), 3);
			String query = queryFromTerms(terms, 10);
			if (!query.isBlank()) {
				queries.add(query);
			}
		}
	}

	private static void addFallbackEntityIntentQueries(
		LinkedHashSet<String> queries,
		QuestionIntentProfile profile,
		List<String> intentTypes
	) {
		for (QuestionEntity entity : profile.entities()) {
			for (String intentType : intentTypes) {
				LinkedHashSet<String> terms = new LinkedHashSet<>();
				addTerm(terms, entity.label());
				addLimitedTerms(terms, QuestionIntentDictionary.values("intent." + intentType + ".terms", List.of()), 4);
				addLimitedTerms(terms, profile.focusedKeywords(), 4);
				addFirstTerms(terms, profile.directEvidenceGroups(), 2);
				if (terms.size() >= 2) {
					String query = queryFromTerms(terms, 8);
					if (!query.isBlank()) {
						queries.add(query);
					}
				}
			}
		}
	}

	private static List<String> buildClarificationQuestions(String question, QuestionIntentProfile profile) {
		LinkedHashSet<String> questions = new LinkedHashSet<>();
		Set<String> entityIds = new LinkedHashSet<>(profile.entities().stream().map(QuestionEntity::id).toList());
		if (entityIds.contains("irm")) {
			questions.add("말씀하신 IRM이 정보자원관리시스템 IRM을 의미하는지, 그리고 권한·성과측정·등록절차 중 어떤 항목인지 함께 알려주세요.");
		}
		if (entityIds.contains("procurement_catalog")) {
			questions.add("조달청 디지털서비스몰 또는 종합쇼핑몰 구매 기준으로 계약방식을 확인하려는 것인지 알려주세요.");
		}
		if (entityIds.contains("project_review") && profile.intentTypes().contains("exception_scope")) {
			questions.add("확인하려는 사업이 상용SW 직접구매인지, 단순 H/W 도입인지, 일반 SW 개발사업인지 함께 알려주세요.");
		}
		if (entityIds.contains("pre_consultation")) {
			questions.add("국가기관·지자체·공공기관 중 어느 기관 기준의 사전협의인지 알려주세요.");
		}
		if (profile.intentTypes().contains("target_scope") && entityIds.isEmpty()) {
			questions.add("대상 여부를 확인할 제도명, 문서명, 기관명 중 하나를 함께 적어 주세요.");
		}
		if (profile.intentTypes().contains("contract_method") && !entityIds.contains("procurement_catalog")) {
			questions.add("계약 방식인지, 구매 경로인지, 예외 적용 여부인지 함께 지정해 주세요.");
		}
		if (questions.isEmpty() && isShortQuestion(question)) {
			questions.add("제도명이나 문서명을 조금 더 구체적으로 적어 주시면 근거를 다시 찾겠습니다.");
		}
		return questions.stream().limit(2).toList();
	}

	private static List<String> buildAnswerFocusInstructions(QuestionIntentProfile profile) {
		LinkedHashSet<String> instructions = new LinkedHashSet<>();
		List<String> intentTypes = new ArrayList<>(profile.intentTypes());
		for (QuestionEntity entity : profile.entities()) {
			for (String intentType : intentTypes) {
				addAll(instructions, QuestionIntentDictionary.values("answer_focus." + entity.id() + "." + intentType, List.of()));
			}
			addAll(instructions, QuestionIntentDictionary.values("answer_focus." + entity.id(), List.of()));
		}
		for (String intentType : intentTypes) {
			addAll(instructions, QuestionIntentDictionary.values("answer_focus." + intentType, List.of()));
		}
		return instructions.stream()
			.map(value -> value == null ? "" : value.trim())
			.filter(value -> !value.isBlank())
			.distinct()
			.limit(5)
			.toList();
	}

	private static boolean isShortQuestion(String question) {
		return KoreanQueryNormalizer.normalizeForMatch(question).length() <= 12;
	}

	private static List<String> prioritizedIntentTypes(String question, Set<String> intentTypes) {
		List<String> values = new ArrayList<>(intentTypes == null ? Set.of() : intentTypes);
		String normalized = KoreanQueryNormalizer.normalizeForMatch(question);
		values.sort((left, right) -> Integer.compare(intentPriority(left, normalized), intentPriority(right, normalized)));
		return values;
	}

	private static int intentPriority(String intentType, String normalizedQuestion) {
		if ("penalty".equals(intentType)
			&& hasAny(normalizedQuestion, "불이익", "미준수", "준수안", "준수하지", "위반", "제재", "처분", "조치")) {
			return 0;
		}
		if ("exception_scope".equals(intentType) && hasAny(normalizedQuestion, "안해도", "않아도", "제외", "비대상", "예외")) {
			return 0;
		}
		if ("period".equals(intentType) && hasAny(normalizedQuestion, "언제", "시기", "기한", "기간", "마감")) {
			return 0;
		}
		if ("target_scope".equals(intentType) && hasAny(normalizedQuestion, "대상", "포함", "해당")) {
			return 1;
		}
		if ("contract_method".equals(intentType) && hasAny(normalizedQuestion, "수의계약", "계약방식", "구매")) {
			return 1;
		}
		if ("purchase_channel".equals(intentType) && hasAny(normalizedQuestion, "디지털", "조달", "구매")) {
			return 2;
		}
		return 5;
	}

	private static boolean hasAny(String text, String... terms) {
		if (text == null || text.isBlank()) {
			return false;
		}
		for (String term : terms) {
			if (text.contains(KoreanQueryNormalizer.normalizeForMatch(term))) {
				return true;
			}
		}
		return false;
	}

	private static List<String> excludedHints(QuestionIntentProfile profile) {
		LinkedHashSet<String> hints = new LinkedHashSet<>(List.of("목차", "작성예시", "화면예시", "메뉴", "클릭"));
		if (profile.preferredSectionTypes().contains("target_scope")) {
			hints.add("제안서 평가 방법");
			hints.add("위원회 회의");
		}
		if (profile.preferredSectionTypes().contains("requirement")) {
			hints.add("작성 따라하기");
		}
		return List.copyOf(hints);
	}

	private static void addFirstTerms(Set<String> target, List<List<String>> groups) {
		if (groups == null) {
			return;
		}
		for (List<String> group : groups) {
			if (group != null && !group.isEmpty()) {
				target.add(group.get(0));
			}
		}
	}

	private static void addFirstTerms(Set<String> target, List<List<String>> groups, int limit) {
		if (groups == null || limit <= 0) {
			return;
		}
		int count = 0;
		for (List<String> group : groups) {
			if (group != null && !group.isEmpty() && addTerm(target, group.get(0))) {
				count++;
				if (count >= limit) {
					return;
				}
			}
		}
	}

	private static void addLimitedTerms(Set<String> target, Iterable<String> values, int limit) {
		if (values == null || limit <= 0) {
			return;
		}
		int count = 0;
		for (String value : values) {
			if (addTerm(target, value)) {
				count++;
				if (count >= limit) {
					return;
				}
			}
		}
	}

	private static boolean addTerm(Set<String> target, String value) {
		if (value == null) {
			return false;
		}
		String cleaned = value.replaceAll("\\s+", " ").trim();
		if (cleaned.length() < 2) {
			return false;
		}
		return target.add(cleaned);
	}

	private static String queryFromTerms(Set<String> terms, int limit) {
		if (terms == null || terms.isEmpty()) {
			return "";
		}
		return terms.stream()
			.map(value -> value == null ? "" : value.replaceAll("\\s+", " ").trim())
			.filter(value -> value.length() >= 2)
			.distinct()
			.limit(limit)
			.reduce((left, right) -> left + " " + right)
			.orElse("");
	}

	private static void addAll(Set<String> target, Iterable<String> values) {
		if (values == null) {
			return;
		}
		for (String value : values) {
			target.add(value);
		}
	}

	private static List<String> flatten(List<List<String>> groups) {
		if (groups == null || groups.isEmpty()) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (List<String> group : groups) {
			if (group != null) {
				values.addAll(group);
			}
		}
		return values;
	}

	private static List<String> cleanKeywords(Set<String> values, int limit) {
		return values.stream()
			.map(value -> value == null ? "" : value.trim())
			.filter(value -> value.length() >= 2)
			.distinct()
			.limit(limit)
			.toList();
	}

	private record VariantDraft(String id, String query, List<String> plannedKeywords) {
		private VariantDraft {
			id = id == null ? "" : id.trim();
			query = query == null ? "" : query.replaceAll("\\s+", " ").trim();
			plannedKeywords = plannedKeywords == null ? List.of() : List.copyOf(plannedKeywords);
		}
	}
}
