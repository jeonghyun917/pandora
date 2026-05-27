package com.kaces.pandora.common.text;

import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import org.springframework.web.util.HtmlUtils;

public final class LawTextUtils {
	private static final Pattern SCRIPT_STYLE_BLOCK = Pattern.compile("(?is)<\\s*(script|style)\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>");
	private static final Pattern LINE_BREAK_TAG = Pattern.compile("(?i)<\\s*br\\s*/?\\s*>");
	private static final Pattern BLOCK_END_TAG = Pattern.compile("(?i)<\\s*/\\s*(p|div|li|tr|h[1-6]|table|section|article)\\s*>");
	private static final Pattern HTML_TAG = Pattern.compile("(?is)<\\s*/?\\s*[a-zA-Z][^>]*>");
	private static final Pattern ENCODED_HTML_TAG = Pattern.compile("(?is)&lt;\\s*/?\\s*[a-zA-Z][^&]*&gt;");

	
	// 메소드 설명: LawTextUtils 처리 흐름을 수행합니다.
	private LawTextUtils() {
	}

	
	// 메소드 설명: emptyToNull 처리 흐름을 수행합니다.
	public static String emptyToNull(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	
	// 메소드 설명: firstNonBlank 처리 흐름을 수행합니다.
	public static String firstNonBlank(String left, String right) {
		return StringUtils.hasText(left) ? left : right;
	}

	
	// 메소드 설명: normalizeText 처리 흐름을 수행합니다.
	public static String normalizeText(String value) {
		return value == null ? "" : value.replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
	}

	public static String stripHtmlTags(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		if (!containsHtmlMarkup(value)) {
			return normalizeText(value);
		}
		String text = removeHtmlMarkup(value);
		text = HtmlUtils.htmlUnescape(text).replace('\u00A0', ' ');
		text = removeHtmlMarkup(text);
		return normalizeText(text).lines()
			.map(line -> line.replaceAll("[ \\t]+", " ").trim())
			.filter(StringUtils::hasText)
			.collect(Collectors.joining("\n"));
	}

	private static boolean containsHtmlMarkup(String value) {
		return HTML_TAG.matcher(value).find() || ENCODED_HTML_TAG.matcher(value).find();
	}

	private static String removeHtmlMarkup(String value) {
		return HTML_TAG.matcher(BLOCK_END_TAG.matcher(LINE_BREAK_TAG.matcher(
			SCRIPT_STYLE_BLOCK.matcher(value).replaceAll(" ")
		).replaceAll("\n")).replaceAll("\n")).replaceAll(" ");
	}

	
	// 메소드 설명: formatDate 처리 흐름을 수행합니다.
	public static String formatDate(String value) {
		if (value == null) {
			return "";
		}
		String digits = value.replaceAll("\\D", "");
		if (digits.length() != 8) {
			return StringUtils.hasText(value) ? value : "";
		}
		return Integer.parseInt(digits.substring(0, 4)) + ". "
			+ Integer.parseInt(digits.substring(4, 6)) + ". "
			+ Integer.parseInt(digits.substring(6, 8)) + ".";
	}
}
