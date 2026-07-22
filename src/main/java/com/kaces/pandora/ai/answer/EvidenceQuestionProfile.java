package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

record EvidenceQuestionProfile(
	String normalizedQuestion,
	List<String> terms,
	List<String> requiredTerms,
	List<List<String>> conceptGroups,
	List<List<String>> intentGroups,
	List<List<String>> directEvidenceGroups,
	Set<String> preferredSectionTypes,
	boolean definitionQuestion
) {

	EvidenceQuestionProfile {
		normalizedQuestion = String.valueOf(normalizedQuestion == null ? "" : normalizedQuestion);
		terms = immutableList(terms);
		requiredTerms = immutableList(requiredTerms);
		conceptGroups = immutableGroups(conceptGroups);
		intentGroups = immutableGroups(intentGroups);
		directEvidenceGroups = immutableGroups(directEvidenceGroups);
		preferredSectionTypes = preferredSectionTypes == null ? Set.of() : Set.copyOf(preferredSectionTypes);
	}

	boolean prefersSection(String sectionType) {
		return sectionType != null && preferredSectionTypes.contains(sectionType);
	}

	int requiredConceptMatches() {
		if (conceptGroups.isEmpty()) {
			return 0;
		}
		return conceptGroups.size() >= 2 ? 2 : 1;
	}

	int requiredDirectEvidenceMatches() {
		if (directEvidenceGroups.isEmpty()) {
			return 0;
		}
		return Math.min(2, directEvidenceGroups.size());
	}

	boolean committeeQuestion() {
		return terms.stream().anyMatch(term -> term.endsWith("위원회") || term.endsWith("협의회"));
	}

	boolean trafficCrosswalkStopQuestion() {
		return (normalizedQuestion.contains("횡단보도") || normalizedQuestion.contains("보행자"))
			&& (normalizedQuestion.contains("우회전") || normalizedQuestion.contains("운전") || normalizedQuestion.contains("차"))
			&& (containsStopLike(normalizedQuestion)
				|| normalizedQuestion.contains("해야")
				|| normalizedQuestion.contains("하나")
				|| normalizedQuestion.contains("되나"));
	}

	List<String> preferredCommitteeTerms() {
		List<String> preferredTerms = new ArrayList<>();
		for (String term : terms) {
			if (!term.endsWith("위원회")
				|| term.contains("전략위원회")
				|| term.startsWith("국가")
				|| term.length() <= "위원회".length() + 1) {
				continue;
			}
			String subject = term.substring(0, term.length() - "위원회".length());
			if (subject.length() >= 3) {
				preferredTerms.add("국가" + subject + "전략위원회");
				preferredTerms.add(subject + "전략위원회");
			}
		}
		return preferredTerms.stream()
			.map(EvidenceQuestionProfile::normalize)
			.distinct()
			.toList();
	}

	boolean requiresStrongIntentSignal() {
		return normalizedQuestion.contains("대상")
			|| normalizedQuestion.contains("안해도")
			|| normalizedQuestion.contains("해야")
			|| normalizedQuestion.contains("필요")
			|| normalizedQuestion.contains("면제")
			|| normalizedQuestion.contains("제외")
			|| normalizedQuestion.contains("포함")
			|| normalizedQuestion.contains("해당")
			|| normalizedQuestion.contains("필수")
			|| normalizedQuestion.contains("요소")
			|| normalizedQuestion.contains("항목")
			|| normalizedQuestion.contains("절차")
			|| normalizedQuestion.contains("방법")
			|| normalizedQuestion.contains("서류")
			|| normalizedQuestion.contains("금액")
			|| normalizedQuestion.contains("비용")
			|| normalizedQuestion.contains("방안")
			|| normalizedQuestion.contains("활성화")
			|| isTemporalQuestion(normalizedQuestion)
			|| isScopeQuestion(normalizedQuestion);
	}

	List<String> strongIntentCues() {
		List<String> cues = new ArrayList<>();
		if (normalizedQuestion.contains("대상")) {
			cues.addAll(List.of(
				"대상은", "대상이다", "대상이된다", "대상사업은", "적용대상", "대상기관", "검토대상",
				"지원대상", "표준화대상", "적용범위", "제공대상", "포함한다", "해당한다", "제외한다",
				"제외대상", "비대상"
			));
		}
		if (normalizedQuestion.contains("제외") || normalizedQuestion.contains("포함") || normalizedQuestion.contains("해당")) {
			cues.addAll(List.of("제외한다", "제외대상", "비대상", "포함한다", "해당한다", "해당하지", "볼수없는"));
		}
		if (normalizedQuestion.contains("필수") || normalizedQuestion.contains("요소") || normalizedQuestion.contains("항목")) {
			cues.addAll(List.of("명시하여야", "기재사항", "필수", "포함하여야", "요구사항", "평가요소", "평가방법", "다음각호의사항"));
		}
		if (normalizedQuestion.contains("절차") || normalizedQuestion.contains("방법")) {
			cues.addAll(List.of("절차", "방법", "신청", "제출", "검토", "통보", "요청", "처리"));
		}
		if (KoreanQueryNormalizer.isProcurementCatalogContractQuestion(normalizedQuestion)) {
			cues.addAll(List.of("상용sw직접구매", "상용소프트웨어직접구매", "직접구매", "수의계약", "계약방법", "계약방식", "구매계약"));
		}
		if (normalizedQuestion.contains("서류")) {
			cues.addAll(List.of("서류", "신청서", "제출서류", "첨부", "증빙자료"));
		}
		if (normalizedQuestion.contains("금액") || normalizedQuestion.contains("비용")) {
			cues.addAll(List.of("금액", "비용", "만원", "대가", "지급", "산정"));
		}
		if (normalizedQuestion.contains("방안") || normalizedQuestion.contains("활성화")) {
			cues.addAll(List.of(
				"활성화에필요한사업", "기본목표와추진방향", "개방전략수립", "지원하는사업", "이용인식제고",
				"지원범위", "기본계획"
			));
		}
		if (isTemporalQuestion(normalizedQuestion)) {
			cues.addAll(List.of("기간은", "기한은", "평가기간", "기간내", "월말까지", "일까지", "마감", "시기"));
		}
		if (isScopeQuestion(normalizedQuestion)) {
			cues.addAll(List.of("할수있다", "가능하다", "가능", "허용", "인정", "범위", "한도", "보호조치", "지원"));
		}
		if (cues.isEmpty()) {
			cues.addAll(intentGroups.stream().flatMap(List::stream).toList());
		}
		return cues.stream()
			.map(EvidenceQuestionProfile::normalize)
			.filter(cue -> cue.length() >= 2)
			.distinct()
			.toList();
	}

	private static boolean containsStopLike(String normalized) {
		return normalized.contains("멈추")
			|| normalized.contains("멈춰")
			|| normalized.contains("정지")
			|| normalized.contains("일시정지")
			|| normalized.contains("서야")
			|| normalized.contains("세워");
	}

	private static boolean isTemporalQuestion(String normalized) {
		if (normalized.contains("어디까지")) {
			return false;
		}
		return normalized.contains("언제")
			|| normalized.contains("시기")
			|| normalized.contains("일정")
			|| normalized.contains("기한")
			|| normalized.contains("기간")
			|| normalized.contains("마감")
			|| normalized.contains("몇월")
			|| normalized.contains("몇일")
			|| normalized.contains("며칠")
			|| normalized.contains("까지");
	}

	private static boolean isScopeQuestion(String normalized) {
		return normalized.contains("어디까지")
			|| normalized.contains("범위")
			|| normalized.contains("한도")
			|| normalized.contains("어느정도")
			|| normalized.contains("얼마나")
			|| normalized.contains("가능");
	}

	private static String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(String.valueOf(value == null ? "" : value));
	}

	private static List<String> immutableList(List<String> values) {
		return values == null ? List.of() : List.copyOf(values);
	}

	private static List<List<String>> immutableGroups(List<List<String>> groups) {
		if (groups == null || groups.isEmpty()) {
			return List.of();
		}
		return groups.stream()
			.filter(group -> group != null && !group.isEmpty())
			.map(List::copyOf)
			.toList();
	}
}
