package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionEntity;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class CompleteProcedureAnswerComposer {

	private static final Pattern STAGE_MARKER = Pattern.compile(
		"(?:^|[\\s↓→])([②③④⑤]|[2-5][.)])\\s*",
		Pattern.MULTILINE
	);
	private static final Set<String> GENERIC_TERMS = Set.of(
		"절차", "방법", "어떻게", "처리", "진행", "단계",
		"요청", "신청", "제출", "검토", "심사", "협의", "통보", "결과", "회신"
	);

	private CompleteProcedureAnswerComposer() {
	}

	static String compose(String question, List<LawAiAnswerGround> grounds) {
		QuestionIntentProfile profile = QuestionIntentProfile.from(question);
		if (!profile.intentTypes().contains("procedure") || grounds == null || grounds.isEmpty()) {
			return null;
		}
		for (LawAiAnswerGround ground : grounds) {
			if (ground == null || !"direct".equalsIgnoreCase(String.valueOf(ground.evidenceRole()))) {
				continue;
			}
			String source = preferredSource(ground);
			if (source.isBlank() || !alignsWithQuestionDomain(ground, source, profile)) {
				continue;
			}
			String procedure = extractCompleteProcedure(source);
			if (procedure != null) {
				return "절차는 다음 순서입니다.\n" + procedure;
			}
		}
		return null;
	}

	private static String preferredSource(LawAiAnswerGround ground) {
		for (String value : List.of(
			nullToEmpty(ground.matchedChildText()),
			nullToEmpty(ground.snippet()),
			nullToEmpty(ground.parentContextText())
		)) {
			if (!value.isBlank()) {
				return value;
			}
		}
		return "";
	}

	private static String extractCompleteProcedure(String source) {
		List<Stage> stages = new ArrayList<>();
		Matcher matcher = STAGE_MARKER.matcher(source);
		while (matcher.find()) {
			stages.add(new Stage(stageNumber(matcher.group(1)), matcher.start(1), matcher.end()));
		}
		for (int index = 0; index < stages.size(); index++) {
			Stage request = stages.get(index);
			if (request.number() != 2) {
				continue;
			}
			Stage review = nextStage(stages, index + 1, 3);
			Stage notification = review == null ? null : nextStage(stages, stages.indexOf(review) + 1, 4);
			if (review == null || notification == null) {
				continue;
			}
			Stage following = nextStage(stages, stages.indexOf(notification) + 1, 5);
			int end = following == null ? source.length() : following.start();
			String value = clean(source.substring(request.start(), end));
			String normalized = normalize(value);
			if (containsAny(normalized, "검토요청", "검토신청", "검토를요청", "신청서")
				&& containsAny(normalized, "보안성검토", "검토수행", "적절성검토", "검토실시")
				&& containsAny(normalized, "결과통보", "검토결과통보", "결과회신", "결과서송부")) {
				return value;
			}
		}
		return null;
	}

	private static Stage nextStage(List<Stage> stages, int from, int number) {
		for (int index = from; index < stages.size(); index++) {
			Stage stage = stages.get(index);
			if (stage.number() == number) {
				return stage;
			}
			if (stage.number() > number) {
				return null;
			}
		}
		return null;
	}

	private static boolean alignsWithQuestionDomain(
		LawAiAnswerGround ground,
		String source,
		QuestionIntentProfile profile
	) {
		String haystack = normalize(String.join(" ",
			nullToEmpty(ground.title()),
			nullToEmpty(ground.chunkTitle()),
			source
		));
		if (profile.entities() != null && !profile.entities().isEmpty()) {
			return profile.entities().stream().allMatch(entity -> matchesEntity(haystack, entity));
		}
		List<String> anchors = profile.terms().stream()
			.map(CompleteProcedureAnswerComposer::normalize)
			.filter(term -> term.length() >= 2 && !GENERIC_TERMS.contains(term))
			.toList();
		return anchors.isEmpty() || anchors.stream().anyMatch(haystack::contains);
	}

	private static boolean matchesEntity(String text, QuestionEntity entity) {
		List<String> names = new ArrayList<>();
		if (entity.label() != null) {
			names.add(entity.label());
		}
		if (entity.aliases() != null) {
			names.addAll(entity.aliases());
		}
		return names.stream()
			.map(CompleteProcedureAnswerComposer::normalize)
			.filter(value -> value.length() >= 2)
			.anyMatch(text::contains);
	}

	private static int stageNumber(String marker) {
		return switch (marker.charAt(0)) {
			case '②', '2' -> 2;
			case '③', '3' -> 3;
			case '④', '4' -> 4;
			case '⑤', '5' -> 5;
			default -> -1;
		};
	}

	private static boolean containsAny(String text, String... phrases) {
		for (String phrase : phrases) {
			if (text.contains(normalize(phrase))) {
				return true;
			}
		}
		return false;
	}

	private static String clean(String value) {
		return value
			.replace('↓', '\n')
			.replaceAll("[ \\t]+", " ")
			.replaceAll(" *\\R *", "\n")
			.replaceAll("(?:\\.\\.\\.|…)+$", "")
			.trim();
	}

	private static String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(nullToEmpty(value)).replaceAll("\\s+", "");
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private record Stage(int number, int start, int contentStart) {
	}
}
