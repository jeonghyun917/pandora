package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.eq;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class SemanticLexicalSchemaMaintenanceTests {

	@Test
	void keepsTermIdentityBinaryForBothNewAndExistingTables() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(),
			eq(String.class), org.mockito.ArgumentMatchers.anyString()))
			.thenReturn("utf8mb4_unicode_ci");
		SemanticLexicalSchemaMaintenance maintenance = new SemanticLexicalSchemaMaintenance(jdbcTemplate);

		maintenance.run(null);

		ArgumentCaptor<String> statements = ArgumentCaptor.forClass(String.class);
		verify(jdbcTemplate, org.mockito.Mockito.atLeast(6)).execute(statements.capture());
		List<String> sql = statements.getAllValues();
		assertThat(sql).anySatisfy(statement -> assertThat(statement)
			.contains("CREATE TABLE IF NOT EXISTS semantic_lexical_terms")
			.contains("term VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"));
		assertThat(sql).anySatisfy(statement -> assertThat(statement)
			.contains("CREATE TABLE IF NOT EXISTS semantic_lexical_term_stats")
			.contains("term VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"));
		assertThat(sql).contains(
			"ALTER TABLE semantic_lexical_terms MODIFY term VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL",
			"ALTER TABLE semantic_lexical_term_stats MODIFY term VARCHAR(80) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL"
		);
	}

	@Test
	void doesNotAlterLargeTermTablesAgainWhenBinaryIdentityIsAlreadyInstalled() throws Exception {
		JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
		when(jdbcTemplate.queryForObject(org.mockito.ArgumentMatchers.anyString(),
			eq(String.class), org.mockito.ArgumentMatchers.anyString()))
			.thenReturn("utf8mb4_bin");

		new SemanticLexicalSchemaMaintenance(jdbcTemplate).run(null);

		verify(jdbcTemplate, never()).execute(org.mockito.ArgumentMatchers.startsWith("ALTER TABLE"));
	}
}
