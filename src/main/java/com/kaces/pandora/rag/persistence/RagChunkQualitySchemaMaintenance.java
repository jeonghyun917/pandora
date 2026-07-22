package com.kaces.pandora.rag.persistence;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 21)
public class RagChunkQualitySchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RagChunkQualitySchemaMaintenance.class);
	private static final String TABLE_NAME = "rag_document_chunks";
	private final JdbcTemplate jdbcTemplate;

	public RagChunkQualitySchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!tableExists()) {
			return;
		}
		ensureColumn(
			"quality_status",
			"VARCHAR(20) NOT NULL DEFAULT 'PASS' COMMENT 'PASS, REVIEW, CONTEXT_ONLY, REJECT'"
		);
		ensureColumn("quality_reason", "VARCHAR(100) NULL COMMENT 'chunk quality decision reason'");
		ensureIndex("idx_rag_document_chunks_quality", "quality_status, chunk_version, use_yn");
	}

	private boolean tableExists() {
		Integer count = jdbcTemplate.queryForObject(
			"""
			SELECT COUNT(*)
			FROM information_schema.TABLES
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			""",
			Integer.class,
			TABLE_NAME
		);
		return count != null && count > 0;
	}

	private void ensureColumn(String columnName, String definition) {
		Integer count = jdbcTemplate.queryForObject(
			"""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			  AND COLUMN_NAME = ?
			""",
			Integer.class,
			TABLE_NAME,
			columnName
		);
		if (count != null && count > 0) {
			return;
		}
		jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + columnName + " " + definition);
		log.info("Added missing RAG chunk quality column: {}", columnName);
	}

	private void ensureIndex(String indexName, String columns) {
		List<String> existing = jdbcTemplate.queryForList(
			"""
			SELECT INDEX_NAME
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			  AND INDEX_NAME = ?
			LIMIT 1
			""",
			String.class,
			TABLE_NAME,
			indexName
		);
		if (!existing.isEmpty()) {
			return;
		}
		jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " ADD INDEX " + indexName + " (" + columns + ")");
		log.info("Added missing RAG chunk quality index: {}", indexName);
	}
}
