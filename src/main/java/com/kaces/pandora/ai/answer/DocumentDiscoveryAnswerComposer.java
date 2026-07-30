package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DocumentDiscoveryAnswerComposer {

	private static final int MAX_DOCUMENTS = 8;

	private DocumentDiscoveryAnswerComposer() {
	}

	static String compose(String question, List<LawAiAnswerGround> grounds) {
		QuestionIntentProfile profile = QuestionIntentProfile.from(question);
		if (!profile.documentDiscoveryQuestion()
			|| grounds == null
			|| grounds.isEmpty()) {
			return null;
		}
		List<LawAiAnswerGround> ordered = DocumentDiscoveryPolicy.orderGrounds(question, grounds).stream()
			.filter(ground -> !clean(ground.title()).isBlank())
			.limit(MAX_DOCUMENTS)
			.toList();
		if (ordered.isEmpty()) {
			return null;
		}

		StringBuilder answer = new StringBuilder("관련 문서 검색 결과입니다.\n\n");
		for (LawAiAnswerGround ground : ordered) {
			answer.append(ground.number())
				.append(". [")
				.append(DocumentDiscoveryPolicy.sourceLabel(ground.target()))
				.append("] ")
				.append(clean(ground.title()));
			String agency = clean(ground.agencyName());
			if (!agency.isBlank()) {
				answer.append(" — ").append(agency);
			}
			answer.append(" [근거 ")
				.append(ground.number())
				.append("]\n");
		}
		List<String> topics = clarificationTopics(profile);
		if (topics.isEmpty()) {
			answer.append("\n원하는 쟁점(적용 대상·요건·절차·기한·예외)을 입력하면 관련 조문을 확인할 수 있습니다.");
		} else {
			answer.append("\n확인할 주제를 입력해 주세요: ")
				.append(String.join(" · ", topics));
		}
		return answer.toString();
	}

	private static List<String> clarificationTopics(QuestionIntentProfile profile) {
		Set<String> aliases = profile.entities().stream()
			.flatMap(entity -> entity.aliases().stream())
			.map(KoreanQueryNormalizer::normalizeForMatch)
			.filter(alias -> alias.length() >= 4)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		return profile.focusedKeywords().stream()
			.map(DocumentDiscoveryAnswerComposer::clean)
			.filter(value -> value.length() >= 2 && value.length() <= 20)
			.filter(value -> !value.matches(".*\\d.*"))
			.filter(value -> !containsDocumentOrAgencyCue(value))
			.filter(value -> {
				String normalized = KoreanQueryNormalizer.normalizeForMatch(value);
				return aliases.stream().noneMatch(normalized::contains);
			})
			.distinct()
			.limit(5)
			.toList();
	}

	private static boolean containsDocumentOrAgencyCue(String value) {
		String normalized = KoreanQueryNormalizer.normalizeForMatch(value);
		return List.of(
			"가이드", "가이드라인", "안내서", "해설서", "매뉴얼",
			"법령", "법률", "행정규칙", "위원회", "부처", "기관"
		).stream().anyMatch(normalized::contains);
	}

	private static String clean(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("\\s+", " ")
			.trim();
	}
}
