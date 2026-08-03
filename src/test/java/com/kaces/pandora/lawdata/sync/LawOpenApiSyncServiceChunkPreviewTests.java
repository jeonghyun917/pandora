package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawSyncHistoryMapper;
import com.kaces.pandora.lawdata.chunk.LawChunkRebuildRow;
import java.util.List;
import org.junit.jupiter.api.Test;

class LawOpenApiSyncServiceChunkPreviewTests {

	@Test
	void createCandidateChunksRejectsUnsupportedTargetBeforeWriting() {
		LawOpenApiSyncService service = new LawOpenApiSyncService(
			mock(LawOpenApiService.class), mock(LawOpenApiPayloadParser.class), mock(LawDocumentWriter.class),
			mock(LawDetailMapper.class), mock(LawSyncHistoryMapper.class), mock(LawJsonWriter.class), mock(LawChunkActivationSaga.class)
		);

		assertThatThrownBy(() -> service.createCandidateChunks("guide", 42L))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessage("Unsupported law data target: guide");
	}

	@Test
	void createCandidateRejectsAbsentOrStalePreviewApprovalTokenBeforeWriting() {
		LawOpenApiPayloadParser parser = mock(LawOpenApiPayloadParser.class);
		LawDocumentWriter writer = mock(LawDocumentWriter.class);
		LawDetailMapper detailMapper = mock(LawDetailMapper.class);
		LawOpenApiSyncService service = new LawOpenApiSyncService(
			mock(LawOpenApiService.class), parser, writer, detailMapper,
			mock(LawSyncHistoryMapper.class), mock(LawJsonWriter.class), mock(LawChunkActivationSaga.class)
		);
		LawChunkRebuildRow row = new LawChunkRebuildRow(42L, 7L, "law", "Document", "Document", "raw-source", "", 1, 0);
		when(detailMapper.findChunkRebuildRowsByDocumentIds("law", List.of(42L))).thenReturn(List.of(row));
		when(parser.parseDetailDocument("raw-source", "Document")).thenReturn(new SyncDetailDocument(
			"Document", List.of(new SyncDetailSection("article", "Article 1", "Scope", "Alpha beta gamma.", "$.articles[0]", 1, 1)), List.of()
		));

		assertThatThrownBy(() -> service.createCandidateChunks("law", 42L, ""))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Preview approval token");
		assertThatThrownBy(() -> service.createCandidateChunks("law", 42L, "stale-token"))
			.isInstanceOf(IllegalArgumentException.class)
			.hasMessageContaining("Preview approval token");
		verify(writer, never()).createCandidateChunks(
			org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.anyString(),
			org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt()
		);
	}
}
