package com.kaces.pandora.rag.document;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.detail.LawDetailResponse;
import com.kaces.pandora.lawdata.detail.LawDetailSectionResponse;
import com.kaces.pandora.rag.chunk.RagTextNoiseFilter;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import com.kaces.pandora.rag.importing.RagImportResponse;
import com.kaces.pandora.rag.importing.RagImportService;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.preview.HwpxHtmlPreviewService;
import com.kaces.pandora.rag.preview.RagDocumentPreviewService;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.core.io.InputStreamResource;
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
	private final RagOriginalDocumentStore originalDocumentStore;

	public RagDocumentController(
		RagImportService importService,
		RagDocumentMapper mapper,
		RagDocumentPreviewService previewService,
		HwpxHtmlPreviewService htmlPreviewService,
		RagOriginalDocumentStore originalDocumentStore
	) {
		this.importService = importService;
		this.mapper = mapper;
		this.previewService = previewService;
		this.htmlPreviewService = htmlPreviewService;
		this.originalDocumentStore = originalDocumentStore;
	}

	@PostMapping("/import-folder")
	public ResponseEntity<RagImportResponse> importFolder(
		@RequestParam(defaultValue = "") String documentType,
		@RequestParam(defaultValue = "") String path,
		@RequestParam(defaultValue = "true") boolean indexNow,
		@RequestParam(defaultValue = "false") boolean force
	) {
		return ResponseEntity.ok(importService.importFolder(documentType, path, indexNow, force));
	}

	@PostMapping("/reimport-existing")
	public ResponseEntity<RagImportResponse> reimportExisting(
		@RequestParam(defaultValue = "") String documentType,
		@RequestParam(defaultValue = "false") boolean indexNow,
		@RequestParam(defaultValue = "true") boolean force
	) {
		return ResponseEntity.ok(importService.reimportExistingDocuments(documentType, indexNow, force));
	}

	@GetMapping("/{documentId}/detail")
	public ResponseEntity<LawDetailResponse> detail(@PathVariable long documentId) {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		if (document == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		List<LawDetailSectionResponse> sections = mapper.findSemanticChunksByDocumentId(documentId).stream()
			.map(chunk -> section(chunk))
			.filter(section -> !RagTextNoiseFilter.isTableOfContents(section.title(), section.body()))
			.filter(section -> !RagTextNoiseFilter.isMeaninglessSection(section.title(), section.body()))
			.toList();
		List<String> meta = Stream.of(
			label(document.documentType()),
			nonBlank(document.sourceOrg(), document.fileName()),
			nonBlank(document.publishedDate(), document.version())
		).filter(value -> value != null && !value.isBlank()).toList();
		String originalFileUrl = originalDocumentStore.exists(document)
			? "/api/rag-documents/" + document.documentId() + "/file"
			: null;

		return ResponseEntity.ok(new LawDetailResponse(
			false,
			"rag",
			document.documentId(),
			document.title(),
			meta,
			sections,
			originalFileUrl,
			document.fileName(),
			document.mimeType(),
			previewService.canPreview(document) ? "/api/rag-documents/" + document.documentId() + "/preview.pdf" : null,
			htmlPreviewService.canPreview(document) ? "/api/rag-documents/" + document.documentId() + "/preview.html" : null,
			null
		));
	}

	@GetMapping("/{documentId}/file")
	public ResponseEntity<Resource> file(@PathVariable long documentId) {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		if (document == null || !originalDocumentStore.exists(document)) {
			return ResponseEntity.notFound().build();
		}
		try {
			RagOriginalDocumentStore.StoredOriginal original = originalDocumentStore.open(document);
			ResponseEntity.BodyBuilder response = ResponseEntity.ok()
				.contentType(mediaType(original.contentType(), document.mimeType()))
				.header(
					HttpHeaders.CONTENT_DISPOSITION,
					inlineDisposition(document.fileName() == null ? "document" : document.fileName())
				);
			if (original.contentLength() >= 0) {
				response.contentLength(original.contentLength());
			}
			return response.body(new InputStreamResource(original.inputStream()));
		} catch (FileNotFoundException exception) {
			return ResponseEntity.notFound().build();
		} catch (IOException exception) {
			return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
		}
	}

	@GetMapping("/{documentId}/preview.pdf")
	public ResponseEntity<Resource> previewPdf(@PathVariable long documentId) throws MalformedURLException {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		Resource resource = previewService.previewPdf(document);
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_PDF)
			.header(
				HttpHeaders.CONTENT_DISPOSITION,
				inlineDisposition((document == null ? "preview" : document.title()) + ".pdf")
			)
			.body(resource);
	}

	@GetMapping(value = "/{documentId}/preview.html", produces = MediaType.TEXT_HTML_VALUE)
	public ResponseEntity<String> previewHtml(@PathVariable long documentId) {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_HTML)
			.body(htmlPreviewService.previewHtml(document));
	}

	@GetMapping("/{documentId}/preview-assets/{fileName}")
	public ResponseEntity<Resource> previewAsset(@PathVariable long documentId, @PathVariable String fileName) throws MalformedURLException {
		RagDocumentRow document = mapper.findDocumentById(documentId);
		Path asset = htmlPreviewService.previewAsset(document, fileName);
		String mimeType;
		try {
			mimeType = Files.probeContentType(asset);
		} catch (Exception exception) {
			mimeType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
		}
		return ResponseEntity.ok()
			.contentType(mediaType(mimeType, null))
			.body(new UrlResource(asset.toUri()));
	}

	static String inlineDisposition(String fileName) {
		return ContentDisposition.inline()
			.filename(fileName, StandardCharsets.UTF_8)
			.build()
			.toString();
	}

	private LawDetailSectionResponse section(LawSemanticChunkRow chunk) {
		return new LawDetailSectionResponse(
			HwpxTextCleaner.clean(chunk.chunkTitle()),
			HwpxTextCleaner.clean(chunk.chunkText()),
			chunk.pageNo(),
			chunk.sourcePath(),
			chunk.chunkId()
		);
	}

	private String label(String documentType) {
		return switch (String.valueOf(documentType)) {
			case RagDocumentType.OFFICIAL_DOC -> "공식 가이드 문서";
			case RagDocumentType.INTERNAL_DOC -> "내부 지침/매뉴얼";
			case RagDocumentType.REFERENCE_DOC -> "참고자료";
			default -> documentType;
		};
	}

	private MediaType mediaType(String preferred, String fallback) {
		String value = nonBlank(preferred, nonBlank(fallback, MediaType.APPLICATION_OCTET_STREAM_VALUE));
		try {
			return MediaType.parseMediaType(value);
		} catch (Exception ignored) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	private String nonBlank(String first, String second) {
		if (first != null && !first.isBlank()) {
			return first;
		}
		return second;
	}
}
