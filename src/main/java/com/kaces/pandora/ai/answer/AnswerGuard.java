package com.kaces.pandora.ai.answer;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class AnswerGuard {

	private static final Pattern CITATION_GROUP = Pattern.compile(
		"\\[\\s*((?:(?:근거|출처|문서)?\\s*\\d+\\s*(?:번)?\\s*)(?:[,，、/]\\s*(?:근거|출처|문서)?\\s*\\d+\\s*(?:번)?\\s*)*)\\]"
	);
	private static final Pattern CITATION_NUMBER = Pattern.compile("\\d+");
	private static final String INSUFFICIENT_EVIDENCE_MESSAGE =
		"제공된 근거만으로는 답변을 확정하기 어렵습니다. 관련 법령명이나 문서 범위를 더 구체적으로 입력해 주세요.";

	// 메소드 설명: guard 처리 흐름을 수행합니다.
	public String guard(String answer, List<LawAiAnswerGround> grounds) {
		List<LawAiAnswerGround> safeGrounds = grounds == null ? List.of() : grounds;
		if (safeGrounds.isEmpty()) {
			return INSUFFICIENT_EVIDENCE_MESSAGE;
		}
		if (answer == null || answer.isBlank()) {
			return INSUFFICIENT_EVIDENCE_MESSAGE;
		}

		Set<Integer> validCitationNumbers = validCitationNumbers(safeGrounds);
		String guarded = answer.replace("\r\n", "\n").trim();
		guarded = removeInvalidCitations(guarded, validCitationNumbers);
		guarded = normalizeDashes(guarded);
		guarded = softenFinality(guarded);
		guarded = trimBlankLines(guarded);

		if (guarded.isBlank()) {
			return INSUFFICIENT_EVIDENCE_MESSAGE;
		}
		if (!containsValidCitation(guarded, validCitationNumbers)) {
			guarded = appendPrimaryCitation(guarded, safeGrounds);
		}
		return guarded;
	}

	// 메소드 설명: validCitationNumbers 처리 흐름을 수행합니다.
	private Set<Integer> validCitationNumbers(List<LawAiAnswerGround> grounds) {
		Set<Integer> validNumbers = new LinkedHashSet<>();
		for (int i = 0; i < grounds.size(); i++) {
			int number = grounds.get(i).number();
			validNumbers.add(number > 0 ? number : i + 1);
		}
		return validNumbers;
	}

	// 메소드 설명: removeInvalidCitations 처리 흐름을 수행합니다.
	private String removeInvalidCitations(String answer, Set<Integer> validCitationNumbers) {
		Matcher matcher = CITATION_GROUP.matcher(answer);
		StringBuffer result = new StringBuffer();
		while (matcher.find()) {
			String replacement = normalizeCitationGroup(matcher.group(1), validCitationNumbers);
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}
		matcher.appendTail(result);
		return result.toString()
			.replaceAll("[ \\t]+([,.!?])", "$1")
			.replaceAll("[ \\t]+\\n", "\n")
			.trim();
	}

	// 메소드 설명: normalizeCitationGroup 처리 흐름을 수행합니다.
	private String normalizeCitationGroup(String rawNumbers, Set<Integer> validCitationNumbers) {
		Set<Integer> keptNumbers = new LinkedHashSet<>();
		Matcher numberMatcher = CITATION_NUMBER.matcher(rawNumbers);
		while (numberMatcher.find()) {
			try {
				int number = Integer.parseInt(numberMatcher.group());
				if (validCitationNumbers.contains(number)) {
					keptNumbers.add(number);
				}
			} catch (NumberFormatException ignored) {
				// Regex already limits this to digits, but keep parsing defensive.
			}
		}
		if (keptNumbers.isEmpty()) {
			return "";
		}
		return "[" + String.join(", ", keptNumbers.stream().map(String::valueOf).toList()) + "]";
	}

	// 메소드 설명: softenFinality 처리 흐름을 수행합니다.
	private String softenFinality(String answer) {
		return answer
			.replace("법률 자문입니다", "법률 자문은 아니며, 제공된 근거 기준의 안내입니다")
			.replace("최종 판단입니다", "제공된 근거 기준의 판단입니다")
			.replace("문제가 없습니다", "제공된 근거만으로는 문제가 확인되지 않습니다")
			.replace("위법하지 않습니다", "제공된 근거만으로는 위법하다고 단정하기 어렵습니다");
	}

	// 메소드 설명: normalizeDashes 처리 흐름을 수행합니다.
	private String normalizeDashes(String answer) {
		return answer
			.replaceAll("\\s+[—–ㅡ]\\s+", ". ")
			.replaceAll("\\s+-\\s+", ". ")
			.replaceAll("\\.{2,}", ".")
			.replaceAll("\\.\\s*([,，])", "$1")
			.trim();
	}

	// 메소드 설명: trimBlankLines 처리 흐름을 수행합니다.
	private String trimBlankLines(String value) {
		return value
			.replaceAll("\\n{3,}", "\n\n")
			.trim();
	}

	// 메소드 설명: containsValidCitation 처리 흐름을 수행합니다.
	private boolean containsValidCitation(String answer, Set<Integer> validCitationNumbers) {
		Matcher matcher = CITATION_GROUP.matcher(answer);
		while (matcher.find()) {
			Matcher numberMatcher = CITATION_NUMBER.matcher(matcher.group(1));
			while (numberMatcher.find()) {
				try {
					if (validCitationNumbers.contains(Integer.parseInt(numberMatcher.group()))) {
						return true;
					}
				} catch (NumberFormatException ignored) {
					return false;
				}
			}
		}
		return false;
	}

	// 메소드 설명: appendPrimaryCitation 처리 흐름을 수행합니다.
	private String appendPrimaryCitation(String answer, List<LawAiAnswerGround> grounds) {
		int primaryNumber = grounds.get(0).number() > 0 ? grounds.get(0).number() : 1;
		return answer.trim() + " [" + primaryNumber + "]";
	}
}
