package com.kaces.pandora.lawdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.io.InputStream;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class LawChunkMapperXmlTests {
	private static final String MAPPER_RESOURCE = "mapper/law/LawChunkMapper.xml";
	private static final String DISCOVERY_HEADING_STATEMENT =
		"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findSemanticChunksByHeadingOrDocumentTitle";
	private static final String INTEGRITY_AUDIT_STATEMENT =
		"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findLawIndexIntegrityRows";
	private static final List<String> SEMANTIC_CHUNK_COMPONENTS = List.of(
		"chunkId", "documentId", "target", "externalId", "title", "agencyName", "categoryName",
		"sourceDate", "effectiveStatus", "chunkNo", "chunkTitle", "chunkText", "pageNo", "sourcePath",
		"sourceUrl", "sortOrder", "contentHash", "parentSectionTitle", "sectionType", "qualityStatus",
		"embeddingText", "parentKey", "chunkVersion"
	);
	private static final Pattern PROJECTION_ALIAS = Pattern.compile(
		"(?i)\\bAS\\s+([A-Za-z][A-Za-z0-9]*)\\s*(?=,|$)"
	);

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
			.contains("c.parent_key AS parentKey", "c.chunk_version AS chunkVersion", "NULLIF(c.parent_title")
			.contains("c.activation_status = 'ACTIVE'");
		assertThat(retrievalSql)
			.contains("c.chunk_text AS chunkText")
			.doesNotContain("c.embedding_text AS chunkText");
	}

	@Test
	void currentIndexSnapshotExcludesCandidateAndRetiredChunks() throws Exception {
		Configuration configuration = parseMapper();
		String sql = configuration.getMappedStatement(
			"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findCurrentIndexedSnapshot"
		).getBoundSql(Map.of("model", "text-embedding-3-small", "vectorStore", "law_chunks"))
			.getSql().replaceAll("\\s+", " ").trim();

		assertThat(sql).contains("c.activation_status = 'ACTIVE'");
	}

	@Test
	void cleanupCompletionUsesTheClaimedOperationRatherThanThePriorVersionOwner() throws Exception {
		Configuration configuration = parseMapper();
		String sql = configuration.getMappedStatement(
			"com.kaces.pandora.lawdata.persistence.LawChunkMapper.completeCandidateCleanupForOperation"
		).getBoundSql(Map.of("documentId", 42L, "chunkVersion", 2, "owner", "replacement-owner"))
			.getSql().replaceAll("\\s+", " ").trim();

		assertThat(sql)
			.contains("o.owner_token=?", "o.phase='DB_ACTIVE_CLEANUP_PENDING'")
			.doesNotContain("v.activation_owner=?");
	}

	@Test
	void everySemanticChunkSelectMatchesTheCanonicalRecordProjection() throws Exception {
		Configuration configuration = parseMapper();
		List<MappedStatement> statements = configuration.getMappedStatements().stream()
			.filter(MappedStatement.class::isInstance)
			.map(MappedStatement.class::cast)
			.distinct()
			.filter(statement -> statement.getResultMaps().stream()
				.anyMatch(resultMap -> resultMap.getType().equals(LawSemanticChunkRow.class)))
			.sorted(Comparator.comparing(MappedStatement::getId))
			.toList();

		assertThat(statements).isNotEmpty();
		for (MappedStatement statement : statements) {
			String sql = boundSql(statement);
			assertThat(projectionAliases(sql))
				.as("canonical LawSemanticChunkRow projection for %s", statement.getId())
				.containsExactlyElementsOf(SEMANTIC_CHUNK_COMPONENTS);
			assertThat(sql)
				.as("stable Integer pageNo constructor type for %s", statement.getId())
				.contains("CAST(NULL AS SIGNED) AS pageNo")
				.doesNotContain("NULL AS pageNo");
			assertThat(sql)
				.as("stable String embeddingText constructor type for %s", statement.getId())
				.doesNotContain("NULL AS embeddingText");
		}
	}

	@Test
	void findSemanticChunksByIdsUsesExactCanonicalAliasesAndTypedNulls() throws Exception {
		Configuration configuration = parseMapper();
		MappedStatement statement = configuration.getMappedStatement(
			"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findSemanticChunksByIds"
		);
		String sql = boundSql(statement);

		assertThat(projectionAliases(sql))
			.containsExactlyElementsOf(SEMANTIC_CHUNK_COMPONENTS);
		assertThat(sql)
			.contains("CAST(NULL AS SIGNED) AS pageNo", "CAST(NULL AS CHAR) AS embeddingText")
			.doesNotContain("NULL AS pageNo", "NULL AS embeddingText");
	}

	private String boundSql(MappedStatement statement) {
		Map<String, Object> parameters = new HashMap<>();
		parameters.put("target", "law");
		parameters.put("query", "");
		parameters.put("model", "text-embedding-3-small");
		parameters.put("vectorStore", "law_chunks");
		parameters.put("limit", 20);
		parameters.put("documentIds", List.of(1L));
		parameters.put("documentId", 1L);
		parameters.put("chunkVersion", 1);
		parameters.put("chunkIds", List.of(1L));
		parameters.put("includeFuture", true);
		parameters.put("sortOrder", 1);
		parameters.put("radius", 1);
		parameters.put("targets", List.of("law"));
		parameters.put("keywords", List.of("keyword"));
		parameters.put("titleKeywords", List.of("title"));
		parameters.put("textKeywords", List.of("text"));
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
	}

	private List<String> projectionAliases(String sql) {
		int selectStart = sql.toUpperCase().indexOf("SELECT ") + "SELECT ".length();
		assertThat(selectStart).as("SELECT boundary in %s", sql).isGreaterThan("SELECT ".length() - 1);
		int fromStart = sql.toUpperCase().indexOf(" FROM ", selectStart);
		assertThat(fromStart).as("outer FROM boundary in %s", sql).isGreaterThan(selectStart);
		String projection = sql.substring(selectStart, fromStart);
		Matcher matcher = PROJECTION_ALIAS.matcher(projection);
		List<String> aliases = new java.util.ArrayList<>();
		while (matcher.find()) {
			aliases.add(matcher.group(1));
		}
		return aliases;
	}

	private Configuration parseMapper() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
			new XMLMapperBuilder(input, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}
}
