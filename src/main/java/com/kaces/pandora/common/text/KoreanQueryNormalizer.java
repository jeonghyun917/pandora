package com.kaces.pandora.common.text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class KoreanQueryNormalizer {

	private KoreanQueryNormalizer() {
	}

	public static String normalizeForMatch(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("[^\\p{IsHangul}\\p{Alnum}]", "")
			.toLowerCase();
	}

	public static String normalizeQueryTerm(String term) {
		String normalized = normalizeForMatch(term);
		String previous;
		do {
			previous = normalized;
			normalized = stripQuestionSuffix(stripTrailingJosa(stripQuestionSuffix(stripIntentSuffix(normalized))));
		} while (!previous.equals(normalized));
		return normalized;
	}

	public static boolean isWeakQuestionTerm(String value) {
		String normalized = normalizeForMatch(value);
		return normalized.isBlank() || Set.of(
			"알려줘",
			"알수있어",
			"알수있나요",
			"어떻게",
			"어떤",
			"어디",
			"어디까지",
			"가능",
			"가능해",
			"가능한가",
			"가능한가요",
			"가능하나요",
			"가능한지",
			"무엇",
			"무슨",
			"뭐야",
			"뭔가요",
			"뭔지",
			"이란",
			"란",
			"정의",
			"질문",
			"유형",
			"새로운",
			"한걸",
			"하는게",
			"해야해",
			"해야하나",
			"해야하나요",
			"적어야해",
			"확인",
			"확인하는게",
			"확인해",
			"확인하나",
			"확인하나요",
			"확인하는지",
			"여부",
			"있나",
			"있나요",
			"되나요",
			"된다",
			"있어",
			"관련",
			"대해",
			"대한"
		).contains(normalized);
	}

	public static String stripQuestionSuffix(String term) {
		if (term == null || term.length() < 3) {
			return term;
		}
		for (String suffix : List.of(
			"이라는건뭐야", "라는건뭐야", "이라는거뭐야", "라는거뭐야",
			"이라는게뭐야", "라는게뭐야", "이란건뭐야", "란건뭐야",
			"이란게뭐야", "란게뭐야", "이란거뭐야", "란거뭐야",
			"이라는건", "라는건", "이라는거야", "라는거야",
			"이라는거", "라는거", "이라는게", "라는게",
			"이라는것", "라는것", "이란건", "란건",
			"이란거야", "란거야", "이란거", "란거",
			"이란게", "란게", "이란", "란",
			"무엇인가요", "무엇인가", "무엇인지", "뭔가요", "뭔지",
			"뭐야", "무엇", "정의"
		)) {
			if (term.endsWith(suffix) && term.length() > suffix.length() + 1) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	public static String stripTrailingJosa(String term) {
		if (term == null || term.length() < 3) {
			return term;
		}
		for (String protectedTerm : List.of("과업심의", "사전협의")) {
			if (term.equals(protectedTerm)) {
				return term;
			}
			if (term.length() == protectedTerm.length() + 1 && term.startsWith(protectedTerm)) {
				return protectedTerm;
			}
		}
		for (String suffix : List.of("으로", "에서", "에게", "까지", "부터", "하고", "하면", "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도")) {
			if (term.endsWith(suffix) && term.length() > suffix.length() + 1) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	public static String stripIntentSuffix(String term) {
		if (term == null || term.length() < 4) {
			return term;
		}
		for (String suffix : List.of(
			"대상사업", "대상시스템", "대상기관", "적용대상", "필수요소",
			"검토내용", "추진절차", "신청방법", "제출서류",
			"가능하나요", "가능한가요", "가능한가", "가능한지", "가능해", "가능",
			"언제하나요", "언제해요", "언제인지", "언제해", "언제",
			"어디까지", "시기", "기한", "기간",
			"대상", "시스템", "사업", "기관", "요소", "항목", "절차", "방법", "서류",
			"인가요", "인가", "인지", "일까요", "일까", "건가요", "건가",
			"하는게", "한걸", "정의", "이란", "란"
		)) {
			if (term.endsWith(suffix) && term.length() > suffix.length() + 2) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	public static List<String> expandSearchKeywords(String term) {
		String normalized = normalizeQueryTerm(term);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> keywords = new ArrayList<>();
		keywords.add(normalized);
		addKnownSpacingVariants(normalized, keywords);
		if (normalized.endsWith("위원회") && normalized.length() > "위원회".length() + 1) {
			String subject = normalized.substring(0, normalized.length() - "위원회".length());
			if (subject.length() >= 3) {
				keywords.add("국가" + subject + "전략위원회");
				keywords.add(subject + "전략위원회");
				keywords.add(subject + "윤리위원회");
				keywords.add(subject + "전문위원회");
				keywords.add(subject + "심의위원회");
				keywords.add(subject + "보호위원회");
			}
		}
		return keywords.stream()
			.filter(keyword -> keyword.length() >= 2)
			.distinct()
			.toList();
	}

	private static void addKnownSpacingVariants(String normalized, List<String> keywords) {
		if (normalized.contains("보안성검토")) {
			keywords.add("보안성 검토");
			keywords.add("정보화사업 보안성 검토");
			keywords.add("보안성 검토 개요");
			keywords.add("보안성 검토 대상");
		}
		if (normalized.contains("사전협의")) {
			keywords.add("사전 협의");
		}
		if (normalized.contains("과업심의")) {
			keywords.add("과업 심의");
		}
		if (normalized.contains("업무성과계획")) {
			keywords.add("업무 성과 계획");
		}
		if (normalized.contains("성과측정")) {
			keywords.add("성과 측정");
		}
	}

	public static List<List<String>> conceptGroupsForTerm(String term) {
		String normalized = normalizeQueryTerm(term);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<List<String>> groups = new ArrayList<>();
		List<String> exactTerms = new ArrayList<>();
		exactTerms.add(normalized);
		if (normalized.endsWith("위원회") && normalized.length() > "위원회".length() + 1) {
			String subject = normalized.substring(0, normalized.length() - "위원회".length());
			if (subject.length() >= 3) {
				exactTerms.add("국가" + subject + "전략위원회");
				exactTerms.add(subject + "전략위원회");
				exactTerms.add(subject + "윤리위원회");
				exactTerms.add(subject + "전문위원회");
				exactTerms.add(subject + "심의위원회");
				exactTerms.add(subject + "보호위원회");
			}
		}
		groups.add(exactTerms.stream().distinct().toList());
		return groups;
	}
}
