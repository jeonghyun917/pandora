package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;

/** A numbered exclusion inherits only its own verified parent predicate. */
final class SourceOwnedConsultationExclusion {
	private static final Pattern INTRO = Pattern.compile(
		"제4조\\s*\\(대상사업\\)\\s*①\\s*(사전협의의\\s+대상사업은[^.!?①-⑳]{1,240}\\.)");
	private static final String EXCLUSION = "제1항에 따른 대상사업 중 다음 각 호의 사업은 제외한다.";
	private static final String PROVISO = "다만, 제1호부터 제3호까지 중 어느 하나에 해당하는 사업으로서 해당 연도에 신규로 정보화를 추진하는 사업은 그러하지 아니하다.";
	private static final String HEAD = EXCLUSION + " " + PROVISO;
	private static final Pattern PARAGRAPH = Pattern.compile("②\\s*" + Pattern.quote(HEAD).replace(" ", "\\E\\s+\\Q")
		+ "\\s*(?<list>[^①-⑳]+)(?=③|$)");
	private static final Pattern ITEM = Pattern.compile("(?<![0-9])([1-9][0-9]?)[.]\\s+");
	private static final Pattern PROJECT = Pattern.compile("[가-힣()A-Za-z, ]{3,140}사업");
	private static final Pattern CLAIM = Pattern.compile("^(.{3,150}?)(?:은|는)\\s+사전협의\\s+대상에서\\s+제외(?:된다|됩니다)[.]?$");
	private static final Pattern QUESTION = Pattern.compile("^(.{3,150}?)(?:은|는)\\s+"
		+ "(?:전자정부\\s+성과관리\\s+지침\\s+제4조의\\s+)?"
		+ "사전협의\\s+대상에서\\s+제외(?:되나요|됩니까)[?？]?$");

	private SourceOwnedConsultationExclusion() {}

	record Rule(int owner, String subject, String literal) {
		boolean matches(String claim) {
			if (claim == null || claim.length() > 220) return false;
			var candidate = CLAIM.matcher(claim.strip());
			return candidate.matches() && compact(candidate.group(1)).equals(subject);
		}

		boolean answers(String question, ClaimVerifier.ClaimEvidenceLink link) {
			if (question == null || question.length() > 240 || link.groundNumber() != owner || !matches(link.claim())
				|| !literal.equals(link.evidenceSentence())) return false;
			return matchesQuestion(question);
		}

		boolean matchesQuestion(String question) {
			var requested = QUESTION.matcher(question.strip());
			return requested.matches() && compact(requested.group(1)).equals(subject);
		}
	}

	static boolean sourceAnswers(String question, LawSemanticChunkRow row) {
		if (question == null || question.length() > 240 || !QUESTION.matcher(question.strip()).matches()
			|| row == null || !"PASS".equals(row.qualityStatus())) return false;
		return index(List.of(asGround(row))).stream().anyMatch(rule -> rule.matchesQuestion(question));
	}

	static boolean sourceOwnsScope(LawSemanticChunkRow row) {
		return row != null && "PASS".equals(row.qualityStatus()) && !scopePremises(asGround(row)).isEmpty();
	}

	private static LawAiAnswerGround asGround(LawSemanticChunkRow row) {
		return new LawAiAnswerGround(1, row.chunkId(), row.documentId(), row.target(), row.title(),
			null, null, row.sourceDate(), row.effectiveStatus(), row.chunkNo(), row.chunkTitle(),
			null, row.chunkText(), row.sourcePath(), row.sourceUrl(), 0);
	}

	static List<Rule> index(List<LawAiAnswerGround> grounds) {
		if (grounds == null || grounds.size() > 64) return List.of();
		var owners = new HashSet<Integer>();
		var rules = new ArrayList<Rule>();
		for (var ground : grounds) {
			if (ground == null) continue;
			if (!owners.add(ground.number())) return List.of();
			String source = ground.matchedChildText();
			if (ground.number() <= 0 || ground.documentId() <= 0 || ground.chunkId() <= 0 || source == null
				|| source.length() > 5000 || !"admrul".equals(ground.target()) || !"CURRENT".equals(ground.effectiveStatus())
				|| !"direct".equals(ground.evidenceRole()) || !"전자정부성과관리지침".equals(compact(ground.title()))) continue;
			var intro = INTRO.matcher(source.stripLeading());
			if (!intro.lookingAt()) continue;
			var paragraph = PARAGRAPH.matcher(source);
			if (paragraph.find() && paragraph.start() == source.indexOf('②'))
				append(rules, ground.number(), intro.group(1), paragraph.group("list"));
		}
		return List.copyOf(rules);
	}

	private static void append(List<Rule> rules, int owner, String intro, String list) {
		var starts = boundaries(list);
		for (int i = 3; i < starts.size(); i++) {
			int end = i + 1 == starts.size() ? list.length() : starts.get(i + 1);
			String item = list.substring(starts.get(i), end).strip();
			var token = ITEM.matcher(item);
			if (!token.lookingAt()) continue;
			String subject = item.substring(token.end()).strip().replaceAll("\\s+", " ");
			String key = compact(subject).replace("(ApplicationProgrammingInterface,이하API)", "(API)");
			if (!PROJECT.matcher(subject).matches() || key.contains("다만") || key.contains("제외")) continue;
			rules.add(new Rule(owner, key, intro + "\n" + HEAD + "\n" + item));
		}
	}

	private static List<Integer> boundaries(String list) {
		List<Integer> starts = new ArrayList<>();
		var tokens = ITEM.matcher(list);
		while (tokens.find()) {
			if (Integer.parseInt(tokens.group(1)) != starts.size() + 1) return List.of();
			starts.add(tokens.start());
		}
		return starts.size() == 15 && list.substring(0, starts.get(0)).isBlank() ? starts : List.of();
	}

	private static String compact(String text) {
		return text == null ? "" : text.replaceAll("\\s+", "");
	}

	static List<String> scopePremises(LawAiAnswerGround ground) {
		if (ground == null || !"admrul".equals(ground.target()) || !"CURRENT".equals(ground.effectiveStatus())
			|| !"전자정부성과관리지침".equals(compact(ground.title()))) return List.of();
		var child = ground.matchedChildText();
		if (child == null || child.length() > 5000) return List.of();
		var intro = INTRO.matcher(child.stripLeading());
		var paragraph = PARAGRAPH.matcher(child);
		if (!intro.lookingAt() || !paragraph.find() || paragraph.start() != child.indexOf('②')) return List.of();
		var items = boundaries(paragraph.group("list"));
		return items.isEmpty() ? List.of() : List.of(intro.group(1), EXCLUSION, PROVISO);
	}
}
