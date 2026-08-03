package com.kaces.pandora.lawdata.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LawApiSchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LawApiSchemaMaintenance.class);

	private final JdbcTemplate jdbcTemplate;

	public LawApiSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAssetProxyUrlCanStoreLongUrls();
		ensureVersionedChunkMetadata();
	}

	private void ensureVersionedChunkMetadata() {
		if (!tableExists("law_api_document_chunks")) {
			return;
		}
		ensureColumn("chunk_schema_version", "INT NOT NULL DEFAULT 1");
		ensureColumn("chunk_version", "INT NOT NULL DEFAULT 1");
		ensureColumn("activation_status", "VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'");
		ensureColumn("parent_key", "CHAR(64) NULL");
		ensureColumn("parent_title", "VARCHAR(500) NULL");
		ensureColumn("parent_source_path", "VARCHAR(500) NULL");
		ensureColumn("child_order", "INT NOT NULL DEFAULT 0");
		ensureColumn("embedding_text", "LONGTEXT NULL");
		ensureColumn("quality_status", "VARCHAR(20) NOT NULL DEFAULT 'PASS'");
		ensureColumn("quality_reason", "VARCHAR(100) NULL");
		ensureIndex("idx_law_api_document_chunks_active_version", "document_id, activation_status, chunk_version, sort_order");
		ensureIndex("idx_law_api_document_chunks_parent_child", "document_id, parent_key, child_order");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS law_api_document_chunk_versions (
				document_id BIGINT NOT NULL,
				chunk_version INT NOT NULL,
				activation_status VARCHAR(20) NOT NULL DEFAULT 'CANDIDATE',
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
				PRIMARY KEY (document_id, chunk_version),
				CONSTRAINT fk_law_api_document_chunk_versions_document
					FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id) ON DELETE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
	}

	private boolean tableExists(String tableName) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.TABLES
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			""", Integer.class, tableName);
		return count != null && count > 0;
	}

	private void ensureColumn(String columnName, String definition) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_document_chunks'
			  AND COLUMN_NAME = ?
			""", Integer.class, columnName);
		if (count == null || count == 0) {
			jdbcTemplate.execute("ALTER TABLE law_api_document_chunks ADD COLUMN " + columnName + " " + definition);
		}
	}

	private void ensureIndex(String indexName, String columns) {
		List<String> indexes = jdbcTemplate.queryForList("""
			SELECT INDEX_NAME
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_document_chunks'
			  AND INDEX_NAME = ?
			LIMIT 1
			""", String.class, indexName);
		if (indexes.isEmpty()) {
			jdbcTemplate.execute("ALTER TABLE law_api_document_chunks ADD INDEX " + indexName + " (" + columns + ")");
		}
	}

	private void ensureAssetProxyUrlCanStoreLongUrls() {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_assets'
			  AND COLUMN_NAME = 'proxy_url'
			  AND DATA_TYPE IN ('text', 'mediumtext', 'longtext')
			""", Integer.class);
		if (count != null && count > 0) {
			return;
		}
		jdbcTemplate.execute("ALTER TABLE law_api_assets MODIFY proxy_url TEXT NULL");
		log.info("Updated law_api_assets.proxy_url to TEXT for long proxied asset URLs.");
	}
}
