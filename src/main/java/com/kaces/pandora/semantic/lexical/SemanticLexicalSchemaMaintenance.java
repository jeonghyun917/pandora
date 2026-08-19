package com.kaces.pandora.semantic.lexical;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class SemanticLexicalSchemaMaintenance implements ApplicationRunner {

	private final JdbcTemplate jdbcTemplate;

	public SemanticLexicalSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		jdbcTemplate.execute(indexStateTable());
		jdbcTemplate.execute(chunkTable());
		jdbcTemplate.execute(termTable());
		jdbcTemplate.execute(termStatsTable());
	}

	private String indexStateTable() {
		return """
			CREATE TABLE IF NOT EXISTS semantic_lexical_index_state (
				index_version VARCHAR(64) NOT NULL,
				tokenizer_version VARCHAR(64) NOT NULL,
				active_chunk_count INT NOT NULL DEFAULT 0,
				average_weighted_length DOUBLE NOT NULL DEFAULT 0,
				content_fingerprint CHAR(64) NULL,
				status VARCHAR(20) NOT NULL,
				completed_at DATETIME(6) NULL,
				created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
				PRIMARY KEY (index_version),
				KEY idx_semantic_lexical_state_ready (status, completed_at),
				CONSTRAINT chk_semantic_lexical_state_status CHECK (status IN ('BUILDING','READY','FAILED'))
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""";
	}

	private String chunkTable() {
		return """
			CREATE TABLE IF NOT EXISTS semantic_lexical_chunks (
				index_version VARCHAR(64) NOT NULL,
				target VARCHAR(30) NOT NULL,
				chunk_id BIGINT NOT NULL,
				document_id BIGINT NOT NULL,
				parent_key VARCHAR(100) NULL,
				content_hash CHAR(64) NULL,
				weighted_length INT NOT NULL,
				build_status VARCHAR(20) NOT NULL,
				completed_at DATETIME(6) NULL,
				PRIMARY KEY (index_version, target, chunk_id),
				KEY idx_semantic_lexical_chunks_target (index_version, target, chunk_id),
				CONSTRAINT fk_semantic_lexical_chunks_state FOREIGN KEY (index_version)
					REFERENCES semantic_lexical_index_state (index_version) ON DELETE CASCADE,
				CONSTRAINT chk_semantic_lexical_chunks_status CHECK (build_status IN ('BUILDING','READY'))
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""";
	}

	private String termTable() {
		return """
			CREATE TABLE IF NOT EXISTS semantic_lexical_terms (
				index_version VARCHAR(64) NOT NULL,
				target VARCHAR(30) NOT NULL,
				chunk_id BIGINT NOT NULL,
				term VARCHAR(80) NOT NULL,
				field_kind VARCHAR(30) NOT NULL,
				term_frequency INT NOT NULL,
				field_weight SMALLINT NOT NULL,
				PRIMARY KEY (index_version, target, chunk_id, term, field_kind),
				KEY idx_semantic_lexical_terms_lookup (index_version, term, target, chunk_id),
				CONSTRAINT fk_semantic_lexical_terms_chunk FOREIGN KEY (index_version, target, chunk_id)
					REFERENCES semantic_lexical_chunks (index_version, target, chunk_id) ON DELETE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""";
	}

	private String termStatsTable() {
		return """
			CREATE TABLE IF NOT EXISTS semantic_lexical_term_stats (
				index_version VARCHAR(64) NOT NULL,
				term VARCHAR(80) NOT NULL,
				document_frequency INT NOT NULL,
				PRIMARY KEY (index_version, term),
				CONSTRAINT fk_semantic_lexical_term_stats_state FOREIGN KEY (index_version)
					REFERENCES semantic_lexical_index_state (index_version) ON DELETE CASCADE
			) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
			""";
	}
}
