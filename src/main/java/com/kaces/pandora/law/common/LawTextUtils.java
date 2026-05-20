package com.kaces.pandora.law.common;

import org.springframework.util.StringUtils;

public final class LawTextUtils {

	private LawTextUtils() {
	}

	public static String emptyToNull(String value) {
		return StringUtils.hasText(value) ? value : null;
	}

	public static String firstNonBlank(String left, String right) {
		return StringUtils.hasText(left) ? left : right;
	}

	public static String normalizeText(String value) {
		return value == null ? "" : value.replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
	}

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
