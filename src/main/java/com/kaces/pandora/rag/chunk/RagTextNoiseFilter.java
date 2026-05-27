package com.kaces.pandora.rag.chunk;

import java.util.regex.Pattern;

public final class RagTextNoiseFilter {
	private static final Pattern DOT_LEADER = Pattern.compile("\\.{5,}");

	// 메소드 설명: RagTextNoiseFilter 처리 흐름을 수행합니다.
	private RagTextNoiseFilter() {
	}

	// 메소드 설명: isTableOfContents 처리 흐름을 수행합니다.
	public static boolean isTableOfContents(String title, String text) {
		String combined = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).toLowerCase();
		int dotLeaders = countDotLeaders(combined);
		if (combined.contains("contents") && dotLeaders > 0) {
			return true;
		}
		if (combined.contains("목차") && dotLeaders > 0) {
			return true;
		}
		return dotLeaders >= 4 && countPageNumberLines(combined) >= 3;
	}

	// 메소드 설명: isMeaninglessSection 처리 흐름을 수행합니다.
	public static boolean isMeaninglessSection(String title, String text) {
		String combined = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).trim();
		if (combined.isBlank()) {
			return true;
		}
		String compact = combined.replaceAll("\\s+", "");
		String semantic = compact.replaceAll("[\\p{L}\\p{N}]", "");
		int meaningfulChars = compact.length() - semantic.length();
		if (meaningfulChars < 8 && compact.length() < 80) {
			return true;
		}
		return compact.matches("(?i)^(p\\.?\\d+)?[\\^()\\[\\].,;:ㆍ·\\-0-9]+$");
	}

	// 메소드 설명: countDotLeaders 처리 흐름을 수행합니다.
	private static int countDotLeaders(String value) {
		return (int) DOT_LEADER.matcher(value).results().count();
	}

	// 메소드 설명: countPageNumberLines 처리 흐름을 수행합니다.
	private static int countPageNumberLines(String value) {
		int count = 0;
		for (String line : value.split("\\R")) {
			String trimmed = line.trim();
			if (trimmed.matches(".*\\.{5,}\\s*\\d+\\s*$")) {
				count++;
			}
		}
		return count;
	}
}
