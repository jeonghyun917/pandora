package com.kaces.pandora.lawdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class LawApiSchemaMaintenanceTests {

	@Test
	void runAddsMissingVersionedChunkColumnsAndIndexesWithoutTouchingExistingRows() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class)))
			.thenAnswer(invocation -> invocation.getArgument(0, String.class).contains("information_schema.TABLES") ? 1 : 0);
		when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());

		new LawApiSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(13)).execute(ddl.capture());
		assertThat(ddl.getAllValues())
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN chunk_schema_version INT NOT NULL DEFAULT 1"))
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN embedding_text LONGTEXT NULL"))
			.anySatisfy(sql -> assertThat(sql).contains("idx_law_api_document_chunks_active_version"))
			.anySatisfy(sql -> assertThat(sql).contains("idx_law_api_document_chunks_parent_child"))
			.anySatisfy(sql -> assertThat(sql).contains("CREATE TABLE IF NOT EXISTS law_api_document_chunk_versions")
				.contains("expected_chunk_count INT NOT NULL DEFAULT 0")
				.contains("preview_approved TINYINT(1) NOT NULL DEFAULT 0")
				.contains("unexplained_loss_span_count INT NOT NULL DEFAULT 0")
				.contains("chk_law_chunk_versions_activation_status")
				.contains("ACTIVATING", "ACTIVE_CLEANUP_PENDING"))
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN expected_chunk_count INT NOT NULL DEFAULT 0"))
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN preview_approved TINYINT(1) NOT NULL DEFAULT 0"))
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN unexplained_loss_span_count INT NOT NULL DEFAULT 0"));
		assertThat(ddl.getAllValues()).anySatisfy(sql -> assertThat(sql)
			.contains("ADD CONSTRAINT chk_law_chunk_versions_activation_status")
			.contains("'CANDIDATE','ACTIVATING','ACTIVE_CLEANUP_PENDING','ACTIVE','RETIRED'"));
	}

	@Test
	void runSkipsExistingVersionedChunkColumnsAndIndexes() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
		when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of("existing"));

		new LawApiSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(2)).execute(ddl.capture());
		assertThat(ddl.getAllValues())
			.anySatisfy(sql -> assertThat(sql).contains("CREATE TABLE IF NOT EXISTS law_api_document_chunk_versions"))
			.anySatisfy(sql -> assertThat(sql).contains("ADD CONSTRAINT chk_law_chunk_versions_activation_status")
				.contains("ACTIVATING", "ACTIVE_CLEANUP_PENDING"));
	}

	@Test
	void runDoesNotReplaceAnAlreadyCanonicalActivationStatusCheck() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), any(Object[].class))).thenReturn(1);
		when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of("chk_law_chunk_versions_activation_status"));
		when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of(Map.of(
			"CONSTRAINT_NAME", "chk_law_chunk_versions_activation_status",
			"CHECK_CLAUSE", "(activation_status IN ('CANDIDATE','ACTIVATING','ACTIVE_CLEANUP_PENDING','ACTIVE','RETIRED'))"
		)));

		new LawApiSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(1)).execute(ddl.capture());
		assertThat(ddl.getAllValues())
			.noneSatisfy(sql -> assertThat(sql).contains("DROP CONSTRAINT chk_law_chunk_versions_activation_status"))
			.noneSatisfy(sql -> assertThat(sql).contains("ADD CONSTRAINT chk_law_chunk_versions_activation_status"));
	}
}
