package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class SemanticLexicalMapperXmlTests {

	private static final String RESOURCE = "mapper/law/SemanticLexicalMapper.xml";
	private static final String NAMESPACE = SemanticLexicalMapper.class.getName();

	@Test
	void commonProjectionReadsOnlyActiveSearchableLawAndLatestRagChunks() throws Exception {
		String sql = sql("findActiveSearchableChunks", Map.of());

		assertThat(sql)
			.contains("FROM law_api_document_chunks c")
			.contains("c.activation_status = 'ACTIVE'")
			.contains("COALESCE(c.quality_status, 'PASS') IN ('PASS', 'REVIEW')")
			.contains("FROM rag_document_chunks c")
			.contains("c.chunk_version = ( SELECT MAX(c2.chunk_version)")
			.contains("doc.target AS target")
			.contains("doc.document_type AS target")
			.contains("UNION ALL");
	}

	@Test
	void sideBySideWritesQualifyChunkAndTermIdentityByIndexVersionAndTarget() throws Exception {
		SemanticLexicalMapper.ChunkRow chunk = new SemanticLexicalMapper.ChunkRow(
			"law", 1L, 2L, "parent", "a".repeat(64), 4
		);
		SemanticLexicalMapper.TermRow term = new SemanticLexicalMapper.TermRow(
			"law", 1L, "국가계약법", "document_title", 1, 8
		);

		assertThat(sql("insertChunks", Map.of("indexVersion", "v1", "chunks", List.of(chunk))))
			.contains("index_version, target, chunk_id")
			.contains("VALUES (?, ?, ?, ?, ?, ?, ?, 'BUILDING'")
			.doesNotContain("ON DUPLICATE KEY UPDATE");
		assertThat(sql("insertTerms", Map.of("indexVersion", "v1", "terms", List.of(term))))
			.contains("index_version, target, chunk_id, term, field_kind, term_frequency, field_weight")
			.doesNotContain("ON DUPLICATE KEY UPDATE");
	}

	@Test
	void currentRevisionCanOnlyComeFromTheNewestReadyBuild() throws Exception {
		assertThat(sql("findReadyRevision", Map.of()))
			.contains("WHERE status = 'READY'")
			.contains("ORDER BY completed_at DESC, index_version DESC")
			.contains("LIMIT 1");
	}

	@Test
	void bm25TermReadHasAOneSecondStatementTimeout() throws Exception {
		Configuration configuration = configuration();
		MappedStatement statement = configuration.getMappedStatement(NAMESPACE + ".findBm25TermMatches");

		assertThat(statement.getTimeout()).isEqualTo(1);
		assertThat(statement.getBoundSql(Map.of(
			"revision", "revision-a",
			"terms", List.of("검사"),
			"targets", List.of("law")
		)).getSql().replaceAll("\\s+", " "))
			.contains("state.status = 'READY'")
			.contains("state.content_fingerprint = ?")
			.contains("c.build_status = 'READY'");
	}

	private String sql(String id, Map<String, ?> parameters) throws Exception {
		Configuration configuration = configuration();
		MappedStatement statement = configuration.getMappedStatement(NAMESPACE + "." + id);
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
	}

	private Configuration configuration() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(RESOURCE)) {
			new XMLMapperBuilder(input, configuration, RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}
}
