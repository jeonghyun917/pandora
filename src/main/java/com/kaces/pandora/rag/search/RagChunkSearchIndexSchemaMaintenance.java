package com.kaces.pandora.rag.search;

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
public class RagChunkSearchIndexSchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(RagChunkSearchIndexSchemaMaintenance.class);
	private final JdbcTemplate jdbcTemplate;

	public RagChunkSearchIndexSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!tableExists("rag_documents") || !tableExists("rag_document_chunks")) {
			return;
		}
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS rag_chunk_search_terms (
				term VARCHAR(80) NOT NULL,
				chunk_id BIGINT NOT NULL,
				document_id BIGINT NOT NULL,
				field_kind VARCHAR(30) NOT NULL,
				weight SMALLINT NOT NULL DEFAULT 1,
				created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (term, chunk_id),
				KEY idx_rag_chunk_search_terms_chunk (chunk_id),
				KEY idx_rag_chunk_search_terms_document (document_id, chunk_id),
				CONSTRAINT fk_rag_chunk_search_terms_chunk
					FOREIGN KEY (chunk_id) REFERENCES rag_document_chunks (chunk_id) ON DELETE CASCADE,
				CONSTRAINT fk_rag_chunk_search_terms_document
					FOREIGN KEY (document_id) REFERENCES rag_documents (document_id) ON DELETE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS rag_chunk_search_index_state (
				chunk_id BIGINT NOT NULL,
				document_id BIGINT NOT NULL,
				index_version INT NOT NULL DEFAULT 2,
				content_hash CHAR(64) NULL,
				term_count INT NOT NULL DEFAULT 0,
				completed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
				PRIMARY KEY (chunk_id),
				KEY idx_rag_chunk_search_index_state_document (document_id, chunk_id),
				CONSTRAINT fk_rag_chunk_search_index_state_chunk
					FOREIGN KEY (chunk_id) REFERENCES rag_document_chunks (chunk_id) ON DELETE CASCADE,
				CONSTRAINT fk_rag_chunk_search_index_state_document
					FOREIGN KEY (document_id) REFERENCES rag_documents (document_id) ON DELETE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		jdbcTemplate.execute("""
			CREATE TABLE IF NOT EXISTS rag_search_index_control (
				index_name VARCHAR(60) NOT NULL,
				index_version INT NOT NULL DEFAULT 2,
				status VARCHAR(20) NOT NULL DEFAULT 'BUILDING',
				updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
				PRIMARY KEY (index_name)
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""");
		jdbcTemplate.update("""
			INSERT INTO rag_search_index_control (index_name, index_version, status)
			VALUES ('rag_chunk_terms', 2, 'BUILDING')
			ON DUPLICATE KEY UPDATE
				status = IF(index_version = VALUES(index_version), status, 'BUILDING'),
				index_version = VALUES(index_version)
			""");
		log.info("RAG chunk lexical index schema is ready.");
	}

	private boolean tableExists(String tableName) {
		Integer count = jdbcTemplate.queryForObject(
			"""
			SELECT COUNT(*)
			FROM information_schema.TABLES
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			""",
			Integer.class,
			tableName
		);
		return count != null && count > 0;
	}
}
