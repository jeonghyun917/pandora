package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.List;
import java.util.regex.Pattern;

final class EvidenceNoiseClassifier {
	private static final int LOW_SIGNAL_MAX_LENGTH = 160;
	private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");
	private static final Pattern DATE_MARKER = Pattern.compile(
		"^\\d{4}\\.\\d{1,2}\\.\\d{1,2}(,\\d{4}\\.\\d{1,2}\\.\\d{1,2})*>?$"
	);
	private static final Pattern ARTICLE_REFERENCE = Pattern.compile(".*제\\s*\\d+\\s*(조|항|호|목).*");
	private static final Pattern SHORT_PUBLICATION_FOOTER = Pattern.compile(
		"(?i).*(©|copyright|all\\s*rights\\s*reserved|oecd\\s*\\d{4}|national\\s*tax\\s*service|www\\.[a-z0-9._-]+\\.[a-z]{2,}).*"
	);
	private static final Pattern REQUIREMENT_FIELD_LABEL = Pattern.compile(
		"(?i)^(요구사항\\s*(분류|고유번호|명칭)|관련\\s*요구사항|준수\\s*항목|requirement\\s*(id|name|type)).{0,80}$"
	);
	private static final Pattern PAGE_MARKED_TABLE_UNIT = Pattern.compile(
		"(?iu)^\\s*[-–—]?\\s*\\d{1,4}\\s*[-–—]?\\s*[-–—]?\\s*.{0,80}\\(\\s*단위\\s*[:：][^)]+\\).*$"
	);
	private static final Pattern PAGE_PREFIX = Pattern.compile(
		"(?iu)^\\s*[-–—]?\\s*\\d{1,4}\\s*[-–—]?\\s*.{6,140}$"
	);
	private static final Pattern HEADING_ONLY_VISIBLE = Pattern.compile(
		"(?iu)^\\s*(part|chapter|section|appendix|[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\\.?|부록|붙임|제\\s*\\d+\\s*[장절관]|\\d+(\\.\\d+)*\\.?)\\s+\\S.{0,90}$"
	);
	private static final Pattern ROMAN_TOC_MARKER = Pattern.compile("[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]\\s*\\.?");
	private static final Pattern NUMBERED_TOC_MARKER = Pattern.compile("(?<!\\d)\\d{1,2}\\s*\\.");
	private static final Pattern ROMAN_TOC_LINE_WITH_PAGE = Pattern.compile(
		"(?iu)^\\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\\s*\\.?\\s+\\S.{0,90}\\s+\\d{1,4}(\\s+\\S.{0,40})?$"
	);
	private static final Pattern PAGE_WRAPPED_HEADING = Pattern.compile(
		"(?iu)^\\s*[-–—|]?\\s*\\d{1,4}\\s*(\\([^)]{1,20}\\))?\\s*[-–—]?\\s+\\S.{1,110}$"
	);
	private static final Pattern PAGE_ROMAN_HEADING_OR_CAPTION = Pattern.compile(
		"(?iu)^\\s*\\d{1,4}\\s*[ⅠⅡⅢⅣⅤⅥⅦⅧⅨⅩ]+\\s*\\.?\\s+\\S.{1,110}$"
	);
	private static final Pattern BRACKETED_INDEX_LABEL = Pattern.compile(
		"(?iu)^\\s*\\[[^\\]]{1,40}\\]\\s*[\\d①-⑳⑴-⑽ⅰ-ⅹ]+\\s*$"
	);
	private static final Pattern REPORT_HEADING_WITH_PAGE = Pattern.compile(
		"(?iu)^\\s*.{2,50}\\s+\\d{1,3}\\s+.{2,70}(보고서|백서|가이드|안내서|매뉴얼|계획서)\\d?\\s*$"
	);
	private static final Pattern MID_PAGE_HEADING = Pattern.compile(
		"(?iu)^\\s*.{2,80}\\s+[-–—]\\s*\\d{1,4}\\s*[-–—]\\s+[■□▪•]?\\s*\\S.{1,100}$"
	);
	private static final Pattern REPORT_TITLE_FRAGMENT = Pattern.compile(
		"(?iu)^\\s*.{1,90}(보고서|백서|가이드|안내서|매뉴얼|계획서|조사표)(\\s*\\d{1,3})?\\s*$"
	);
	private static final Pattern FIGURE_OR_TABLE_CAPTION = Pattern.compile(
		"(?iu).*(\\[\\s*(그림|표)\\s*\\d|그림\\s*\\d|표\\s*\\d|단위\\s*[:：]).*"
	);
	private static final Pattern BRANDED_COVER_FRAGMENT = Pattern.compile(
		"(?iu)^\\s*.{0,70}\\b(city|sokcho|korea|platform|service)\\b.{0,40}$"
	);
	private static final Pattern DECORATIVE_MARK = Pattern.compile("[▪󰠏■□•]");
	private static final Pattern REPEATED_CULTURE_MARK = Pattern.compile("(三樂|三寶|三)");
	private static final List<String> REVISION_QUERY_TERMS = List.of(
		"개정", "신설", "삭제", "폐지", "시행", "시행일", "변경", "연혁"
	);
	private static final List<String> LOW_SIGNAL_TERMS = List.of(
		"첨부", "첨부파일", "상단", "메뉴", "클릭", "버튼", "화면", "이미지", "다운로드", "누르", "이용"
	);
	private static final List<String> NAVIGATION_NOTICE_TERMS = List.of(
		"자세한내용", "확인하십시오", "이용하십시오", "상단메뉴", "상단첨부파일", "첨부파일을다운로드",
		"버튼을이용", "메뉴를이용", "첨부파일을이용"
	);
	private static final List<String> SUBSTANTIVE_TERMS = List.of(
		"하여야", "해야", "기준", "대상", "절차", "요건", "의무", "금지", "제외", "가능", "신청", "처리",
		"보유", "파기", "동의", "공개", "고지", "제출", "심사", "계약", "설치", "운영", "관리",
		"요구사항", "충족", "검증"
	);

	private EvidenceNoiseClassifier() {
	}

	static boolean shouldSuppressAsEvidence(LawSemanticChunkRow chunk, String normalizedQuestion) {
		if (chunk == null) {
			return false;
		}
		if (isLawRevisionMarkerOnlyChunk(chunk) && !isRevisionHistoryQuestion(normalizedQuestion)) {
			return true;
		}
		if (isDecorativeShortFragment(chunk)) {
			return true;
		}
		String visible = visibleText(chunk.chunkText());
		String normalized = normalize(visible);
		if (isTableUnitOrPageMarkerFragment(visible, normalized)) {
			return true;
		}
		return isLowSignalInstructionOnlyChunk(chunk);
	}

	static boolean isLawRevisionMarkerOnlyChunk(LawSemanticChunkRow chunk) {
		if (chunk == null || !isLawTarget(chunk.target())) {
			return false;
		}
		String compact = cleanText(chunk.chunkText()).replaceAll("\\s+", "");
		if (compact.isBlank() || compact.length() > 45) {
			return false;
		}
		return isAngleBracketMarker(compact) || DATE_MARKER.matcher(compact).matches();
	}

	static boolean isRevisionHistoryQuestion(String normalizedQuestion) {
		String query = normalize(normalizedQuestion);
		return REVISION_QUERY_TERMS.stream().anyMatch(query::contains);
	}

	static boolean isLowSignalInstructionOnlyChunk(LawSemanticChunkRow chunk) {
		if (chunk == null) {
			return false;
		}
		String raw = chunk.chunkText() == null ? "" : chunk.chunkText();
		String visible = visibleText(raw);
		String normalized = normalize(visible);
		if (normalized.isBlank()) {
			return raw.toLowerCase().contains("<img");
		}
		if (isImageOnly(raw, visible)) {
			return true;
		}
		if (normalized.length() > LOW_SIGNAL_MAX_LENGTH) {
			return false;
		}
		if (isStrongNavigationNotice(normalized)) {
			return true;
		}
		if (!containsAny(normalized, LOW_SIGNAL_TERMS)) {
			return false;
		}
		return !hasSubstantiveEvidenceSignal(normalized);
	}

	static boolean shouldDownrankAsContextOnly(LawSemanticChunkRow chunk) {
		if (chunk == null) {
			return false;
		}
		String raw = chunk.chunkText();
		String visible = visibleText(raw);
		String normalized = normalize(visible);
		if (normalized.isBlank()) {
			return isLowSignalInstructionOnlyChunk(chunk)
				|| isSymbolicRepetitionFragment(raw, visible, normalized);
		}
		if (normalized.length() > LOW_SIGNAL_MAX_LENGTH) {
			return false;
		}
		if (isLowSignalInstructionOnlyChunk(chunk)) {
			return true;
		}
		if (isDecorativeShortFragment(chunk)) {
			return true;
		}
		if (isTableUnitOrPageMarkerFragment(visible, normalized)
			|| isRunningHeaderOrCoverFragment(chunk, visible, normalized)
			|| isTocLikeShortFragment(visible)
			|| isHeadingOnlyFragment(visible, normalized)
			|| isPagedHeadingOrFormFragment(visible, normalized)
			|| isSymbolicRepetitionFragment(raw, visible, normalized)) {
			return true;
		}
		if (isTitleOnlyContextFragment(chunk, normalized) || isRequirementFieldLabel(normalized)) {
			return true;
		}
		if ("toc".equalsIgnoreCase(String.valueOf(chunk.sectionType()).trim())) {
			return true;
		}
		if (hasSubstantiveEvidenceSignal(normalized)) {
			return false;
		}
		String heading = normalize(chunk.title() + " " + chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		if (!heading.isBlank() && normalized.length() <= 120 && heading.contains(normalized)) {
			return true;
		}
		return false;
	}

	private static boolean isDecorativeShortFragment(LawSemanticChunkRow chunk) {
		String raw = chunk == null ? "" : chunk.chunkText();
		String visible = visibleText(raw);
		String normalized = normalize(visible);
		if (normalized.isBlank()) {
			return raw != null && raw.toLowerCase().contains("<img");
		}
		if (normalized.length() <= 1) {
			return true;
		}
		if (visible.length() > 140) {
			return false;
		}
		if (SHORT_PUBLICATION_FOOTER.matcher(visible).matches()
			&& !hasSubstantiveEvidenceSignal(normalized)) {
			return true;
		}
		return normalized.matches("^[0-9]+$")
			|| normalized.matches("^[\\p{Punct}·ㆍ\\-_/\\\\|]+$")
			|| normalized.matches("^[\\p{Punct}·ㆍ\\-_/\\\\|0-9]+$");
	}

	private static boolean isTitleOnlyContextFragment(LawSemanticChunkRow chunk, String normalizedText) {
		if (chunk == null || normalizedText == null || normalizedText.isBlank() || normalizedText.length() > 140) {
			return false;
		}
		String title = normalize(chunk.title());
		String heading = normalize(chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		if (title.isBlank() && heading.isBlank()) {
			return false;
		}
		boolean containedInTitle = !title.isBlank() && title.contains(normalizedText);
		boolean containedInHeading = !heading.isBlank() && heading.contains(normalizedText);
		boolean sharedTitleRun = hasSharedRun(normalizedText, title + heading, 16);
		if (!containedInTitle && !containedInHeading && !sharedTitleRun) {
			return false;
		}
		return !hasSubstantiveEvidenceSignal(normalizedText);
	}

	private static boolean isRequirementFieldLabel(String normalizedText) {
		return normalizedText != null
			&& normalizedText.length() <= 120
			&& REQUIREMENT_FIELD_LABEL.matcher(normalizedText).matches();
	}

	private static boolean isTableUnitOrPageMarkerFragment(String visible, String normalizedText) {
		if (visible == null || normalizedText == null || normalizedText.isBlank() || visible.length() > 140) {
			return false;
		}
		if (hasSubstantiveEvidenceSignal(normalizedText)) {
			return false;
		}
		return PAGE_MARKED_TABLE_UNIT.matcher(visible).matches()
			|| visible.matches("(?iu)^\\s*[-–—]\\s*\\d{1,4}\\s*[-–—]\\s*[-–—]\\s*.{1,50}\\s*[-–—]\\s*$");
	}

	private static boolean isRunningHeaderOrCoverFragment(LawSemanticChunkRow chunk, String visible, String normalizedText) {
		if (chunk == null || visible == null || normalizedText == null || normalizedText.isBlank() || visible.length() > 160) {
			return false;
		}
		if (hasSubstantiveEvidenceSignal(normalizedText)) {
			return false;
		}
		String titleText = normalize(chunk.title() + " " + chunk.parentSectionTitle() + " " + chunk.chunkTitle());
		if (titleText.isBlank()) {
			return false;
		}
		String stripped = stripLeadingPageCue(normalizedText);
		boolean hasPageCue = PAGE_PREFIX.matcher(visible).matches()
			|| normalizedText.matches("^\\d{2,4}[\\p{IsHangul}a-z].*");
		boolean titleEcho = titleText.contains(stripped)
			|| stripped.contains(titleText)
			|| hasSharedRun(stripped, titleText, 12);
		return titleEcho && (hasPageCue || looksLikeCoverTitleFragment(visible, normalizedText));
	}

	private static boolean isHeadingOnlyFragment(String visible, String normalizedText) {
		if (visible == null || normalizedText == null || visible.length() > 120) {
			return false;
		}
		if (hasSubstantiveEvidenceSignal(normalizedText)) {
			return false;
		}
		return HEADING_ONLY_VISIBLE.matcher(visible).matches()
			|| visible.matches("(?iu).*(white\\s*paper|appendix|directory\\s*book|annual\\s*report).{0,120}$");
	}

	private static boolean isTocLikeShortFragment(String visible) {
		if (visible == null || visible.isBlank() || visible.length() > 140) {
			return false;
		}
		String compact = visible.replaceAll("\\s+", " ").trim();
		int romanMarkers = countMatches(ROMAN_TOC_MARKER, compact);
		int numberedMarkers = countMatches(NUMBERED_TOC_MARKER, compact);
		if (romanMarkers >= 2 || numberedMarkers >= 3) {
			return true;
		}
		if (ROMAN_TOC_LINE_WITH_PAGE.matcher(compact).matches()) {
			return true;
		}
		String lower = compact.toLowerCase();
		return lower.contains("white paper") && (lower.contains("appendix") || romanMarkers >= 1);
	}

	private static boolean isPagedHeadingOrFormFragment(String visible, String normalizedText) {
		if (visible == null || normalizedText == null || normalizedText.isBlank() || visible.length() > 140) {
			return false;
		}
		if (hasSubstantiveEvidenceSignal(normalizedText)) {
			return false;
		}
		return PAGE_WRAPPED_HEADING.matcher(visible).matches()
			|| PAGE_ROMAN_HEADING_OR_CAPTION.matcher(visible).matches()
			|| BRACKETED_INDEX_LABEL.matcher(visible).matches()
			|| REPORT_HEADING_WITH_PAGE.matcher(visible).matches()
			|| MID_PAGE_HEADING.matcher(visible).matches()
			|| REPORT_TITLE_FRAGMENT.matcher(visible).matches()
			|| FIGURE_OR_TABLE_CAPTION.matcher(visible).matches()
			|| BRANDED_COVER_FRAGMENT.matcher(visible).matches();
	}

	private static boolean isSymbolicRepetitionFragment(String raw, String visible, String normalizedText) {
		String signalText = (raw == null ? "" : raw) + " " + (visible == null ? "" : visible);
		if (signalText.isBlank() || signalText.length() > 260 || normalizedText == null) {
			return false;
		}
		if (!normalizedText.isBlank() && hasSubstantiveEvidenceSignal(normalizedText)) {
			return false;
		}
		int decorativeMarks = countMatches(DECORATIVE_MARK, signalText);
		int repeatedCultureMarks = countMatches(REPEATED_CULTURE_MARK, signalText);
		return decorativeMarks >= 4 || repeatedCultureMarks >= 6;
	}

	private static int countMatches(Pattern pattern, String value) {
		int count = 0;
		java.util.regex.Matcher matcher = pattern.matcher(value);
		while (matcher.find()) {
			count++;
		}
		return count;
	}

	private static boolean looksLikeCoverTitleFragment(String visible, String normalizedText) {
		if (visible == null || normalizedText == null || visible.length() > 120) {
			return false;
		}
		return visible.matches("(?iu).*\\b\\d{4}(\\.\\s*\\d{1,2})?\\b.*")
			|| visible.matches("(?iu).*[A-Z]{2,}.*")
			|| normalizedText.matches(".*(보고서|백서|가이드|매뉴얼|안내서|사례집|보도자료|결과보고서).*");
	}

	private static String stripLeadingPageCue(String normalizedText) {
		if (normalizedText == null) {
			return "";
		}
		return normalizedText
			.replaceFirst("^p\\d{1,4}", "")
			.replaceFirst("^\\d{1,4}", "");
	}

	private static boolean hasSharedRun(String left, String right, int minLength) {
		if (left == null || right == null || left.length() < minLength || right.length() < minLength) {
			return false;
		}
		String shorter = left.length() <= right.length() ? left : right;
		String longer = left.length() <= right.length() ? right : left;
		for (int start = 0; start <= shorter.length() - minLength; start++) {
			String probe = shorter.substring(start, start + minLength);
			if (longer.contains(probe)) {
				return true;
			}
		}
		return false;
	}

	private static boolean isStrongNavigationNotice(String normalizedText) {
		return containsAny(normalizedText, NAVIGATION_NOTICE_TERMS)
			&& containsAny(normalizedText, LOW_SIGNAL_TERMS)
			&& !ARTICLE_REFERENCE.matcher(normalizedText).matches();
	}

	private static boolean hasSubstantiveEvidenceSignal(String normalizedText) {
		if (normalizedText == null || normalizedText.isBlank()) {
			return false;
		}
		return ARTICLE_REFERENCE.matcher(normalizedText).matches()
			|| containsAny(normalizedText, SUBSTANTIVE_TERMS);
	}

	private static boolean isImageOnly(String raw, String visible) {
		if (raw == null || !raw.toLowerCase().contains("<img")) {
			return false;
		}
		String stripped = normalize(visible);
		return stripped.isBlank() || stripped.length() <= 20;
	}

	private static boolean isAngleBracketMarker(String compact) {
		return compact.startsWith("<")
			&& compact.length() <= 45
			&& !containsAny(normalize(compact), SUBSTANTIVE_TERMS);
	}

	private static boolean containsAny(String text, List<String> terms) {
		if (text == null || terms == null || terms.isEmpty()) {
			return false;
		}
		return terms.stream()
			.map(EvidenceNoiseClassifier::normalize)
			.anyMatch(term -> !term.isBlank() && text.contains(term));
	}

	private static String visibleText(String value) {
		String cleaned = cleanText(value);
		return HTML_TAG.matcher(cleaned)
			.replaceAll(" ")
			.replace("&nbsp;", " ")
			.replace("&lt;", " ")
			.replace("&gt;", " ")
			.replace("&amp;", " ")
			.replaceAll("\\s+", " ")
			.trim();
	}

	private static String cleanText(String value) {
		return HwpxTextCleaner.clean(value == null ? "" : value);
	}

	private static String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(cleanText(value));
	}

	private static boolean isLawTarget(String target) {
		return "law".equals(target) || "admrul".equals(target);
	}
}
