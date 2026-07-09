package com.kaces.pandora.rag.preview;

import com.kaces.pandora.rag.common.HwpxTextCleaner;
import com.kaces.pandora.rag.document.RagDocumentRow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.xml.parsers.DocumentBuilderFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

@Service
public class HwpxHtmlPreviewService {
	private static final String PREVIEW_VERSION = "hwpx-html-v5";

	// 메소드 설명: canPreview 처리 흐름을 수행합니다.
	public boolean canPreview(RagDocumentRow document) {
		Path source = sourcePath(document);
		return source != null && Files.isRegularFile(source) && Files.isReadable(source);
	}

	// 메소드 설명: previewHtml 처리 흐름을 수행합니다.
	public String previewHtml(RagDocumentRow document) {
		Path source = sourcePath(document);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (source == null || !Files.isRegularFile(source) || !Files.isReadable(source)) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		try {
			Path preview = previewPath(document, source);
			if (shouldRegenerate(preview, source)) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				Files.createDirectories(preview.getParent());
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				Files.writeString(preview, renderHtml(document, source, preview), StandardCharsets.UTF_8);
			}
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return Files.readString(preview, StandardCharsets.UTF_8);
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "HWPX HTML preview is not available for this document.", exception);
		}
	}

	// 메소드 설명: previewAsset 처리 흐름을 수행합니다.
	public Path previewAsset(RagDocumentRow document, String fileName) {
		Path source = sourcePath(document);
		if (source == null || fileName == null || fileName.isBlank() || fileName.contains("/") || fileName.contains("\\")) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND);
		}
		try {
			Path preview = previewPath(document, source);
			if (shouldRegenerate(preview, source)) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				Files.writeString(preview, renderHtml(document, source, preview), StandardCharsets.UTF_8);
			}
			Path asset = assetDirectory(preview).resolve(fileName).normalize();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			if (!asset.startsWith(assetDirectory(preview)) || !Files.isRegularFile(asset) || !Files.isReadable(asset)) {
				throw new ResponseStatusException(HttpStatus.NOT_FOUND);
			}
			return asset;
		} catch (ResponseStatusException exception) {
			throw exception;
		} catch (Exception exception) {
			throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Preview asset was not found.", exception);
		}
	}

	// 메소드 설명: shouldRegenerate 처리 흐름을 수행합니다.
	private boolean shouldRegenerate(Path preview, Path source) throws IOException {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return !Files.exists(preview)
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			|| Files.size(preview) == 0
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			|| Files.getLastModifiedTime(preview).compareTo(Files.getLastModifiedTime(source)) < 0
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			|| !Files.readString(preview, StandardCharsets.UTF_8).contains(PREVIEW_VERSION);
	}

	// 메소드 설명: renderHtml 처리 흐름을 수행합니다.
	private String renderHtml(RagDocumentRow document, Path source, Path preview) throws IOException {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		try (ZipFile zipFile = new ZipFile(source.toFile(), StandardCharsets.UTF_8)) {
			Map<String, String> images = readImages(zipFile, assetDirectory(preview), document.documentId());
			List<? extends ZipEntry> sections = zipFile.stream()
				.filter(entry -> !entry.isDirectory())
				.filter(entry -> entry.getName().matches("Contents/section\\d+\\.xml"))
				.sorted(Comparator.comparing(ZipEntry::getName))
				.toList();
			if (sections.isEmpty()) {
				throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "HWPX section XML was not found.");
			}

			StringBuilder body = new StringBuilder();
			int pageNo = 1;
			for (ZipEntry section : sections) {
				Document xml = parseXml(zipFile, section);
				body.append("<section class=\"hx-page\" data-page=\"").append(pageNo).append("\">");
				body.append("<div class=\"hx-page-label\">PAGE ").append(pageNo).append("</div>");
				renderChildren(xml.getDocumentElement(), body, images, true);
				body.append("</section>");
				pageNo++;
			}
			return htmlShell(document, body.toString());
		}
	}

	// 메소드 설명: readImages 처리 흐름을 수행합니다.
	// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
	private Map<String, String> readImages(ZipFile zipFile, Path assetDirectory, long documentId) throws IOException {
		Map<String, String> images = new HashMap<>();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Files.createDirectories(assetDirectory);
		zipFile.stream()
			.filter(entry -> !entry.isDirectory())
			.filter(entry -> entry.getName().startsWith("BinData/"))
			.forEach(entry -> {
				try (InputStream input = zipFile.getInputStream(entry)) {
					byte[] bytes = input.readAllBytes();
					if (bytes.length == 0) {
						return;
					}
					String fileName = Path.of(entry.getName()).getFileName().toString();
					String key = stripExtension(fileName);
					String assetName = safeAssetName(fileName);
					// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
					Files.write(assetDirectory.resolve(assetName), bytes);
					images.put(key, "/api/rag-documents/" + documentId + "/preview-assets/" + assetName);
				} catch (IOException ignored) {
					// Broken embedded images should not block the text preview.
				}
			});
		return images;
	}

	// 메소드 설명: parseXml 처리 흐름을 수행합니다.
	// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
	private Document parseXml(ZipFile zipFile, ZipEntry entry) {
		try (InputStream input = zipFile.getInputStream(entry)) {
			DocumentBuilderFactory factory = secureDocumentBuilderFactory();
			return factory.newDocumentBuilder().parse(input);
		} catch (Exception exception) {
			throw new IllegalStateException("HWPX XML parse failed: " + entry.getName(), exception);
		}
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

	// 메소드 설명: renderChildren 처리 흐름을 수행합니다.
	private void renderChildren(Node parent, StringBuilder html, Map<String, String> images, boolean topLevel) {
		for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String name = localName(child);
			if ("p".equals(name)) {
				renderParagraph((Element) child, html, images);
			} else if ("tbl".equals(name)) {
				renderTable((Element) child, html, images);
			} else if (!topLevel) {
				renderInline(child, html, images);
			}
		}
	}

	// 메소드 설명: renderParagraph 처리 흐름을 수행합니다.
	private void renderParagraph(Element paragraph, StringBuilder html, Map<String, String> images) {
		StringBuilder content = new StringBuilder();
		List<String> blockParts = new ArrayList<>();
		for (Node child = paragraph.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String name = localName(child);
			if ("run".equals(name)) {
				renderRun((Element) child, content, blockParts, images);
			}
		}

		String text = plainText(paragraph).trim();
		boolean hasBlocks = !blockParts.isEmpty();
		if (content.isEmpty() && !hasBlocks && text.isBlank()) {
			return;
		}

		String styleId = attr(paragraph, "styleIDRef");
		String className = paragraphClass(text, styleId, hasBlocks);
		String cleanedContent = HwpxTextCleaner.clean(content.toString());
		if (!cleanedContent.isEmpty()) {
			html.append("<p class=\"").append(className).append("\">").append(cleanedContent).append("</p>");
		}
		for (String block : blockParts) {
			html.append(block);
		}
	}

	// 메소드 설명: renderRun 처리 흐름을 수행합니다.
	private void renderRun(Element run, StringBuilder inline, List<String> blockParts, Map<String, String> images) {
		for (Node child = run.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() != Node.ELEMENT_NODE) {
				continue;
			}
			String name = localName(child);
			if ("tbl".equals(name)) {
				StringBuilder table = new StringBuilder();
				renderTable((Element) child, table, images);
				blockParts.add(table.toString());
			} else if ("pic".equals(name)) {
				inline.append(renderImage((Element) child, images));
			} else {
				renderInline(child, inline, images);
			}
		}
	}

	// 메소드 설명: renderInline 처리 흐름을 수행합니다.
	private void renderInline(Node node, StringBuilder html, Map<String, String> images) {
		String name = localName(node);
		if ("t".equals(name)) {
			html.append(escape(node.getTextContent()));
			return;
		}
		if ("tab".equals(name)) {
			html.append("<span class=\"hx-tab\"></span>");
			return;
		}
		if ("lineBreak".equals(name) || "br".equals(name)) {
			html.append("<br>");
			return;
		}
		if ("pic".equals(name) && node instanceof Element element) {
			html.append(renderImage(element, images));
			return;
		}
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() == Node.TEXT_NODE) {
				html.append(escape(child.getNodeValue()));
			} else if (child.getNodeType() == Node.ELEMENT_NODE) {
				renderInline(child, html, images);
			}
		}
	}

	// 메소드 설명: renderTable 처리 흐름을 수행합니다.
	private void renderTable(Element table, StringBuilder html, Map<String, String> images) {
		html.append("<table class=\"hx-table\"><tbody>");
		for (Node row = table.getFirstChild(); row != null; row = row.getNextSibling()) {
			if (row.getNodeType() == Node.ELEMENT_NODE && "tr".equals(localName(row))) {
				html.append("<tr>");
				for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNextSibling()) {
					if (cell.getNodeType() == Node.ELEMENT_NODE && "tc".equals(localName(cell))) {
						renderCell((Element) cell, html, images);
					}
				}
				html.append("</tr>");
			}
		}
		html.append("</tbody></table>");
	}

	// 메소드 설명: renderCell 처리 흐름을 수행합니다.
	private void renderCell(Element cell, StringBuilder html, Map<String, String> images) {
		Element span = firstChild(cell, "cellSpan");
		String colSpan = span == null ? "" : attr(span, "colSpan");
		String rowSpan = span == null ? "" : attr(span, "rowSpan");
		html.append("<td");
		if (isSpan(colSpan)) {
			html.append(" colspan=\"").append(escape(colSpan)).append("\"");
		}
		if (isSpan(rowSpan)) {
			html.append(" rowspan=\"").append(escape(rowSpan)).append("\"");
		}
		html.append(">");
		Element subList = firstChild(cell, "subList");
		if (subList != null) {
			renderChildren(subList, html, images, true);
		}
		html.append("</td>");
	}

	// 메소드 설명: renderImage 처리 흐름을 수행합니다.
	private String renderImage(Element pic, Map<String, String> images) {
		String key = findBinaryImageKey(pic);
		if (key == null || !images.containsKey(key)) {
			return "<span class=\"hx-image-missing\">이미지</span>";
		}
		int width = hwpxToPx(attr(firstChild(pic, "curSz"), "width"));
		int height = hwpxToPx(attr(firstChild(pic, "curSz"), "height"));
		StringBuilder html = new StringBuilder("<img class=\"hx-image\" src=\"");
		html.append(images.get(key)).append("\" alt=\"문서 이미지\"");
		if (width > 0) {
			html.append(" style=\"max-width:min(100%, ").append(width).append("px);");
			if (height > 0) {
				html.append(" aspect-ratio:").append(width).append("/").append(height).append(";");
			}
			html.append("\"");
		}
		html.append(">");
		return html.toString();
	}

	// 메소드 설명: findBinaryImageKey 처리 흐름을 수행합니다.
	private String findBinaryImageKey(Node node) {
		if (node.getNodeType() == Node.ELEMENT_NODE) {
			Element element = (Element) node;
			String value = attr(element, "binaryItemIDRef");
			if (!value.isBlank()) {
				return value;
			}
		}
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			String found = findBinaryImageKey(child);
			if (found != null) {
				return found;
			}
		}
		return null;
	}

	// 메소드 설명: paragraphClass 처리 흐름을 수행합니다.
	private String paragraphClass(String text, String styleId, boolean hasBlocks) {
		if (hasBlocks) {
			return "hx-para hx-para-block";
		}
		if (text.length() <= 80 && (!styleId.isBlank() && !"0".equals(styleId))) {
			return "hx-para hx-heading";
		}
		if (text.startsWith("※") || text.startsWith("*")) {
			return "hx-para hx-note";
		}
		return "hx-para";
	}

	// 메소드 설명: htmlShell 처리 흐름을 수행합니다.
	private String htmlShell(RagDocumentRow document, String body) {
		String title = escape(document.title() == null || document.title().isBlank() ? document.fileName() : document.title());
		return """
			<!doctype html>
			<html lang="ko">
			<head>
			  <meta charset="utf-8">
			  <meta name="viewport" content="width=device-width, initial-scale=1">
			  <title>%s</title>
			  <style>
			    :root { color-scheme: dark; background: #07140f; }
			    * { box-sizing: border-box; }
			    body { margin: 0; padding: 28px; color: #17211d; background: #07140f; font-family: "Malgun Gothic", "Apple SD Gothic Neo", sans-serif; }
			    .hx-page { width: min(100%%, 920px); min-height: 1180px; margin: 0 auto 28px; padding: 58px 64px; background: #f7f8f3; border-radius: 3px; box-shadow: 0 22px 70px rgba(0,0,0,.42); }
			    .hx-page-label { margin: -34px 0 28px; color: #75807a; font-size: 11px; font-weight: 800; letter-spacing: .08em; text-align: right; }
			    .hx-para { margin: 0 0 9px; color: #17211d; font-size: 14px; line-height: 1.78; word-break: keep-all; overflow-wrap: anywhere; }
			    .hx-heading { margin: 18px 0 11px; color: #0d1713; font-size: 18px; line-height: 1.5; font-weight: 900; }
			    .hx-note { color: #39453f; font-size: 13px; }
			    .hx-tab { display: inline-block; width: 2.4em; }
			    .hx-table { width: 100%%; margin: 12px 0 18px; border-collapse: collapse; table-layout: fixed; color: #17211d; background: #fff; }
			    .hx-table td { min-height: 24px; padding: 7px 9px; border: 1px solid #727a74; vertical-align: top; font-size: 13px; line-height: 1.55; }
			    .hx-table .hx-para { margin-bottom: 4px; font-size: inherit; line-height: inherit; }
			    .hx-image { display: block; height: auto; margin: 14px auto; border: 0; object-fit: contain; }
			    .hx-image-missing { display: inline-flex; align-items: center; justify-content: center; min-width: 72px; min-height: 28px; padding: 4px 9px; border: 1px dashed #9aa39d; color: #5b655f; font-size: 12px; }
			    @media (max-width: 760px) {
			      body { padding: 12px; }
			      .hx-page { min-height: 0; padding: 34px 24px; }
			    }
			  </style>
			</head>
			<body data-preview-version="%s">%s</body>
			</html>
			""".formatted(title, PREVIEW_VERSION, body);
	}

	// 메소드 설명: sourcePath 처리 흐름을 수행합니다.
	private Path sourcePath(RagDocumentRow document) {
		if (document == null || document.filePath() == null || document.filePath().isBlank()) {
			return null;
		}
		try {
			Path source = Path.of(document.filePath()).toAbsolutePath().normalize();
			return isHwpx(source) ? source : null;
		} catch (InvalidPathException exception) {
			return null;
		}
	}

	// 메소드 설명: previewPath 처리 흐름을 수행합니다.
	private Path previewPath(RagDocumentRow document, Path source) throws IOException {
		Path directory = Path.of("storage", "rag-preview").toAbsolutePath().normalize();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		Files.createDirectories(directory);
		String baseName = document.fileHash() == null || document.fileHash().isBlank()
			? stripExtension(source.getFileName().toString()).replaceAll("[^A-Za-z0-9._-]", "_")
			: document.fileHash();
		return directory.resolve(baseName + ".html");
	}

	// 메소드 설명: assetDirectory 처리 흐름을 수행합니다.
	private Path assetDirectory(Path preview) {
		return preview.getParent().resolve(stripExtension(preview.getFileName().toString()) + "-assets").toAbsolutePath().normalize();
	}

	// 메소드 설명: isHwpx 처리 흐름을 수행합니다.
	private boolean isHwpx(Path source) {
		return source.getFileName().toString().toLowerCase().endsWith(".hwpx");
	}

	// 메소드 설명: firstChild 처리 흐름을 수행합니다.
	private Element firstChild(Node node, String localName) {
		if (node == null) {
			return null;
		}
		for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
			if (child.getNodeType() == Node.ELEMENT_NODE && localName.equals(localName(child))) {
				return (Element) child;
			}
		}
		return null;
	}

	// 메소드 설명: plainText 처리 흐름을 수행합니다.
	private String plainText(Node node) {
		return node == null ? "" : HwpxTextCleaner.clean(node.getTextContent()).replaceAll("\\s+", " ");
	}

	// 메소드 설명: localName 처리 흐름을 수행합니다.
	private String localName(Node node) {
		String local = node.getLocalName();
		if (local != null) {
			return local;
		}
		String name = node.getNodeName();
		int index = name.indexOf(':');
		return index >= 0 ? name.substring(index + 1) : name;
	}

	// 메소드 설명: attr 처리 흐름을 수행합니다.
	private String attr(Element element, String name) {
		return element == null || !element.hasAttribute(name) ? "" : element.getAttribute(name);
	}

	// 메소드 설명: isSpan 처리 흐름을 수행합니다.
	private boolean isSpan(String value) {
		try {
			return Integer.parseInt(value) > 1;
		} catch (Exception exception) {
			return false;
		}
	}

	// 메소드 설명: hwpxToPx 처리 흐름을 수행합니다.
	private int hwpxToPx(String value) {
		try {
			int hwpxUnit = Integer.parseInt(value);
			return Math.max(1, Math.round(hwpxUnit / 100f));
		} catch (Exception exception) {
			return 0;
		}
	}

	// 메소드 설명: stripExtension 처리 흐름을 수행합니다.
	private String stripExtension(String fileName) {
		int index = fileName.lastIndexOf('.');
		return index > 0 ? fileName.substring(0, index) : fileName;
	}

	// 메소드 설명: safeAssetName 처리 흐름을 수행합니다.
	private String safeAssetName(String fileName) {
		String safe = fileName.replaceAll("[^A-Za-z0-9._-]", "_");
		return safe.isBlank() ? Base64.getUrlEncoder().withoutPadding().encodeToString(fileName.getBytes(StandardCharsets.UTF_8)) : safe;
	}

	// 메소드 설명: escape 처리 흐름을 수행합니다.
	private String escape(String value) {
		if (value == null) {
			return "";
		}
		return value
			.replace("&", "&amp;")
			.replace("<", "&lt;")
			.replace(">", "&gt;")
			.replace("\"", "&quot;");
	}
}
