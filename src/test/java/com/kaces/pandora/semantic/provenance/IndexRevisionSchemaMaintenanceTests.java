package com.kaces.pandora.semantic.provenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class IndexRevisionSchemaMaintenanceTests {

	@Test
	void addsBackfillsAndConstrainsMaterializedRevisionHashes() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
			.thenReturn(1);
		when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
			.thenReturn(List.of());

		new IndexRevisionSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(6)).execute(sql.capture());
		assertThat(sql.getAllValues())
			.anySatisfy(value -> assertThat(value)
				.contains("ALTER TABLE law_api_chunk_embeddings ADD COLUMN revision_hash CHAR(64) NULL"))
			.anySatisfy(value -> assertThat(value)
				.contains("UPDATE law_api_chunk_embeddings")
				.contains("ELSE SHA2(CONCAT(CAST(chunk_id AS CHAR), ':', content_hash), 256)"))
			.anySatisfy(value -> assertThat(value)
				.contains("ADD CONSTRAINT chk_law_api_chunk_embeddings_revision_hash")
				.contains("revision_hash = SHA2(CONCAT(CAST(chunk_id AS CHAR), ':', content_hash), 256)"))
			.anySatisfy(value -> assertThat(value)
				.contains("ALTER TABLE rag_chunk_embeddings ADD COLUMN revision_hash CHAR(64) NULL"))
			.anySatisfy(value -> assertThat(value)
				.contains("UPDATE rag_chunk_embeddings")
				.contains("ELSE SHA2(CONCAT(CAST(chunk_id AS CHAR), ':', content_hash), 256)"))
			.anySatisfy(value -> assertThat(value)
				.contains("ADD CONSTRAINT chk_rag_chunk_embeddings_revision_hash"));
	}
}
