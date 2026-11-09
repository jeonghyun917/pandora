package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Binds a literal nominal action to the subject of its closed numbered duty list. */
final class SourceOwnedNumberedDuty {
	private static final Pattern OWNER = Pattern.compile(
		"(?m)^[ \\t]*(?:제[0-9]+조(?:의[0-9]+)?[ \\t]*\\([^\\r\\n()]{1,80}\\)[ \\t]+)?①[ \\t]+"
			+ "([가-힣]{2,30})(은|는)[ \\t]+([^①-⑳※*]{1,340}?)다음[ \\t]+각[ \\t]+호의[ \\t]+"
			+ "안전성[ \\t]+확보[ \\t]+조치를[ \\t]+해야[ \\t]+한다\\."
			+ "(?:[ \\t]+<개정[ 0-9.,]+>)?[ \\t]*\\R(?=[ \\t]*1[.][ \\t])");
	private static final Pattern ITEM = Pattern.compile("(?m)^[ \\t]*([1-9][0-9]?)[.][ \\t]+");
	private static final Pattern NEXT_PARAGRAPH = Pattern.compile("(?m)^[ \\t]*②[ \\t]+");
	private static final Pattern STORAGE = Pattern.compile(
		"^([가-힣]{2,30})(?:와|과) ([가-힣]{2,30})의 분리 보관\\.");

	private SourceOwnedNumberedDuty() {}

	record Rule(int owner, String actor, String particle, String first, String second, String literal) {
		boolean matches(String claim) {
			return claim != null && claim.length() <= 250
				&& canonical(claim).matches(Pattern.quote(canonical(actor + particle + second))
				+ "(?:을|를)" + Pattern.quote(first) + "(?:와|과)분리하여보관해야(?:한다|합니다)[.]?");
		}
	}

	static List<Rule> index(List<LawAiAnswerGround> grounds) {
		if (grounds == null || grounds.size() > 64) return List.of();
		var result = new ArrayList<Rule>();
		var owners = new HashSet<Integer>();
		for (var ground : grounds) {
			if (ground == null) continue;
			if (!owners.add(ground.number())) return List.of();
			if (ground.number() <= 0 || ground.chunkId() <= 0 || ground.documentId() <= 0
				|| !"direct".equals(ground.evidenceRole())
				|| !isSupportedTarget(ground.target())) continue;
			appendRules(result, ground.number(), ground.matchedChildText());
		}
		return List.copyOf(result);
	}

	static boolean provesSeparateStorage(String target, String matchedChild) {
		if (!isSupportedTarget(target)) return false;
		var rules = new ArrayList<Rule>();
		appendRules(rules, 0, matchedChild);
		return rules.stream().anyMatch(rule -> rule.first().equals("가명정보") && rule.second().equals("추가정보"));
	}

	private static boolean isSupportedTarget(String target) {
		return target != null && List.of("law", "admrul", "official_doc").contains(target);
	}

	private static void appendRules(List<Rule> result, int ownerNumber, String source) {
		if (source == null || source.length() > 4000) return;
		var owner = OWNER.matcher(source);
		while (owner.find()) {
			if (owner.group(3).matches("(?s).*\\R[ \\t]*\\R.*")
				|| owner.group(3).matches("(?s).*(?:[.!?]|다만|예외|경우|한정|제외|불구하고|아닌|아니|않는|없이).*")) continue;
			var next = NEXT_PARAGRAPH.matcher(source);
			int end = next.find(owner.end()) ? next.start() : source.length();
			String list = source.substring(owner.end(), end).stripTrailing();
			if (list.matches("(?s).*(?:\\R[ \\t]*\\R|[①-⑳※*□○]).*")) continue;
			var item = ITEM.matcher(list);
			var starts = new ArrayList<Integer>();
			var ends = new ArrayList<Integer>();
			int number = 1;
			while (item.find() && Integer.parseInt(item.group(1)) == number++) {
				starts.add(item.start());
				ends.add(item.end());
			}
			if (starts.size() != 3 || number != 4 || starts.get(0) != 0) continue;
			String itemText = list.substring(ends.get(1), starts.get(2)).replaceAll("\\s+", " ").trim();
			var storage = STORAGE.matcher(itemText);
			if (!storage.find()) continue;
			String first = storage.group(1), second = storage.group(2);
			String scope = canonical(owner.group(3));
			if (first.equals(second) || !scope.endsWith("에대하여")
				|| !scope.contains(first) || !scope.contains(second)) continue;
			String tail = canonical(itemText.substring(storage.end()));
			if (!tail.isEmpty() && !tail.matches("다만,?" + Pattern.quote(second)
				+ "(?:이|가)불필요한경우에는" + Pattern.quote(second) + "(?:을|를)파기해야한다[.]")) continue;
			result.add(new Rule(ownerNumber, owner.group(1), owner.group(2), first, second, storage.group()));
		}
	}

	private static String canonical(String value) { return value.replaceAll("\\s+", ""); }
}
