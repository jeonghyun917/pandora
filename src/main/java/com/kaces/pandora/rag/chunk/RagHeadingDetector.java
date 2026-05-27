package com.kaces.pandora.rag.chunk;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

final class RagHeadingDetector {
	private static final Pattern STRUCTURAL_HEADING_START = Pattern.compile(
		"^\\s*(?:"
			+ "[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\\s*[.)]|"
			+ "\\d{1,3}\\s*[.)]|"
			+ "제\\s*\\d+(?:의\\d+)?\\s*[장절조]|"
			+ "[가-하]\\s*[.)]|"
			+ "[①②③④⑤⑥⑦⑧⑨⑩⑪⑫⑬⑭⑮⑯⑰⑱⑲⑳]|"
			+ "[▣□■●○◆◇※]|"
			+ "\\[[^\\]]{1,40}\\]|"
			+ "붙임\\s*\\d*|별첨\\s*\\d*|서식\\s*\\d*"
			+ ").*"
	);
	private static final List<String> GENERAL_HEADING_TERMS = List.of(
		"개요",
		"목적",
		"대상",
		"절차",
		"방법",
		"기준",
		"예외",
		"유의사항",
		"제출서류",
		"작성예시",
		"검토결과"
	);
	private static final List<String> DOMAIN_HEADING_TERMS = List.of(
		"사전협의",
		"과업심의",
		"적용대상",
		"대상사업",
		"대상기관",
		"보안성검토",
		"제안요청서",
		"정보화사업",
		"필수항목",
		"공공소프트웨어"
	);

	boolean isHeadingLine(String line) {
		return assess(line).accepted();
	}

	Optional<String> bestHeading(List<String> lines) {
		String bestLine = "";
		int bestScore = Integer.MIN_VALUE;
		for (String line : lines) {
			HeadingAssessment assessment = assess(line);
			if (assessment.accepted() && assessment.score() > bestScore) {
				bestLine = normalize(line);
				bestScore = assessment.score();
			}
		}
		return bestLine.isBlank() ? Optional.empty() : Optional.of(bestLine);
	}

	HeadingAssessment assess(String value) {
		String line = normalize(value);
		if (line.isBlank() || line.length() > 120) {
			return new HeadingAssessment(false, 0);
		}

		boolean structural = STRUCTURAL_HEADING_START.matcher(line).matches();
		boolean generalTerm = containsAnyTerm(line, GENERAL_HEADING_TERMS);
		boolean domainTerm = containsAnyTerm(line, DOMAIN_HEADING_TERMS);
		int score = 0;
		if (structural) {
			score += 2;
		}
		if (line.length() <= 80) {
			score += 1;
		}
		if (endsLikeSentence(line)) {
			score -= 2;
		} else {
			score += 1;
		}
		if (generalTerm) {
			score += 1;
		}
		if (domainTerm) {
			score += 1;
		}
		if (line.length() > 100) {
			score -= 2;
		}
		if (commaCount(line) >= 2) {
			score -= 2;
		}
		if (line.contains("?") || line.contains("？")) {
			score -= 2;
		}

		return new HeadingAssessment(score >= 3 && (structural || generalTerm || domainTerm), score);
	}

	private String normalize(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceFirst("(?i)^p\\.?\\s*\\d+\\s*", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private boolean containsAnyTerm(String line, List<String> terms) {
		String compact = line.replaceAll("\\s+", "");
		return terms.stream().anyMatch(compact::contains);
	}

	private boolean endsLikeSentence(String line) {
		return line.matches(".*(?:다|니다|한다|합니다|된다|됩니다|함|임)[.!?。]?$")
			|| line.matches(".*요[.!?。]$")
			|| line.matches(".*[.!?。]$");
	}

	private int commaCount(String line) {
		int count = 0;
		for (int i = 0; i < line.length(); i++) {
			char ch = line.charAt(i);
			if (ch == ',' || ch == '，' || ch == '、') {
				count++;
			}
		}
		return count;
	}

	record HeadingAssessment(boolean accepted, int score) {
	}
}
