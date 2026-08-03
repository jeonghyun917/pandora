package com.kaces.pandora.lawdata.sync;

import static com.kaces.pandora.common.text.LawHashUtils.sha256;

import com.kaces.pandora.common.text.LawTextUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

final class LawSemanticChunkPlanner {

	private static final int MIN_CHILD_CHARS = 800;
	private static final int MAX_CHILD_CHARS = 2_500;
	private static final int OVERLAP_CHARS = 160;
	private static final int TINY_CHILD_CHARS = 80;
	private static final int MAX_STORED_TYPE_CHARS = 50;
	private static final int MAX_STORED_NO_CHARS = 100;
	private static final int MAX_STORED_TITLE_CHARS = 500;
	private static final int MAX_STORED_SOURCE_PATH_CHARS = 500;
	private static final Pattern KOREAN_LEGAL_CUE = Pattern.compile(
		"제\\s*\\d+\\s*조|별표|서식|부칙"
	);
	private static final Pattern PARAGRAPH_LINE_SUFFIX = Pattern.compile("\\s*문단\\s*\\d+.*$");
	private static final Pattern PARAGRAPH_NO_SUFFIX = Pattern.compile("\\s*문단\\d+.*$");
	private static final Pattern SPLIT_SUFFIX = Pattern.compile("\\s*\\(\\d+/\\d+\\)$");
	private static final Pattern GENERIC_TITLE = Pattern.compile("^(항내용|호내용|목내용|조문내용|별표내용|개정문내용|본문|text|상세 내용)#?\\d*$");
	private static final Pattern ARTICLE_HEADING = Pattern.compile("^(제\\d+조(?:의\\d+)?)(\\([^\\n]{1,80}?\\))?");
	private static final Pattern APPENDIX_HEADING = Pattern.compile("^(별표\\s*\\d*)");
	private static final Pattern LAW_ARTICLE_SOURCE_PATH = Pattern.compile("^(.*?조문단위\\[[0-9]+\\]).*$");
	private static final Pattern LAW_APPENDIX_SOURCE_PATH = Pattern.compile("^(.*?별표단위\\[[0-9]+\\]).*$");
	private static final Pattern SOURCE_UNIT_INDEX = Pattern.compile("(조문단위|조문내용|별표단위|부칙단위)\\[([0-9]+)]");
	private static final List<String> TINY_APPENDIX_FORM_TERMS = List.of(
		"별지", "서식", "진단서", "보증서", "제출", "요청번호", "요청일",
		"제공 항목", "제공 목적", "보유 기간", "청구인", "청구사항", "참고자료",
		"성 명", "소 속", "연 락 처", "담당업무", "사 건", "사건", "신청인",
		"대표자", "기관명", "채용 현황", "징 계", "징계", "요구 사유",
		"결정사유", "사업장명", "대부금액", "검사방법", "정기안전검사",
		"기타의견", "보호제도", "포상제도", "보상제도", "강사기준", "강사양성교육",
		"신고를 준비", "신고를 접수", "지급 요건", "국번 없이", "상담하시기",
		"학명", "국명", "삽화", "서식지", "대상 종", "200자 이상 작성"
	);

	List<PlannedLawChunk> plan(List<SyncDetailSection> sections) {
		return plan(sections, false);
	}

	private List<PlannedLawChunk> plan(List<SyncDetailSection> sections, boolean preserveParentBoundaries) {
		List<PlannedLawChunk> planned = new ArrayList<>();
		SectionGroup current = null;
		for (SyncDetailSection section : sections) {
			String text = LawTextUtils.stripHtmlTags(section.body());
			if (!StringUtils.hasText(text)) {
				continue;
			}
			String key = parentKey(section);
			if (current == null || !current.key.equals(key)) {
				flush(planned, current);
				current = new SectionGroup(key, sectionType(section), baseNo(section.no()), parentTitle(section), parentSourcePath(section));
			}
			current.add(text);
			if (!StringUtils.hasText(current.no) || !StringUtils.hasText(current.title)) {
				current.applyHeadingIfMissing(inferHeading(text));
			}
		}
		flush(planned, current);
		return mergeShortAdjacentChunks(planned, preserveParentBoundaries).stream()
			.map(this::withInferredMetadata)
			.map(this::normalizeForStorage)
			.toList();
	}

	private List<PlannedLawChunk> mergeShortAdjacentChunks(List<PlannedLawChunk> planned, boolean preserveParentBoundaries) {
		if (planned.size() <= 1) {
			return planned;
		}
		List<PlannedLawChunk> merged = new ArrayList<>();
		PlannedLawChunk current = null;
		for (PlannedLawChunk next : planned) {
			if (current == null) {
				current = next;
				continue;
			}
			if (shouldMerge(current, next, preserveParentBoundaries)) {
				current = merge(current, next);
				continue;
			}
			merged.add(current);
			current = next;
		}
		if (current != null) {
			merged.add(current);
		}
		return removeContainedShortDuplicates(mergeTinyOrphans(mergeShortWithPrevious(merged, preserveParentBoundaries), preserveParentBoundaries));
	}

	private List<PlannedLawChunk> removeContainedShortDuplicates(List<PlannedLawChunk> planned) {
		if (planned.size() <= 1) {
			return planned;
		}
		List<PlannedLawChunk> compacted = new ArrayList<>();
		for (int index = 0; index < planned.size(); index++) {
			if (isContainedShortDuplicate(planned, index)) {
				continue;
			}
			compacted.add(planned.get(index));
		}
		return compacted;
	}

	private boolean isContainedShortDuplicate(List<PlannedLawChunk> planned, int index) {
		PlannedLawChunk current = planned.get(index);
		String needle = normalizeForContainment(current.text());
		if (needle.length() < TINY_CHILD_CHARS || needle.length() >= MIN_CHILD_CHARS) {
			return false;
		}
		for (int otherIndex = 0; otherIndex < planned.size(); otherIndex++) {
			if (otherIndex == index) {
				continue;
			}
			if (containsNormalized(planned.get(otherIndex).text(), needle)) {
				return true;
			}
		}
		return false;
	}

	private boolean containsNormalized(String haystackText, String needle) {
		String haystack = normalizeForContainment(haystackText);
		return haystack.length() > needle.length() && haystack.contains(needle);
	}

	private String normalizeForContainment(String text) {
		if (!StringUtils.hasText(text)) {
			return "";
		}
		return text.replaceAll("\\s+", " ").trim();
	}

	private List<PlannedLawChunk> mergeShortWithPrevious(List<PlannedLawChunk> planned, boolean preserveParentBoundaries) {
		if (planned.size() <= 1) {
			return planned;
		}
		List<PlannedLawChunk> merged = new ArrayList<>();
		PlannedLawChunk current = null;
		for (PlannedLawChunk next : planned) {
			if (current == null) {
				current = next;
				continue;
			}
			if (shouldMergeIntoPrevious(current, next, preserveParentBoundaries)) {
				current = merge(current, next);
				continue;
			}
			merged.add(current);
			current = next;
		}
		if (current != null) {
			merged.add(current);
		}
		return merged;
	}

	private boolean shouldMerge(PlannedLawChunk current, PlannedLawChunk next, boolean preserveParentBoundaries) {
		if (!canMerge(current, next, preserveParentBoundaries)) {
			return false;
		}
		int currentLength = length(current.text());
		int combinedLength = currentLength + 1 + length(next.text());
		return currentLength < MIN_CHILD_CHARS && combinedLength <= MAX_CHILD_CHARS;
	}

	private boolean shouldMergeIntoPrevious(PlannedLawChunk current, PlannedLawChunk next, boolean preserveParentBoundaries) {
		if (!canMerge(current, next, preserveParentBoundaries)) {
			return false;
		}
		int nextLength = length(next.text());
		int combinedLength = length(current.text()) + 1 + nextLength;
		return nextLength < MIN_CHILD_CHARS && combinedLength <= MAX_CHILD_CHARS;
	}

	private List<PlannedLawChunk> mergeTinyOrphans(List<PlannedLawChunk> planned, boolean preserveParentBoundaries) {
		if (planned.size() <= 1) {
			return planned;
		}
		List<PlannedLawChunk> merged = new ArrayList<>();
		for (int index = 0; index < planned.size(); index++) {
			PlannedLawChunk chunk = planned.get(index);
			if (!isTiny(chunk)) {
				merged.add(chunk);
				continue;
			}
			if (!merged.isEmpty() && canMergeTiny(merged.get(merged.size() - 1), chunk, preserveParentBoundaries)) {
				PlannedLawChunk previous = merged.remove(merged.size() - 1);
				merged.add(merge(previous, chunk));
				continue;
			}
			if (!merged.isEmpty()) {
				PlannedLawChunk previous = merged.get(merged.size() - 1);
				List<PlannedLawChunk> rebalanced = rebalanceTiny(previous, chunk, preserveParentBoundaries);
				if (!rebalanced.isEmpty()) {
					merged.remove(merged.size() - 1);
					merged.addAll(rebalanced);
					continue;
				}
			}
			if (index + 1 < planned.size() && canMergeTiny(chunk, planned.get(index + 1), preserveParentBoundaries)) {
				merged.add(merge(chunk, planned.get(index + 1)));
				index++;
				continue;
			}
			if (index + 1 < planned.size()) {
				List<PlannedLawChunk> rebalanced = rebalanceTiny(chunk, planned.get(index + 1), preserveParentBoundaries);
				if (!rebalanced.isEmpty()) {
					merged.addAll(rebalanced);
					index++;
					continue;
				}
			}
			if (isLowSignalTinyFormTail(chunk.text())) {
				continue;
			}
			merged.add(chunk);
		}
		return merged;
	}

	private boolean isTiny(PlannedLawChunk chunk) {
		return length(chunk.text()) < TINY_CHILD_CHARS;
	}

	private boolean isLowSignalTinyFormTail(String text) {
		if (length(text) >= TINY_CHILD_CHARS || !StringUtils.hasText(text)) {
			return false;
		}
		String normalized = text.replaceAll("\\s+", " ").trim();
		if (!hasFormFieldMarker(normalized)) {
			return false;
		}
		int fieldTerms = 0;
		for (String term : List.of("주소", "성명", "서명", "직위", "소속", "개인정보", "보유기간", "제3자", "저작물명", "종류")) {
			if (normalized.contains(term)) {
				fieldTerms++;
			}
		}
		if (fieldTerms >= 3) {
			return true;
		}
		if (looksLikeSignaturePlaceholderTail(normalized)) {
			return true;
		}
		return checkboxCount(normalized) >= 2
			&& normalized.contains("|")
			&& signalLength(normalized) < 70;
	}

	private boolean looksLikeSignaturePlaceholderTail(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		boolean hasPlaceholderDate = text.matches(".*\\d{4}\\.\\s*\\.\\s*\\..*")
			|| text.matches(".*\\.\\s*\\.\\s*\\..*");
		boolean hasSignatureCue = text.contains("(인)")
			|| text.contains("귀하")
			|| text.contains("대표자")
			|| text.contains("검 사 인")
			|| text.contains("검사인");
		boolean hasBusinessFormCue = text.contains("사업자등록번호")
			|| text.contains("사업장소재지")
			|| text.contains("상호")
			|| text.contains("합계");
		return (hasPlaceholderDate || hasSignatureCue || hasBusinessFormCue)
			&& (text.contains("|") || checkboxCount(text) > 0);
	}

	private int checkboxCount(String text) {
		if (!StringUtils.hasText(text)) {
			return 0;
		}
		int count = 0;
		for (int index = 0; index < text.length(); index++) {
			char ch = text.charAt(index);
			if (ch == '\u25a1' || ch == '\u2610') {
				count++;
			}
		}
		return count;
	}

	private boolean canMergeTiny(PlannedLawChunk left, PlannedLawChunk right, boolean preserveParentBoundaries) {
		if (!canMerge(left, right, preserveParentBoundaries)) {
			return false;
		}
		return length(left.text()) + 1 + length(right.text()) <= MAX_CHILD_CHARS;
	}

	private List<PlannedLawChunk> rebalanceTiny(PlannedLawChunk left, PlannedLawChunk right, boolean preserveParentBoundaries) {
		if (!canMerge(left, right, preserveParentBoundaries)) {
			return List.of();
		}
		PlannedLawChunk merged = merge(left, right);
		List<String> pieces = splitLongText(merged.text());
		if (pieces.size() <= 1
			|| pieces.stream().anyMatch(piece -> length(piece) > MAX_CHILD_CHARS)
			|| pieces.stream().anyMatch(piece -> length(piece) < TINY_CHILD_CHARS)) {
			return List.of();
		}
		List<PlannedLawChunk> chunks = new ArrayList<>();
		for (int index = 0; index < pieces.size(); index++) {
			String suffix = pieces.size() > 1 ? " (" + (index + 1) + "/" + pieces.size() + ")" : "";
			chunks.add(new PlannedLawChunk(
				merged.type(),
				appendSuffix(baseNo(merged.no()), suffix),
				appendSuffix(baseTitle(merged.title()), suffix),
				pieces.get(index),
				merged.sourcePath()
			));
		}
		return chunks;
	}

	private boolean sameMergeFamily(PlannedLawChunk left, PlannedLawChunk right) {
		boolean leftAdministrativeRuleArticle = isAdministrativeRuleArticle(left);
		boolean rightAdministrativeRuleArticle = isAdministrativeRuleArticle(right);
		if (leftAdministrativeRuleArticle || rightAdministrativeRuleArticle) {
			String leftNo = baseNo(left.no());
			return leftAdministrativeRuleArticle
				&& rightAdministrativeRuleArticle
				&& StringUtils.hasText(leftNo)
				&& leftNo.equals(baseNo(right.no()));
		}
		return mergeFamily(left.sourcePath()).equals(mergeFamily(right.sourcePath()));
	}

	private boolean canMerge(PlannedLawChunk left, PlannedLawChunk right, boolean preserveParentBoundaries) {
		return sameMergeFamily(left, right)
			&& (!preserveParentBoundaries || sameCanonicalParent(left, right));
	}

	private boolean sameCanonicalParent(PlannedLawChunk left, PlannedLawChunk right) {
		return canonicalParentSourcePath(left.sourcePath()).equals(canonicalParentSourcePath(right.sourcePath()))
			&& baseNo(left.no()).equals(baseNo(right.no()));
	}

	List<PlannedLawChunk> plan(ChunkPlanningContext context, List<SyncDetailSection> sections) {
		List<PlannedLawChunk> planned = plan(sections, true);
		java.util.Map<String, Integer> nextChildOrders = new java.util.LinkedHashMap<>();
		List<PlannedLawChunk> versioned = new ArrayList<>();
		for (PlannedLawChunk chunk : planned) {
			String parentNumber = baseNo(chunk.no());
			String parentPath = canonicalParentSourcePath(chunk.sourcePath());
			String parentTitle = parentTitle(chunk, context, parentNumber);
			String parentKey = sha256(String.join("\n",
				context.documentTarget().trim(),
				String.valueOf(context.documentId()),
				parentPath,
				parentNumber
			));
			int childOrder = nextChildOrders.getOrDefault(parentKey, 0);
			nextChildOrders.put(parentKey, childOrder + 1);
			String embeddingText = embeddingText(context.documentTitle(), parentNumber, parentTitle, chunk);
			versioned.add(new PlannedLawChunk(
				chunk.type(), chunk.no(), chunk.title(), chunk.text(), chunk.sourcePath(),
				2, parentKey, parentTitle, childOrder, embeddingText, "PASS", null
			));
		}
		return versioned;
	}

	private boolean isAdministrativeRuleArticle(PlannedLawChunk chunk) {
		return chunk != null && "admin-rule-article".equals(chunk.type());
	}

	private String mergeFamily(String sourcePath) {
		if (!StringUtils.hasText(sourcePath)) {
			return "other";
		}
		if (isSupplementarySource(sourcePath)) {
			return "supplementary";
		}
		if (isAppendixSource(sourcePath)) {
			return "appendix";
		}
		if (isRevisionSource(sourcePath)) {
			return "revision";
		}
		if (LAW_ARTICLE_SOURCE_PATH.matcher(sourcePath).matches()) {
			return "article";
		}
		return "other";
	}

	private PlannedLawChunk merge(PlannedLawChunk left, PlannedLawChunk right) {
		return new PlannedLawChunk(
			mergeMetadata(left.type(), right.type(), "mixed"),
			mergeMetadata(left.no(), right.no(), " 등"),
			mergeMetadata(left.title(), right.title(), " 등"),
			left.text().trim() + "\n" + right.text().trim(),
			mergeSourcePath(left.sourcePath(), right.sourcePath())
		);
	}

	private PlannedLawChunk withInferredMetadata(PlannedLawChunk chunk) {
		if (StringUtils.hasText(chunk.no()) && StringUtils.hasText(chunk.title())) {
			return chunk;
		}
		InferredHeading heading = inferHeading(chunk.text());
		if (heading == null) {
			return chunk;
		}
		return new PlannedLawChunk(
			chunk.type(),
			StringUtils.hasText(chunk.no()) ? chunk.no() : heading.no(),
			StringUtils.hasText(chunk.title()) ? chunk.title() : heading.title(),
			chunk.text(),
			chunk.sourcePath()
		);
	}

	private PlannedLawChunk normalizeForStorage(PlannedLawChunk chunk) {
		return new PlannedLawChunk(
			trimToMax(chunk.type(), MAX_STORED_TYPE_CHARS),
			trimToMax(chunk.no(), MAX_STORED_NO_CHARS),
			trimToMax(chunk.title(), MAX_STORED_TITLE_CHARS),
			chunk.text(),
			trimToMax(chunk.sourcePath(), MAX_STORED_SOURCE_PATH_CHARS)
		);
	}

	private String trimToMax(String value, int maxChars) {
		if (!StringUtils.hasText(value)) {
			return value;
		}
		String trimmed = value.trim();
		if (trimmed.length() <= maxChars) {
			return trimmed;
		}
		if (maxChars <= 3) {
			return trimmed.substring(0, maxChars);
		}
		return trimmed.substring(0, maxChars - 3).trim() + "...";
	}

	private InferredHeading inferHeading(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		String value = text.stripLeading();
		var article = ARTICLE_HEADING.matcher(value);
		if (article.find()) {
			String no = article.group(1);
			String suffix = article.group(2) == null ? "" : article.group(2);
			return new InferredHeading(no, no + suffix);
		}
		var appendix = APPENDIX_HEADING.matcher(value);
		if (appendix.find()) {
			String title = appendix.group(1).trim();
			return new InferredHeading(title, title);
		}
		if (value.startsWith("부칙")) {
			return new InferredHeading("부칙", "부칙");
		}
		return null;
	}

	private String mergeMetadata(String left, String right, String suffix) {
		if (!StringUtils.hasText(left)) {
			return right;
		}
		if (!StringUtils.hasText(right) || left.equals(right)) {
			return left;
		}
		if (StringUtils.hasText(suffix) && left.endsWith(suffix)) {
			return left;
		}
		return left + suffix;
	}

	private String mergeSourcePath(String left, String right) {
		if (!StringUtils.hasText(left)) {
			return right;
		}
		if (!StringUtils.hasText(right) || left.equals(right)) {
			return left;
		}
		return left;
	}

	private int length(String value) {
		return value == null ? 0 : value.length();
	}

	private void flush(List<PlannedLawChunk> planned, SectionGroup group) {
		if (group == null || group.texts.isEmpty()) {
			return;
		}
		List<String> children = finalChildren(group, splitGroup(group.texts));
		for (int index = 0; index < children.size(); index++) {
			String child = children.get(index);
			String suffix = children.size() > 1 ? " (" + (index + 1) + "/" + children.size() + ")" : "";
			planned.add(new PlannedLawChunk(
				group.type,
				appendSuffix(fallbackNo(group, child), suffix),
				appendSuffix(fallbackTitle(group, child), suffix),
				child,
				group.sourcePath
			));
		}
	}

	private List<String> finalChildren(SectionGroup group, List<String> rawChildren) {
		List<String> children = new ArrayList<>();
		for (String rawChild : rawChildren) {
			if (isLowSignalAppendixChild(group, rawChild)) {
				continue;
			}
			String child = LawTextUtils.stripHtmlTags(normalizeChildText(group, rawChild));
			if (!StringUtils.hasText(child) || isLowSignalAppendixChild(group, child)) {
				continue;
			}
			List<String> pieces = child.length() > MAX_CHILD_CHARS ? splitLongText(child) : List.of(child);
			for (String piece : pieces) {
				String normalized = LawTextUtils.normalizeText(piece);
				if (StringUtils.hasText(normalized)
					&& !isLowSignalAppendixChild(group, normalized)
					&& !isLowSignalTinyAppendixFragment(group, normalized)
					&& !isLowSignalTinyFormTail(normalized)) {
					children.add(normalized);
				}
			}
		}
		return children;
	}

	private boolean isLowSignalAppendixChild(SectionGroup group, String text) {
		if (!isAppendixGroup(group)) {
			return false;
		}
		if (length(text) < TINY_CHILD_CHARS
			&& inferHeading(text) == null
			&& !hasFormFieldMarker(text)
			&& !KOREAN_LEGAL_CUE.matcher(text).find()) {
			return true;
		}
		if (length(text) < 120 && inferHeading(text) == null && signalLength(text) < 20) {
			return true;
		}
		return isSparseFormDecoration(text);
	}

	private boolean isLowSignalTinyAppendixFragment(SectionGroup group, String text) {
		if (!isAppendixGroup(group) || length(text) >= TINY_CHILD_CHARS || !StringUtils.hasText(text)) {
			return false;
		}
		String normalized = text.replaceAll("\\s+", " ").trim();
		if (isCitationOnlyFragment(normalized)) {
			return true;
		}
		if (normalized.contains("<의견>") || normalized.contains("의견>")) {
			return true;
		}
		if (normalized.contains("정기안전검사") && normalized.contains("규칙」")) {
			return true;
		}
		int formTerms = 0;
		for (String term : TINY_APPENDIX_FORM_TERMS) {
			if (normalized.contains(term)) {
				formTerms++;
			}
		}
		if (hasFormFieldMarker(normalized) && formTerms > 0) {
			return true;
		}
		if (normalized.contains("|")) {
			long cells = List.of(normalized.split("\\|")).stream()
				.map(String::trim)
				.filter(StringUtils::hasText)
				.count();
			return formTerms >= 2
				|| cells >= 6
				|| normalized.endsWith("|")
				|| normalized.startsWith("|");
		}
		return formTerms >= 2;
	}

	private boolean isCitationOnlyFragment(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		String compact = text.replaceAll("[\\s,ㆍ·및과와]+", "");
		if (!compact.startsWith("제")) {
			return false;
		}
		String stripped = compact
			.replaceAll("제\\d+조(?:의\\d+)?(?:제\\d+항)?(?:제\\d+호)?", "")
			.replaceAll("제\\d+항(?:제\\d+호)?", "")
			.replaceAll("제\\d+호", "")
			.replaceAll("제\\d+목", "");
		return stripped.isEmpty();
	}

	private String normalizeChildText(SectionGroup group, String text) {
		if (boxDrawingLength(text) == 0) {
			return text;
		}
		return normalizeAppendixTableText(text);
	}

	private String normalizeAppendixTableText(String text) {
		if (!StringUtils.hasText(text) || boxDrawingLength(text) == 0) {
			return text;
		}
		List<String> lines = new ArrayList<>();
		for (String line : text.split("\\R")) {
			String normalized = normalizeAppendixTableLine(line);
			if (!StringUtils.hasText(normalized)) {
				continue;
			}
			if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(normalized)) {
				continue;
			}
			lines.add(normalized);
		}
		return LawTextUtils.normalizeText(String.join("\n", lines));
	}

	private String normalizeAppendixTableLine(String line) {
		if (!StringUtils.hasText(line)) {
			return "";
		}
		String normalized = line
			.replace('\u2502', '|')
			.replace('\u2503', '|')
			.replace('\u2551', '|')
			.replaceAll("[\\u2500-\\u257f]+", " ")
			.replaceAll("[ \\t]+", " ")
			.replaceAll("\\s*\\|\\s*", " | ")
			.trim()
			.replaceAll("(?:\\|\\s*){2,}", "| ")
			.replaceAll("^(?:\\|\\s*)+", "")
			.replaceAll("(?:\\s*\\|)+$", "")
			.trim();
		if (!StringUtils.hasText(normalized)) {
			return "";
		}
		if (!StringUtils.hasText(normalized.replace("|", "").trim())) {
			return "";
		}
		return normalized;
	}

	private int signalLength(String text) {
		if (!StringUtils.hasText(text)) {
			return 0;
		}
		int count = 0;
		for (int index = 0; index < text.length(); index++) {
			if (Character.isLetterOrDigit(text.charAt(index))) {
				count++;
			}
		}
		return count;
	}

	private boolean isSparseFormDecoration(String text) {
		int textLength = length(text);
		int signal = signalLength(text);
		int boxDrawing = boxDrawingLength(text);
		if (textLength >= 800 || boxDrawing < 4) {
			return false;
		}
		if (hasFormFieldMarker(text)) {
			return false;
		}
		if (textLength < 650
			&& signal < 140
			&& hangulLength(text) < 10
			&& !KOREAN_LEGAL_CUE.matcher(text).find()) {
			return true;
		}
		if (hangulLength(text) >= 10 || KOREAN_LEGAL_CUE.matcher(text).find()) {
			return false;
		}
		return signal < 45
			&& boxDrawing * 3 >= signal;
	}

	private int hangulLength(String text) {
		if (!StringUtils.hasText(text)) {
			return 0;
		}
		int count = 0;
		for (int index = 0; index < text.length(); index++) {
			char ch = text.charAt(index);
			if (ch >= '\uac00' && ch <= '\ud7a3') {
				count++;
			}
		}
		return count;
	}

	private boolean hasFormFieldMarker(String text) {
		if (!StringUtils.hasText(text)) {
			return false;
		}
		return text.indexOf('\u25a1') >= 0
			|| text.indexOf('\u2610') >= 0;
	}

	private int boxDrawingLength(String text) {
		if (!StringUtils.hasText(text)) {
			return 0;
		}
		int count = 0;
		for (int index = 0; index < text.length(); index++) {
			char ch = text.charAt(index);
			if (ch >= '\u2500' && ch <= '\u257f') {
				count++;
			}
		}
		return count;
	}

	private String inferAppendixFieldHeading(String text) {
		if (!StringUtils.hasText(text)) {
			return null;
		}
		for (String line : text.split("\\R")) {
			String cleaned = cleanAppendixFieldLine(line);
			if (isUsefulAppendixFieldHeading(cleaned)) {
				return cleaned;
			}
		}
		return null;
	}

	private String cleanAppendixFieldLine(String line) {
		if (!StringUtils.hasText(line)) {
			return "";
		}
		return line
			.replace('\u2502', ' ')
			.replace('\u2500', ' ')
			.replace('\u251c', ' ')
			.replace('\u2524', ' ')
			.replace('\u2514', ' ')
			.replace('\u2518', ' ')
			.replace('\u250c', ' ')
			.replace('\u2510', ' ')
			.replaceAll("^[\\s\\u2500-\\u257f\\u25a1\\u2610\\[\\](){}<>:;.,*\\-]+", "")
			.replaceAll("\\s*\\|.*$", "")
			.replaceAll("\\s{2,}.*$", "")
			.trim();
	}

	private boolean isUsefulAppendixFieldHeading(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		int signal = signalLength(value);
		return signal >= 4
			&& signal <= 60
			&& value.length() <= 80;
	}

	private String fallbackNo(SectionGroup group, String text) {
		if (StringUtils.hasText(group.no)) {
			return group.no;
		}
		InferredHeading heading = inferHeading(text);
		if (heading != null && StringUtils.hasText(heading.no())) {
			return heading.no();
		}
		if (isAppendixGroup(group)) {
			String appendixHeading = inferAppendixFieldHeading(text);
			if (StringUtils.hasText(appendixHeading)) {
				return appendixHeading;
			}
			return indexedSourceLabel(group.sourcePath, "별표/서식");
		}
		if (isSupplementarySource(group.sourcePath)) {
			return indexedSourceLabel(group.sourcePath, "부칙");
		}
		if (isRevisionReasonSource(group.sourcePath)) {
			return "제개정이유";
		}
		return fallbackSourceHeading(group);
	}

	private String fallbackTitle(SectionGroup group, String text) {
		if (StringUtils.hasText(group.title)) {
			return group.title;
		}
		InferredHeading heading = inferHeading(text);
		if (heading != null && StringUtils.hasText(heading.title())) {
			return heading.title();
		}
		if (isAppendixGroup(group)) {
			String appendixHeading = inferAppendixFieldHeading(text);
			if (StringUtils.hasText(appendixHeading)) {
				return appendixHeading;
			}
			return indexedSourceLabel(group.sourcePath, "별표/서식");
		}
		if (isSupplementarySource(group.sourcePath)) {
			return indexedSourceLabel(group.sourcePath, "부칙");
		}
		if (isRevisionReasonSource(group.sourcePath)) {
			return "제개정이유";
		}
		return fallbackSourceHeading(group);
	}

	private String fallbackSourceHeading(SectionGroup group) {
		if (group == null) {
			return null;
		}
		String sourcePath = group.sourcePath;
		if (isAppendixGroup(group)) {
			return indexedSourceLabel(sourcePath, "별표/서식");
		}
		if (isSupplementarySource(sourcePath)) {
			return indexedSourceLabel(sourcePath, "부칙");
		}
		if (isRevisionSource(sourcePath)) {
			if (StringUtils.hasText(sourcePath) && sourcePath.contains("개정문")) {
				return "개정문";
			}
			return "제개정이유";
		}
		if (isLawArticleSource(sourcePath)) {
			return indexedSourceLabel(sourcePath, "조문");
		}
		if (StringUtils.hasText(sourcePath) && sourcePath.contains("조문")) {
			return fallbackTypeHeading(group.type, "조문");
		}
		return fallbackTypeHeading(group.type, "본문");
	}

	private String indexedSourceLabel(String sourcePath, String baseLabel) {
		if (!StringUtils.hasText(sourcePath)) {
			return baseLabel;
		}
		var matcher = SOURCE_UNIT_INDEX.matcher(sourcePath);
		if (!matcher.find()) {
			return baseLabel;
		}
		try {
			int oneBasedIndex = Integer.parseInt(matcher.group(2)) + 1;
			return baseLabel + " " + oneBasedIndex;
		}
		catch (NumberFormatException ignored) {
			return baseLabel;
		}
	}

	private String fallbackTypeHeading(String type, String defaultValue) {
		if ("article".equals(type) || "paragraph".equals(type) || "subparagraph".equals(type)) {
			return "조문";
		}
		if ("admin-rule-article".equals(type)) {
			return "행정규칙 조문";
		}
		if ("appendix".equals(type)) {
			return "별표/서식";
		}
		return defaultValue;
	}

	private boolean isAppendixSource(String sourcePath) {
		return StringUtils.hasText(sourcePath) && sourcePath.contains("별표");
	}

	private boolean isAppendixGroup(SectionGroup group) {
		return group != null && ("appendix".equals(group.type) || isAppendixSource(group.sourcePath));
	}

	private boolean isSupplementarySource(String sourcePath) {
		return StringUtils.hasText(sourcePath) && sourcePath.contains("부칙");
	}

	private boolean isRevisionReasonSource(String sourcePath) {
		return StringUtils.hasText(sourcePath) && sourcePath.contains("제개정이유");
	}

	private boolean isRevisionSource(String sourcePath) {
		return StringUtils.hasText(sourcePath)
			&& (sourcePath.contains("개정문") || sourcePath.contains("개정이유") || sourcePath.contains("제개정이유"));
	}

	private boolean isLawArticleSource(String sourcePath) {
		return StringUtils.hasText(sourcePath) && LAW_ARTICLE_SOURCE_PATH.matcher(sourcePath).matches();
	}

	private List<String> splitGroup(List<String> texts) {
		List<String> children = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String text : texts) {
			if (text.length() > MAX_CHILD_CHARS) {
				flushCurrent(children, current);
				children.addAll(splitLongText(text));
				continue;
			}
			int nextLength = current.length() + (current.isEmpty() ? 0 : 1) + text.length();
			if (nextLength > MAX_CHILD_CHARS) {
				flushCurrent(children, current);
			}
			if (!current.isEmpty()) {
				current.append('\n');
			}
			current.append(text);
		}
		flushCurrent(children, current);
		return children;
	}

	private void flushCurrent(List<String> children, StringBuilder current) {
		if (current.isEmpty()) {
			return;
		}
		String text = current.toString().trim();
		if (StringUtils.hasText(text)) {
			children.add(text);
		}
		current.setLength(0);
	}

	private List<String> splitLongText(String text) {
		List<String> pieces = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			int end = Math.min(start + MAX_CHILD_CHARS, text.length());
			if (end < text.length()) {
				int boundary = splitBoundary(text, start, end);
				if (boundary > start) {
					end = boundary;
				}
				end = avoidTinyTail(text, start, end);
			}
			String piece = text.substring(start, end).trim();
			if (StringUtils.hasText(piece)) {
				pieces.add(piece);
			}
			if (end >= text.length()) {
				break;
			}
			start = Math.max(end - OVERLAP_CHARS, start + 1);
		}
		return pieces;
	}

	private int avoidTinyTail(String text, int start, int end) {
		int remaining = text.length() - end;
		if (remaining == 0 || remaining >= TINY_CHILD_CHARS) {
			return end;
		}
		if (text.length() - start <= MAX_CHILD_CHARS) {
			return text.length();
		}
		int adjusted = text.length() - TINY_CHILD_CHARS;
		int min = start + Math.max(TINY_CHILD_CHARS, MAX_CHILD_CHARS / 2);
		if (adjusted >= min && adjusted <= start + MAX_CHILD_CHARS) {
			return adjusted;
		}
		return end;
	}

	private int splitBoundary(String text, int start, int preferredEnd) {
		int min = start + Math.max(1, MAX_CHILD_CHARS / 2);
		for (int index = preferredEnd; index > min; index--) {
			char value = text.charAt(index - 1);
			if (value == '\n' || value == '.' || value == '。' || value == ';' || value == '；') {
				return index;
			}
		}
		int space = text.lastIndexOf(' ', preferredEnd);
		return space > min ? space : preferredEnd;
	}

	private String parentKey(SyncDetailSection section) {
		String title = parentTitle(section);
		if (StringUtils.hasText(title)) {
			return "title:" + title;
		}
		String no = baseNo(section.no());
		if (StringUtils.hasText(no)) {
			return "no:" + no;
		}
		String sourcePath = parentSourcePath(section);
		if (StringUtils.hasText(sourcePath)) {
			return "path:" + sourcePath;
		}
		return "fallback";
	}

	private String parentTitle(SyncDetailSection section) {
		String title = baseTitle(section.title());
		if (!StringUtils.hasText(title) || isGenericTitle(title)) {
			return "";
		}
		if (title.startsWith("$.")) {
			return "";
		}
		return title;
	}

	private String baseTitle(String title) {
		if (!StringUtils.hasText(title)) {
			return "";
		}
		String value = PARAGRAPH_LINE_SUFFIX.matcher(title.trim()).replaceFirst("");
		value = SPLIT_SUFFIX.matcher(value).replaceFirst("");
		return value.trim();
	}

	private String baseNo(String no) {
		if (!StringUtils.hasText(no)) {
			return "";
		}
		String value = PARAGRAPH_NO_SUFFIX.matcher(no.trim()).replaceFirst("");
		value = SPLIT_SUFFIX.matcher(value).replaceFirst("");
		return value.trim();
	}

	private boolean isGenericTitle(String title) {
		return GENERIC_TITLE.matcher(title.trim()).matches();
	}

	private String parentSourcePath(SyncDetailSection section) {
		String sourcePath = section.sourcePath();
		if (!StringUtils.hasText(sourcePath) && StringUtils.hasText(section.title()) && section.title().startsWith("$.")) {
			sourcePath = section.title();
		}
		if (!StringUtils.hasText(sourcePath)) {
			return "";
		}
		return canonicalParentSourcePath(sourcePath);
	}

	private String canonicalParentSourcePath(String sourcePath) {
		if (!StringUtils.hasText(sourcePath)) {
			return "";
		}
		sourcePath = sourcePath.replaceFirst("#\\d+$", "");
		var article = LAW_ARTICLE_SOURCE_PATH.matcher(sourcePath);
		if (article.matches()) {
			return article.group(1);
		}
		var appendix = LAW_APPENDIX_SOURCE_PATH.matcher(sourcePath);
		if (appendix.matches()) {
			return appendix.group(1);
		}
		return sourcePath.replaceFirst("\\[[0-9]+]$", "");
	}

	private String parentTitle(PlannedLawChunk chunk, ChunkPlanningContext context, String parentNumber) {
		String title = baseTitle(chunk.title());
		if (StringUtils.hasText(title) && !isGenericTitle(title)) {
			return title;
		}
		if (StringUtils.hasText(parentNumber)) {
			return parentNumber;
		}
		return StringUtils.hasText(context.documentTitle()) ? context.documentTitle().trim() : "Document section";
	}

	private String embeddingText(String documentTitle, String parentNumber, String parentTitle, PlannedLawChunk chunk) {
		return String.join("\n",
			nullToEmpty(documentTitle),
			nullToEmpty(parentNumber),
			nullToEmpty(parentTitle),
			nullToEmpty(chunk.title()),
			nullToEmpty(chunk.type()),
			nullToEmpty(chunk.text())
		).trim();
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private String sectionType(SyncDetailSection section) {
		String type = section.type();
		if (!StringUtils.hasText(type)) {
			return "text";
		}
		return type.trim();
	}

	private String appendSuffix(String value, String suffix) {
		if (!StringUtils.hasText(suffix)) {
			return value;
		}
		if (StringUtils.hasText(value)) {
			return value + suffix;
		}
		return suffix.trim();
	}

	private static final class SectionGroup {
		private final String key;
		private final String type;
		private String no;
		private String title;
		private final String sourcePath;
		private final List<String> texts = new ArrayList<>();

		private SectionGroup(String key, String type, String no, String title, String sourcePath) {
			this.key = key;
			this.type = type;
			this.no = no;
			this.title = title;
			this.sourcePath = sourcePath;
		}

		private void add(String text) {
			texts.add(text);
		}

		private void applyHeadingIfMissing(InferredHeading heading) {
			if (heading == null) {
				return;
			}
			if (!StringUtils.hasText(no)) {
				no = heading.no();
			}
			if (!StringUtils.hasText(title)) {
				title = heading.title();
			}
		}
	}

	private record InferredHeading(String no, String title) {
	}
}
