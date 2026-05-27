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
				.filter(entry -> entry.getName().startsWith("Contents/"))
				.filter(entry -> entry.getName().endsWith(".xml"))
				.sorted(java.util.Comparator.comparing(java.util.zip.ZipEntry::getName))
				.toList();
			int pageNo = 1;
			for (var entry : entries) {
				try (InputStream input = zipFile.getInputStream(entry)) {
					var document = factory.newDocumentBuilder().parse(input);
					StringBuilder builder = new StringBuilder();
					appendText(document.getDocumentElement(), builder);
					String text = normalize(builder.toString());
					if (!text.isBlank()) {
						pages.add(new ExtractedPage(pageNo++, text));
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
		if (node == null) {
			return;
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
			appendText(child, builder);
		}
	}

	// 메소드 설명: normalize 처리 흐름을 수행합니다.
	private String normalize(String value) {
		return value == null ? "" : HwpxTextCleaner.clean(value.replace("\r\n", "\n").replace('\r', '\n'));
	}
}
