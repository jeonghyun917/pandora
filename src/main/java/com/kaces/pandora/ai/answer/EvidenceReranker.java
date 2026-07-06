package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.List;

final class EvidenceReranker {

	double score(LawSemanticChunkRow chunk, QuestionIntentProfile profile) {
		if (chunk == null || profile == null) {
			return 0.0;
		}
		String title = normalize(chunk.title() + " " + chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		String body = normalize(chunk.chunkText());
		String text = title + " " + body;
		double score = 0.0;

		int titleTermMatches = countTerms(title, profile.terms());
		int bodyTermMatches = countTerms(body, profile.terms());
		int conceptMatches = countGroups(text, profile.conceptGroups());
		int intentMatches = countGroups(text, profile.intentGroups());
		int directMatches = countGroups(text, profile.directEvidenceGroups());
		int bodyDirectMatches = countGroups(body, profile.directEvidenceGroups());
		int bodyQuestionAnchoredDirectMatches = countQuestionAnchoredDirectGroups(body, profile);
		int focusedMatches = countTerms(text, profile.focusedKeywords());
		int requiredDirectMatches = Math.min(2, profile.directEvidenceGroups().size());

		score += titleTermMatches * 0.12;
		score += bodyTermMatches * 0.045;
		score += conceptMatches * 0.28;
		score += intentMatches * 0.18;
		score += directMatches * 0.42;
		score += bodyDirectMatches * 0.62;
		if (bodyQuestionAnchoredDirectMatches > 0) {
			score += 1.65 + Math.min(0.65, bodyQuestionAnchoredDirectMatches * 0.25);
		}
		else if (hasQuestionAnchors(profile) && directMatches > 0) {
			score -= 0.45;
		}
		score += focusedMatches * 0.08;
		if (requiredDirectMatches > 0) {
			if (bodyDirectMatches >= requiredDirectMatches) {
				score += 1.2;
			}
			else if (directMatches < requiredDirectMatches) {
				score -= 0.28;
			}
		}

		if (isPreferredTarget(chunk.target(), profile.preferredTargets())) {
			score += 0.32;
		}
		if (profile.prefersSection(chunk.sectionType())) {
			score += 0.45;
			if (requiredDirectMatches > 0 && bodyDirectMatches >= requiredDirectMatches) {
				score += 0.42;
			}
		}
		if (!profile.preferredSectionTypes().isEmpty()
			&& chunk.sectionType() != null
			&& !chunk.sectionType().isBlank()
			&& !profile.prefersSection(chunk.sectionType())) {
			score -= 0.16;
		}
		if (!profile.terms().isEmpty() && titleTermMatches + bodyTermMatches == 0) {
			score -= 0.65;
		}
		if (!profile.conceptGroups().isEmpty() && conceptMatches == 0) {
			score -= 0.55;
		}
		if (hasNearEvidence(body, profile.conceptGroups(), profile.intentGroups(), 120)
			|| hasNearEvidence(body, profile.conceptGroups(), profile.directEvidenceGroups(), 120)) {
			score += 0.38;
		}
		if (isLikelyNavigationOrExampleNoise(title, body)) {
			score -= 0.75;
		}
		if (EvidenceNoiseClassifier.shouldSuppressAsEvidence(chunk, profile.normalizedQuestion())) {
			score -= 1.45;
		}
		if (EvidenceNoiseClassifier.shouldDownrankAsContextOnly(chunk)) {
			score -= 0.55;
		}
		if (isShortOrEmptyBody(body)) {
			score -= 0.25;
		}
		if (isDocumentTitleAnchor(title, profile)) {
			score += 0.24;
		}
		return score;
	}

	private int countQuestionAnchoredDirectGroups(String body, QuestionIntentProfile profile) {
		if (body == null || body.isBlank() || profile == null || profile.directEvidenceGroups().isEmpty()) {
			return 0;
		}
		List<String> anchors = questionAnchorTerms(profile);
		if (anchors.isEmpty()) {
			return 0;
		}
		int count = 0;
		boolean strictAnchorMatching = requiresStrictQuestionAnchoredDirectMatching(profile);
		for (List<String> group : profile.directEvidenceGroups()) {
			List<String> anchoredTerms = strictAnchorMatching
				? anchoredDirectEvidenceTerms(group, anchors)
				: (containsAny(group, anchors) ? group : List.<String>of());
			if (!anchoredTerms.isEmpty() && containsAny(body, anchoredTerms)) {
				count++;
			}
		}
		return count;
	}

	private boolean requiresStrictQuestionAnchoredDirectMatching(QuestionIntentProfile profile) {
		String question = normalize(profile.normalizedQuestion());
		return question.contains("하드웨어")
			|| question.contains("hw")
			|| question.contains("hardware")
			|| question.contains("appliance");
	}

	private List<String> anchoredDirectEvidenceTerms(List<String> group, List<String> anchors) {
		if (group == null || group.isEmpty() || anchors == null || anchors.isEmpty()) {
			return List.of();
		}
		java.util.ArrayList<String> anchoredTerms = new java.util.ArrayList<>();
		for (String groupTerm : group) {
			String normalizedGroupTerm = normalize(groupTerm);
			if (normalizedGroupTerm.isBlank()) {
				continue;
			}
			for (String anchor : anchors) {
				String normalizedAnchor = normalize(anchor);
				if (normalizedAnchor.length() < 2 || isWeakAnchorTerm(normalizedAnchor)) {
					continue;
				}
				if (normalizedGroupTerm.contains(normalizedAnchor) || normalizedAnchor.contains(normalizedGroupTerm)) {
					anchoredTerms.add(groupTerm);
					break;
				}
			}
		}
		return anchoredTerms;
	}

	private boolean hasQuestionAnchors(QuestionIntentProfile profile) {
		return !questionAnchorTerms(profile).isEmpty();
	}

	private List<String> questionAnchorTerms(QuestionIntentProfile profile) {
		String normalizedQuestion = normalize(profile.normalizedQuestion());
		java.util.LinkedHashSet<String> anchors = new java.util.LinkedHashSet<>();
		for (String term : profile.terms()) {
			String normalized = normalize(term);
			if (!isWeakAnchorTerm(normalized)) {
				anchors.add(normalized);
			}
		}
		if (normalizedQuestion.contains("하드웨어") || normalizedQuestion.contains("hw") || normalizedQuestion.contains("appliance")) {
			anchors.add("하드웨어");
			anchors.add("단순hw");
			anchors.add("hw");
			anchors.add("appliance");
		}
		return anchors.stream().toList();
	}

	private boolean isWeakAnchorTerm(String term) {
		return term == null
			|| term.length() < 2
			|| term.equals("사업")
			|| term.equals("대상")
			|| term.equals("해야")
			|| term.equals("가능")
			|| term.equals("필요")
			|| term.equals("범위")
			|| term.equals("포함")
			|| term.equals("해당");
	}

	private boolean isPreferredTarget(String target, List<String> preferredTargets) {
		if (target == null || target.isBlank() || preferredTargets == null || preferredTargets.isEmpty()) {
			return false;
		}
		return preferredTargets.stream().anyMatch(target::equals);
	}

	private boolean isDocumentTitleAnchor(String title, QuestionIntentProfile profile) {
		if (title.isBlank() || profile.terms().isEmpty()) {
			return false;
		}
		int matches = countTerms(title, profile.terms());
		return matches >= Math.min(2, profile.terms().size());
	}

	private boolean isShortOrEmptyBody(String body) {
		return body == null || body.length() < 60;
	}

	private boolean isLikelyNavigationOrExampleNoise(String title, String body) {
		String text = title + " " + body;
		return text.contains("contents")
			|| text.contains("목차")
			|| text.contains("작성예시")
			|| text.contains("작성 예시")
			|| text.contains("따라하기")
			|| text.contains("화면예시")
			|| text.contains("메뉴")
			|| text.contains("클릭")
			|| text.contains("입력한다");
	}

	private int countTerms(String text, List<String> terms) {
		if (text == null || text.isBlank() || terms == null || terms.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (String term : terms) {
			String normalized = normalize(term);
			if (!normalized.isBlank() && text.contains(normalized)) {
				count++;
			}
		}
		return count;
	}

	private int countGroups(String text, List<List<String>> groups) {
		if (text == null || text.isBlank() || groups == null || groups.isEmpty()) {
			return 0;
		}
		int count = 0;
		for (List<String> group : groups) {
			if (containsAny(text, group)) {
				count++;
			}
		}
		return count;
	}

	private boolean hasNearEvidence(String body, List<List<String>> leftGroups, List<List<String>> rightGroups, int distance) {
		if (body == null || body.isBlank() || leftGroups == null || rightGroups == null
			|| leftGroups.isEmpty() || rightGroups.isEmpty()) {
			return false;
		}
		for (List<String> leftGroup : leftGroups) {
			for (String left : leftGroup) {
				int leftIndex = body.indexOf(normalize(left));
				if (leftIndex < 0) {
					continue;
				}
				for (List<String> rightGroup : rightGroups) {
					for (String right : rightGroup) {
						int rightIndex = body.indexOf(normalize(right));
						if (rightIndex >= 0 && Math.abs(leftIndex - rightIndex) <= distance) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private boolean containsAny(String text, List<String> terms) {
		if (text == null || terms == null || terms.isEmpty()) {
			return false;
		}
		return terms.stream()
			.map(this::normalize)
			.anyMatch(term -> !term.isBlank() && text.contains(term));
	}

	private boolean containsAny(List<String> group, List<String> anchors) {
		if (group == null || group.isEmpty() || anchors == null || anchors.isEmpty()) {
			return false;
		}
		return !anchoredDirectEvidenceTerms(group, anchors).isEmpty();
	}

	private String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(HwpxTextCleaner.clean(value == null ? "" : value));
	}
}
