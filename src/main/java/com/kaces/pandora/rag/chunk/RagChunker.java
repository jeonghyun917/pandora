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

	List<String> splitPage(String text) {
		String normalized = normalizePageText(text);
		if (normalized.isBlank()) {
			return List.of();
		}
		List<String> chunks = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String block : splitBlocks(normalized)) {
			if (block.isBlank()) {
				continue;
			}
			if (!isHeadingLine(firstLine(block)) && RagTextNoiseFilter.isMeaninglessSection("", block)) {
				continue;
			}
			if (current.length() > 0 && current.length() + block.length() + 1 > MAX_CHARS) {
				chunks.add(current.toString());
				current.setLength(0);
			}
			if (block.length() > MAX_CHARS) {
				chunks.addAll(splitLongBlock(block));
				continue;
			}
			if (current.length() > 0) {
				current.append('\n');
			}
			current.append(block);
		}
		if (current.length() > 0) {
			if (!chunks.isEmpty() && current.length() < MIN_CHARS) {
				chunks.set(chunks.size() - 1, chunks.get(chunks.size() - 1) + "\n" + current);
			} else {
				chunks.add(current.toString());
			}
		}
		return chunks;
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
			boolean startsNewBlock = current.length() > 0 && isHeadingLine(line);
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
