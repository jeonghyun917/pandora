package com.kaces.pandora.rag.persistence;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.jdbc.core.JdbcTemplate;

class RagObjectStorageSchemaMaintenanceTests {

	@Test
	void addsObjectKeyOnlyWhenTheExistingRagDocumentTableIsMissingTheColumn() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(contains("information_schema.TABLES"), eq(Integer.class), eq("rag_documents")))
			.thenReturn(1);
		when(jdbcTemplate.queryForObject(contains("information_schema.COLUMNS"), eq(Integer.class), eq("rag_documents"), eq("object_key")))
			.thenReturn(0);

		new RagObjectStorageSchemaMaintenance(jdbcTemplate).run(mock(ApplicationArguments.class));

		verify(jdbcTemplate).execute(
			"ALTER TABLE rag_documents ADD COLUMN object_key VARCHAR(1000) NULL "
				+ "COMMENT 'private object storage key for original file'"
		);
	}

	@Test
	void leavesCurrentSchemaUntouchedWhenObjectKeyAlreadyExists() {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(contains("information_schema.TABLES"), eq(Integer.class), eq("rag_documents")))
			.thenReturn(1);
		when(jdbcTemplate.queryForObject(contains("information_schema.COLUMNS"), eq(Integer.class), eq("rag_documents"), eq("object_key")))
			.thenReturn(1);

		new RagObjectStorageSchemaMaintenance(jdbcTemplate).run(mock(ApplicationArguments.class));

		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.anyString());
	}
}
