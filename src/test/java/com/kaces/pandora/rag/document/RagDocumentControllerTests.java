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
