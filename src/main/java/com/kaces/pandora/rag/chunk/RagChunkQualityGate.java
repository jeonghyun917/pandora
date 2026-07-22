package com.kaces.pandora.rag.chunk;

import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RagChunkQualityGate {

	private static final int SHORT_REVIEW_MAX_CHARS = 120;
	private static final int CONTEXT_ONLY_MAX_CHARS = 160;
	private static final Pattern SENTENCE_END = Pattern.compile("(?iu).*(?:다[.!?]?|함[.!?]?|됨[.!?]?|한다[.!?]?|한다는\s*것이다[.!?]?)$");
	private static final Pattern FIELD_LABEL = Pattern.compile(
		"(?iu)^(?:요구사항(?:분류|고유번호|명칭)|관련요구사항|준수항목|requirement\s*(?:id|name|type))\s*[:：-]?.{0,50}$"
	);
	private static final List<String> NAVIGATION_TERMS = List.of(
		"상단", "메뉴", "버튼", "첨부파일", "다운로드", "클릭", "이용하십시오",
		"download", "attachment", "click", "menu", "button"
	);
	private static final List<String> NAVIGATION_ACTION_TERMS = List.of(
		"클릭", "선택", "누르", "이동", "다운로드", "열기", "확인",
		"click", "select", "press", "move", "download", "open", "confirm"
	);

	public Result evaluate(String documentTitle, List<RagDocumentChunkRow> chunks) {
		if (chunks == null || chunks.isEmpty()) {
			return new Result(List.of(), List.of(), 0, 0, 0, 0);
		}
		List<RagDocumentChunkRow> retained = new ArrayList<>();
		List<RejectedChunk> rejected = new ArrayList<>();
		Set<String> seenBodies = new LinkedHashSet<>();
		int pass = 0;
		int review = 0;
		int contextOnly = 0;

		for (RagDocumentChunkRow chunk : chunks) {
			Decision decision = classify(documentTitle, chunk, seenBodies);
			if (decision.status() == RagChunkQualityStatus.REJECT) {
				rejected.add(new RejectedChunk(chunk, decision.reason()));
				continue;
			}
			retained.add(withQuality(chunk, decision));
			switch (decision.status()) {
				case PASS -> pass++;
				case REVIEW -> review++;
				case CONTEXT_ONLY -> contextOnly++;
				case REJECT -> { }
			}
		}
		return new Result(List.copyOf(retained), List.copyOf(rejected), pass, review, contextOnly, rejected.size());
	}

	private Decision classify(String documentTitle, RagDocumentChunkRow chunk, Set<String> seenBodies) {
		if (chunk == null) {
			return new Decision(RagChunkQualityStatus.REJECT, "NULL_CHUNK");
		}
		String visible = visibleText(chunk.chunkText());
		String embedding = visibleText(chunk.embeddingText());
		if (visible.isBlank()) {
			return new Decision(RagChunkQualityStatus.REJECT, "EMPTY_TEXT");
		}
		if (embedding.isBlank()) {
			return new Decision(RagChunkQualityStatus.REJECT, "EMPTY_EMBEDDING_TEXT");
		}
		if ("toc".equalsIgnoreCase(String.valueOf(chunk.sectionType()))
			|| RagTextNoiseFilter.isTableOfContents(chunk.chunkTitle(), visible)) {
			return new Decision(RagChunkQualityStatus.REJECT, "TABLE_OF_CONTENTS");
		}
		if (RagTextNoiseFilter.isMeaninglessSection(chunk.chunkTitle(), visible)) {
			return new Decision(RagChunkQualityStatus.REJECT, "MEANINGLESS_FRAGMENT");
		}

		String bodyKey = compact(visible);
		if (!bodyKey.isBlank() && !seenBodies.add(bodyKey)) {
			return new Decision(RagChunkQualityStatus.REJECT, "DUPLICATE_TEXT");
		}
		if (FIELD_LABEL.matcher(visible).matches()) {
			return new Decision(RagChunkQualityStatus.CONTEXT_ONLY, "FIELD_LABEL_ONLY");
		}
		if (visible.length() <= CONTEXT_ONLY_MAX_CHARS && isNavigationInstruction(visible)) {
			if (hasNavigationSubject(visible)) {
				return new Decision(RagChunkQualityStatus.REVIEW, "NAVIGATION_WITH_SUBJECT");
			}
			return new Decision(RagChunkQualityStatus.CONTEXT_ONLY, "NAVIGATION_NOTICE");
		}
		if (visible.length() <= CONTEXT_ONLY_MAX_CHARS && isTitleOrHeadingOnly(documentTitle, chunk, visible)) {
			return new Decision(RagChunkQualityStatus.CONTEXT_ONLY, "TITLE_OR_HEADING_ONLY");
		}
		if (visible.length() < SHORT_REVIEW_MAX_CHARS) {
			if (SENTENCE_END.matcher(visible).matches()) {
				return new Decision(RagChunkQualityStatus.PASS, "SHORT_COMPLETE_STATEMENT");
			}
			return new Decision(RagChunkQualityStatus.REVIEW, "SHORT_AMBIGUOUS");
		}
		return new Decision(RagChunkQualityStatus.PASS, "STRUCTURAL_CHECKS_PASSED");
	}

	private boolean isNavigationInstruction(String value) {
		String normalized = value.toLowerCase(Locale.ROOT);
		boolean hasNavigationTerm = NAVIGATION_TERMS.stream().anyMatch(normalized::contains)
			|| normalized.contains(">")
			|| normalized.contains("→");
		boolean hasNavigationAction = NAVIGATION_ACTION_TERMS.stream().anyMatch(normalized::contains);
		return hasNavigationTerm && hasNavigationAction && !SENTENCE_END.matcher(normalized).matches();
	}

	private boolean hasNavigationSubject(String value) {
		String remainder = value.toLowerCase(Locale.ROOT);
		for (String term : NAVIGATION_TERMS) {
			remainder = remainder.replace(term, " ");
		}
		for (String term : NAVIGATION_ACTION_TERMS) {
			remainder = remainder.replace(term, " ");
		}
		remainder = remainder
			.replaceAll("(?iu)(?:화면|페이지|해당|메뉴경로)", " ")
			.replaceAll("[^\\p{L}\\p{N}]", "");
		return remainder.length() >= 4;
	}

	private boolean isTitleOrHeadingOnly(String documentTitle, RagDocumentChunkRow chunk, String value) {
		if (SENTENCE_END.matcher(value).matches()) {
			return false;
		}
		long nonBlankLines = value.lines().map(String::trim).filter(line -> !line.isBlank()).count();
		if (nonBlankLines > 3) {
			return false;
		}
		String text = compact(value);
		String title = compact(documentTitle);
		String headings = compact(String.join(" ",
			nullToEmpty(chunk.parentSectionTitle()),
			nullToEmpty(chunk.chunkTitle())
		));
		return (!title.isBlank() && (title.contains(text) || text.contains(title)))
			|| (!headings.isBlank() && headings.contains(text));
	}

	private String visibleText(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("<[^>]+>", " ")
			.replace("&nbsp;", " ")
			.replace("&lt;", " ")
			.replace("&gt;", " ")
			.replace("&amp;", " ")
			.replaceAll("[\\p{Cc}&&[^\\r\\n\\t]]", " ")
			.replaceAll("[ \\t]+", " ")
			.replaceAll("\\R{3,}", "\n\n")
			.trim();
	}

	private String compact(String value) {
		return visibleText(value)
			.toLowerCase(Locale.ROOT)
			.replaceAll("[\\s\\p{Punct}·ㆍ]+", "");
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	private RagDocumentChunkRow withQuality(RagDocumentChunkRow chunk, Decision decision) {
		return new RagDocumentChunkRow(
			chunk.chunkId(),
			chunk.documentId(),
			chunk.chunkVersion(),
			chunk.chunkNo(),
			chunk.parentSectionTitle(),
			chunk.chunkTitle(),
			chunk.sectionType(),
			chunk.chunkText(),
			chunk.embeddingText(),
			chunk.pageNo(),
			chunk.sourcePath(),
			chunk.sourceUrl(),
			chunk.sortOrder(),
			chunk.contentHash(),
			decision.status().name(),
			decision.reason()
		);
	}

	private record Decision(RagChunkQualityStatus status, String reason) {
	}

	public record RejectedChunk(RagDocumentChunkRow chunk, String reason) {
	}

	public record Result(
		List<RagDocumentChunkRow> retainedChunks,
		List<RejectedChunk> rejectedChunks,
		int passCount,
		int reviewCount,
		int contextOnlyCount,
		int rejectedCount
	) {
		public int searchableCount() {
			return passCount + reviewCount;
		}
	}
}
