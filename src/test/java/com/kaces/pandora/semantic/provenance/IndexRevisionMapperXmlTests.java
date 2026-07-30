package com.kaces.pandora.semantic.provenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class IndexRevisionMapperXmlTests {
	private static final String LAW_RESOURCE = "mapper/law/LawChunkMapper.xml";
	private static final String RAG_RESOURCE = "mapper/law/RagDocumentMapper.xml";

	@Test
	void lawSnapshotAggregatesCurrentIndexedHashesWithoutGroupConcat() throws Exception {
		String sql = sql(
			LAW_RESOURCE,
			"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findCurrentIndexedSnapshot"
		);

		assertCommonAggregate(sql, "law_api_chunk_embeddings");
		assertThat(sql)
			.contains("JOIN law_api_document_chunks c ON c.chunk_id = e.chunk_id")
			.contains("JOIN law_api_documents doc ON doc.document_id = c.document_id")
			.contains("doc.use_yn = 'Y'")
			.contains("c.use_yn = 'Y'");
	}

	@Test
	void ragSnapshotAggregatesOnlyLatestSearchableCurrentIndexedHashes() throws Exception {
		String sql = sql(
			RAG_RESOURCE,
			"com.kaces.pandora.rag.persistence.RagDocumentMapper.findCurrentIndexedSnapshot"
		);

		assertCommonAggregate(sql, "rag_chunk_embeddings");
		assertThat(sql)
			.contains("JOIN rag_document_chunks c ON c.chunk_id = e.chunk_id")
			.contains("JOIN rag_documents doc ON doc.document_id = c.document_id")
			.contains("doc.use_yn = 'Y'")
			.contains("c.use_yn = 'Y'")
			.contains("COALESCE(c.quality_status, 'PASS') IN ('PASS', 'REVIEW')")
			.contains("c.chunk_version = ( SELECT MAX(c2.chunk_version)");
	}

	private void assertCommonAggregate(String sql, String embeddingTable) {
		assertThat(sql)
			.contains("COUNT(*) AS currentIndexedCount")
			.contains("SHA2(CONCAT(CAST(c.chunk_id AS CHAR), ':', c.content_hash), 256)")
			.contains("HEX(BIT_XOR")
			.contains("AS contentFingerprint")
			.contains("MAX(GREATEST(doc.updated_at, c.updated_at, e.updated_at))")
			.contains("AS updatedWatermark")
			.contains("FROM " + embeddingTable + " e")
			.contains("e.embedding_model = ?")
			.contains("e.vector_store = ?")
			.contains("e.status = 'INDEXED'")
			.contains("e.content_hash = c.content_hash")
			.contains("c.content_hash REGEXP '^[0-9A-Fa-f]{64}$'")
			.doesNotContain("GROUP_CONCAT")
			.doesNotContain("indexed_vectors_count")
			.doesNotContain("segments_count");
	}

	private String sql(String resource, String statementId) throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(resource)) {
			new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
		}
		MappedStatement statement = configuration.getMappedStatement(statementId);
		return statement.getBoundSql(Map.of(
			"model", "text-embedding-3-small",
			"vectorStore", "collection"
		)).getSql().replaceAll("\\s+", " ").trim();
	}
}
