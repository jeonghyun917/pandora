package com.kaces.pandora.ai.answer;

import java.util.regex.Pattern;

final class LawArticleOwnership {
	static final String REFERENCE = "제[0-9]+조(?:의[0-9]+)?(?:제[0-9]+항)?";
	private static final Pattern HEADING = Pattern.compile(
		"(?m)^[\\h]*(제[0-9]+조(?:의[0-9]+)?)[\\h]*\\([^\\r\\n)]{1,120}\\)");
	private static final Pattern TITLE = Pattern.compile("^(제[0-9]+조(?:의[0-9]+)?)(?=\\s|\\(|$)");
	private static final Pattern PARAGRAPH = Pattern.compile("제([0-9]+)항$");

	private LawArticleOwnership() {}

	static String article(String reference) {
		return PARAGRAPH.matcher(reference).replaceFirst("");
	}

	static boolean owns(String reference, String heading, String body) {
		String article = article(reference);
		var paragraph = PARAGRAPH.matcher(reference);
		int ordinal = paragraph.find() && paragraph.group(1).length() <= 2 ? Integer.parseInt(paragraph.group(1)) : 0;
		if (!article.equals(reference) && (ordinal < 1 || ordinal > 20)) {
			return false;
		}
		String raw = body == null ? "" : body;
		var headings = HEADING.matcher(raw);
		int start = -1;
		int end = raw.length();
		int firstHeading = raw.length();
		while (headings.find()) {
			firstHeading = Math.min(firstHeading, headings.start());
			if (article.equals(headings.group(1))) {
				start = headings.end();
				if (headings.find()) {
					end = headings.start();
				}
				break;
			}
		}
		if (start < 0 && firstHeading > 0) {
			var title = TITLE.matcher(heading == null ? "" : heading.stripLeading());
			if (title.find() && article.equals(title.group(1))) {
				start = 0;
				end = firstHeading;
			}
		}
		if (start < 0 || raw.substring(start, end).isBlank()) {
			return false;
		}
		if (ordinal == 0) {
			return true;
		}
		String content = raw.substring(start, end).stripLeading();
		char marker = (char) ('①' + ordinal - 1);
		return Pattern.compile("(?:^|[.。]|\\R)\\h*" + marker + "\\h*[^\\s①-⑳]")
			.matcher(content).find();
	}
}
