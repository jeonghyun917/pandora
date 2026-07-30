package com.kaces.pandora.rag.preview;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import org.junit.jupiter.api.Test;

class RagDocumentPreviewStorageContractTests {

	@Test
	void previewServicesReceiveTheOriginalDocumentStore() {
		assertThatCode(() -> RagDocumentPreviewService.class.getDeclaredConstructor(
			RagTextExtractor.class,
			RagOriginalDocumentStore.class
		)).doesNotThrowAnyException();
		assertThatCode(() -> HwpxHtmlPreviewService.class.getDeclaredConstructor(RagOriginalDocumentStore.class))
			.doesNotThrowAnyException();
	}
}
