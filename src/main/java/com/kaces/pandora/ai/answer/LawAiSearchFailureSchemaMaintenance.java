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
		ensureColumn("failure_type", "VARCHAR(50) NOT NULL DEFAULT 'PIPELINE_RESULT_INCONSISTENT' COMMENT 'classified failure type'");
		ensureColumn("failure_stage", "VARCHAR(50) NOT NULL DEFAULT 'PIPELINE' COMMENT 'pipeline stage where failure happened'");
		ensureColumn("retryable", "TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether search logic/data can improve this failure'");
		ensureColumn("eval_candidate", "TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether this failure should become an evaluation candidate'");
		ensureColumn("review_status", "VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT 'review workflow status'");
		ensureColumn("promoted_eval_case_id", "VARCHAR(120) NULL COMMENT 'evaluation case id when promoted'");
		ensureColumn("topic_aligned_count", "INT NOT NULL DEFAULT 0 COMMENT 'topic-aligned evidence count'");
		ensureColumn("relevant_count", "INT NOT NULL DEFAULT 0 COMMENT 'relevant evidence count'");
		ensureColumn("direct_evidence_count", "INT NOT NULL DEFAULT 0 COMMENT 'direct evidence count'");
		ensureColumn("evidence_selection_policy", "VARCHAR(50) NOT NULL DEFAULT 'empty' COMMENT 'evidence selection policy'");
		ensureColumn("document_scope_mismatch", "TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'selected targets excluded preferred targets'");
		ensureDefault(
			"failure_type",
			"PIPELINE_RESULT_INCONSISTENT",
			"VARCHAR(50) NOT NULL DEFAULT 'PIPELINE_RESULT_INCONSISTENT' COMMENT 'classified failure type'"
		);
		ensureDefault(
			"failure_stage",
			"PIPELINE",
			"VARCHAR(50) NOT NULL DEFAULT 'PIPELINE' COMMENT 'pipeline stage where failure happened'"
		);
		backfillLegacyClassifications();
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

	private void ensureDefault(String columnName, String expectedDefault, String definition) {
		String currentDefault = jdbcTemplate.queryForObject(
			"""
			SELECT COLUMN_DEFAULT
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = ?
			  AND COLUMN_NAME = ?
			""",
			String.class,
			TABLE_NAME,
			columnName
		);
		if (expectedDefault.equals(currentDefault)) {
			return;
		}
		jdbcTemplate.execute("ALTER TABLE " + TABLE_NAME + " MODIFY COLUMN " + columnName + " " + definition);
		log.info("Updated AI search failure log default: {}={}", columnName, expectedDefault);
	}

	private void backfillLegacyClassifications() {
		int updated = jdbcTemplate.update("""
			UPDATE law_ai_search_failure_logs
			SET failure_type = CASE
			      WHEN diagnostic_message LIKE '%운영 내부 상태%' THEN 'UNSUPPORTED_OPERATIONAL_STATUS'
			      WHEN diagnostic_message LIKE '%근거를 만들어내%'
			        OR diagnostic_message LIKE '%문서를 있다고 말%' THEN 'UNSUPPORTED_FABRICATION_REQUEST'
			      WHEN document_scope_mismatch = 1 THEN 'DOCUMENT_SCOPE_MISMATCH'
			      WHEN qdrant_hit_count > 0 AND vector_chunk_count = 0 AND lexical_chunk_count = 0 THEN 'INDEX_DB_MAPPING_MISMATCH'
			      WHEN merged_count = 0 OR (qdrant_hit_count = 0 AND lexical_chunk_count = 0) THEN 'SEARCH_NO_CANDIDATE'
			      WHEN ranked_count = 0 THEN 'RANKING_DROPPED_ALL'
			      WHEN intent_filtered_count = 0 THEN 'DICTIONARY_OR_INTENT_GAP'
			      WHEN judge_candidate_count = 0 THEN 'JUDGE_CANDIDATE_EMPTY'
			      WHEN judged_count = 0 AND direct_evidence_count = 0 THEN 'JUDGE_NO_DIRECT_EVIDENCE'
			      WHEN judged_count = 0 THEN 'JUDGE_REJECTED_ALL'
			      WHEN final_ground_count = 0 AND judged_count > 0 THEN 'CHUNK_QUALITY_REJECTED'
			      WHEN result_msg = 'NO_GROUNDS' THEN 'EVIDENCE_SELECTION_REJECTED'
			      ELSE 'PIPELINE_RESULT_INCONSISTENT'
			    END,
			    failure_stage = CASE
			      WHEN diagnostic_message LIKE '%운영 내부 상태%'
			        OR diagnostic_message LIKE '%근거를 만들어내%'
			        OR diagnostic_message LIKE '%문서를 있다고 말%' THEN 'PRECHECK'
			      WHEN document_scope_mismatch = 1 THEN 'TARGET_SCOPE'
			      WHEN qdrant_hit_count > 0 AND vector_chunk_count = 0 AND lexical_chunk_count = 0 THEN 'DB_LOOKUP'
			      WHEN merged_count = 0 OR (qdrant_hit_count = 0 AND lexical_chunk_count = 0) THEN 'RETRIEVAL'
			      WHEN ranked_count = 0 THEN 'RERANK'
			      WHEN intent_filtered_count = 0 THEN 'INTENT_FILTER'
			      WHEN judge_candidate_count = 0 OR judged_count = 0 THEN 'EVIDENCE_JUDGE'
			      WHEN final_ground_count = 0 AND judged_count > 0 THEN 'GROUND_BUILD'
			      WHEN result_msg = 'NO_GROUNDS' THEN 'EVIDENCE_SELECTION'
			      ELSE 'PIPELINE'
			    END,
			    retryable = CASE
			      WHEN diagnostic_message LIKE '%운영 내부 상태%'
			        OR diagnostic_message LIKE '%근거를 만들어내%'
			        OR diagnostic_message LIKE '%문서를 있다고 말%'
			        OR document_scope_mismatch = 1
			        OR (qdrant_hit_count > 0 AND vector_chunk_count = 0 AND lexical_chunk_count = 0)
			        THEN 0
			      ELSE 1
			    END,
			    eval_candidate = CASE
			      WHEN diagnostic_message LIKE '%운영 내부 상태%'
			        OR diagnostic_message LIKE '%근거를 만들어내%'
			        OR diagnostic_message LIKE '%문서를 있다고 말%'
			        OR document_scope_mismatch = 1
			        OR (qdrant_hit_count > 0 AND vector_chunk_count = 0 AND lexical_chunk_count = 0)
			        THEN 0
			      ELSE 1
			    END
			WHERE failure_type IS NULL
			   OR TRIM(failure_type) = ''
			   OR UPPER(failure_type) = 'UNKNOWN'
			   OR failure_type = 'NO_GROUNDS_UNCLASSIFIED'
			   OR failure_type = 'GROUND_TEXT_FILTERED'
			   OR failure_stage IS NULL
			   OR TRIM(failure_stage) = ''
			   OR UPPER(failure_stage) = 'UNKNOWN'
			""");
		if (updated > 0) {
			log.info("Reclassified {} legacy AI search failure logs", updated);
		}
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
