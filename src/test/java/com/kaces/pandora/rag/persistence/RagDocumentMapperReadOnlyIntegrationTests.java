package com.kaces.pandora.rag.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "pandora.readonly-db-test", matches = "true")
class RagDocumentMapperReadOnlyIntegrationTests {
	@Test
	void bothQueriesMapAnActiveSourceVersionThroughJdbc() throws Exception {
		String password = System.getenv("PANDORA_DB_PASSWORD");
		if (password == null || password.isBlank()) password = "pandora";
		var dataSource = new UnpooledDataSource("org.mariadb.jdbc.Driver",
			"jdbc:mariadb://127.0.0.1:3306/pandora?connectTimeout=3000", "pandora", password);
		var configuration = new Configuration(new Environment("readonly-verification",
			new JdbcTransactionFactory(), dataSource));
		String resource = "mapper/law/RagDocumentMapper.xml";
		try (var input = Resources.getResourceAsStream(resource)) {
			new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
		}
		try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
			var connection = session.getConnection();
			connection.setReadOnly(true);
			try (var statement = connection.createStatement()) {
				statement.setQueryTimeout(3);
				try (var result = statement.executeQuery("SELECT c.chunk_id,c.document_id,c.sort_order,c.chunk_version "
					+ "FROM rag_document_chunks c JOIN rag_documents d ON d.document_id=c.document_id "
					+ "WHERE c.use_yn='Y' AND d.use_yn='Y' AND COALESCE(c.quality_status,'PASS')='PASS' "
					+ "AND c.chunk_version=(SELECT MAX(v.chunk_version) FROM rag_document_chunks v "
					+ "WHERE v.document_id=c.document_id AND v.use_yn='Y') ORDER BY c.chunk_id LIMIT 1")) {
					assertThat(result.next()).as("an active source exists for the opt-in check").isTrue();
					long id = result.getLong(1), documentId = result.getLong(2);
					int sortOrder = result.getInt(3), version = result.getInt(4);
					assertThat(result.wasNull()).as("the active source version must not be SQL NULL").isFalse();
					assertThat(version).isPositive();
					var mapper = session.getMapper(RagDocumentMapper.class);
					var matched = mapper.findSemanticChunksByIds(List.of(id));
					assertThat(matched).hasSize(1);
					assertThat(matched.get(0).chunkVersion()).isEqualTo(version);
					assertThat(matched.get(0).documentId()).isEqualTo(documentId);
					assertThat(matched.get(0).sortOrder()).isEqualTo(sortOrder);
					assertThat(mapper.findSemanticContextChunks(documentId, sortOrder, 1))
						.anySatisfy(row -> {
							assertThat(row.chunkId()).isEqualTo(id);
							assertThat(row.chunkVersion()).isEqualTo(version);
							assertThat(row.documentId()).isEqualTo(documentId);
							assertThat(row.sortOrder()).isEqualTo(sortOrder);
						});
				}
			}
			session.rollback();
		}
	}
}
