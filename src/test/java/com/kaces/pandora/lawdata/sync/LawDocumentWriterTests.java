package com.kaces.pandora.lawdata.sync;

import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawAssetMapper;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentMapper;
import com.kaces.pandora.lawdata.version.LawVersionStatusService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LawDocumentWriterTests {

	@Test
	void replaceChunksPersistsExactEmbeddingHashAndCanonicalParentSourcePath() {
		LawChunkMapper chunkMapper = mock(LawChunkMapper.class);
		when(chunkMapper.findChunkIdsByDocumentId(41L)).thenReturn(List.of());
		LawDocumentWriter writer = new LawDocumentWriter(
			mock(LawDocumentMapper.class), mock(LawDetailMapper.class), chunkMapper,
			mock(LawAssetMapper.class), mock(LawJsonWriter.class), mock(QdrantClient.class),
			mock(LawVersionStatusService.class)
		);

		writer.replaceChunks(41L, 7L, "law", "Document title", List.of(
			new SyncDetailSection("article", "Article 1", "Article 1 (Scope)", "Operative child text.", "$.law.articles[0].body", 1, 1)
		), "https://example.test/law");

		ArgumentCaptor<StoredChunk> stored = ArgumentCaptor.forClass(StoredChunk.class);
		verify(chunkMapper).insertChunk(stored.capture());
		assertThat(stored.getValue().contentHash()).isEqualTo(sha256(stored.getValue().embeddingText()));
		assertThat(stored.getValue().parentSourcePath()).isEqualTo("$.law.articles");
	}
}
