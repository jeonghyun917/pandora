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
    private com.kaces.pandora.rag.recovery.RagPdfOriginalReader pdfOriginals;
    @org.springframework.beans.factory.annotation.Autowired
    void configurePdfOriginals(com.kaces.pandora.rag.recovery.RagPdfOriginalReader reader) {
        pdfOriginals=java.util.Objects.requireNonNull(reader);
    }
    private com.kaces.pandora.rag.recovery.RagRecoveryRoutingService recoveryRouting;
    @org.springframework.beans.factory.annotation.Autowired
    void configureRecoveryRouting(com.kaces.pandora.rag.recovery.RagRecoveryRoutingService routing) {
        this.recoveryRouting=java.util.Objects.requireNonNull(routing);
    }
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
        var routing=recoveryRouting==null ? null : recoveryRouting.capture();
		RagDocumentRow document = mapper.findDocumentById(documentId);
		if (document == null) {
			return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
		}

		List<LawDetailSectionResponse> sections = (routing==null ? mapper.findSemanticChunksByDocumentId(documentId)
            : mapper.findSemanticChunksByDocumentIdRouted(documentId,routing)).stream()
			.map(chunk -> section(chunk))
			.filter(section -> !RagTextNoiseFilter.isTableOfContents(section.title(), section.body()))
			.filter(section -> !RagTextNoiseFilter.isMeaninglessSection(section.title(), section.body()))
			.toList();
		var pdf=pdfSource(documentId,routing);
        if(pdf!=null) {
            String url="/api/rag-documents/"+documentId+"/sources/"+pdf.generationId()+"/file";
            return ResponseEntity.ok(new LawDetailResponse(false,"rag",documentId,pdf.title(),
                    List.of(label(document.documentType())),sections,url,pdf.title()+".pdf","application/pdf",url,null,null));
        }
		List<String> meta = Stream.of(
			label(document.documentType()),
			nonBlank(document.sourceOrg(), document.fileName()),
			nonBlank(document.publishedDate(), document.version())
		).filter(value -> value != null && !value.isBlank()).toList();
		String originalFileUrl = originalDocumentStore.exists(document)
			? "/api/rag-documents/" + document.documentId() + "/file"
			: null;
        if(originalFileUrl!=null && routing!=null && routing.documents().containsKey(documentId))
            originalFileUrl="/api/rag-documents/"+documentId+"/sources/"+routing.documents().get(documentId).activeGeneration()+"/file";
        String previewPrefix="/api/rag-documents/"+documentId;
        if(routing!=null && routing.documents().containsKey(documentId))
            previewPrefix+="/sources/"+routing.documents().get(documentId).activeGeneration();

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
			previewService.canPreview(document) ? previewPrefix + "/preview.pdf" : null,
			htmlPreviewService.canPreview(document) ? previewPrefix + "/preview.html" : null,
			null
		));
	}

	@GetMapping("/{documentId}/file")
	public ResponseEntity<Resource> file(@PathVariable long documentId) {
		var pdf=currentPdfSource(documentId);
        if(pdf!=null)return capturedPdf(pdf);
		RagDocumentRow document = mapper.findDocumentById(documentId);
        return preservedFile(document);
    }

    @GetMapping("/{documentId}/sources/{generationId}/file")
    public ResponseEntity<Resource> sourceFile(@PathVariable long documentId,@PathVariable String generationId) {
        try {
            if(pdfOriginals==null)throw new IllegalStateException("Published original reader unavailable");
            var document=mapper.findDocumentById(documentId);
            if(document==null)return ResponseEntity.notFound().build();
            if(pdfOriginals.isPreservedOriginal(documentId,generationId,document.fileHash()))return preservedFile(document);
            var original=pdfOriginals.open(documentId,generationId);
            return pdfResponse(original.bytes(),original.source().title());
        } catch(IllegalArgumentException invalid) {return ResponseEntity.badRequest().build();}
          catch(IllegalStateException unavailable) {return ResponseEntity.status(HttpStatus.CONFLICT).build();}
    }

    private ResponseEntity<Resource> preservedFile(RagDocumentRow document) {
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
		var pdf=currentPdfSource(documentId);
        if(pdf!=null)return capturedPdf(pdf);
		RagDocumentRow document = mapper.findDocumentById(documentId);
        return preservedPreviewPdf(document);
    }

    @GetMapping("/{documentId}/sources/{generationId}/preview.pdf")
    public ResponseEntity<Resource> sourcePreviewPdf(@PathVariable long documentId,@PathVariable String generationId) throws MalformedURLException {
        var document=preservedDocument(documentId,generationId);
        return document==null?sourceFile(documentId,generationId):preservedPreviewPdf(document);
    }

    private ResponseEntity<Resource> preservedPreviewPdf(RagDocumentRow document) throws MalformedURLException {
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
        if(currentPdfSource(documentId)!=null)return ResponseEntity.notFound().build();
		RagDocumentRow document = mapper.findDocumentById(documentId);
		return ResponseEntity.ok()
			.contentType(MediaType.TEXT_HTML)
			.body(htmlPreviewService.previewHtml(document));
	}

    @GetMapping(value="/{documentId}/sources/{generationId}/preview.html",produces=MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> sourcePreviewHtml(@PathVariable long documentId,@PathVariable String generationId) {
        var document=preservedDocument(documentId,generationId);
        if(document==null)return ResponseEntity.notFound().build();
        String html=htmlPreviewService.previewHtml(document).replace("/api/rag-documents/"+documentId+"/preview-assets/",
                "/api/rag-documents/"+documentId+"/sources/"+generationId+"/preview-assets/");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML).body(html);
    }

	@GetMapping("/{documentId}/preview-assets/{fileName}")
	public ResponseEntity<Resource> previewAsset(@PathVariable long documentId, @PathVariable String fileName) throws MalformedURLException {
        if(currentPdfSource(documentId)!=null)return ResponseEntity.notFound().build();
		RagDocumentRow document = mapper.findDocumentById(documentId);
        return preservedPreviewAsset(document,fileName);
    }

    @GetMapping("/{documentId}/sources/{generationId}/preview-assets/{fileName}")
    public ResponseEntity<Resource> sourcePreviewAsset(@PathVariable long documentId,@PathVariable String generationId,@PathVariable String fileName) throws MalformedURLException {
        var document=preservedDocument(documentId,generationId);
        if(document==null)return ResponseEntity.notFound().build();
        return preservedPreviewAsset(document,fileName);
    }

    private ResponseEntity<Resource> preservedPreviewAsset(RagDocumentRow document,String fileName) throws MalformedURLException {
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

    private RagDocumentRow preservedDocument(long documentId,String generationId) {
        if(pdfOriginals==null)throw new IllegalStateException("Published original reader unavailable");
        var document=mapper.findDocumentById(documentId);
        return document!=null && pdfOriginals.isPreservedOriginal(documentId,generationId,document.fileHash())?document:null;
    }
    private com.kaces.pandora.rag.recovery.RecoveryPdfSource currentPdfSource(long documentId) {
        return pdfSource(documentId,recoveryRouting==null?null:recoveryRouting.capture());
    }
    private com.kaces.pandora.rag.recovery.RecoveryPdfSource pdfSource(long documentId,
            com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot routing) {
        var pointer=routing==null?null:routing.documents().get(documentId);
        return pointer==null?null:routing.pdfSources().get(pointer.activeGeneration());
    }
    private ResponseEntity<Resource> capturedPdf(com.kaces.pandora.rag.recovery.RecoveryPdfSource source) {
        try {
            if(pdfOriginals==null)throw new IllegalStateException("Published original reader unavailable");
            return pdfResponse(pdfOriginals.read(source),source.title());
        } catch(IllegalArgumentException invalid){return ResponseEntity.badRequest().build();}
          catch(IllegalStateException unavailable){return ResponseEntity.status(HttpStatus.CONFLICT).build();}
    }
    private ResponseEntity<Resource> pdfResponse(byte[] bytes,String title) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF).contentLength(bytes.length)
                .header(HttpHeaders.CONTENT_DISPOSITION,inlineDisposition(title+".pdf"))
                .body(new org.springframework.core.io.ByteArrayResource(bytes));
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
