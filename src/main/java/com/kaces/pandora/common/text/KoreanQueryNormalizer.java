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
			"위한",
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
			"방안",
			"유형",
			"새로운",
			"한걸",
			"하는게",
			"받아야해",
			"받아야하나",
			"받아야하나요",
			"받아야하는지",
			"해야해",
			"해야하나",
			"해야하나요",
			"하나",
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
			"대한",
			"만으로",
			"만으로도"
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
			"뭐야", "무엇", "정의",
			"받아야하나요", "받아야하나", "받아야해요", "받아야해",
			"해야하나요", "해야하나", "해야해요", "해야해", "하나요", "하나"
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
		for (String protectedTerm : List.of(
			"과업심의",
			"사전협의",
			"횡단보도",
			"공공데이터",
			"수의계약",
			"직접구매",
			"디지털서비스몰",
			"디지털카탈로그",
			"디지털카달로그",
			"종합쇼핑몰",
			"상용소프트웨어",
			"상용sw"
		)) {
			if (term.equals(protectedTerm)) {
				return term;
			}
			if (term.length() == protectedTerm.length() + 1 && term.startsWith(protectedTerm)) {
				return protectedTerm;
			}
		}
		for (String suffix : List.of(
			"이라고", "라고",
			"으로", "에서", "에게", "까지", "부터", "하고", "하면",
			"은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도"
		)) {
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
			"할때에는", "할때는", "할때에", "할때", "하는때", "때에는", "때는", "때에",
			"받아야하나요", "받아야하나", "받아야해요", "받아야해", "받아야",
			"해야하나요", "해야하나", "해야해요", "해야해", "해야", "하나요", "하나",
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
		if (containsProcurementCatalogCue(normalized)) {
			addProcurementCatalogKeywords(keywords);
		}
		if (normalized.endsWith("위원회") && normalized.length() > "위원회".length() + 1) {
			String subject = normalized.substring(0, normalized.length() - "위원회".length());
			if (subject.length() >= 3) {
				keywords.add("국가" + subject + "전략위원회");
				keywords.add(subject + "전략위원회");
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
			keywords.add("사전협의의 대상사업");
			keywords.add("사전협의 대상사업");
			keywords.add("대상기관이 추진하는 모든 정보화사업");
			keywords.add("예산과목 및 계약방식과 관계없이");
		}
		if (normalized.contains("기타공공기관") || normalized.contains("기타공공")) {
			keywords.add("기타공공기관");
			keywords.add("공공기관");
			keywords.add("중앙·공공기관");
			keywords.add("중앙 공공기관");
			keywords.add("대상기관");
			keywords.add("대상기관이 추진하는 모든 정보화사업");
		}
		if (normalized.contains("사전협의") && (normalized.contains("공공기관") || normalized.contains("대상"))) {
			keywords.add("사전협의의 대상사업");
			keywords.add("사전협의 대상사업");
			keywords.add("대상기관이 추진하는 모든 정보화사업");
			keywords.add("예산과목 및 계약방식과 관계없이");
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
		if (normalized.contains("우회전")) {
			keywords.add("우회전");
			keywords.add("교차로 통행방법");
			keywords.add("교차로통행방법");
		}
		if (normalized.contains("횡단보도")) {
			keywords.add("횡단보도");
			keywords.add("보행자 보호의무");
			keywords.add("보행자보호의무");
			keywords.add("보행자");
		}
		if (normalized.contains("공공데이터베이스") || normalized.contains("표준화")) {
			keywords.add("공공데이터베이스 표준화");
			keywords.add("공공데이터베이스 표준화 관리 매뉴얼");
			keywords.add("표준용어");
			keywords.add("표준도메인");
			keywords.add("데이터 표준");
			keywords.add("품질관리 진단");
			keywords.add("예방적 품질관리");
		}
		if (normalized.contains("전처리")) {
			keywords.add("데이터 전처리");
			keywords.add("데이터 전처리 절차");
			keywords.add("오류 원인 분석");
			keywords.add("대상 선정");
			keywords.add("방법 결정");
		}
		if (normalized.contains("가명정보") || normalized.contains("추가정보")) {
			keywords.add("가명정보");
			keywords.add("가명정보 처리");
			keywords.add("추가정보");
			keywords.add("분리보관");
			keywords.add("분리하여 보관");
			keywords.add("파기");
		}
		if (normalized.contains("영상정보") || normalized.contains("cctv")) {
			keywords.add("고정형 영상정보처리기기");
			keywords.add("설치 목적");
			keywords.add("촬영범위");
			keywords.add("촬영시간");
			keywords.add("보관기간");
			keywords.add("30일 이내");
		}
		if (isStopLikeTerm(normalized)) {
			keywords.add("정지");
			keywords.add("일시정지");
			keywords.add("정지하여야");
			keywords.add("정지하거나");
			keywords.add("멈추");
		}
		if (normalized.contains("운전")) {
			keywords.add("운전");
			keywords.add("운전자");
			keywords.add("차의 운전자");
		}
	}

	public static boolean isProcurementCatalogContractQuestion(String value) {
		String normalized = normalizeForMatch(value);
		return containsProcurementCatalogCue(normalized) && containsProcurementContractCue(normalized);
	}

	public static List<String> procurementCatalogKeywords(String value) {
		String normalized = normalizeForMatch(value);
		if (!containsProcurementCatalogCue(normalized) && !containsProcurementContractCue(normalized)) {
			return List.of();
		}
		List<String> keywords = new ArrayList<>();
		addProcurementCatalogKeywords(keywords);
		keywords.add("수의계약");
		keywords.add("계약방법");
		keywords.add("계약 방식");
		keywords.add("구매계약");
		return keywords.stream()
			.filter(keyword -> keyword.length() >= 2)
			.distinct()
			.toList();
	}

	public static List<String> procurementCatalogFocusedKeywords(String value) {
		String normalized = normalizeForMatch(value);
		if (!containsProcurementCatalogCue(normalized) && !containsProcurementContractCue(normalized)) {
			return List.of();
		}
		List<String> keywords = new ArrayList<>();
		boolean asksContractMethod = normalized.contains("수의계약")
			|| normalized.contains("계약방식")
			|| normalized.contains("계약방법");
		if (normalized.contains("디지털") || normalized.contains("카탈로그") || normalized.contains("카달로그")) {
			keywords.add("디지털서비스몰");
		}
		if (normalized.contains("종합쇼핑몰")) {
			keywords.add("조달청 종합쇼핑몰");
		}
		if (asksContractMethod) {
			keywords.add("수의계약");
		}
		else {
			keywords.add("상용SW 직접구매");
		}
		return keywords.stream()
			.distinct()
			.limit(2)
			.toList();
	}

	public static List<List<String>> procurementCatalogConceptGroups(String value) {
		String normalized = normalizeForMatch(value);
		if (!containsProcurementCatalogCue(normalized) && !containsProcurementContractCue(normalized)) {
			return List.of();
		}
		return List.of(
			List.of("조달청", "나라장터", "종합쇼핑몰", "조달청종합쇼핑몰", "디지털서비스몰", "디지털서비스", "디지털카탈로그", "디지털카달로그", "카탈로그", "카달로그"),
			List.of("상용SW직접구매", "상용소프트웨어직접구매", "상용SW 직접구매", "상용소프트웨어 직접구매", "직접구매", "상용SW", "상용소프트웨어"),
			List.of("수의계약", "계약방법", "계약방식", "구매계약", "조달계약")
		);
	}

	private static void addProcurementCatalogKeywords(List<String> keywords) {
		keywords.add("디지털서비스몰");
		keywords.add("디지털 서비스몰");
		keywords.add("디지털카탈로그");
		keywords.add("디지털카달로그");
		keywords.add("디지털 카탈로그");
		keywords.add("디지털 카달로그");
		keywords.add("조달청 종합쇼핑몰");
		keywords.add("종합쇼핑몰");
		keywords.add("나라장터");
		keywords.add("상용SW 직접구매");
		keywords.add("상용소프트웨어 직접구매");
		keywords.add("상용 SW 직접구매");
		keywords.add("직접구매");
		keywords.add("상용SW");
		keywords.add("상용소프트웨어");
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
			}
		}
		groups.add(exactTerms.stream().distinct().toList());
		if (normalized.contains("우회전")) {
			groups.add(List.of("우회전", "교차로통행방법", "교차로 통행방법"));
		}
		if (normalized.contains("횡단보도")) {
			groups.add(List.of("횡단보도", "보행자보호의무", "보행자 보호의무", "보행자"));
		}
		if (isStopLikeTerm(normalized)) {
			groups.add(List.of("정지", "일시정지", "정지하여야", "정지하거나", "멈추"));
		}
		if (normalized.contains("운전")) {
			groups.add(List.of("운전", "운전자", "차의운전자", "차의 운전자"));
		}
		if (containsProcurementCatalogCue(normalized)) {
			groups.add(List.of("조달청", "나라장터", "종합쇼핑몰", "디지털서비스몰", "디지털카탈로그", "디지털카달로그", "카탈로그", "카달로그"));
			groups.add(List.of("상용SW직접구매", "상용소프트웨어직접구매", "직접구매", "상용SW", "상용소프트웨어"));
		}
		if (containsProcurementContractCue(normalized)) {
			groups.add(List.of("수의계약", "계약방법", "계약방식", "구매계약", "조달계약"));
		}
		return groups;
	}

	private static boolean containsProcurementCatalogCue(String normalized) {
		return containsAny(normalized, List.of(
			"조달청",
			"나라장터",
			"종합쇼핑몰",
			"디지털서비스몰",
			"디지털서비스",
			"디지털카탈로그",
			"디지털카달로그",
			"카탈로그",
			"카달로그"
		));
	}

	private static boolean containsProcurementContractCue(String normalized) {
		return containsAny(normalized, List.of(
			"수의계약",
			"계약",
			"구매",
			"직접구매",
			"상용sw",
			"상용소프트웨어"
		));
	}

	private static boolean containsAny(String normalized, List<String> terms) {
		if (normalized == null || normalized.isBlank()) {
			return false;
		}
		for (String term : terms) {
			if (normalized.contains(normalizeForMatch(term))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isStopLikeTerm(String normalized) {
		return normalized.contains("멈추")
			|| normalized.contains("멈춰")
			|| normalized.contains("정지")
			|| normalized.contains("서야")
			|| normalized.contains("세워")
			|| normalized.contains("서야하")
			|| normalized.contains("일시정지");
	}
}
