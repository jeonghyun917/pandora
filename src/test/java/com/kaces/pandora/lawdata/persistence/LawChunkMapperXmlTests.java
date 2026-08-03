package com.kaces.pandora.lawdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class LawChunkMapperXmlTests {
	private static final String MAPPER_RESOURCE = "mapper/law/LawChunkMapper.xml";
	private static final String DISCOVERY_HEADING_STATEMENT =
		"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findSemanticChunksByHeadingOrDocumentTitle";
	private static final String INTEGRITY_AUDIT_STATEMENT =
		"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findLawIndexIntegrityRows";

	@Test
	void integrityAuditFiltersTargetAndEnforcesTenThousandRowBound() throws Exception {
		Configuration configuration = parseMapper();
		String sql = configuration.getMappedStatement(INTEGRITY_AUDIT_STATEMENT)
			.getBoundSql(Map.of(
				"target", "law",
				"model", "text-embedding-3-small",
				"vectorStore", "law_chunks",
				"limit", 99_999
			))
			.getSql()
			.replaceAll("\\s+", " ")
			.trim();

		assertThat(sql)
			.contains("doc.use_yn = 'Y'", "(? = '' OR doc.target = ?)", "LIMIT LEAST(?, 10000)")
			.contains("e.embedding_model = ?", "e.vector_store = ?");
	}

	@Test
	void discoveryHeadingSearchDoesNotScanChunkBody() throws Exception {
		Configuration configuration = parseMapper();
		String sql = configuration.getMappedStatement(DISCOVERY_HEADING_STATEMENT)
			.getBoundSql(Map.of(
				"targets", List.of("law", "admrul"),
				"keywords", List.of("고정형 영상정보처리기기", "영상정보처리기기"),
				"includeFuture", false,
				"limit", 20
			))
			.getSql()
			.replaceAll("\\s+", " ")
			.trim();

		String whereClause = sql.substring(sql.indexOf("FROM law_api_document_chunks"), sql.lastIndexOf("ORDER BY"));
		assertThat(whereClause)
			.contains("c.chunk_title LIKE CONCAT('%', ?, '%')")
			.contains("doc.title LIKE CONCAT('%', ?, '%')")
			.doesNotContain("c.chunk_text LIKE");
		assertThat(sql.substring(sql.lastIndexOf("ORDER BY")))
			.contains("WHEN 'law' THEN 0")
			.contains("WHEN 'admrul' THEN 1");
	}

	@Test
	void indexingProjectionUsesStoredEmbeddingTextWhileRetrievalKeepsChildText() throws Exception {
		Configuration configuration = parseMapper();
		String indexingSql = configuration.getMappedStatement(
			"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findSemanticIndexCandidates"
		).getBoundSql(Map.of("target", "law", "query", "", "model", "text-embedding-3-small", "vectorStore", "law_chunks", "limit", 10))
			.getSql().replaceAll("\\s+", " ").trim();
		String retrievalSql = configuration.getMappedStatement(
			"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findSemanticChunksByIds"
		).getBoundSql(Map.of("chunkIds", List.of(1L), "includeFuture", true))
			.getSql().replaceAll("\\s+", " ").trim();

		assertThat(indexingSql)
			.contains("c.chunk_text AS chunkText", "c.embedding_text AS embeddingText")
			.contains("c.parent_key AS parentKey", "c.chunk_version AS chunkVersion", "NULLIF(c.parent_title");
		assertThat(retrievalSql)
			.contains("c.chunk_text AS chunkText")
			.doesNotContain("c.embedding_text AS chunkText");
	}

	private Configuration parseMapper() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
			new XMLMapperBuilder(input, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}
}
