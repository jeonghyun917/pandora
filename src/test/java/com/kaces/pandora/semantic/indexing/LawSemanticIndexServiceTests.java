package com.kaces.pandora.semantic.indexing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import org.junit.jupiter.api.Test;

class LawSemanticIndexServiceTests {

	@Test
	void indexCandidateRejectsNonPositiveDocumentOrVersionWithoutIndexing() {
		LawSemanticIndexService service = new LawSemanticIndexService(
			mock(LawChunkMapper.class), new LawAiProperties(null, null, null, null),
			mock(OpenAiEmbeddingClient.class), mock(QdrantClient.class), mock(LawJsonWriter.class)
		);

		assertThat(service.indexCandidate("law", 0L, 2, 10).indexed()).isZero();
		assertThat(service.indexCandidate("law", 42L, 0, 10).requested()).isZero();
	}
}
