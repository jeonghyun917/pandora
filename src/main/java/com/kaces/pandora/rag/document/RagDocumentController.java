package com.kaces.pandora.rag.document;


import com.kaces.pandora.rag.chunk.RagTextNoiseFilter;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import com.kaces.pandora.rag.importing.RagImportResponse;
import com.kaces.pandora.rag.importing.RagImportService;
import com.kaces.pandora.rag.preview.HwpxHtmlPreviewService;
import com.kaces.pandora.rag.preview.RagDocumentPreviewService;
import com.kaces.pandora.lawdata.detail.LawDetailResponse;
import com.kaces.pandora.lawdata.detail.LawDetailSectionResponse;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.document.RagDocumentRow;
import java.net.MalformedURLException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rag-documents")
public class RagDocumentController {
	private final RagImportService importService;
	private final RagDocumentMapper mapper;
	private final RagDocumentPreviewService previewService;
	private final HwpxHtmlPreviewService htmlPreviewService;

	public RagDocumentController(
		RagImportService importService,
		RagDocumentMapper mapper,
		RagDocumentPreviewService previewService,
		HwpxHtmlPreviewService htmlPreviewService
	) {
		this.importService = importService;
		this.mapper = mapper;
		this.previewService = previewService;
		this.htmlPreviewService = htmlPreviewService;
	}

	@PostMapping("/import-folder")
	public ResponseEntity<RagImportResponse> importFolder(
		@RequestParam(defaultValue = "") String documentType,
		@RequestParam(defaultValue = "") String path,
		@RequestParam(defaultValue = "true") boolean indexNow
	) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(importService.importFolder(documentType, path, indexNow));
	}

	@GetMapping("/{documentId}/detail")
	// 메소드 설명: detail 처리 흐름을 수행합니다.
	public ResponseEntity<LawDetailResponse> detail(@PathVariable long documentId) {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		if (document == null) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		List<LawSemanticChunkRow> chunks = mapper.findSemanticChunksByDocumentId(documentId);
		List<LawDetailSectionResponse> sections = chunks.stream()
			.map(chunk -> new LawDetailSectionResponse(
				cleanHwpxText(chunk.chunkTitle()),
				cleanHwpxText(chunk.chunkText()),
				chunk.pageNo(),
				chunk.sourcePath()
			))
			.filter(section -> !RagTextNoiseFilter.isTableOfContents(section.title(), section.body()))
			.filter(section -> !RagTextNoiseFilter.isMeaninglessSection(section.title(), section.body()))
			.toList();
		List<String> meta = Stream.of(
			label(document.documentType()),
			nonBlank(document.sourceOrg(), document.fileName()),
			nonBlank(document.publishedDate(), document.version())
		).filter(value -> value != null && !value.isBlank()).toList();

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok(new LawDetailResponse(
			false,
			"rag",
			document.documentId(),
			document.title(),
			meta,
			sections,
			"/api/rag-documents/" + document.documentId() + "/file",
			document.fileName(),
			document.mimeType(),
			previewService.canPreview(document) ? "/api/rag-documents/" + document.documentId() + "/preview.pdf" : null,
			htmlPreviewService.canPreview(document) ? "/api/rag-documents/" + document.documentId() + "/preview.html" : null
		));
	}

	@GetMapping("/{documentId}/file")
	// 메소드 설명: file 처리 흐름을 수행합니다.
	public ResponseEntity<Resource> file(@PathVariable long documentId) throws MalformedURLException {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		if (document == null || document.filePath() == null || document.filePath().isBlank()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return ResponseEntity.notFound().build();
		}

		Path file = Path.of(document.filePath()).toAbsolutePath().normalize();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			return ResponseEntity.notFound().build();
		}

		Resource resource = new UrlResource(file.toUri());
		String mimeType = nonBlank(document.mimeType(), null);
		if (mimeType == null) {
			try {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				mimeType = Files.probeContentType(file);
			} catch (Exception ignored) {
				mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
			}
		}

		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(mimeType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mimeType))
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
				.filename(document.fileName() == null ? file.getFileName().toString() : document.fileName())
				.build()
				.toString())
			.body(resource);
	}

	@GetMapping("/{documentId}/preview.pdf")
	// 메소드 설명: previewPdf 처리 흐름을 수행합니다.
	public ResponseEntity<Resource> previewPdf(@PathVariable long documentId) throws MalformedURLException {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		Resource resource = previewService.previewPdf(document);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline()
				.filename((document == null ? "preview" : document.title()) + ".pdf")
				.build()
			.toString())
			.body(resource);
	}

	@GetMapping(value = "/{documentId}/preview.html", produces = MediaType.TEXT_HTML_VALUE)
	// 메소드 설명: previewHtml 처리 흐름을 수행합니다.
	public ResponseEntity<String> previewHtml(@PathVariable long documentId) {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_HTML)
			.body(htmlPreviewService.previewHtml(document));
	}

	@GetMapping("/{documentId}/preview-assets/{fileName}")
	// 메소드 설명: previewAsset 처리 흐름을 수행합니다.
	public ResponseEntity<Resource> previewAsset(@PathVariable long documentId, @PathVariable String fileName) throws MalformedURLException {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		Path asset = htmlPreviewService.previewAsset(document, fileName);
		String mimeType;
		try {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			mimeType = Files.probeContentType(asset);
		} catch (Exception exception) {
			mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.parseMediaType(mimeType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : mimeType))
			.body(new UrlResource(asset.toUri()));
	}

	// 메소드 설명: label 처리 흐름을 수행합니다.
	private String label(String documentType) {
		return switch (String.valueOf(documentType)) {
			case RagDocumentType.OFFICIAL_DOC -> "공식 가이드 문서";
			case RagDocumentType.INTERNAL_DOC -> "내부 지침/매뉴얼";
			case RagDocumentType.REFERENCE_DOC -> "참고자료";
			default -> documentType;
		};
	}

	// 메소드 설명: nonBlank 처리 흐름을 수행합니다.
	private String nonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second;
	}

	// 메소드 설명: cleanHwpxText 처리 흐름을 수행합니다.
	private String cleanHwpxText(String value) {
		return HwpxTextCleaner.clean(value);
	}
}
