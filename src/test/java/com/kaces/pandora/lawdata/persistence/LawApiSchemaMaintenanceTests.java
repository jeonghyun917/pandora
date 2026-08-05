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
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN unexplained_loss_span_count INT NOT NULL DEFAULT 0"))
			.anySatisfy(sql -> assertThat(sql).contains("CREATE TABLE IF NOT EXISTS law_api_document_activation_operations")
				.contains("runtime_instance_id CHAR(36) NOT NULL"))
			.anySatisfy(sql -> assertThat(sql).contains("CREATE TABLE IF NOT EXISTS law_missing_embedding_repair_operations")
				.contains("idempotency_key CHAR(64) NOT NULL")
				.contains("CONSTRAINT pk_law_missing_embedding_repair_operation PRIMARY KEY (operation_id)")
				.contains("UNIQUE KEY uq_law_missing_embedding_repair_operation_idempotency (idempotency_key)")
				.contains("runtime_instance_id CHAR(36) NOT NULL")
				.contains("trusted_index_revision CHAR(64) NOT NULL")
				.contains("chk_law_missing_embedding_repair_operation_status")
				.contains("candidate_count <= 1000", "document_count <= 50", "indexed_count + failed_count <= candidate_count")
				.contains("READY","RUNNING","INDEXING_COMPLETE","FAILED"))
			.anySatisfy(sql -> assertThat(sql).contains("CREATE TABLE IF NOT EXISTS law_missing_embedding_repair_items")
				.contains("PRIMARY KEY (operation_id, ordinal)")
				.contains("UNIQUE KEY uq_law_missing_embedding_repair_item_chunk (operation_id, chunk_id)")
				.contains("FOREIGN KEY (operation_id) REFERENCES law_missing_embedding_repair_operations (operation_id) ON DELETE CASCADE")
				.contains("chk_law_missing_embedding_repair_item_state")
				.contains("READY","PROCESSING","INDEXED","FAILED","NOT_ATTEMPTED"))
			.anySatisfy(sql -> assertThat(sql).contains("ADD COLUMN runtime_instance_id CHAR(36) NULL"));
		assertThat(ddl.getAllValues()).anySatisfy(sql -> assertThat(sql)
			.contains("ADD CONSTRAINT chk_law_chunk_versions_activation_status")
			.contains("'CANDIDATE','ACTIVATING','ACTIVE_CLEANUP_PENDING','ACTIVE','RETIRED'"));
	}

	@Test
	void runRepairsMissingDurableOperationIndexesConstraintsAndForeignKey() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
			String sql = invocation.getArgument(0, String.class);
			return sql.contains("information_schema.TABLES") ? 0 : 0;
		});
		when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class))).thenReturn(List.of());
		when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

		new LawApiSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(12)).execute(ddl.capture());
		assertThat(ddl.getAllValues())
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_operations ADD PRIMARY KEY (operation_id)"))
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_operations ADD INDEX idx_law_missing_embedding_repair_operation_lease"))
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_operations ADD UNIQUE INDEX uq_law_missing_embedding_repair_operation_idempotency"))
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_items ADD UNIQUE INDEX uq_law_missing_embedding_repair_item_chunk"))
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_operations ADD CONSTRAINT chk_law_missing_embedding_repair_operation_counts"))
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_items ADD CONSTRAINT fk_law_missing_embedding_repair_item_operation"));
	}

	@Test
	void runRebuildsSameNamedNonUniqueIdempotencyIndex() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenAnswer(invocation -> {
			String sql = invocation.getArgument(0, String.class);
			return sql.contains("MAX(NON_UNIQUE)") ? 1 : 1;
		});
		when(jdbcTemplate.queryForList(anyString(), eq(String.class), any(Object[].class))).thenAnswer(invocation -> {
			return java.util.Arrays.stream(invocation.getArguments())
				.anyMatch("uq_law_missing_embedding_repair_operation_idempotency"::equals)
				? List.of("idempotency_key") : List.of();
		});
		when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

		new LawApiSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(1)).execute(ddl.capture());
		assertThat(ddl.getAllValues())
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_operations DROP INDEX uq_law_missing_embedding_repair_operation_idempotency"))
			.anySatisfy(sql -> assertThat(sql).contains("ALTER TABLE law_missing_embedding_repair_operations ADD UNIQUE INDEX uq_law_missing_embedding_repair_operation_idempotency"));
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
		when(jdbcTemplate.queryForList(anyString())).thenAnswer(invocation -> {
			String sql = invocation.getArgument(0, String.class);
			if (!sql.contains("law_api_document_chunk_versions")) {
				return List.of();
			}
			return List.of(Map.of(
				"CONSTRAINT_NAME", "chk_law_chunk_versions_activation_status",
				"CHECK_CLAUSE", "(activation_status IN ('CANDIDATE','ACTIVATING','ACTIVE_CLEANUP_PENDING','ACTIVE','RETIRED'))"
			));
		});

		new LawApiSchemaMaintenance(jdbcTemplate).run(new DefaultApplicationArguments());

		ArgumentCaptor<String> ddl = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(1)).execute(ddl.capture());
		assertThat(ddl.getAllValues())
			.noneSatisfy(sql -> assertThat(sql).contains("DROP CONSTRAINT chk_law_chunk_versions_activation_status"))
			.noneSatisfy(sql -> assertThat(sql).contains("ADD CONSTRAINT chk_law_chunk_versions_activation_status"));
	}
}
