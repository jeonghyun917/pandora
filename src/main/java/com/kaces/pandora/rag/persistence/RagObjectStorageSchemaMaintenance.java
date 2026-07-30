package com.kaces.pandora.rag.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 22)
public class RagObjectStorageSchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RagObjectStorageSchemaMaintenance.class);
	private static final String TABLE_NAME = "rag_documents";
	private final JdbcTemplate jdbcTemplate;

	public RagObjectStorageSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!tableExists() || columnExists("object_key")) {
			return;
		}
		jdbcTemplate.execute(
			"ALTER TABLE " + TABLE_NAME
				+ " ADD COLUMN object_key VARCHAR(1000) NULL COMMENT 'private object storage key for original file'"
		);
		log.info("Added missing RAG original document object storage key column");
	}

	private boolean tableExists() {
		return count(
			"""
			SELECT COUNT(*)
			FROM information_schema.TABLES
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			""",
			TABLE_NAME
		) > 0;
	}

	private boolean columnExists(String columnName) {
		return count(
			"""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			  AND COLUMN_NAME = ?
			""",
			TABLE_NAME,
			columnName
		) > 0;
	}

	private int count(String sql, Object... arguments) {
		Integer count = jdbcTemplate.queryForObject(sql, Integer.class, arguments);
		return count == null ? 0 : count;
	}
}
