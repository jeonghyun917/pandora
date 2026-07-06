package com.kaces.pandora.lawdata.version;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.util.StringUtils;

public final class LawVersionUtils {

	private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private LawVersionUtils() {
	}

	public static boolean isVersionedTarget(String target) {
		return "law".equals(target) || "admrul".equals(target);
	}

	public static String canonicalKey(String target, String title) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "";
		String normalizedTitle = String.valueOf(title == null ? "" : title)
			.replaceAll("[^\\p{IsHangul}A-Za-z0-9]", "")
			.toLowerCase();
		if (!StringUtils.hasText(normalizedTitle)) {
			normalizedTitle = "unknown";
		}
		return safeTarget + ":" + normalizedTitle;
	}

	public static String normalizeEffectiveDate(String sourceDate) {
		String text = String.valueOf(sourceDate == null ? "" : sourceDate).trim();
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String digits = text.replaceAll("[^0-9]", "");
		if (digits.length() < 8) {
			return null;
		}
		String compact = digits.substring(0, 8);
		try {
			LocalDate.parse(compact, COMPACT_DATE);
			return compact;
		} catch (RuntimeException exception) {
			return null;
		}
	}

	public static String initialStatus(String target, String effectiveDate, Clock clock) {
		if (!isVersionedTarget(target)) {
			return "CURRENT";
		}
		if (!StringUtils.hasText(effectiveDate)) {
			return "UNKNOWN";
		}
		LocalDate effective = LocalDate.parse(effectiveDate, COMPACT_DATE);
		LocalDate today = LocalDate.now(clock);
		return effective.isAfter(today) ? "FUTURE" : "CURRENT";
	}
}
