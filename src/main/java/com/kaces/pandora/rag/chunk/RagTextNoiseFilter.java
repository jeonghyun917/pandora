package com.kaces.pandora.rag.chunk;

import java.util.regex.Pattern;

public final class RagTextNoiseFilter {
	private static final Pattern DOT_LEADER = Pattern.compile("\\.{5,}");
	private static final Pattern SHORT_PUBLICATION_FOOTER = Pattern.compile(
		"(?i).*(©|copyright|all\\s*rights\\s*reserved|oecd\\s*\\d{4}|national\\s*tax\\s*service|www\\.[a-z0-9._-]+\\.[a-z]{2,}).*"
	);
	private static final Pattern PAGE_MARKED_TABLE_UNIT = Pattern.compile(
		"(?iu)^\\s*[-–—]?\\s*\\d{1,4}\\s*[-–—]?\\s*[-–—]?\\s*.{0,80}\\(\\s*단위\\s*[:：][^)]+\\).*$"
	);

	private RagTextNoiseFilter() {
	}

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

	public static boolean isMeaninglessSection(String title, String text) {
		String combined = ((title == null ? "" : title) + "\n" + (text == null ? "" : text)).trim();
		if (combined.isBlank()) {
			return true;
		}
		if (isDecorativeShortFragment(combined)) {
			return true;
		}
		String compact = combined.replaceAll("\\s+", "");
		String semantic = compact.replaceAll("[\\p{L}\\p{N}]", "");
		int meaningfulChars = compact.length() - semantic.length();
		if (meaningfulChars < 8 && compact.length() < 80) {
			return true;
		}
		if (compact.length() < 80 && compact.matches("^법제처\\d+국가법령정보센터[\\p{L}\\p{N}]+$")) {
			return true;
		}
		return compact.matches("(?i)^(p\\.?\\d+)?[\\^()\\[\\].,;:ㆍ·\\-0-9]+$");
	}

	private static boolean isDecorativeShortFragment(String value) {
		String visible = String.valueOf(value == null ? "" : value)
			.replaceAll("<[^>]+>", " ")
			.replace("&nbsp;", " ")
			.replace("&lt;", " ")
			.replace("&gt;", " ")
			.replace("&amp;", " ")
			.replaceAll("\\s+", " ")
			.trim();
		if (visible.isBlank()) {
			return true;
		}
		String compact = visible.replaceAll("\\s+", "");
		if (compact.length() <= 1) {
			return true;
		}
		if (visible.length() <= 140 && SHORT_PUBLICATION_FOOTER.matcher(visible).matches()) {
			return true;
		}
		if (visible.length() <= 140 && PAGE_MARKED_TABLE_UNIT.matcher(visible).matches()) {
			return true;
		}
		return compact.matches("^[0-9]+$")
			|| compact.matches("^[\\p{Punct}·ㆍ\\-_/\\\\|]+$")
			|| compact.matches("^[\\p{Punct}·ㆍ\\-_/\\\\|0-9]+$")
			|| visible.matches("(?iu)^\\s*[-–—]\\s*\\d{1,4}\\s*[-–—]\\s*[-–—]\\s*.{1,50}\\s*[-–—]\\s*$");
	}

	private static int countDotLeaders(String value) {
		return (int) DOT_LEADER.matcher(value).results().count();
	}

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
