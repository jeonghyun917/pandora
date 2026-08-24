package com.kaces.pandora.common.text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DocumentSearchAnchorExtractor {
	private static final int MAX_TITLE_TERMS = 6;
	private static final int MAX_PROVISION_TERMS = 6;
	private static final int MAX_HEADING_TERMS = 8;
	private static final int MAX_EVIDENCE_TERMS = 18;
	private static final Pattern QUOTED_TITLE = Pattern.compile("[「『\\\"']\\s*([^」』\\\"']{2,100}?)\\s*[」』\\\"']");
	private static final Pattern TITLE_SUFFIX = Pattern.compile(
		"(?<![\\p{IsHangul}\\p{Alnum}])([\\p{IsHangul}\\p{Alnum}][\\p{IsHangul}\\p{Alnum}\\s]{0,70}?"
			+ "(?:법\\s*시행규칙|법\\s*시행령|시행규칙|시행령|규정|지침|고시|법))(?=$|[\\s\\p{Punct}]|은|는|이|가|을|를|의|에|에서|로|으로)");
	private static final Pattern ARTICLE = Pattern.compile("제\\s*(\\d+)\\s*조(?:\\s*의\\s*(\\d+))?");
	private static final Pattern APPENDIX = Pattern.compile("별표\\s*(\\d+)");
	private static final Pattern SECTION_HEADING = Pattern.compile(
		"(?:제\\s*\\d+\\s*조(?:\\s*의\\s*\\d+)?|별표\\s*\\d+)\\s*\\(([^)]+)\\)"
	);
	private static final Pattern ASCII_ACRONYM = Pattern.compile("[A-Za-z0-9]{2,8}");
	private static final List<String> GENERIC_ALIAS_SUFFIXES = List.of(
		"대상", "대상사업", "대상기관", "대상시스템", "적용대상", "범위", "절차", "방법", "신청", "요건"
	);

	private DocumentSearchAnchorExtractor() {
	}

	public static DocumentSearchAnchor extract(
		String question,
		QuestionIntentProfile profile,
		List<String> lexicalKeywords,
		List<String> focusedKeywords
	) {
		if (question == null || question.isBlank()) {
			return new DocumentSearchAnchor(
				List.of(), List.of(), List.of(), List.of(), List.of(),
				DocumentSearchAnchor.AnchorType.NONE,
				DocumentSearchAnchor.Status.INVALID
			);
		}
		QuestionIntentProfile effectiveProfile = profile == null ? QuestionIntentProfile.from(question) : profile;
		List<String> explicitTitles = explicitTitleTerms(question);
		List<String> provisions = provisionTerms(question);
		List<String> headings = headingTerms(question);
		List<String> stableAliases = explicitTitles.isEmpty() ? stableAliasTerms(question, effectiveProfile) : List.of();
		List<String> titles = explicitTitles.isEmpty() ? stableAliases : explicitTitles;
		DocumentSearchAnchor.AnchorType anchorType = anchorType(explicitTitles, stableAliases, provisions);
		DocumentSearchAnchor.Status status = anchorType == DocumentSearchAnchor.AnchorType.NONE
			? DocumentSearchAnchor.Status.NO_STRONG_ANCHOR
			: DocumentSearchAnchor.Status.ELIGIBLE;
		return new DocumentSearchAnchor(
			titles,
			provisions,
			headings,
			evidenceTerms(focusedKeywords, lexicalKeywords),
			cleanTerms(effectiveProfile.preferredTargets(), Integer.MAX_VALUE),
			anchorType,
			status
		);
	}

	private static DocumentSearchAnchor.AnchorType anchorType(
		List<String> explicitTitles,
		List<String> stableAliases,
		List<String> provisions
	) {
		if (!explicitTitles.isEmpty()) {
			return provisions.isEmpty()
				? DocumentSearchAnchor.AnchorType.EXPLICIT_TITLE
				: DocumentSearchAnchor.AnchorType.TITLE_WITH_PROVISION;
		}
		return stableAliases.isEmpty()
			? DocumentSearchAnchor.AnchorType.NONE
			: DocumentSearchAnchor.AnchorType.STABLE_ALIAS;
	}

	private static List<String> explicitTitleTerms(String question) {
		List<String> titles = new ArrayList<>();
		Matcher quoted = QUOTED_TITLE.matcher(question);
		while (quoted.find()) {
			titles.add(quoted.group(1));
		}
		Matcher suffix = TITLE_SUFFIX.matcher(question);
		while (suffix.find()) {
			titles.add(suffix.group(1));
		}
		return cleanTerms(titles, MAX_TITLE_TERMS);
	}

	private static List<String> provisionTerms(String question) {
		List<String> provisions = new ArrayList<>();
		Matcher articles = ARTICLE.matcher(question);
		while (articles.find()) {
			provisions.add("제" + articles.group(1) + "조" + (articles.group(2) == null ? "" : "의" + articles.group(2)));
		}
		Matcher appendices = APPENDIX.matcher(question);
		while (appendices.find()) {
			provisions.add("별표 " + appendices.group(1));
		}
		return cleanTerms(provisions, MAX_PROVISION_TERMS);
	}

	private static List<String> headingTerms(String question) {
		List<String> headings = new ArrayList<>();
		Matcher matcher = SECTION_HEADING.matcher(question);
		while (matcher.find()) {
			headings.add(matcher.group(1));
		}
		return cleanTerms(headings, MAX_HEADING_TERMS);
	}

	private static List<String> stableAliasTerms(String question, QuestionIntentProfile profile) {
		String normalizedQuestion = KoreanQueryNormalizer.normalizeForMatch(question);
		List<AliasMatch> matches = new ArrayList<>();
		for (QuestionEntity entity : profile.entities()) {
			for (String alias : entity.aliases()) {
				String normalizedAlias = KoreanQueryNormalizer.normalizeForMatch(alias);
				int position = normalizedQuestion.indexOf(normalizedAlias);
				if (position >= 0 && isStableAlias(alias, normalizedAlias)) {
					matches.add(new AliasMatch(alias, position, position + normalizedAlias.length()));
				}
			}
		}
		matches.sort(Comparator.comparingInt(AliasMatch::start)
			.thenComparing(Comparator.comparingInt(AliasMatch::length).reversed()));
		List<AliasMatch> selected = new ArrayList<>();
		for (AliasMatch match : matches) {
			if (selected.stream().noneMatch(selectedMatch -> selectedMatch.overlaps(match))) {
				selected.add(match);
			}
		}
		return cleanTerms(selected.stream().map(AliasMatch::alias).toList(), MAX_TITLE_TERMS);
	}

	private static boolean isStableAlias(String alias, String normalizedAlias) {
		return (normalizedAlias.length() >= 6 && GENERIC_ALIAS_SUFFIXES.stream().noneMatch(normalizedAlias::endsWith))
			|| ASCII_ACRONYM.matcher(alias == null ? "" : alias.trim()).matches();
	}

	private record AliasMatch(String alias, int start, int end) {
		private int length() {
			return end - start;
		}

		private boolean overlaps(AliasMatch other) {
			return start < other.end && other.start < end;
		}
	}

	private static List<String> evidenceTerms(List<String> focusedKeywords, List<String> lexicalKeywords) {
		List<String> terms = new ArrayList<>();
		if (focusedKeywords != null) {
			terms.addAll(focusedKeywords);
		}
		if (lexicalKeywords != null) {
			terms.addAll(lexicalKeywords);
		}
		return cleanTerms(terms, MAX_EVIDENCE_TERMS).stream()
			.filter(term -> !KoreanQueryNormalizer.isWeakQuestionTerm(term))
			.limit(MAX_EVIDENCE_TERMS)
			.toList();
	}

	private static List<String> cleanTerms(List<String> values, int max) {
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		LinkedHashSet<String> normalizedTerms = new LinkedHashSet<>();
		List<String> cleaned = new ArrayList<>();
		for (String value : values) {
			String display = String.valueOf(value == null ? "" : value).replaceAll("\\s+", " ").trim();
			String normalized = KoreanQueryNormalizer.normalizeForMatch(display);
			if (normalized.isBlank() || !normalizedTerms.add(normalized)) {
				continue;
			}
			cleaned.add(display);
			if (cleaned.size() == max) {
				break;
			}
		}
		return List.copyOf(cleaned);
	}
}
