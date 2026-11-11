package com.kaces.pandora.rag.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kaces.pandora.rag.document.RagDocumentRow;
import com.kaces.pandora.rag.importing.RagImportService;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.preview.HwpxHtmlPreviewService;
import com.kaces.pandora.rag.preview.RagDocumentPreviewService;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.junit.jupiter.api.Test;

class RagDocumentControllerTests {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test void managedBaselineDownloadLinkRemainsPinned() throws Exception {
        var mapper=mock(RagDocumentMapper.class);var store=mock(RagOriginalDocumentStore.class);
        var originals=mock(com.kaces.pandora.rag.recovery.RagPdfOriginalReader.class);
        String generation="33333333-3333-3333-3333-333333333333";
        var doc=document();byte[] bytes="preserved original".getBytes(StandardCharsets.UTF_8);
        when(mapper.findDocumentById(7)).thenReturn(doc);
        var captured=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,
                java.util.Map.of(7L,new com.kaces.pandora.rag.recovery.RecoverySnapshot(7,0,generation,doc.fileHash(),"f".repeat(64))),
                java.util.Map.of(),java.util.Set.of(generation),null,null);
        var routing=mock(com.kaces.pandora.rag.recovery.RagRecoveryRoutingService.class);when(routing.capture()).thenReturn(captured);
        when(mapper.findSemanticChunksByDocumentIdRouted(7,captured)).thenReturn(java.util.List.of());
        when(originals.isPreservedOriginal(7,generation,doc.fileHash())).thenReturn(true);
        when(store.exists(doc)).thenReturn(true);
        when(store.open(doc)).thenReturn(new RagOriginalDocumentStore.StoredOriginal(new ByteArrayInputStream(bytes),(long)bytes.length,"application/pdf"));
        var preview=mock(RagDocumentPreviewService.class);var html=mock(HwpxHtmlPreviewService.class);
        when(preview.canPreview(doc)).thenReturn(true);when(html.canPreview(doc)).thenReturn(true);
        when(preview.previewPdf(doc)).thenReturn(new org.springframework.core.io.ByteArrayResource(bytes));
        when(html.previewHtml(doc)).thenReturn("<img src=\"/api/rag-documents/7/preview-assets/old.png\">");
        var image=root.resolve("old.png");java.nio.file.Files.write(image,new byte[]{1,2,3});
        when(html.previewAsset(doc,"old.png")).thenReturn(image);
        var controller=new RagDocumentController(mock(RagImportService.class),mapper,preview,html,store);
        controller.configureRecoveryRouting(routing);controller.configurePdfOriginals(originals);
        var detail=controller.detail(7).getBody();String prefix="/api/rag-documents/7/sources/"+generation;
        assertThat(detail.originalFileUrl()).isEqualTo(prefix+"/file");
        assertThat(detail.previewFileUrl()).isEqualTo(prefix+"/preview.pdf");
        assertThat(detail.previewHtmlUrl()).isEqualTo(prefix+"/preview.html");
        assertThat(controller.sourceFile(7,generation).getBody().getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(controller.sourcePreviewPdf(7,generation).getBody().getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(controller.sourcePreviewHtml(7,generation).getBody()).contains(prefix+"/preview-assets/old.png");
        try(var input=controller.sourcePreviewAsset(7,generation,"old.png").getBody().getInputStream()) {
            assertThat(input.readAllBytes()).containsExactly(1,2,3);
        }
    }

    @Test void publishedSourceReadRequiresLoginAndDoesNotLeakInternalFailures() throws Exception {
        var mapper=mock(RagDocumentMapper.class);var store=mock(RagOriginalDocumentStore.class);
        var originals=mock(com.kaces.pandora.rag.recovery.RagPdfOriginalReader.class);
        String generation="11111111-1111-1111-1111-111111111111";
        when(mapper.findDocumentById(7)).thenReturn(document());
        when(originals.open(7,generation)).thenThrow(new IllegalStateException("secret credential C:/internal/private"));
        var controller=controller(mapper,store);controller.configurePdfOriginals(originals);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(controller)
            .addInterceptors(new com.kaces.pandora.app.auth.AdminAuthInterceptor(new com.kaces.pandora.app.auth.AdminAuthProperties())).build();
        String url="/api/rag-documents/7/sources/"+generation+"/file";
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(originals);
        var session=new org.springframework.mock.web.MockHttpSession();
        session.setAttribute(com.kaces.pandora.app.auth.AdminSessionSupport.SESSION_ATTRIBUTE,
                new com.kaces.pandora.app.auth.AdminSession(1,"user","User","USER"));
        var response=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).session(session))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret","credential","internal","private");
        org.mockito.Mockito.verifyNoInteractions(store);
    }

    @Test void replacementDetailAndDownloadsStayOnCapturedSource() throws Exception {
        var mapper=mock(RagDocumentMapper.class);var store=mock(RagOriginalDocumentStore.class);
        var originals=mock(com.kaces.pandora.rag.recovery.RagPdfOriginalReader.class);
        String generation="11111111-1111-1111-1111-111111111111",hash="a".repeat(64),manifest="b".repeat(64);
        var source=new com.kaces.pandora.rag.recovery.RecoveryPdfSource(generation,7,"22222222-2222-2222-2222-222222222222",
                hash,"c".repeat(64),manifest,"New official edition","https://agency.example/new.pdf");
        var assets=new com.kaces.pandora.rag.recovery.RecoveryAssets(generation,manifest,"rag_recovery_"+generation.replace("-",""),"d".repeat(64),"e".repeat(64),1);
        var captured=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(1,
                java.util.Map.of(7L,new com.kaces.pandora.rag.recovery.RecoverySnapshot(7,1,generation,hash,"f".repeat(64))),
                java.util.Map.of(generation,assets),java.util.Set.of(),"e".repeat(64),"recovery_"+generation,
                com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot.empty(),java.util.Map.of(generation,source));
        var routing=mock(com.kaces.pandora.rag.recovery.RagRecoveryRoutingService.class);when(routing.capture()).thenReturn(captured);
        when(mapper.findDocumentById(7)).thenReturn(document());
        when(mapper.findSemanticChunksByDocumentIdRouted(7,captured)).thenReturn(java.util.List.of());
        byte[] bytes="retained replacement PDF".getBytes(StandardCharsets.UTF_8);
        when(originals.read(source)).thenReturn(bytes);
        when(originals.open(7,generation)).thenReturn(new com.kaces.pandora.rag.recovery.RagPdfOriginalReader.Original(source,bytes));
        var controller=controller(mapper,store);controller.configureRecoveryRouting(routing);controller.configurePdfOriginals(originals);
        var detail=controller.detail(7).getBody();
        assertThat(detail.title()).isEqualTo("New official edition");
        assertThat(detail.meta()).doesNotContain("Guide.pdf");
        assertThat(detail.originalFileUrl()).isEqualTo("/api/rag-documents/7/sources/"+generation+"/file");
        assertThat(detail.previewFileUrl()).isEqualTo(detail.originalFileUrl());
        assertThat(detail.previewHtmlUrl()).isNull();
        assertThat(controller.file(7).getBody().getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(controller.previewPdf(7).getBody().getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(controller.sourceFile(7,generation).getBody().getInputStream().readAllBytes()).isEqualTo(bytes);
        assertThat(controller.previewHtml(7).getStatusCode().value()).isEqualTo(404);
        assertThat(controller.previewAsset(7,"old.png").getStatusCode().value()).isEqualTo(404);
        org.mockito.Mockito.verifyNoInteractions(store);
    }

	@Test void detailUsesCapturedGenerationInsteadOfLegacyChunks() {
		var mapper=mock(RagDocumentMapper.class);var store=mock(RagOriginalDocumentStore.class);
		when(mapper.findDocumentById(7)).thenReturn(document());
		var routing=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),null,null);
		var router=mock(com.kaces.pandora.rag.recovery.RagRecoveryRoutingService.class);when(router.capture()).thenReturn(routing);
		when(mapper.findSemanticChunksByDocumentIdRouted(7,routing)).thenReturn(java.util.List.of(
			new com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow(123,7,"official_doc","7","문서","기관","공식",null,null,"1","보존 의무",
				"기관은 원본 문서를 보존하여야 한다.",1,null,null,0,"a".repeat(64),null,"body","PASS")));
		var controller=controller(mapper,store);controller.configureRecoveryRouting(router);
		assertThat(controller.detail(7).getBody().sections()).extracting(section->section.body()).containsExactly("기관은 원본 문서를 보존하여야 한다.");
	}

	@Test
	void encodesKoreanPreviewFilenameAsRfc5987ContentDisposition() {
		String header = RagDocumentController.inlineDisposition("개인정보 처리 가이드.pdf");

		assertThat(header)
			.startsWith("inline;")
			.contains("filename*=");
	}

	@Test
	void receivesTheOriginalDocumentStoreInsteadOfReadingDatabasePathsDirectly() {
		assertThatCode(() -> RagDocumentController.class.getDeclaredConstructor(
			RagImportService.class,
			RagDocumentMapper.class,
			RagDocumentPreviewService.class,
			HwpxHtmlPreviewService.class,
			RagOriginalDocumentStore.class
		)).doesNotThrowAnyException();
	}

	@Test
	void fileStreamsStoreContentWithoutUsingDatabaseLocalPath() throws Exception {
		RagDocumentMapper mapper = mock(RagDocumentMapper.class);
		RagOriginalDocumentStore store = mock(RagOriginalDocumentStore.class);
		RagDocumentRow document = document();
		byte[] bytes = "stored-original".getBytes(StandardCharsets.UTF_8);
		when(mapper.findDocumentById(7L)).thenReturn(document);
		when(store.exists(document)).thenReturn(true);
		when(store.open(document)).thenReturn(new RagOriginalDocumentStore.StoredOriginal(
			new ByteArrayInputStream(bytes),
			(long) bytes.length,
			"application/pdf"
		));

		ResponseEntity<Resource> response = controller(mapper, store).file(7L);

		assertThat(response.getStatusCode().value()).isEqualTo(200);
		assertThat(response.getHeaders().getContentType().toString()).isEqualTo("application/pdf");
		assertThat(response.getHeaders().getContentLength()).isEqualTo(bytes.length);
		assertThat(response.getBody().getInputStream().readAllBytes()).isEqualTo(bytes);
	}

	private RagDocumentController controller(RagDocumentMapper mapper, RagOriginalDocumentStore store) {
		return new RagDocumentController(
			mock(RagImportService.class),
			mapper,
			mock(RagDocumentPreviewService.class),
			mock(HwpxHtmlPreviewService.class),
			store
		);
	}

	private RagDocumentRow document() {
		return new RagDocumentRow(
			7L,
			"official_doc",
			"Guide",
			null,
			null,
			null,
			null,
			null,
			1,
			"Guide.pdf",
			"C:/not-used/Guide.pdf",
			"rag-originals/sha256/ab/example.pdf",
			"ab" + "1".repeat(62),
			"application/pdf",
			null,
			"INDEXED"
		);
	}
}
