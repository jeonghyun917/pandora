package com.kaces.pandora.rag.importing;

import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;
import org.w3c.dom.Node;
import org.w3c.dom.Element;

@Component
public class RagTextExtractor {

	// 메소드 설명: extract 처리 흐름을 수행합니다.
	public ExtractedDocument extract(Path file) {
		String fileName = file.getFileName().toString().toLowerCase();
		try {
			if (fileName.endsWith(".pdf")) {
				return extractPdf(file);
			}
			if (fileName.endsWith(".docx")) {
				return extractDocx(file);
			}
			if (fileName.endsWith(".hwpx")) {
				return extractHwpx(file);
			}
			if (fileName.endsWith(".txt") || fileName.endsWith(".md")) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				return new ExtractedDocument(List.of(new ExtractedPage(null, Files.readString(file, StandardCharsets.UTF_8))));
			}
		} catch (IOException exception) {
			throw new IllegalStateException("Document text extraction failed: " + file, exception);
		}
		throw new IllegalArgumentException("Unsupported document file type: " + file.getFileName());
	}

	// 메소드 설명: extractPdf 처리 흐름을 수행합니다.
	private ExtractedDocument extractPdf(Path file) throws IOException {
		try (PDDocument document = Loader.loadPDF(file.toFile())) {
			PDFTextStripper stripper = new PDFTextStripper();
			List<ExtractedPage> pages = new ArrayList<>();
			for (int page = 1; page <= document.getNumberOfPages(); page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				pages.add(new ExtractedPage(page, normalize(stripper.getText(document))));
			}
			return new ExtractedDocument(pages);
		}
	}

	// 메소드 설명: extractDocx 처리 흐름을 수행합니다.
	private ExtractedDocument extractDocx(Path file) throws IOException {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (InputStream input = Files.newInputStream(file);
			 XWPFDocument document = new XWPFDocument(input);
			 XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
			return new ExtractedDocument(List.of(new ExtractedPage(null, normalize(extractor.getText()))));
		}
	}

	// 메소드 설명: extractHwpx 처리 흐름을 수행합니다.
	private ExtractedDocument extractHwpx(Path file) throws IOException {
		List<ExtractedPage> pages = new ArrayList<>();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (ZipFile zipFile = new ZipFile(file.toFile())) {
			DocumentBuilderFactory factory = secureDocumentBuilderFactory();
			var entries = zipFile.stream()
				.filter(entry -> !entry.isDirectory())
				.filter(entry -> entry.getName().matches("Contents/section\\d+\\.xml"))
				.sorted(java.util.Comparator.comparing((java.util.zip.ZipEntry entry) ->
					new java.math.BigInteger(entry.getName().substring("Contents/section".length(), entry.getName().length() - 4)))
					.thenComparing(java.util.zip.ZipEntry::getName))
				.toList();
			int pageNo = 1;
			for (var entry : entries) {
				try (InputStream input = zipFile.getInputStream(entry)) {
					var document = factory.newDocumentBuilder().parse(input);
					StringBuilder builder = new StringBuilder();
					List<SourceTableRow> tableRows = new ArrayList<>();
					appendText(document.getDocumentElement(), builder, tableRows, entry.getName());
					String text = normalize(builder.toString());
					// Until cleanup carries an exact offset transform, changed text has no structural proof.
					if (!text.equals(builder.toString())) { tableRows.clear(); }
					if (!text.isBlank()) {
						pages.add(new ExtractedPage(pageNo++, text, tableRows));
					}
				} catch (Exception exception) {
					throw new IllegalStateException("HWPX text extraction failed: " + entry.getName(), exception);
				}
			}
		}
		return new ExtractedDocument(pages);
	}

	// 메소드 설명: secureDocumentBuilderFactory 처리 흐름을 수행합니다.
	private DocumentBuilderFactory secureDocumentBuilderFactory() {
		try {
			DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setNamespaceAware(false);
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
			factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
			return factory;
		} catch (Exception exception) {
			throw new IllegalStateException("Failed to configure HWPX XML parser.", exception);
		}
	}

	// 메소드 설명: appendText 처리 흐름을 수행합니다.
	private void appendText(Node node, StringBuilder builder) {
		appendText(node, builder, null, null);
	}

	private void appendText(Node node, StringBuilder builder, List<SourceTableRow> tableRows, String sourcePart) {
		if (node == null) {
			return;
		}
		if ("tbl".equals(localName(node))) {
			int firstRow = tableRows == null ? 0 : tableRows.size();
			String table = tableText(node, tableRows, sourcePart);
			if (table != null) {
				if (builder.length() > 0) { builder.append('\n'); }
				int tableStart = builder.length();
				builder.append(table);
				if (tableRows != null) {
					for (int i = firstRow; i < tableRows.size(); i++) {
						SourceTableRow row = tableRows.get(i);
						tableRows.set(i, new SourceTableRow(row.sourcePart(), row.tablePath(), row.rowIndex(),
							row.renderedText(), row.cells(), tableStart + row.textStart(), tableStart + row.textEnd()));
					}
				}
				return;
			}
		}
		if (node.getNodeType() == Node.TEXT_NODE) {
			String value = node.getNodeValue();
			if (value != null && !value.isBlank()) {
				if (builder.length() > 0) {
					builder.append('\n');
				}
				builder.append(value.trim());
			}
			return;
		}
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			appendText(child, builder, tableRows, sourcePart);
		}
	}

	// Preserve only relations explicitly owned by XML cell addresses and spans.
	// Malformed or nested tables retain the existing raw-text fallback.
	private String tableText(Node table, List<SourceTableRow> tableRows, String sourcePart) {
		try {
			int columns = Integer.parseInt(((Element) table).getAttribute("colCnt"));
			List<Node> rows = children(table, "tr");
			if (columns < 1 || columns > 200 || rows.isEmpty() || rows.size() > 10000) { return null; }
			Node[][] grid = new Node[rows.size()][columns];
			for (int r = 0; r < rows.size(); r++) {
				for (Node cell : children(rows.get(r), "tc")) {
					var descendants = ((Element) cell).getElementsByTagName("*");
					for (int i = 0; i < descendants.getLength(); i++) {
						if ("tbl".equals(localName(descendants.item(i)))) { return null; }
					}
					List<Node> addresses = children(cell, "cellAddr"), spans = children(cell, "cellSpan");
					if (addresses.size() != 1 || spans.size() != 1) { return null; }
					Element address = (Element) addresses.get(0), span = (Element) spans.get(0);
					int row = Integer.parseInt(address.getAttribute("rowAddr"));
					int col = Integer.parseInt(address.getAttribute("colAddr"));
					int height = Integer.parseInt(span.getAttribute("rowSpan"));
					int width = Integer.parseInt(span.getAttribute("colSpan"));
					if (row != r || col < 0 || height < 1 || width < 1
						|| height > rows.size() - row || width > columns - col) { return null; }
					for (int y = row; y < row + height; y++) {
						for (int x = col; x < col + width; x++) {
							if (grid[y][x] != null) { return null; }
							grid[y][x] = cell;
						}
					}
				}
			}
			List<String> lines = new ArrayList<>();
			List<String> headers = List.of();
			List<Node> headerCells = List.of();
			List<SourceTableRow> pendingRows = new ArrayList<>();
			int rowTextStart = 0;
			for (int rowIndex = 0; rowIndex < grid.length; rowIndex++) {
				Node[] row = grid[rowIndex];
				List<String> values = new ArrayList<>();
				Node previous = null;
				boolean anyHeader = false, allHeaders = true;
				for (Node cell : row) {
					if (cell == null) { return null; }
					boolean header = "1".equals(((Element) cell).getAttribute("header"));
					anyHeader |= header;
					allHeaders &= header;
					if (cell != previous) {
						values.add(cellText(cell));
					}
					previous = cell;
				}
				boolean numberLabel = !values.isEmpty()
					&& values.get(0).replaceAll("\\s+", "").matches("(?i)(?:연번|번호|순번|no\\.?)");
				// Ambiguous new headings must also end the previous header's ownership.
				if (values.size() != columns || anyHeader || numberLabel) {
					headers = List.of();
					headerCells = List.of();
				}
				boolean numberedHeader = !anyHeader && numberLabel && values.size() == columns && columns > 1
					&& values.stream().allMatch(value -> value.length() <= 40 && !value.matches(".*[0-9].*"))
					&& rowIndex + 1 < grid.length
					&& java.util.Arrays.stream(grid[rowIndex + 1]).allMatch(java.util.Objects::nonNull)
					&& java.util.Arrays.stream(grid[rowIndex + 1]).distinct().count() == columns
					&& cellText(grid[rowIndex + 1][0]).matches("[1-9][0-9]{0,5}");
				if ((allHeaders || numberedHeader) && columns > 1 && values.size() == columns
					&& values.stream().allMatch(value -> !value.isBlank() && !value.contains(":") && !value.contains("|") && !value.contains("："))
					&& values.stream().map(value -> value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT))
						.distinct().count() == columns) {
					headers = List.copyOf(values);
					headerCells = List.of(row.clone());
				} else if (!headers.isEmpty()) {
					for (int col = 0; col < columns; col++) {
						values.set(col, headers.get(col) + ": " + values.get(col));
					}
				}
				String rendered = String.join(" | ", values);
				lines.add(rendered);
				if (tableRows != null && !hasTableAncestor(table)) {
					List<SourceTableRow.Cell> cells = new ArrayList<>();
					java.util.Set<Node> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
					for (int col = 0; col < columns; col++) {
						Node cell = row[col];
						if (!seen.add(cell)) { continue; }
						Element address = (Element) children(cell, "cellAddr").get(0);
						Element span = (Element) children(cell, "cellSpan").get(0);
						// Header rows describe source cells; they do not own themselves as data.
						Node header = headerCells.isEmpty() || headerCells.contains(cell) ? null : headerCells.get(col);
						cells.add(new SourceTableRow.Cell(Integer.parseInt(address.getAttribute("rowAddr")),
							Integer.parseInt(address.getAttribute("colAddr")), Integer.parseInt(span.getAttribute("rowSpan")),
							Integer.parseInt(span.getAttribute("colSpan")), cellText(cell),
							header == null ? null : xmlPath(header), header == null ? null : cellText(header)));
					}
					pendingRows.add(new SourceTableRow(sourcePart, xmlPath(table), rowIndex, rendered, cells,
						rowTextStart, rowTextStart + rendered.length()));
				}
				rowTextStart += rendered.length() + 1;
			}
			if (tableRows != null) { tableRows.addAll(pendingRows); }
			return String.join("\n", lines);
		} catch (NumberFormatException exception) {
			return null;
		}
	}

	private boolean hasTableAncestor(Node node) {
		for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
			if ("tbl".equals(localName(parent))) { return true; }
		}
		return false;
	}

	private String xmlPath(Node node) {
		if (node == null || node.getNodeType() != Node.ELEMENT_NODE) { return ""; }
		int position = 1;
		for (Node sibling = node.getPreviousSibling(); sibling != null; sibling = sibling.getPreviousSibling()) {
			if (sibling.getNodeType() == Node.ELEMENT_NODE && localName(sibling).equals(localName(node))) { position++; }
		}
		return xmlPath(node.getParentNode()) + "/" + localName(node) + "[" + position + "]";
	}

	private String cellText(Node cell) {
		StringBuilder text = new StringBuilder();
		appendText(cell, text);
		return text.toString().replaceAll("\\s+", " ").trim();
	}

	private List<Node> children(Node parent, String name) {
		List<Node> result = new ArrayList<>();
		for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() == Node.ELEMENT_NODE && name.equals(localName(child))) { result.add(child); }
		}
		return result;
	}

	private String localName(Node node) {
		String name = node.getNodeName();
		return name.substring(name.indexOf(':') + 1);
	}

	// 메소드 설명: normalize 처리 흐름을 수행합니다.
	private String normalize(String value) {
		return value == null ? "" : HwpxTextCleaner.clean(value.replace("\r\n", "\n").replace('\r', '\n'));
	}
}
