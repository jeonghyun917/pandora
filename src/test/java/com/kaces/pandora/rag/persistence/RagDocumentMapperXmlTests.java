package com.kaces.pandora.rag.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class RagDocumentMapperXmlTests {
	private static final String MAPPER_RESOURCE = "mapper/law/RagDocumentMapper.xml";
	private static final String HEADING_STATEMENT =
		"com.kaces.pandora.rag.persistence.RagDocumentMapper.findSemanticChunksByHeadingText";
	private static final String TEXT_STATEMENT =
		"com.kaces.pandora.rag.persistence.RagDocumentMapper.findSemanticChunksByText";
	private static final String LEGACY_TEXT_STATEMENT =
		"com.kaces.pandora.rag.persistence.RagDocumentMapper.findSemanticChunksByLegacyText";
	private static final String MISSING_INDEX_STATEMENT =
		"com.kaces.pandora.rag.persistence.RagDocumentMapper.countMissingChunkSearchTerms";
	private static final String OBJECT_STORAGE_DOCUMENTS_STATEMENT =
		"com.kaces.pandora.rag.persistence.RagDocumentMapper.findActiveDocumentsForObjectStorage";
	private static final String UPDATE_OBJECT_KEY_STATEMENT =
		"com.kaces.pandora.rag.persistence.RagDocumentMapper.assignObjectKeyIfHashMatches";

	@Test
	void headingSearchFiltersAndRanksHeadingFieldsWithoutSearchingBodyText() throws Exception {
		String sql = headingSearchSql();
		String whereClause = sql.substring(sql.indexOf("FROM rag_document_chunks"), sql.lastIndexOf("ORDER BY"));

		assertThat(whereClause)
			.contains("FROM rag_document_chunks c JOIN rag_documents doc ON doc.document_id = c.document_id")
			.contains("doc.use_yn = 'Y'")
			.contains("c.use_yn = 'Y'")
			.contains("COALESCE(c.quality_status, 'PASS') IN ('PASS', 'REVIEW')")
			.contains("c.chunk_version = ( SELECT MAX(c2.chunk_version)")
			.contains("doc.document_type IN ( ? )")
			.contains("c.chunk_title LIKE CONCAT('%', ?, '%')")
			.contains("c.parent_section_title LIKE CONCAT('%', ?, '%')")
			.doesNotContain("doc.title LIKE")
			.doesNotContain("c.chunk_text LIKE")
			.doesNotContain("c.embedding_text LIKE")
			.doesNotContain("doc.source_org LIKE")
			.doesNotContain("doc.document_category LIKE")
			.doesNotContain("doc.document_topic LIKE");

		String orderBy = sql.substring(sql.lastIndexOf("ORDER BY"));
		assertThat(orderBy)
			.contains("CASE WHEN ( c.chunk_title LIKE CONCAT('%', ?, '%') OR c.parent_section_title LIKE CONCAT('%', ?, '%') ) THEN 1 ELSE 0 END")
			.contains("GREATEST( 0, CASE")
			.contains("doc.title LIKE CONCAT('%', ?, '%')");
		assertOrdered(orderBy,
			"CASE WHEN ( c.chunk_title LIKE CONCAT('%', ?, '%') OR c.parent_section_title LIKE CONCAT('%', ?, '%') ) THEN 1 ELSE 0 END",
			"GREATEST( 0, CASE");
		assertOrdered(orderBy,
			"WHEN c.chunk_title = ? THEN 9",
			"WHEN c.chunk_title LIKE CONCAT(?, '%') THEN 8",
			"WHEN c.chunk_title LIKE CONCAT('%', ?, '%') THEN 7",
			"WHEN c.parent_section_title = ? THEN 6",
			"WHEN c.parent_section_title LIKE CONCAT(?, '%') THEN 5",
			"WHEN c.parent_section_title LIKE CONCAT('%', ?, '%') THEN 4",
			"WHEN doc.title = ? THEN 3",
			"WHEN doc.title LIKE CONCAT(?, '%') THEN 2",
			"WHEN doc.title LIKE CONCAT('%', ?, '%') THEN 1");
	}

	@Test
	void textSearchUsesExactTermIndexInsteadOfLeadingWildcardScan() throws Exception {
		Configuration configuration = parseMapper();
		String sql = configuration.getMappedStatement(TEXT_STATEMENT)
			.getBoundSql(java.util.Map.of(
				"documentTypes", java.util.List.of("official_doc"),
				"keywords", java.util.List.of("개인정보", "이메일"),
				"limit", 30
			))
			.getSql();

		assertThat(sql)
			.contains("rag_chunk_search_terms")
			.contains("WITH query_terms(query_term) AS")
			.contains("COUNT(DISTINCT query_term.query_term) AS matched_term_count")
			.contains("search_term.term LIKE CONCAT(query_term.query_term, '%')")
			.contains("ORDER BY matched_term_count DESC, term_score DESC");
		assertThat(sql).doesNotContain("chunk_text LIKE CONCAT('%'");
		String finalOrderBy = sql.substring(sql.lastIndexOf("ORDER BY")).replaceAll("\\s+", " ");
		assertThat(finalOrderBy)
			.contains("matched.matched_term_count DESC")
			.contains("matched.term_score DESC");
	}

	@Test
	void migrationFallbackIsIsolatedFromTheIndexedSearchStatement() throws Exception {
		Configuration configuration = parseMapper();
		Map<String, Object> parameters = Map.of(
			"documentTypes", List.of("official_doc"),
			"keywords", List.of("privacy", "email"),
			"limit", 30
		);
		String indexedSql = configuration.getMappedStatement(TEXT_STATEMENT)
			.getBoundSql(parameters)
			.getSql();
		String legacySql = configuration.getMappedStatement(LEGACY_TEXT_STATEMENT)
			.getBoundSql(parameters)
			.getSql();
		String missingSql = configuration.getMappedStatement(MISSING_INDEX_STATEMENT)
			.getBoundSql(Map.of())
			.getSql();

		assertThat(indexedSql).doesNotContain("chunk_text LIKE CONCAT('%'");
		assertThat(legacySql).contains("chunk_text LIKE CONCAT('%'");
		assertThat(missingSql)
			.contains("rag_chunk_search_index_state")
			.contains("state.content_hash")
			.doesNotContain("rag_chunk_search_terms term");
	}

	@Test
	void objectStorageStatementsOnlySelectActiveFileBackedDocumentsAndAssignMatchingHashes() throws Exception {
		Configuration configuration = parseMapper();

		assertThat(configuration.hasStatement(OBJECT_STORAGE_DOCUMENTS_STATEMENT)).isTrue();
		assertThat(configuration.hasStatement(UPDATE_OBJECT_KEY_STATEMENT)).isTrue();

		String selectSql = configuration.getMappedStatement(OBJECT_STORAGE_DOCUMENTS_STATEMENT)
			.getBoundSql(Map.of())
			.getSql()
			.replaceAll("\\s+", " ")
			.trim();
		String updateSql = configuration.getMappedStatement(UPDATE_OBJECT_KEY_STATEMENT)
			.getBoundSql(Map.of(
				"documentId", 7L,
				"fileHash", "a".repeat(64),
				"objectKey", "rag-originals/sha256/ab/example.pdf"
			))
			.getSql()
			.replaceAll("\\s+", " ")
			.trim();

		assertThat(selectSql)
			.contains("object_key AS objectKey")
			.contains("use_yn = 'Y'")
			.contains("file_path IS NOT NULL")
			.contains("file_path != ''")
			.contains("ORDER BY document_id");
		assertThat(updateSql)
			.contains("UPDATE rag_documents")
			.contains("SET object_key = ?")
			.contains("WHERE document_id = ?")
			.contains("AND file_hash = ?")
			.contains("AND use_yn = 'Y'");
	}

	private String headingSearchSql() throws Exception {
		Configuration configuration = parseMapper();
		MappedStatement statement = configuration.getMappedStatement(HEADING_STATEMENT);
		Map<String, Object> parameters = Map.of(
			"documentTypes", List.of("official_doc"),
			"keywords", List.of("security", "control"),
			"limit", 6
		);
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
	}

	private Configuration parseMapper() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
			new XMLMapperBuilder(input, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}

	private void assertOrdered(String text, String... fragments) {
		int previous = -1;
		for (String fragment : fragments) {
			int current = text.indexOf(fragment);
			assertThat(current)
				.as("'%s' should appear after the previous ranking condition in: %s", fragment, text)
				.isGreaterThan(previous);
			previous = current;
		}
	}
}
