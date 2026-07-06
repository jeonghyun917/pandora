package com.kaces.pandora.rag.chunk;


import com.kaces.pandora.rag.importing.ExtractedDocument;
import com.kaces.pandora.rag.importing.ExtractedPage;
import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class RagChunker {
	public static final int V2_CHUNK_VERSION = 2;
	public static final int V3_CHUNK_VERSION = 3;
	public static final int V4_CHUNK_VERSION = 4;
	private static final int MAX_CHARS = 1700;
	private static final int MIN_CHARS = 220;
	private static final int LONG_BLOCK_OVERLAP_CHARS = 160;
	private final RagHeadingDetector headingDetector = new RagHeadingDetector();

	// 메소드 설명: chunk 처리 흐름을 수행합니다.
	public List<RagDocumentChunkRow> chunk(long documentId, ExtractedDocument document, String sourceUrl) {
		List<RagDocumentChunkRow> chunks = new ArrayList<>();
		int[] sortOrder = {0};
		for (ExtractedPage page : document.pages()) {
			for (String text : splitPage(page.text())) {
				String title = inferTitle(text, page.pageNo(), sortOrder[0] + 1);
				if (RagTextNoiseFilter.isTableOfContents(title, text)) {
					continue;
				}
				String chunkNo = page.pageNo() == null ? "chunk " + (sortOrder[0] + 1) : "page " + page.pageNo();
				String sourcePath = page.pageNo() == null ? "$.chunks[" + sortOrder[0] + "]" : "$.pages[" + page.pageNo() + "]";
				chunks.add(new RagDocumentChunkRow(
					0,
					documentId,
					chunkNo,
					title,
					text,
					page.pageNo(),
					sourcePath,
					sourceUrl,
					sortOrder[0]++,
					LawHashUtils.sha256(text)
				));
			}
		}
		return chunks;
	}

	public List<RagDocumentChunkRow> chunkV2(
		long documentId,
		ExtractedDocument document,
		String sourceUrl,
		String documentTitle
	) {
		return chunkVersioned(documentId, document, sourceUrl, documentTitle, V2_CHUNK_VERSION);
	}

	public List<RagDocumentChunkRow> chunkV3(
		long documentId,
		ExtractedDocument document,
		String sourceUrl,
		String documentTitle
	) {
		return chunkVersioned(documentId, document, sourceUrl, documentTitle, V3_CHUNK_VERSION);
	}

	public List<RagDocumentChunkRow> chunkV4(
		long documentId,
		ExtractedDocument document,
		String sourceUrl,
		String documentTitle
	) {
		return chunkVersioned(documentId, document, sourceUrl, documentTitle, V4_CHUNK_VERSION);
	}

	private List<RagDocumentChunkRow> chunkVersioned(
		long documentId,
		ExtractedDocument document,
		String sourceUrl,
		String documentTitle,
		int chunkVersion
	) {
		List<RagDocumentChunkRow> chunks = new ArrayList<>();
		int[] sortOrder = {0};
		String[] parentSection = {""};
		for (ExtractedPage page : document.pages()) {
			for (String text : splitPage(page.text())) {
				String title = inferTitle(text, page.pageNo(), sortOrder[0] + 1);
				if (RagTextNoiseFilter.isTableOfContents(title, text)) {
					continue;
				}
				String cleanTitle = stripPagePrefix(title);
				String firstHeading = firstHeadingLine(text);
				if (!firstHeading.isBlank()) {
					parentSection[0] = firstHeading;
				}
				String parentTitle = parentSection[0].isBlank() ? cleanTitle : parentSection[0];
				String sectionType = inferSectionType(parentTitle, cleanTitle, text);
				String semanticTitle = findSemanticSectionTitle(text, sectionType);
				if (!semanticTitle.isBlank()) {
					cleanTitle = semanticTitle;
					parentTitle = semanticTitle;
					title = page.pageNo() == null ? semanticTitle : "p." + page.pageNo() + " " + semanticTitle;
				}
				String chunkNo = page.pageNo() == null ? "chunk " + (sortOrder[0] + 1) : "page " + page.pageNo();
				String versionPrefix = "$.v" + chunkVersion;
				String sourcePath = page.pageNo() == null
					? versionPrefix + ".chunks[" + sortOrder[0] + "]"
					: versionPrefix + ".pages[" + page.pageNo() + "]";
				String embeddingText = chunkVersion >= V3_CHUNK_VERSION
					? buildEmbeddingTextV3(documentTitle, parentTitle, cleanTitle, sectionType, page.pageNo(), text)
					: buildEmbeddingText(documentTitle, parentTitle, cleanTitle, sectionType, page.pageNo(), text);
				chunks.add(new RagDocumentChunkRow(
					0,
					documentId,
					chunkVersion,
					chunkNo,
					parentTitle,
					title,
					sectionType,
					text,
					embeddingText,
					page.pageNo(),
					sourcePath,
					sourceUrl,
					sortOrder[0]++,
					LawHashUtils.sha256(embeddingText)
				));
			}
		}
		return chunks;
	}

	List<String> splitPage(String text) {
		String normalized = normalizePageText(text);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean currentSemanticBlock = false;
		for (String block : splitBlocks(normalized)) {
			if (block.isBlank()) {
				continue;
			}
			boolean semanticBlock = isSemanticHeadingLine(firstLine(block));
			boolean mergeWithCurrent = current.length() > 0 && semanticBlock
				&& shouldMergeDanglingSemanticLead(current.toString(), block);
			if (!mergeWithCurrent && current.length() > 0 && currentSemanticBlock && !semanticBlock) {
				mergeWithCurrent = shouldMergeShortSemanticLead(current.toString(), block);
			}
			if (current.length() > 0 && semanticBlock && !mergeWithCurrent) {
				appendPendingChunk(chunks, current.toString(), currentSemanticBlock);
				current.setLength(0);
				currentSemanticBlock = false;
			}
			if (!isHeadingLine(firstLine(block)) && RagTextNoiseFilter.isMeaninglessSection("", block)) {
				continue;
			}
			if (current.length() > 0 && !mergeWithCurrent && current.length() + block.length() + 1 > MAX_CHARS) {
				appendPendingChunk(chunks, current.toString(), currentSemanticBlock);
				current.setLength(0);
				currentSemanticBlock = false;
			}
			if (block.length() > MAX_CHARS) {
				if (current.length() > 0) {
					appendPendingChunk(chunks, current.toString(), currentSemanticBlock);
					current.setLength(0);
					currentSemanticBlock = false;
				}
				chunks.addAll(splitLongBlock(block));
				continue;
			}
			if (current.length() > 0) {
				current.append('\n');
			}
			if (current.length() == 0) {
				currentSemanticBlock = semanticBlock;
			}
			current.append(block);
		}
		if (current.length() > 0) {
			appendPendingChunk(chunks, current.toString(), currentSemanticBlock);
		}
		return chunks;
	}

	private void appendPendingChunk(List<String> chunks, String text, boolean semanticBlock) {
		String value = String.valueOf(text == null ? "" : text).trim();
		if (value.isBlank()) {
			return;
		}
		if (RagTextNoiseFilter.isMeaninglessSection("", value)) {
			return;
		}
		if (semanticBlock && isDanglingSemanticOnly(value)) {
			return;
		}
		if (!chunks.isEmpty() && value.length() < MIN_CHARS && !semanticBlock) {
			chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "\n" + value);
			return;
		}
		chunks.add(value);
	}

	private boolean shouldMergeShortSemanticLead(String current, String next) {
		if (current == null || next == null || current.isBlank() || next.isBlank()) {
			return false;
		}
		if (current.length() >= MIN_CHARS || current.length() + next.length() + 1 > MAX_CHARS) {
			return false;
		}
		String compact = current.replaceAll("\\s+", "");
		return compact.length() <= 80;
	}

	private boolean isDanglingSemanticOnly(String text) {
		String value = String.valueOf(text == null ? "" : text).trim();
		if (value.isBlank()) {
			return true;
		}
		List<String> lines = value.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.toList();
		String compact = value.replaceAll("\\s+", "");
		if (compact.length() > 40 || lines.size() > 3) {
			return false;
		}
		if (compact.matches(".*(?:다|니다|한다|한다\\.|됩니다|입니다|[.。]).*")) {
			return false;
		}
		return lines.stream().allMatch(line -> isSemanticHeadingLine(line) || RagTextNoiseFilter.isMeaninglessSection("", line));
	}

	private boolean shouldMergeDanglingSemanticLead(String current, String next) {
		if (current == null || next == null || current.isBlank() || next.isBlank()) {
			return false;
		}
		if (current.length() + next.length() + 1 > MAX_CHARS) {
			return false;
		}
		String currentNormalized = normalizeForType(current);
		String nextNormalized = normalizeForType(next);
		boolean targetLead = containsAny(currentNormalized, List.of(
			"사전협의대상사업",
			"적용대상사업",
			"대상사업",
			"대상기관",
			"검토대상",
			"지원대상",
			"대상시스템"
		));
		boolean targetContinuation = containsAny(nextNormalized, List.of(
			"대상기관이추진하는모든정보화사업",
			"국가기관등이발주하는모든sw사업",
			"모든정보화사업",
			"모든sw사업",
			"모든소프트웨어사업"
		));
		if (targetLead && targetContinuation && endsWithDanglingMarker(current)) {
			return true;
		}
		boolean requirementLead = containsAny(currentNormalized, List.of(
			"제출서류",
			"필수항목",
			"요구사항",
			"기재사항",
			"평가요소",
			"평가방법"
		));
		boolean requirementContinuation = containsAny(nextNormalized, List.of(
			"제출하여야",
			"작성하여야",
			"명시하여야",
			"포함하여야",
			"평가하여"
		));
		return requirementLead && requirementContinuation && endsWithDanglingMarker(current);
	}

	private boolean endsWithDanglingMarker(String text) {
		String lastLine = String.valueOf(text == null ? "" : text)
			.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.reduce((previous, current) -> current)
			.orElse("");
		if (lastLine.matches("^[*※]+$")) {
			return true;
		}
		String clean = stripHeadingBullet(lastLine);
		return clean.endsWith("은")
			|| clean.endsWith("는")
			|| clean.endsWith(":")
			|| clean.endsWith("관계없이")
			|| clean.endsWith("대하여")
			|| clean.endsWith("경우");
	}

	// 메소드 설명: normalizePageText 처리 흐름을 수행합니다.
	private String normalizePageText(String text) {
		if (text == null) {
			return "";
		}
		List<String> lines = text.replace("\r\n", "\n")
			.replace('\r', '\n')
			.lines()
			.map(line -> line.replaceAll("[ \\t]+", " ").trim())
			.toList();
		return String.join("\n", lines)
			.replaceAll("\\n{3,}", "\n\n")
			.trim();
	}

	// 메소드 설명: splitBlocks 처리 흐름을 수행합니다.
	private List<String> splitBlocks(String text) {
		List<String> blocks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String rawLine : text.split("\\n")) {
			String line = rawLine.trim();
			if (line.isBlank()) {
				flushBlock(blocks, current);
				continue;
			}
			boolean startsNewBlock = current.length() > 0 && (isHeadingLine(line) || isSemanticHeadingLine(line));
			if (startsNewBlock) {
				flushBlock(blocks, current);
			}
			if (current.length() > 0) {
				current.append('\n');
			}
			current.append(line);
		}
		flushBlock(blocks, current);
		return blocks;
	}

	// 메소드 설명: flushBlock 처리 흐름을 수행합니다.
	private void flushBlock(List<String> blocks, StringBuilder current) {
		if (current.length() == 0) {
			return;
		}
		String block = current.toString().trim();
		if (!block.isBlank()) {
			blocks.add(block);
		}
		current.setLength(0);
	}

	// 메소드 설명: splitLongBlock 처리 흐름을 수행합니다.
	private List<String> splitLongBlock(String block) {
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		String heading = firstLine(block);
		boolean carryHeading = isHeadingLine(heading);
		for (String sentence : splitSentences(block)) {
			if (sentence.isBlank()) {
				continue;
			}
			if (sentence.length() > MAX_CHARS) {
				if (current.length() > 0) {
					addLongChunk(chunks, current.toString(), heading, carryHeading);
					current.setLength(0);
				}
				for (String windowChunk : splitByWindow(sentence)) {
					addLongChunk(chunks, windowChunk, heading, carryHeading);
				}
				continue;
			}
			if (current.length() > 0 && current.length() + sentence.length() + 1 > MAX_CHARS) {
				addLongChunk(chunks, current.toString(), heading, carryHeading);
				current.setLength(0);
			}
			if (current.length() > 0) {
				current.append('\n');
			}
			current.append(sentence);
		}
		if (current.length() > 0) {
			addLongChunk(chunks, current.toString(), heading, carryHeading);
		}
		return chunks;
	}

	// 메소드 설명: addLongChunk 처리 흐름을 수행합니다.
	private void addLongChunk(List<String> chunks, String text, String heading, boolean carryHeading) {
		String value = text.trim();
		if (value.isBlank()) {
			return;
		}
		if (carryHeading && !chunks.isEmpty() && !value.startsWith(heading)) {
			String prefixed = heading + "\n" + value;
			if (prefixed.length() <= MAX_CHARS) {
				value = prefixed;
			}
		}
		chunks.add(value);
	}

	// 메소드 설명: splitSentences 처리 흐름을 수행합니다.
	private List<String> splitSentences(String block) {
		String normalized = block.replaceAll("(?m)\\s+$", "").trim();
		if (normalized.isBlank()) {
			return List.of();
		}
		String[] parts = normalized.split("(?<=다\\.|니다\\.|함\\.|임\\.|요\\.)\\s+|(?=\\n\\s*(?:[①②③④⑤⑥⑦⑧⑨⑩]|\\d{1,2}[.)]|[가-하][.)]))");
		List<String> sentences = new ArrayList<>();
		for (String part : parts) {
			String sentence = part.trim();
			if (!sentence.isBlank()) {
				sentences.add(sentence);
			}
		}
		return sentences;
	}

	// 메소드 설명: splitByWindow 처리 흐름을 수행합니다.
	private List<String> splitByWindow(String text) {
		List<String> chunks = new ArrayList<>();
		int start = 0;
		while (start < text.length()) {
			int end = Math.min(text.length(), start + MAX_CHARS);
			if (end < text.length()) {
				int softBreak = Math.max(
					text.lastIndexOf('\n', end),
					Math.max(text.lastIndexOf(". ", end), text.lastIndexOf("다. ", end))
				);
				if (softBreak > start + MIN_CHARS) {
					end = softBreak + 1;
				}
			}
			chunks.add(text.substring(start, end).trim());
			if (end >= text.length()) {
				break;
			}
			start = Math.max(end - LONG_BLOCK_OVERLAP_CHARS, start + 1);
		}
		return chunks;
	}

	// 메소드 설명: inferTitle 처리 흐름을 수행합니다.
	private String inferTitle(String text, Integer pageNo, int number) {
		List<String> lines = text.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.filter(line -> !isJunkLine(line))
			.toList();
		String firstLine = lines.stream()
			.findFirst()
			.orElse("문서 조각 " + number);
		if (!isHeadingLine(firstLine)) {
			firstLine = headingDetector.bestHeading(lines).orElse(firstLine);
		}
		firstLine = firstLine.replaceAll("^p\\.?\\s*\\d+\\s*", "").trim();
		if (firstLine.length() > 90) {
			firstLine = firstLine.substring(0, 90);
		}
		return pageNo == null ? firstLine : "p." + pageNo + " " + firstLine;
	}

	private String firstHeadingLine(String text) {
		return String.valueOf(text == null ? "" : text)
			.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.filter(this::isHeadingLine)
			.findFirst()
			.orElse("");
	}

	private String stripPagePrefix(String title) {
		return String.valueOf(title == null ? "" : title)
			.replaceFirst("(?i)^p\\.?\\s*\\d+\\s*", "")
			.trim();
	}

	private String inferSectionType(String parentTitle, String chunkTitle, String text) {
		String heading = normalizeForType(parentTitle + " " + chunkTitle);
		String normalized = normalizeForType(parentTitle + " " + chunkTitle + " " + text);
		if (normalized.contains("목차") || normalized.contains("contents")) {
			return "toc";
		}
		if (containsAny(heading, List.of("적용대상", "대상사업", "대상기관", "검토대상", "지원대상", "대상시스템"))) {
			return "target_scope";
		}
		if (containsAny(heading, List.of("제외", "비대상", "예외", "생략", "면제"))) {
			return "exception";
		}
		if (containsAny(heading, List.of("제출서류", "필수항목", "요구사항", "기재사항", "명시하여야", "평가요소", "평가방법"))) {
			return "requirement";
		}
		if (containsAny(heading, List.of("절차", "신청", "제출", "통보", "처리", "등록", "조회", "방법", "구성및운영", "운영방법", "위원회구성"))) {
			return "procedure";
		}
		if (containsAny(normalized, List.of("적용대상", "대상사업", "대상기관", "검토대상", "지원대상", "대상시스템"))) {
			return "target_scope";
		}
		if (containsAny(normalized, List.of("제외", "비대상", "예외", "생략", "면제"))) {
			return "exception";
		}
		if (containsAny(normalized, List.of("제출서류", "필수항목", "요구사항", "기재사항", "명시하여야", "평가요소", "평가방법"))) {
			return "requirement";
		}
		if (containsAny(normalized, List.of("절차", "신청", "제출", "통보", "처리", "등록", "조회", "방법", "구성및운영", "운영방법", "위원회구성"))) {
			return "procedure";
		}
		if (containsAny(normalized, List.of("작성예시", "작성예", "예시", "샘플", "양식", "서식"))) {
			return "example";
		}
		if (looksLikeTable(text)) {
			return "table";
		}
		return "body";
	}

	private String buildEmbeddingText(
		String documentTitle,
		String parentTitle,
		String chunkTitle,
		String sectionType,
		Integer pageNo,
		String text
	) {
		List<String> header = new ArrayList<>();
		if (documentTitle != null && !documentTitle.isBlank()) {
			header.add("문서: " + documentTitle.trim());
		}
		if (parentTitle != null && !parentTitle.isBlank()) {
			header.add("상위 섹션: " + parentTitle.trim());
		}
		if (chunkTitle != null && !chunkTitle.isBlank()) {
			header.add("섹션: " + chunkTitle.trim());
		}
		header.add("섹션유형: " + sectionType);
		if (pageNo != null) {
			header.add("페이지: " + pageNo);
		}
		header.add("본문:");
		header.add(text == null ? "" : text.trim());
		return String.join("\n", header).trim();
	}

	private String buildEmbeddingTextV3(
		String documentTitle,
		String parentTitle,
		String chunkTitle,
		String sectionType,
		Integer pageNo,
		String text
	) {
		List<String> header = new ArrayList<>();
		header.add("RAG_SOURCE_TYPE: official_ministry_document");
		if (documentTitle != null && !documentTitle.isBlank()) {
			header.add("DOCUMENT_TITLE: " + documentTitle.trim());
		}
		if (parentTitle != null && !parentTitle.isBlank()) {
			header.add("PARENT_SECTION: " + parentTitle.trim());
		}
		if (chunkTitle != null && !chunkTitle.isBlank()) {
			header.add("CHUNK_SECTION: " + chunkTitle.trim());
		}
		header.add("SECTION_TYPE: " + sectionType);
		if (pageNo != null) {
			header.add("SOURCE_PAGE: " + pageNo);
		}
		header.add("ANSWER_GUARDRAIL: Use this chunk only when the user question matches the document title, section, and body. Prefer explicit statements in BODY over inferred meaning.");
		header.add("BODY:");
		header.add(text == null ? "" : text.trim());
		return String.join("\n", header).trim();
	}

	private String normalizeForType(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("\\s+", "")
			.toLowerCase();
	}

	private boolean containsAny(String normalized, List<String> values) {
		return values.stream().anyMatch(normalized::contains);
	}

	private boolean looksLikeTable(String text) {
		List<String> lines = String.valueOf(text == null ? "" : text).lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.toList();
		if (lines.size() < 4) {
			return false;
		}
		long tableLikeLines = lines.stream()
			.filter(line -> line.contains("|") || line.split("\\s{2,}").length >= 3)
			.count();
		return tableLikeLines >= Math.max(3, lines.size() / 2);
	}

	private boolean isSemanticHeadingLine(String line) {
		String clean = stripHeadingBullet(line);
		if (clean.isBlank()) {
			return false;
		}
		List<String> keywords = List.of(
			"적용 대상 사업",
			"적용대상사업",
			"적용 대상",
			"적용대상",
			"대상 사업",
			"대상사업",
			"대상 기관",
			"대상기관",
			"검토 대상",
			"검토대상",
			"지원 대상",
			"지원대상",
			"대상 시스템",
			"대상시스템",
			"적용 예외",
			"적용예외",
			"비대상",
			"제외 대상",
			"제외대상",
			"필수 항목",
			"필수항목",
			"제출 서류",
			"제출서류",
			"요구사항",
			"기재사항",
			"평가 요소",
			"평가요소",
			"평가 방법",
			"평가방법",
			"신청 절차",
			"신청절차",
			"제출 방법",
			"제출방법",
			"처리 절차",
			"처리절차",
			"구성 및 운영",
			"구성및운영",
			"운영 방법",
			"운영방법",
			"위원회 구성",
			"위원회구성"
		);
		return !semanticTitleFromLine(clean, keywords, keywords.stream().map(this::normalizeForType).toList()).isBlank();
	}

	private String findSemanticSectionTitle(String text, String sectionType) {
		List<String> keywords = switch (String.valueOf(sectionType)) {
			case "target_scope" -> List.of("적용 대상 사업", "적용대상사업", "적용 대상", "적용대상", "대상 사업", "대상사업", "대상 기관", "대상기관", "검토 대상", "검토대상", "대상 시스템", "대상시스템");
			case "exception" -> List.of("적용 예외", "예외", "제외", "비대상", "생략", "면제");
			case "requirement" -> List.of("필수 항목", "필수항목", "제출 서류", "제출서류", "요구사항", "기재사항", "평가 요소", "평가요소", "평가 방법", "평가방법");
			case "procedure" -> List.of("절차", "신청", "제출", "통보", "처리", "등록", "조회", "방법");
			default -> List.of();
		};
		if (keywords.isEmpty()) {
			return "";
		}
		List<String> normalizedKeywords = keywords.stream().map(this::normalizeForType).toList();
		return String.valueOf(text == null ? "" : text)
			.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.map(this::stripHeadingBullet)
			.map(line -> semanticTitleFromLine(line, keywords, normalizedKeywords))
			.filter(line -> !line.isBlank())
			.findFirst()
			.orElse("");
	}

	private String semanticTitleFromLine(String line, List<String> keywords, List<String> normalizedKeywords) {
		String normalizedLine = normalizeForType(line);
		for (int index = 0; index < normalizedKeywords.size(); index++) {
			String keyword = normalizedKeywords.get(index);
			int keywordIndex = normalizedLine.indexOf(keyword);
			if (keywordIndex < 0 || keywordIndex > 6) {
				continue;
			}
			if (normalizedLine.length() - (keywordIndex + keyword.length()) > 8) {
				return keywords.get(index);
			}
			if (line.length() <= 80) {
				return line;
			}
			return keywords.get(index);
		}
		return "";
	}

	private String stripHeadingBullet(String line) {
		return String.valueOf(line == null ? "" : line)
			.replaceFirst("^[\\s\\-+*•○●□■▣※①-⑳0-9.()]+", "")
			.replaceAll("\\s+", " ")
			.trim();
	}

	// 메소드 설명: isHeadingLine 처리 흐름을 수행합니다.
	private boolean isHeadingLine(String line) {
		return headingDetector.isHeadingLine(line);
	}

	// 메소드 설명: firstLine 처리 흐름을 수행합니다.
	private String firstLine(String value) {
		return String.valueOf(value == null ? "" : value)
			.lines()
			.map(String::trim)
			.filter(line -> !line.isBlank())
			.findFirst()
			.orElse("");
	}

	// 메소드 설명: isJunkLine 처리 흐름을 수행합니다.
	private boolean isJunkLine(String line) {
		String normalized = line == null ? "" : line.trim();
		if (normalized.isBlank()) {
			return true;
		}
		if (normalized.matches("(?i)^p\\.?\\s*\\d+$")) {
			return true;
		}
		return normalized.matches("^[\\^()\\[\\].,;:ㆍ·\\-0-9\\s]+$");
	}
}
