package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class DocumentIdentityAnswerComposer {

	private static final Set<String> GENERIC_DOCUMENT_TERMS = Set.of(
		"문서", "공식문서", "자료", "파일",
		"가이드", "가이드라인", "매뉴얼", "안내서", "해설서", "보고서", "보도자료",
		"찾아", "찾아줘", "찾아주세요", "알려줘", "알려주세요"
	);

	private DocumentIdentityAnswerComposer() {
	}

	static String compose(String question, List<LawAiAnswerGround> grounds) {
		QuestionIntentProfile profile = QuestionIntentProfile.from(question);
		if (!profile.documentIdentityQuestion() || grounds == null || grounds.isEmpty()) {
			return null;
		}
		List<String> titleAnchors = titleAnchors(profile);
		if (titleAnchors.isEmpty()) {
			return null;
		}
		for (LawAiAnswerGround ground : grounds) {
			if (ground == null || !titleMatchesAnchors(ground.title(), titleAnchors)) {
				continue;
			}
			String title = String.valueOf(ground.title() == null ? "" : ground.title())
				.replaceAll("\\s+", " ")
				.trim();
			if (!title.isBlank()) {
				return "찾으시는 문서는 “" + title + "”입니다.";
			}
		}
		return null;
	}

	static List<String> titleAnchors(QuestionIntentProfile profile) {
		if (profile == null) {
			return List.of();
		}
		LinkedHashSet<String> anchors = new LinkedHashSet<>();
		for (String term : profile.terms()) {
			String normalized = KoreanQueryNormalizer.normalizeQueryTerm(term);
			if (normalized.length() < 2
				|| GENERIC_DOCUMENT_TERMS.contains(normalized)
				|| KoreanQueryNormalizer.isWeakQuestionTerm(normalized)) {
				continue;
			}
			anchors.add(normalized);
		}
		return List.copyOf(anchors);
	}

	static boolean titleMatchesAnchors(String title, List<String> anchors) {
		String normalizedTitle = KoreanQueryNormalizer.normalizeForMatch(title);
		if (normalizedTitle.isBlank() || anchors == null || anchors.isEmpty()) {
			return false;
		}
		long matched = anchors.stream()
			.map(KoreanQueryNormalizer::normalizeQueryTerm)
			.filter(anchor -> anchor.length() >= 2 && normalizedTitle.contains(anchor))
			.count();
		return matched >= Math.min(2, anchors.size());
	}
}
