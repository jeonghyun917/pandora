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
import java.util.Locale;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class LawApiSchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LawApiSchemaMaintenance.class);
	private static final String VERSION_ACTIVATION_CHECK = "activation_status IN ('CANDIDATE','ACTIVATING','ACTIVE_CLEANUP_PENDING','ACTIVE','RETIRED')";

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
				expected_chunk_count INT NOT NULL DEFAULT 0,
				preview_approved TINYINT(1) NOT NULL DEFAULT 0,
				unexplained_loss_span_count INT NOT NULL DEFAULT 0,
				preview_token_hash CHAR(64) NULL,
				activation_owner CHAR(36) NULL,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
				PRIMARY KEY (document_id, chunk_version),
				CONSTRAINT chk_law_chunk_versions_activation_status CHECK (activation_status IN ('CANDIDATE','ACTIVATING','ACTIVE_CLEANUP_PENDING','ACTIVE','RETIRED')),
				CONSTRAINT fk_law_api_document_chunk_versions_document
					FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id) ON DELETE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		ensureVersionTableColumn("expected_chunk_count", "INT NOT NULL DEFAULT 0");
		ensureVersionTableColumn("preview_approved", "TINYINT(1) NOT NULL DEFAULT 0");
		ensureVersionTableColumn("unexplained_loss_span_count", "INT NOT NULL DEFAULT 0");
		ensureVersionTableColumn("preview_token_hash", "CHAR(64) NULL");
		ensureVersionTableColumn("activation_owner", "CHAR(36) NULL");
		ensureVersionActivationStatusConstraint();
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS law_api_document_activation_operations (
				document_id BIGINT NOT NULL, candidate_version INT NOT NULL, owner_token CHAR(36) NOT NULL, runtime_instance_id CHAR(36) NOT NULL,
				lease_expires_at DATETIME NOT NULL, phase VARCHAR(40) NOT NULL, prior_active_version INT NOT NULL DEFAULT 0,
				prior_point_ids_json LONGTEXT NOT NULL, candidate_point_ids_json LONGTEXT NOT NULL, last_error TEXT NULL,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
				PRIMARY KEY (document_id), KEY idx_law_activation_operations_lease (lease_expires_at),
				CONSTRAINT fk_law_activation_operations_document FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id) ON DELETE CASCADE,
				CONSTRAINT chk_law_activation_operations_phase CHECK (phase IN ('PREPARING','QDRANT_ACTIVATING','RECOVERY_REQUIRED','DB_ACTIVE_CLEANUP_PENDING','DONE'))
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		ensureActivationOperationColumn("runtime_instance_id", "CHAR(36) NULL");
	}

	private void ensureVersionActivationStatusConstraint() {
		List<Map<String, Object>> constraints = jdbcTemplate.queryForList("""
			SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
			FROM information_schema.TABLE_CONSTRAINTS tc
			JOIN information_schema.CHECK_CONSTRAINTS cc
			  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
			WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = 'law_api_document_chunk_versions'
			  AND tc.CONSTRAINT_TYPE = 'CHECK' AND cc.CHECK_CLAUSE LIKE '%activation_status%'
			""");
		if (constraints.size() == 1 && isCanonicalVersionActivationCheck(constraints.get(0))) {
			return;
		}
		for (Map<String, Object> constraint : constraints) {
			jdbcTemplate.execute("ALTER TABLE law_api_document_chunk_versions DROP CONSTRAINT " + constraint.get("CONSTRAINT_NAME"));
		}
		jdbcTemplate.execute("ALTER TABLE law_api_document_chunk_versions ADD CONSTRAINT chk_law_chunk_versions_activation_status CHECK (" + VERSION_ACTIVATION_CHECK + ")");
	}

	private void ensureActivationOperationColumn(String columnName, String definition) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'law_api_document_activation_operations' AND COLUMN_NAME = ?
			""", Integer.class, columnName);
		if (count == null || count == 0) {
			jdbcTemplate.execute("ALTER TABLE law_api_document_activation_operations ADD COLUMN " + columnName + " " + definition);
		}
	}

	private boolean isCanonicalVersionActivationCheck(Map<String, Object> constraint) {
		return "chk_law_chunk_versions_activation_status".equals(constraint.get("CONSTRAINT_NAME"))
			&& normalizeCheckClause(VERSION_ACTIVATION_CHECK).equals(normalizeCheckClause(String.valueOf(constraint.get("CHECK_CLAUSE"))));
	}

	private String normalizeCheckClause(String clause) {
		return clause.replace("`", "").replace("(", "").replace(")", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
	}

	private void ensureVersionTableColumn(String columnName, String definition) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_document_chunk_versions'
			  AND COLUMN_NAME = ?
			""", Integer.class, columnName);
		if (count == null || count == 0) {
			jdbcTemplate.execute("ALTER TABLE law_api_document_chunk_versions ADD COLUMN " + columnName + " " + definition);
		}
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
