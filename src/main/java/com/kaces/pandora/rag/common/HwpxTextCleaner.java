package com.kaces.pandora.rag.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HwpxTextCleaner {
	private static final Pattern FIELD_ARGS = Pattern.compile(";\\s*\\d+;\\s*\\d+;\\s*\\d+;?");
	private static final Pattern HYPERLINK_TOKEN = Pattern.compile("HWPHYPERLINK_[A-Z_]+");
	private static final Pattern FIELD_LINE = Pattern.compile("^.*;\\s*\\d+;\\s*\\d+;\\s*\\d+;?\\s*$");
	private static final Pattern DIGITS_ONLY = Pattern.compile("^\\d+$");
	private static final Pattern COMPACT_FIELD_HINT = Pattern.compile("\\d*\\?[^\\n;]{0,240};\\s*\\d+;\\s*\\d+;\\s*\\d+;?");
	private static final Pattern CONTROL_LINK_PREFIX = Pattern.compile("(?i)(^|[^\\p{Alnum}])0(?:https?\\\\?://|mailto:)");
	private static final Pattern ESCAPED_URL = Pattern.compile("(?i)https?\\\\://");
	private static final int INLINE_LOOKAHEAD = 700;

	// 메소드 설명: HwpxTextCleaner 처리 흐름을 수행합니다.
	private HwpxTextCleaner() {
	}

	// 메소드 설명: clean 처리 흐름을 수행합니다.
	public static String clean(String value) {
		if (value == null || value.isBlank()) {
			return value == null ? "" : value.trim();
		}
		String normalized = value
			.replace("\r\n", "\n")
			.replace('\r', '\n');
		if (!containsHwpxArtifact(normalized)) {
			return normalized.trim();
		}
		return cleanLines(stripInlineControlPrefixes(cleanInlineFields(normalized))).trim();
	}

	// 메소드 설명: containsHwpxArtifact 처리 흐름을 수행합니다.
	private static boolean containsHwpxArtifact(String value) {
		return value.contains("HWPHYPERLINK_")
			|| COMPACT_FIELD_HINT.matcher(value).find()
			|| CONTROL_LINK_PREFIX.matcher(value).find()
			|| ESCAPED_URL.matcher(value).find();
	}

	// 메소드 설명: stripInlineControlPrefixes 처리 흐름을 수행합니다.
	private static String stripInlineControlPrefixes(String value) {
		return value
			.replace("\\://", "://")
			.replace("\\:", ":")
			.replaceAll("(?i)(^|[^\\p{Alnum}])0(?=https?://)", "$1")
			.replaceAll("(?i)(^|[^\\p{Alnum}])0mailto:", "$1");
	}

	// 메소드 설명: cleanInlineFields 처리 흐름을 수행합니다.
	private static String cleanInlineFields(String value) {
		String[] lines = value.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			lines[i] = cleanInlineFieldLine(lines[i]);
		}
		return String.join("\n", lines);
	}

	// 메소드 설명: cleanInlineFieldLine 처리 흐름을 수행합니다.
	private static String cleanInlineFieldLine(String line) {
		String current = line;
		for (int guard = 0; guard < 16; guard++) {
			Matcher args = FIELD_ARGS.matcher(current);
			boolean changed = false;
			while (args.find()) {
				int lookaheadEnd = Math.min(current.length(), args.end() + INLINE_LOOKAHEAD);
				String tail = current.substring(args.end(), lookaheadEnd);
				if (!tail.contains("HWPHYPERLINK_")) {
					continue;
				}

				int displayStart = displayStart(current, args.start());
				if (displayStart < 0 || displayStart > args.start()) {
					continue;
				}
				String display = cleanDisplay(current.substring(displayStart, args.start()));
				int fieldEnd = inlineFieldEnd(current, displayStart, args.end(), lookaheadEnd);
				current = current.substring(0, displayStart) + display + current.substring(fieldEnd);
				changed = true;
				break;
			}
			if (!changed) {
				break;
			}
		}
		return current;
	}

	// 메소드 설명: displayStart 처리 흐름을 수행합니다.
	private static int displayStart(String value, int argsStart) {
		String before = value.substring(0, argsStart);
		int question = before.lastIndexOf('?');
		if (question >= 0 && argsStart - question < 240) {
			int start = question;
			while (start > 0 && Character.isDigit(before.charAt(start - 1))) {
				start--;
			}
			return start;
		}

		int linkStart = lastLinkStart(before);
		if (linkStart >= 0 && argsStart - linkStart < 320) {
			return linkStart;
		}

		int lineStart = Math.max(before.lastIndexOf('\n'), before.lastIndexOf('\r')) + 1;
		return Math.max(0, lineStart);
	}

	// 메소드 설명: lastLinkStart 처리 흐름을 수행합니다.
	private static int lastLinkStart(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		int start = Math.max(lower.lastIndexOf("mailto:"), Math.max(
			Math.max(lower.lastIndexOf("http://"), lower.lastIndexOf("https://")),
			Math.max(lower.lastIndexOf("http\\://"), lower.lastIndexOf("https\\://"))
		));
		if (start > 0 && value.charAt(start - 1) == '0') {
			return start - 1;
		}
		return start;
	}

	// 메소드 설명: inlineFieldEnd 처리 흐름을 수행합니다.
	private static int inlineFieldEnd(String value, int displayStart, int argsEnd, int lookaheadEnd) {
		Matcher token = HYPERLINK_TOKEN.matcher(value.substring(argsEnd, lookaheadEnd));
		int tokenEnd = -1;
		while (token.find()) {
			tokenEnd = argsEnd + token.end();
		}
		if (tokenEnd < 0) {
			return argsEnd;
		}

		if (displayStart > 0 && value.charAt(displayStart - 1) == '(') {
			int close = value.indexOf(')', tokenEnd);
			if (close >= 0) {
				return close;
			}
		}
		return lineEnd(value, tokenEnd);
	}

	// 메소드 설명: lineEnd 처리 흐름을 수행합니다.
	private static int lineEnd(String value, int start) {
		int lineFeed = value.indexOf('\n', start);
		int carriage = value.indexOf('\r', start);
		if (lineFeed < 0) {
			return carriage < 0 ? value.length() : carriage;
		}
		if (carriage < 0) {
			return lineFeed;
		}
		return Math.min(lineFeed, carriage);
	}

	// 메소드 설명: cleanLines 처리 흐름을 수행합니다.
	private static String cleanLines(String value) {
		String[] lines = value.split("\n", -1);
		List<String> cleaned = new ArrayList<>();
		boolean activeBookmarkField = false;
		boolean skipBookmarkTarget = false;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i].trim();
			if (line.isBlank()) {
				continue;
			}
			if (DIGITS_ONLY.matcher(line).matches() && upcomingFieldLine(lines, i + 1, 2)) {
				continue;
			}
			if (isHyperlinkToken(line)) {
				if (line.contains("HWPHYPERLINK_JUMP_CURRENTTAB") && activeBookmarkField) {
					skipBookmarkTarget = true;
					activeBookmarkField = false;
				}
				continue;
			}
			if (looksLikeFieldLine(line)) {
				String display = cleanDisplay(line.substring(0, line.indexOf(';')));
				activeBookmarkField = !looksLikeExternalLink(display);
				if (!display.isBlank()) {
					addIfUseful(cleaned, display);
				}
				continue;
			}
			if (skipBookmarkTarget) {
				skipBookmarkTarget = false;
				continue;
			}
			addIfUseful(cleaned, cleanDisplay(line));
		}
		return String.join("\n", cleaned);
	}

	// 메소드 설명: upcomingFieldLine 처리 흐름을 수행합니다.
	private static boolean upcomingFieldLine(String[] lines, int start, int distance) {
		int end = Math.min(lines.length, start + distance);
		for (int i = start; i < end; i++) {
			String line = lines[i].trim();
			if (line.startsWith("?") || looksLikeFieldLine(line)) {
				return true;
			}
		}
		return false;
	}

	// 메소드 설명: isHyperlinkToken 처리 흐름을 수행합니다.
	private static boolean isHyperlinkToken(String value) {
		return value.contains("HWPHYPERLINK_");
	}

	// 메소드 설명: looksLikeFieldLine 처리 흐름을 수행합니다.
	private static boolean looksLikeFieldLine(String value) {
		return value.contains(";") && FIELD_LINE.matcher(value).matches();
	}

	// 메소드 설명: looksLikeExternalLink 처리 흐름을 수행합니다.
	private static boolean looksLikeExternalLink(String value) {
		String lower = value.toLowerCase(Locale.ROOT);
		return lower.contains("://") || lower.startsWith("mailto:") || lower.contains("@");
	}

	// 메소드 설명: addIfUseful 처리 흐름을 수행합니다.
	private static void addIfUseful(List<String> values, String value) {
		String cleaned = value.trim();
		if (cleaned.isBlank()) {
			return;
		}
		if (!values.isEmpty() && sameCompact(values.get(values.size() - 1), cleaned)) {
			return;
		}
		values.add(cleaned);
	}

	// 메소드 설명: sameCompact 처리 흐름을 수행합니다.
	private static boolean sameCompact(String first, String second) {
		return first.replaceAll("\\s+", "").equals(second.replaceAll("\\s+", ""));
	}

	// 메소드 설명: cleanDisplay 처리 흐름을 수행합니다.
	private static String cleanDisplay(String value) {
		if (value == null || value.isBlank()) {
			return "";
		}
		String cleaned = value.trim()
			.replace("\\://", "://")
			.replace("\\:", ":");
		cleaned = cleaned.replaceFirst("^\\d+\\?", "");
		cleaned = cleaned.replaceFirst("^\\?", "");
		cleaned = cleaned.replaceFirst("^0(?=mailto:)", "");
		cleaned = cleaned.replaceFirst("^0(?=https?://)", "");
		cleaned = cleaned.replaceFirst("^0(?=\\d+\\.)", "");
		cleaned = cleaned.replaceFirst("(?i)^mailto:", "");
		return cleaned.trim();
	}
}
