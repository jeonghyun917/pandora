package com.kaces.pandora.ai.answer;

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
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class LawAiSearchFailureSchemaMaintenance implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LawAiSearchFailureSchemaMaintenance.class);
	private static final String TABLE_NAME = "law_ai_search_failure_logs";

	private final JdbcTemplate jdbcTemplate;

	public LawAiSearchFailureSchemaMaintenance(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!tableExists()) {
			return;
		}
		ensureColumn("failure_type", "VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'classified failure type'");
		ensureColumn("failure_stage", "VARCHAR(50) NOT NULL DEFAULT 'UNKNOWN' COMMENT 'pipeline stage where failure happened'");
		ensureColumn("retryable", "TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether search logic/data can improve this failure'");
		ensureColumn("eval_candidate", "TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether this failure should become an evaluation candidate'");
		ensureColumn("review_status", "VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT 'review workflow status'");
		ensureColumn("promoted_eval_case_id", "VARCHAR(120) NULL COMMENT 'evaluation case id when promoted'");
		ensureIndex("idx_law_ai_search_failure_type", "failure_type, created_at");
		ensureIndex("idx_law_ai_search_failure_review", "review_status, eval_candidate, created_at");
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
		log.info("Added missing AI search failure log column: {}", columnName);
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
		log.info("Added missing AI search failure log index: {}", indexName);
	}
}
