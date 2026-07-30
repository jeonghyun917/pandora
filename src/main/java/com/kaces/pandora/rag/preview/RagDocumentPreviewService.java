package com.kaces.pandora.rag.preview;

import com.kaces.pandora.rag.importing.ExtractedDocument;
import com.kaces.pandora.rag.importing.ExtractedPage;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RagDocumentPreviewService {
	private static final Duration CONVERT_TIMEOUT = Duration.ofMinutes(2);
	private static final float FONT_SIZE = 10.5f;
	private static final float LEADING = 15f;
	private static final float MARGIN = 42f;

	private final RagTextExtractor textExtractor;
	private final RagOriginalDocumentStore originalDocumentStore;

	// 메소드 설명: RagDocumentPreviewService 처리 흐름을 수행합니다.
	public RagDocumentPreviewService(
		RagTextExtractor textExtractor,
		RagOriginalDocumentStore originalDocumentStore
	) {
		this.textExtractor = textExtractor;
		this.originalDocumentStore = originalDocumentStore;
	}

	// 메소드 설명: previewPdf 처리 흐름을 수행합니다.
	public Resource previewPdf(RagDocumentRow document) {
		if (document == null || !originalDocumentStore.exists(document)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		Path source;
		try {
			source = originalDocumentStore.materialize(document);
		} catch (IOException exception) {
			throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Original document could not be read.", exception);
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		try {
			if (isPdf(document, source)) {
				return new UrlResource(source.toUri());
			}
			Path preview = previewPath(document, source);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			if (!Files.exists(preview) || Files.size(preview) == 0 || Files.getLastModifiedTime(preview).compareTo(Files.getLastModifiedTime(source)) < 0) {
				if (isHwpx(source)) {
					generateTextPreviewPdf(document, source, preview);
				} else {
					convertToPdf(source, preview);
				}
			}
			return new UrlResource(preview.toUri());
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF preview is not available for this document.", exception);
		}
	}

	// 메소드 설명: canPreview 처리 흐름을 수행합니다.
	public boolean canPreview(RagDocumentRow document) {
		if (document == null || !originalDocumentStore.exists(document)) {
			return false;
		}
		String lower = document.fileName() == null
			? ""
			: document.fileName().toLowerCase();
		String mimeType = document.mimeType() == null ? "" : document.mimeType().toLowerCase();
		return mimeType.contains("pdf")
			|| lower.endsWith(".pdf")
			|| lower.endsWith(".hwpx")
			|| lower.endsWith(".hwp")
			|| lower.endsWith(".docx")
			|| lower.endsWith(".doc")
			|| lower.endsWith(".pptx")
			|| lower.endsWith(".ppt")
			|| lower.endsWith(".xlsx")
			|| lower.endsWith(".xls");
	}

	// 메소드 설명: hasReadyPreview 처리 흐름을 수행합니다.
	public boolean hasReadyPreview(RagDocumentRow document) {
		if (document == null || !originalDocumentStore.exists(document)) {
			return false;
		}
		Path source;
		try {
			source = originalDocumentStore.materialize(document);
		} catch (IOException exception) {
			return false;
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.isRegularFile(source) || !Files.isReadable(source)) {
			return false;
		}
		if (isPdf(document, source)) {
			return true;
		}
		try {
			Path preview = previewPath(document, source);
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return Files.exists(preview)
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				&& Files.size(preview) > 0
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				&& Files.getLastModifiedTime(preview).compareTo(Files.getLastModifiedTime(source)) >= 0;
		} catch (Exception exception) {
			return false;
		}
	}

	// 메소드 설명: isPdf 처리 흐름을 수행합니다.
	private boolean isPdf(RagDocumentRow document, Path source) {
		String lower = source.getFileName().toString().toLowerCase();
		String mimeType = document.mimeType() == null ? "" : document.mimeType().toLowerCase();
		return lower.endsWith(".pdf") || mimeType.contains("pdf");
	}

	// 메소드 설명: isHwpx 처리 흐름을 수행합니다.
	private boolean isHwpx(Path source) {
		return source.getFileName().toString().toLowerCase().endsWith(".hwpx");
	}

	// 메소드 설명: previewPath 처리 흐름을 수행합니다.
	private Path previewPath(RagDocumentRow document, Path source) throws java.io.IOException {
		Path directory = Path.of("storage", "rag-preview").toAbsolutePath().normalize();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Files.createDirectories(directory);
		String baseName = document.fileHash() == null || document.fileHash().isBlank()
			? safeBaseName(source)
			: document.fileHash();
		return directory.resolve(baseName + ".pdf");
	}

	// 메소드 설명: convertToPdf 처리 흐름을 수행합니다.
	private void convertToPdf(Path source, Path preview) throws Exception {
		Path soffice = findSoffice();
		if (soffice == null) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "LibreOffice soffice executable was not found.");
		}
		Path outputDir = preview.getParent();
		List<String> command = new ArrayList<>();
		command.add(soffice.toString());
		command.add("--headless");
		command.add("--convert-to");
		command.add("pdf");
		command.add("--outdir");
		command.add(outputDir.toString());
		command.add(source.toString());

		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();
		boolean finished = process.waitFor(CONVERT_TIMEOUT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
		if (!finished) {
			process.destroyForcibly();
			throw new ResponseStatusException(HttpStatus.REQUEST_TIMEOUT, "PDF preview conversion timed out.");
		}
		if (process.exitValue() != 0) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF preview conversion failed.");
		}
		Path converted = outputDir.resolve(stripExtension(source.getFileName().toString()) + ".pdf");
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.exists(converted) || Files.size(converted) == 0) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "PDF preview conversion did not create a file.");
		}
		if (!converted.equals(preview)) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.move(converted, preview, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
	}

	// 메소드 설명: generateTextPreviewPdf 처리 흐름을 수행합니다.
	private void generateTextPreviewPdf(RagDocumentRow document, Path source, Path preview) throws IOException {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		ExtractedDocument extracted = textExtractor.extract(source);
		try (PDDocument pdf = new PDDocument()) {
			PDFont font = loadKoreanFont(pdf);
			PdfWriter writer = new PdfWriter(pdf, font);
			writer.writeTitle(document.title() == null || document.title().isBlank() ? source.getFileName().toString() : document.title());
			for (ExtractedPage page : extracted.pages()) {
				String heading = page.pageNo() == null ? "" : "p." + page.pageNo();
				writer.writeHeading(heading);
				writer.writeBody(page.text());
			}
			writer.close();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			Files.createDirectories(preview.getParent());
			pdf.save(preview.toFile());
		}
	}

	// 메소드 설명: loadKoreanFont 처리 흐름을 수행합니다.
	private PDFont loadKoreanFont(PDDocument pdf) throws IOException {
		List<Path> candidates = List.of(
			Path.of("C:", "Windows", "Fonts", "malgun.ttf"),
			Path.of("C:", "Windows", "Fonts", "malgunbd.ttf"),
			Path.of("C:", "Windows", "Fonts", "gulim.ttc")
		);
		for (Path candidate : candidates) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			if (Files.isRegularFile(candidate)) {
				return PDType0Font.load(pdf, candidate.toFile());
			}
		}
		throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Korean font for PDF preview was not found.");
	}

	private static final class PdfWriter {
		private final PDDocument pdf;
		private final PDFont font;
		private PDPageContentStream stream;
		private float y;

		// 메소드 설명: PdfWriter 처리 흐름을 수행합니다.
		private PdfWriter(PDDocument pdf, PDFont font) {
			this.pdf = pdf;
			this.font = font;
		}

		// 메소드 설명: writeTitle 처리 흐름을 수행합니다.
		private void writeTitle(String title) throws IOException {
			ensurePage();
			stream.setFont(font, 15f);
			writeWrappedLine(title, 15f, 18f);
			y -= 12f;
		}

		// 메소드 설명: writeHeading 처리 흐름을 수행합니다.
		private void writeHeading(String heading) throws IOException {
			if (heading == null || heading.isBlank()) {
				return;
			}
			ensurePage();
			stream.setFont(font, 12f);
			writeWrappedLine(heading, 12f, 17f);
			y -= 4f;
		}

		// 메소드 설명: writeBody 처리 흐름을 수행합니다.
		private void writeBody(String body) throws IOException {
			if (body == null || body.isBlank()) {
				return;
			}
			stream.setFont(font, FONT_SIZE);
			for (String paragraph : body.split("\\R")) {
				String line = paragraph.trim();
				if (line.isBlank()) {
					newLine(LEADING);
					continue;
				}
				for (String wrapped : wrap(line, FONT_SIZE, PDRectangle.A4.getWidth() - (MARGIN * 2))) {
					writeLine(wrapped, FONT_SIZE, LEADING);
				}
			}
			y -= 8f;
		}

		// 메소드 설명: close 처리 흐름을 수행합니다.
		private void close() throws IOException {
			if (stream != null) {
				stream.endText();
				stream.close();
				stream = null;
			}
		}

		// 메소드 설명: ensurePage 처리 흐름을 수행합니다.
		private void ensurePage() throws IOException {
			if (stream != null && y > MARGIN + LEADING) {
				return;
			}
			if (stream != null) {
				stream.endText();
				stream.close();
			}
			PDPage page = new PDPage(PDRectangle.A4);
			pdf.addPage(page);
			stream = new PDPageContentStream(pdf, page);
			stream.beginText();
			stream.newLineAtOffset(MARGIN, page.getMediaBox().getHeight() - MARGIN);
			y = page.getMediaBox().getHeight() - MARGIN;
		}

		// 메소드 설명: writeWrappedLine 처리 흐름을 수행합니다.
		private void writeWrappedLine(String value, float fontSize, float leading) throws IOException {
			for (String wrapped : wrap(value, fontSize, PDRectangle.A4.getWidth() - (MARGIN * 2))) {
				writeLine(wrapped, fontSize, leading);
			}
		}

		// 메소드 설명: writeLine 처리 흐름을 수행합니다.
		private void writeLine(String value, float fontSize, float leading) throws IOException {
			ensurePage();
			stream.setFont(font, fontSize);
			stream.showText(sanitizePdfText(value));
			newLine(leading);
		}

		// 메소드 설명: newLine 처리 흐름을 수행합니다.
		private void newLine(float amount) throws IOException {
			ensurePage();
			stream.newLineAtOffset(0, -amount);
			y -= amount;
		}

		// 메소드 설명: wrap 처리 흐름을 수행합니다.
		private List<String> wrap(String text, float fontSize, float maxWidth) throws IOException {
			List<String> lines = new ArrayList<>();
			StringBuilder current = new StringBuilder();
			for (String token : text.split(" ")) {
				String candidate = current.isEmpty() ? token : current + " " + token;
				if (width(candidate, fontSize) <= maxWidth) {
					current.setLength(0);
					current.append(candidate);
					continue;
				}
				if (!current.isEmpty()) {
					lines.add(current.toString());
					current.setLength(0);
				}
				if (width(token, fontSize) <= maxWidth) {
					current.append(token);
				} else {
					lines.addAll(splitLongToken(token, fontSize, maxWidth));
				}
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
			}
			return lines.isEmpty() ? List.of(text) : lines;
		}

		// 메소드 설명: splitLongToken 처리 흐름을 수행합니다.
		private List<String> splitLongToken(String token, float fontSize, float maxWidth) throws IOException {
			List<String> lines = new ArrayList<>();
			StringBuilder current = new StringBuilder();
			for (int offset = 0; offset < token.length(); ) {
				int next = token.offsetByCodePoints(offset, 1);
				String candidate = current + token.substring(offset, next);
				if (width(candidate, fontSize) > maxWidth && !current.isEmpty()) {
					lines.add(current.toString());
					current.setLength(0);
				}
				current.append(token, offset, next);
				offset = next;
			}
			if (!current.isEmpty()) {
				lines.add(current.toString());
			}
			return lines;
		}

		// 메소드 설명: width 처리 흐름을 수행합니다.
		private float width(String value, float fontSize) throws IOException {
			return font.getStringWidth(sanitizePdfText(value)) / 1000f * fontSize;
		}

		// 메소드 설명: sanitizePdfText 처리 흐름을 수행합니다.
		private String sanitizePdfText(String value) {
			if (value == null) {
				return "";
			}
			String normalized = replaceKnownSymbols(value)
				.replace("\u0000", "")
				.replace("\t", "    ");
			StringBuilder builder = new StringBuilder(normalized.length());
			for (int offset = 0; offset < normalized.length(); ) {
				int codePoint = normalized.codePointAt(offset);
				String glyph = new String(Character.toChars(codePoint));
				if (canRender(glyph)) {
					builder.append(glyph);
				} else {
					builder.append(' ');
				}
				offset += Character.charCount(codePoint);
			}
			return builder.toString();
		}

		// 메소드 설명: replaceKnownSymbols 처리 흐름을 수행합니다.
		private String replaceKnownSymbols(String value) {
			StringBuilder builder = new StringBuilder(value.length());
			for (int offset = 0; offset < value.length(); ) {
				int codePoint = value.codePointAt(offset);
				if (codePoint >= 0x2460 && codePoint <= 0x2473) {
					builder.append('(').append(codePoint - 0x2460 + 1).append(')');
				} else if (codePoint >= 0x3251 && codePoint <= 0x325F) {
					builder.append('(').append(codePoint - 0x3251 + 21).append(')');
				} else if (codePoint == 0x32B1) {
					builder.append("(36)");
				} else if (codePoint >= 0x32B2 && codePoint <= 0x32BF) {
					builder.append('(').append(codePoint - 0x32B2 + 37).append(')');
				} else if (codePoint == 0x3000) {
					builder.append(' ');
				} else {
					builder.appendCodePoint(codePoint);
				}
				offset += Character.charCount(codePoint);
			}
			return builder.toString();
		}

		// 메소드 설명: canRender 처리 흐름을 수행합니다.
		private boolean canRender(String glyph) {
			try {
				font.getStringWidth(glyph);
				return true;
			} catch (Exception exception) {
				return false;
			}
		}
	}

	// 메소드 설명: findSoffice 처리 흐름을 수행합니다.
	private Path findSoffice() {
		List<Path> candidates = List.of(
			Path.of("soffice"),
			Path.of("C:", "Program Files", "LibreOffice", "program", "soffice.exe"),
			Path.of("C:", "Program Files (x86)", "LibreOffice", "program", "soffice.exe")
		);
		for (Path candidate : candidates) {
			if (candidate.getNameCount() == 1) {
				return candidate;
			}
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	// 메소드 설명: safeBaseName 처리 흐름을 수행합니다.
	private String safeBaseName(Path source) {
		return stripExtension(source.getFileName().toString()).replaceAll("[^A-Za-z0-9._-]", "_");
	}

	// 메소드 설명: stripExtension 처리 흐름을 수행합니다.
	private String stripExtension(String fileName) {
		int index = fileName.lastIndexOf('.');
		return index > 0 ? fileName.substring(0, index) : fileName;
	}
}
