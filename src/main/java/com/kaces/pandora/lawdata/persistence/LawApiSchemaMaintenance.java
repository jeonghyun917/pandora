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
	private static final String REPAIR_OPERATIONS_TABLE = "law_missing_embedding_repair_operations";
	private static final String REPAIR_ITEMS_TABLE = "law_missing_embedding_repair_items";

	private final JdbcTemplate jdbcTemplate;

	public LawApiSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		ensureAssetProxyUrlCanStoreLongUrls();
		ensureVersionedChunkMetadata();
		ensureMissingEmbeddingRepairOperationTables();
	}

	private void ensureMissingEmbeddingRepairOperationTables() {
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS law_missing_embedding_repair_operations (
				operation_id CHAR(36) NOT NULL,
				idempotency_key CHAR(64) NOT NULL,
				normalized_request LONGTEXT NOT NULL,
				request_hash CHAR(64) NOT NULL,
				target VARCHAR(20) NOT NULL,
				runtime_instance_id CHAR(36) NOT NULL,
				trusted_index_revision CHAR(64) NOT NULL,
				status VARCHAR(32) NOT NULL,
				candidate_count INT NOT NULL,
				document_count INT NOT NULL,
				indexed_count INT NOT NULL DEFAULT 0,
				failed_count INT NOT NULL DEFAULT 0,
				lease_owner CHAR(36) NULL,
				lease_expires_at DATETIME(6) NULL,
				last_error TEXT NULL,
				created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
				updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
				CONSTRAINT pk_law_missing_embedding_repair_operation PRIMARY KEY (operation_id),
				UNIQUE KEY uq_law_missing_embedding_repair_operation_idempotency (idempotency_key),
				KEY idx_law_missing_embedding_repair_operation_lease (status, lease_expires_at),
				CONSTRAINT chk_law_missing_embedding_repair_operation_target CHECK (target = 'law'),
				CONSTRAINT chk_law_missing_embedding_repair_operation_status CHECK (status IN ('READY','RUNNING','INDEXING_COMPLETE','FAILED')),
				CONSTRAINT chk_law_missing_embedding_repair_operation_counts CHECK (
					candidate_count > 0 AND candidate_count <= 1000 AND document_count > 0 AND document_count <= 50
					AND indexed_count >= 0 AND failed_count >= 0 AND indexed_count + failed_count <= candidate_count
				)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS law_missing_embedding_repair_items (
				operation_id CHAR(36) NOT NULL,
				ordinal INT NOT NULL,
				chunk_id BIGINT NOT NULL,
				document_id BIGINT NOT NULL,
				expected_content_hash CHAR(64) NOT NULL,
				state VARCHAR(32) NOT NULL,
				lease_owner CHAR(36) NULL,
				lease_expires_at DATETIME(6) NULL,
				before_index_revision CHAR(64) NULL,
				after_index_revision CHAR(64) NULL,
				detail TEXT NULL,
				created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
				updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
				PRIMARY KEY (operation_id, ordinal),
				UNIQUE KEY uq_law_missing_embedding_repair_item_chunk (operation_id, chunk_id),
				KEY idx_law_missing_embedding_repair_item_lease (operation_id, state, lease_expires_at),
				CONSTRAINT fk_law_missing_embedding_repair_item_operation
					FOREIGN KEY (operation_id) REFERENCES law_missing_embedding_repair_operations (operation_id) ON DELETE CASCADE,
				CONSTRAINT chk_law_missing_embedding_repair_item_state CHECK (state IN ('READY','PROCESSING','INDEXED','FAILED','NOT_ATTEMPTED')),
				CONSTRAINT chk_law_missing_embedding_repair_item_ordinal CHECK (ordinal >= 0),
				CONSTRAINT chk_law_missing_embedding_repair_item_hash CHECK (expected_content_hash REGEXP '^[0-9A-Fa-f]{64}$')
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		ensureRepairOperationSchema();
	}

	private void ensureRepairOperationSchema() {
		ensurePrimaryKey(REPAIR_OPERATIONS_TABLE, "operation_id");
		ensureTableIndex(REPAIR_OPERATIONS_TABLE, "idx_law_missing_embedding_repair_operation_lease", "status, lease_expires_at");
		ensureTableIndex(REPAIR_OPERATIONS_TABLE, "uq_law_missing_embedding_repair_operation_idempotency", "idempotency_key", true);
		ensureTableIndex(REPAIR_ITEMS_TABLE, "idx_law_missing_embedding_repair_item_lease", "operation_id, state, lease_expires_at");
		ensureTableIndex(REPAIR_ITEMS_TABLE, "uq_law_missing_embedding_repair_item_chunk", "operation_id, chunk_id", true);
		ensureCheckConstraint(REPAIR_OPERATIONS_TABLE, "chk_law_missing_embedding_repair_operation_target", "target = 'law'");
		ensureCheckConstraint(REPAIR_OPERATIONS_TABLE, "chk_law_missing_embedding_repair_operation_status", "status IN ('READY','RUNNING','INDEXING_COMPLETE','FAILED')");
		ensureCheckConstraint(REPAIR_OPERATIONS_TABLE, "chk_law_missing_embedding_repair_operation_counts",
			"candidate_count > 0 AND candidate_count <= 1000 AND document_count > 0 AND document_count <= 50 AND indexed_count >= 0 AND failed_count >= 0 AND indexed_count + failed_count <= candidate_count");
		ensureCheckConstraint(REPAIR_ITEMS_TABLE, "chk_law_missing_embedding_repair_item_state", "state IN ('READY','PROCESSING','INDEXED','FAILED','NOT_ATTEMPTED')");
		ensureCheckConstraint(REPAIR_ITEMS_TABLE, "chk_law_missing_embedding_repair_item_ordinal", "ordinal >= 0");
		ensureCheckConstraint(REPAIR_ITEMS_TABLE, "chk_law_missing_embedding_repair_item_hash", "expected_content_hash REGEXP '^[0-9A-Fa-f]{64}$'");
		ensureForeignKey(REPAIR_ITEMS_TABLE, "fk_law_missing_embedding_repair_item_operation",
			"FOREIGN KEY (operation_id) REFERENCES law_missing_embedding_repair_operations (operation_id) ON DELETE CASCADE");
	}

	private void ensureTableIndex(String tableName, String indexName, String columns) {
		ensureTableIndex(tableName, indexName, columns, false);
	}

	private void ensureTableIndex(String tableName, String indexName, String columns, boolean unique) {
		List<String> existingColumns = jdbcTemplate.queryForList("""
			SELECT COLUMN_NAME FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
			ORDER BY SEQ_IN_INDEX
			""", String.class, tableName, indexName);
		List<String> expectedColumns = List.of(columns.replace(" ", "").split(","));
		if (existingColumns.equals(expectedColumns)) {
			if (!unique) {
				return;
			}
			Integer nonUnique = jdbcTemplate.queryForObject("""
				SELECT MAX(NON_UNIQUE) FROM information_schema.STATISTICS
				WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND INDEX_NAME = ?
				""", Integer.class, tableName, indexName);
			if (nonUnique != null && nonUnique == 0) {
				return;
			}
		}
		if (!existingColumns.isEmpty()) {
			jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP INDEX " + indexName);
		}
		jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD " + (unique ? "UNIQUE " : "") + "INDEX " + indexName + " (" + columns + ")");
	}

	private void ensurePrimaryKey(String tableName, String column) {
		List<String> existingColumns = jdbcTemplate.queryForList("""
			SELECT COLUMN_NAME FROM information_schema.KEY_COLUMN_USAGE
			WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = 'PRIMARY'
			ORDER BY ORDINAL_POSITION
			""", String.class, tableName);
		if (existingColumns.equals(List.of(column))) {
			return;
		}
		if (!existingColumns.isEmpty()) {
			jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP PRIMARY KEY");
		}
		jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD PRIMARY KEY (" + column + ")");
	}

	private void ensureCheckConstraint(String tableName, String constraintName, String checkClause) {
		List<Map<String, Object>> constraints = jdbcTemplate.queryForList(String.format("""
			SELECT tc.CONSTRAINT_NAME, cc.CHECK_CLAUSE
			FROM information_schema.TABLE_CONSTRAINTS tc
			JOIN information_schema.CHECK_CONSTRAINTS cc
			  ON cc.CONSTRAINT_SCHEMA = tc.CONSTRAINT_SCHEMA AND cc.CONSTRAINT_NAME = tc.CONSTRAINT_NAME
			WHERE tc.CONSTRAINT_SCHEMA = DATABASE() AND tc.TABLE_NAME = '%s'
			  AND tc.CONSTRAINT_NAME = '%s' AND tc.CONSTRAINT_TYPE = 'CHECK'
			""", tableName, constraintName));
		if (constraints.size() == 1 && normalizeCheckClause(checkClause).equals(normalizeCheckClause(String.valueOf(constraints.get(0).get("CHECK_CLAUSE"))))) {
			return;
		}
		for (Map<String, Object> constraint : constraints) {
			jdbcTemplate.execute("ALTER TABLE " + tableName + " DROP CONSTRAINT " + constraint.get("CONSTRAINT_NAME"));
		}
		jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD CONSTRAINT " + constraintName + " CHECK (" + checkClause + ")");
	}

	private void ensureForeignKey(String tableName, String constraintName, String definition) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
			WHERE CONSTRAINT_SCHEMA = DATABASE() AND TABLE_NAME = ? AND CONSTRAINT_NAME = ? AND CONSTRAINT_TYPE = 'FOREIGN KEY'
			""", Integer.class, tableName, constraintName);
		if (count == null || count == 0) {
			jdbcTemplate.execute("ALTER TABLE " + tableName + " ADD CONSTRAINT " + constraintName + " " + definition);
		}
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
