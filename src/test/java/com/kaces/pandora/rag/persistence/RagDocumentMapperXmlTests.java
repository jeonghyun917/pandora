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

	private String headingSearchSql() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
			new XMLMapperBuilder(input, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
		}
		MappedStatement statement = configuration.getMappedStatement(HEADING_STATEMENT);
		Map<String, Object> parameters = Map.of(
			"documentTypes", List.of("official_doc"),
			"keywords", List.of("security", "control"),
			"limit", 6
		);
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
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
