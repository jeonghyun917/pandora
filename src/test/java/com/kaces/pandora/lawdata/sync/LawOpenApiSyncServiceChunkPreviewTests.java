package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawSyncHistoryMapper;
import org.junit.jupiter.api.Test;

class LawOpenApiSyncServiceChunkPreviewTests {

	@Test
	void createCandidateChunksRejectsUnsupportedTargetBeforeWriting() {
		LawOpenApiSyncService service = new LawOpenApiSyncService(
			mock(LawOpenApiService.class), mock(LawOpenApiPayloadParser.class), mock(LawDocumentWriter.class),
			mock(LawDetailMapper.class), mock(LawSyncHistoryMapper.class), mock(LawJsonWriter.class)
		);

		assertThatThrownBy(() -> service.createCandidateChunks("guide", 42L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unsupported law data target: guide");
	}
}
