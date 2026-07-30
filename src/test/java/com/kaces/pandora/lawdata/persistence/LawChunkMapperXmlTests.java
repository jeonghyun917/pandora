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

	private Configuration parseMapper() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
			new XMLMapperBuilder(input, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}
}
