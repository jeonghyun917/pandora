package com.kaces.pandora.lawdata.version;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Component
public class LawVersionStatusService implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(LawVersionStatusService.class);
	private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;
	private final boolean startupRefreshEnabled;
	private volatile boolean schemaChecked;

	public LawVersionStatusService(
		JdbcTemplate jdbcTemplate,
		@Value("${law.version-status.startup-refresh-enabled:true}") boolean startupRefreshEnabled
	) {
		this.jdbcTemplate = jdbcTemplate;
		this.clock = Clock.system(ZoneId.of("Asia/Seoul"));
		this.startupRefreshEnabled = startupRefreshEnabled;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!startupRefreshEnabled) {
			log.info("Skipping startup law version status refresh.");
			return;
		}
		refreshAllStatuses();
	}

	public void ensureSchema() {
		if (schemaChecked) {
			return;
		}
		addColumnIfMissing("canonical_key", "VARCHAR(600) NULL COMMENT '동일 법령/행정규칙 버전 묶음 식별자'");
		addColumnIfMissing("effective_date", "VARCHAR(8) NULL COMMENT '검색 기준 시행/발령일자(yyyyMMdd)'");
		addColumnIfMissing("effective_status", "VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' COMMENT '버전 상태(CURRENT, FUTURE, PAST, UNKNOWN)'");
		addIndexIfMissing(
			"idx_law_api_documents_effective_status",
			"CREATE INDEX idx_law_api_documents_effective_status ON law_api_documents (target, canonical_key, effective_status, effective_date)"
		);
		schemaChecked = true;
	}

	@Transactional
	public void refreshAllStatuses() {
		ensureSchema();
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"""
			SELECT document_id, target, title, source_date
			FROM law_api_documents
			WHERE canonical_key IS NULL
			   OR canonical_key = ''
			   OR effective_status IS NULL
			   OR effective_status NOT IN ('CURRENT', 'FUTURE', 'PAST', 'UNKNOWN')
			   OR (source_date IS NOT NULL AND source_date <> '' AND effective_date IS NULL)
			"""
		);
		for (Map<String, Object> row : rows) {
			updateDerivedColumns(row);
		}
		refreshAllVersionGroups();
	}

	@Transactional
	public void refreshGroup(String target, String canonicalKey) {
		if (!LawVersionUtils.isVersionedTarget(target) || !StringUtils.hasText(canonicalKey)) {
			return;
		}
		ensureSchema();
		List<Map<String, Object>> rows = jdbcTemplate.queryForList(
			"SELECT document_id, target, title, source_date FROM law_api_documents WHERE target = ? AND canonical_key = ?",
			target,
			canonicalKey
		);
		for (Map<String, Object> row : rows) {
			updateDerivedColumns(row);
		}
		refreshVersionGroup(target, canonicalKey);
	}

	private void addColumnIfMissing(String columnName, String definition) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.COLUMNS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_documents'
			  AND COLUMN_NAME = ?
			""", Integer.class, columnName);
		if (count != null && count > 0) {
			return;
		}
		jdbcTemplate.execute("ALTER TABLE law_api_documents ADD COLUMN " + columnName + " " + definition);
		log.info("Added law_api_documents.{} for version-aware search.", columnName);
	}

	private void addIndexIfMissing(String indexName, String ddl) {
		Integer count = jdbcTemplate.queryForObject("""
			SELECT COUNT(*)
			FROM information_schema.STATISTICS
			WHERE TABLE_SCHEMA = DATABASE()
			  AND TABLE_NAME = 'law_api_documents'
			  AND INDEX_NAME = ?
			""", Integer.class, indexName);
		if (count != null && count > 0) {
			return;
		}
		jdbcTemplate.execute(ddl);
		log.info("Added law_api_documents index {} for version-aware search.", indexName);
	}

	private void updateDerivedColumns(Map<String, Object> row) {
		long documentId = ((Number) row.get("document_id")).longValue();
		String target = stringValue(row.get("target"));
		String title = stringValue(row.get("title"));
		String sourceDate = stringValue(row.get("source_date"));
		String canonicalKey = LawVersionUtils.canonicalKey(target, title);
		String effectiveDate = LawVersionUtils.normalizeEffectiveDate(sourceDate);
		String status = LawVersionUtils.initialStatus(target, effectiveDate, clock);
		jdbcTemplate.update(
			"""
			UPDATE law_api_documents
			SET canonical_key = ?,
			    effective_date = ?,
			    effective_status = ?
			WHERE document_id = ?
			""",
			canonicalKey,
			effectiveDate,
			status,
			documentId
		);
	}

	private void refreshAllVersionGroups() {
		String today = LocalDate.now(clock).format(COMPACT_DATE);
		jdbcTemplate.update("""
			UPDATE law_api_documents
			SET effective_status = CASE
			  WHEN effective_date IS NULL OR effective_date = '' THEN 'UNKNOWN'
			  WHEN effective_date > ? THEN 'FUTURE'
			  ELSE 'PAST'
			END
			WHERE target IN ('law', 'admrul')
			  AND canonical_key IS NOT NULL
			""", today);
		jdbcTemplate.update("""
			UPDATE law_api_documents doc
			JOIN (
			  SELECT target, canonical_key, MAX(effective_date) AS max_effective_date
			  FROM law_api_documents
			  WHERE target IN ('law', 'admrul')
			    AND use_yn = 'Y'
			    AND canonical_key IS NOT NULL
			    AND effective_date IS NOT NULL
			    AND effective_date <= ?
			  GROUP BY target, canonical_key
			) latest
			  ON latest.target = doc.target
			 AND latest.canonical_key = doc.canonical_key
			 AND latest.max_effective_date = doc.effective_date
			SET doc.effective_status = 'CURRENT'
			WHERE doc.use_yn = 'Y'
			""", today);
		jdbcTemplate.update("""
			UPDATE law_api_documents
			SET effective_status = 'CURRENT'
			WHERE target NOT IN ('law', 'admrul')
			  AND effective_status <> 'CURRENT'
			""");
	}

	private void refreshVersionGroup(String target, String canonicalKey) {
		String today = LocalDate.now(clock).format(COMPACT_DATE);
		jdbcTemplate.update(
			"""
			UPDATE law_api_documents
			SET effective_status = CASE
			  WHEN effective_date IS NULL OR effective_date = '' THEN 'UNKNOWN'
			  WHEN effective_date > ? THEN 'FUTURE'
			  ELSE 'PAST'
			END
			WHERE target = ?
			  AND canonical_key = ?
			""",
			today,
			target,
			canonicalKey
		);
		jdbcTemplate.update(
			"""
			UPDATE law_api_documents doc
			JOIN (
			  SELECT MAX(effective_date) AS max_effective_date
			  FROM law_api_documents
			  WHERE target = ?
			    AND canonical_key = ?
			    AND use_yn = 'Y'
			    AND effective_date IS NOT NULL
			    AND effective_date <= ?
			) latest
			SET doc.effective_status = 'CURRENT'
			WHERE doc.target = ?
			  AND doc.canonical_key = ?
			  AND doc.use_yn = 'Y'
			  AND doc.effective_date = latest.max_effective_date
			  AND latest.max_effective_date IS NOT NULL
			""",
			target,
			canonicalKey,
			today,
			target,
			canonicalKey
		);
	}

	private String stringValue(Object value) {
		return value == null ? "" : String.valueOf(value);
	}
}
