package com.kaces.pandora.semantic.provenance;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class IndexRevisionSchemaMaintenance implements ApplicationRunner {

	private static final List<TableRevisionHash> TABLES = List.of(
		new TableRevisionHash(
			"law_api_chunk_embeddings",
			"chk_law_api_chunk_embeddings_revision_hash"
		),
		new TableRevisionHash(
			"rag_chunk_embeddings",
			"chk_rag_chunk_embeddings_revision_hash"
		)
	);

	private final JdbcTemplate jdbcTemplate;

	public IndexRevisionSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		TABLES.forEach(this::ensureMaterializedRevisionHash);
	}

	private void ensureMaterializedRevisionHash(TableRevisionHash table) {
		if (!tableExists(table.tableName())) {
			return;
		}

		List<Map<String, Object>> columns = jdbcTemplate.queryForList("""
			SELECT COLUMN_TYPE, IS_NULLABLE
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = 'revision_hash'
			""", table.tableName());
		boolean columnAdded = columns.isEmpty();
		if (columnAdded) {
			jdbcTemplate.execute("ALTER TABLE " + table.tableName()
				+ " ADD COLUMN revision_hash CHAR(64) NULL AFTER content_hash");
		}
		else {
			assertCompatibleColumn(table.tableName(), columns);
		}

		List<Map<String, Object>> constraints = jdbcTemplate.queryForList("""
			SELECT tc.CONSTRAINT_NAME
			FROM information_schema.TABLE_CONSTRAINTS tc
			WHERE tc.CONSTRAINT_SCHEMA = DATABASE()
			  AND tc.TABLE_NAME = ?
			  AND tc.CONSTRAINT_NAME = ?
			  AND tc.CONSTRAINT_TYPE = 'CHECK'
			""", table.tableName(), table.constraintName());
		if (!columnAdded && !constraints.isEmpty()) {
			return;
		}

		jdbcTemplate.execute("""
			UPDATE %s
			SET revision_hash = CASE
				WHEN content_hash IS NULL THEN NULL
				ELSE SHA2(CONCAT(CAST(chunk_id AS CHAR), ':', content_hash), 256)
			END
			WHERE (content_hash IS NULL AND revision_hash IS NOT NULL)
			   OR (content_hash IS NOT NULL AND (
				 revision_hash IS NULL
				 OR revision_hash <> SHA2(CONCAT(CAST(chunk_id AS CHAR), ':', content_hash), 256)
			   ))
			""".formatted(table.tableName()));

		if (constraints.isEmpty()) {
			jdbcTemplate.execute("ALTER TABLE " + table.tableName()
				+ " ADD CONSTRAINT " + table.constraintName() + " CHECK ("
				+ "(content_hash IS NULL AND revision_hash IS NULL) OR "
				+ "(content_hash IS NOT NULL AND revision_hash = "
				+ "SHA2(CONCAT(CAST(chunk_id AS CHAR), ':', content_hash), 256)))");
		}
	}

	private boolean tableExists(String tableName) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.TABLES
			WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?
			""", Integer.class, tableName);
		return count != null && count == 1;
	}

	private void assertCompatibleColumn(String tableName, List<Map<String, Object>> columns) {
		if (columns.size() != 1) {
			throw new IllegalStateException("Ambiguous revision hash column metadata for " + tableName);
		}
		Map<String, Object> column = columns.get(0);
		String type = String.valueOf(column.get("COLUMN_TYPE")).toLowerCase(Locale.ROOT);
		String nullable = String.valueOf(column.get("IS_NULLABLE"));
		if (!"char(64)".equals(type) || !"YES".equalsIgnoreCase(nullable)) {
			throw new IllegalStateException("Incompatible revision hash column for " + tableName);
		}
	}

	private record TableRevisionHash(String tableName, String constraintName) {
	}
}
