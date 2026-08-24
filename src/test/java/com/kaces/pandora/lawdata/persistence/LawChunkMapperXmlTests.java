package com.kaces.pandora.lawdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
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
	private static final String DOCUMENT_EXPANSION_DOCUMENTS_STATEMENT =
		"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findDocumentExpansionDocuments";
	private static final String DOCUMENT_EXPANSION_CHUNKS_STATEMENT =
		"com.kaces.pandora.lawdata.persistence.LawChunkMapper.findDocumentExpansionChunks";
	private static final String MAPPER_NAMESPACE = LawChunkMapper.class.getName();
	private static final List<String> SEMANTIC_CHUNK_COMPONENTS = Arrays.stream(
		LawSemanticChunkRow.class.getRecordComponents()
	).map(component -> component.getName()).toList();
	private static final Pattern PROJECTION_ALIAS = Pattern.compile(
		"(?i)\\bAS\\s+([A-Za-z][A-Za-z0-9]*)\\s*(?=,|$)"
	);
	private static final Pattern TYPED_PAGE_NO = Pattern.compile(
		"(?i)CAST\\(NULL AS SIGNED\\)\\s+AS\\s+pageNo(?=,|$)"
	);
	private static final Pattern VALID_EMBEDDING_TEXT = Pattern.compile(
		"(?i)(?:CAST\\(NULL AS CHAR\\)|c\\.embedding_text)\\s+AS\\s+embeddingText(?=,|$)"
	);
	private static final Pattern CTE_OUTER_SELECT = Pattern.compile("(?i)\\)\\s+SELECT\\s+");

	@Test
	void documentExpansionDocumentsAreActiveTargetIsolatedCurrentAndAmbiguityBounded() throws Exception {
		Configuration configuration = parseMapper();
		MappedStatement statement = configuration.getMappedStatement(DOCUMENT_EXPANSION_DOCUMENTS_STATEMENT);
		Map<String, Object> parameters = Map.of(
			"targets", List.of("law", "admrul"),
			"titleTerms", List.of("개인정보 보호법", "개인정보"),
			"provisionTerms", List.of("제12조"),
			"includeFuture", false,
			"limit", 4
		);
		String sql = normalizedSql(statement, parameters);

		assertThat(sql)
			.contains("FROM law_api_documents doc", "JOIN law_api_document_chunks c ON c.document_id = doc.document_id")
			.contains("doc.use_yn = 'Y'", "c.use_yn = 'Y'", "c.activation_status = 'ACTIVE'")
			.contains("doc.target IN ( ? , ? )")
			.contains("doc.effective_status IN ('CURRENT', 'UNKNOWN' )")
			.doesNotContain("'FUTURE'")
			.contains("normalized_title =", "OR ( normalized_title LIKE", "AND normalized_title LIKE")
			.contains("ORDER BY exactTitleMatch DESC, matchedTitleTermCount DESC, provisionAnchorMatch DESC, documentId")
			.endsWith("LIMIT ?");
		assertThat(statement.getBoundSql(parameters).getParameterObject())
			.as("the caller supplies maxDocuments + 1 for ambiguity detection")
			.extracting(value -> ((Map<?, ?>) value).get("limit"))
			.isEqualTo(4);
	}

	@Test
	void documentExpansionChunksAreRankedAndBoundedBeforeJdbc() throws Exception {
		Configuration configuration = parseMapper();
		MappedStatement statement = configuration.getMappedStatement(DOCUMENT_EXPANSION_CHUNKS_STATEMENT);
		String sql = normalizedSql(statement, Map.of(
			"documentIds", List.of(10L, 20L),
			"provisionTerms", List.of("제12조"),
			"headingTerms", List.of("적용 대상"),
			"evidenceTerms", List.of("개인정보", "보호조치"),
			"includeFuture", false,
			"perDocumentLimit", 8,
			"limit", 24
		));

		assertThat(sql)
			.contains("WITH chunk_matches AS", "ranked_chunks AS")
			.contains("ROW_NUMBER() OVER ( PARTITION BY matched.document_id ORDER BY matched.match_class, matched.evidence_match_count DESC, matched.sort_order, matched.chunk_id ) AS document_rank")
			.contains("doc.use_yn = 'Y'", "c.use_yn = 'Y'", "c.activation_status = 'ACTIVE'")
			.contains("c.document_id IN ( ? , ? )")
			.contains("doc.effective_status IN ('CURRENT', 'UNKNOWN' )")
			.contains("WHERE ranked.document_rank <= ?")
			.contains("ORDER BY ranked.match_class, ranked.document_id, ranked.sort_order, ranked.chunk_id")
			.endsWith("LIMIT ?")
			.doesNotContain("embedding_text LIKE");
		assertCanonicalProjection(sql);
	}

	@Test
	void documentExpansionChunksRenderValidRankingWithoutOptionalMatchTerms() throws Exception {
		Configuration configuration = parseMapper();
		String sql = normalizedSql(configuration.getMappedStatement(DOCUMENT_EXPANSION_CHUNKS_STATEMENT), Map.of(
			"documentIds", List.of(10L),
			"provisionTerms", List.of(),
			"headingTerms", List.of(),
			"evidenceTerms", List.of(),
			"includeFuture", true,
			"perDocumentLimit", 8,
			"limit", 24
		));

		assertThat(sql)
			.contains("2 AS match_class", "0 AS evidence_match_count")
			.doesNotContain("CASE ELSE 2 END");
	}

	@Test
	void integrityAuditFiltersTargetAndUsesMariaDbCompatibleBoundParameter() throws Exception {
		Configuration configuration = parseMapper();
		String sql = configuration.getMappedStatement(INTEGRITY_AUDIT_STATEMENT)
			.getBoundSql(Map.of(
				"target", "law",
				"model", "text-embedding-3-small",
				"vectorStore", "law_chunks",
				"limit", 99_999,
				"afterChunkId", 123L
			))
			.getSql()
			.replaceAll("\\s+", " ")
			.trim();

		assertThat(sql)
			.contains("doc.use_yn = 'Y'", "(? = '' OR doc.target = ?)", "c.chunk_id > ?", "LIMIT ?")
			.doesNotContain("LIMIT LEAST")
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
		List<String> mapperMethodNames = semanticChunkMapperMethodNames();
		List<String> mappedStatementNames = configuration.getMappedStatements().stream()
			.filter(MappedStatement.class::isInstance)
			.map(MappedStatement.class::cast)
			.distinct()
			.filter(statement -> statement.getId().startsWith(MAPPER_NAMESPACE + "."))
			.filter(statement -> statement.getResultMaps().stream()
				.anyMatch(resultMap -> resultMap.getType().equals(LawSemanticChunkRow.class)))
			.map(statement -> statement.getId().substring(MAPPER_NAMESPACE.length() + 1))
			.sorted()
			.toList();

		assertThat(mappedStatementNames)
			.as("LawChunkMapper List<LawSemanticChunkRow> methods and XML statements")
			.containsExactlyElementsOf(mapperMethodNames);

		for (String mapperMethodName : mapperMethodNames) {
			String statementId = MAPPER_NAMESPACE + "." + mapperMethodName;
			assertThat(configuration.hasStatement(statementId, false))
				.as("mapped statement for %s", mapperMethodName)
				.isTrue();
			MappedStatement statement = configuration.getMappedStatement(statementId);
			assertThat(statement.getResultMaps())
				.as("LawSemanticChunkRow result map for %s", mapperMethodName)
				.singleElement()
				.satisfies(resultMap -> assertThat(resultMap.getType()).isEqualTo(LawSemanticChunkRow.class));

			String sql = boundSql(statement);
			assertThat(projectionAliases(sql))
				.as("canonical LawSemanticChunkRow projection for %s", statement.getId())
				.containsExactlyElementsOf(SEMANTIC_CHUNK_COMPONENTS);
			assertThat(TYPED_PAGE_NO.matcher(projection(sql)).results().count())
				.as("stable Integer pageNo constructor type for %s", statement.getId())
				.isEqualTo(1);
			assertThat(VALID_EMBEDDING_TEXT.matcher(projection(sql)).results().count())
				.as("stable String embeddingText constructor type for %s", statement.getId())
				.isEqualTo(1);
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
		parameters.put("titleTerms", List.of("title"));
		parameters.put("provisionTerms", List.of("provision"));
		parameters.put("headingTerms", List.of("heading"));
		parameters.put("evidenceTerms", List.of("evidence"));
		parameters.put("perDocumentLimit", 8);
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
	}

	private String normalizedSql(MappedStatement statement, Map<String, Object> parameters) {
		return statement.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
	}

	private void assertCanonicalProjection(String sql) {
		assertThat(projectionAliases(sql)).containsExactlyElementsOf(SEMANTIC_CHUNK_COMPONENTS);
		assertThat(projection(sql))
			.contains("CAST(NULL AS SIGNED) AS pageNo", "CAST(NULL AS CHAR) AS embeddingText");
	}

	private List<String> projectionAliases(String sql) {
		Matcher matcher = PROJECTION_ALIAS.matcher(projection(sql));
		List<String> aliases = new java.util.ArrayList<>();
		while (matcher.find()) {
			aliases.add(matcher.group(1));
		}
		return aliases;
	}

	private String projection(String sql) {
		int selectBoundary = 0;
		if (sql.regionMatches(true, 0, "WITH ", 0, "WITH ".length())) {
			Matcher matcher = CTE_OUTER_SELECT.matcher(sql);
			while (matcher.find()) {
				selectBoundary = matcher.end() - "SELECT ".length();
			}
		}
		int selectStart = sql.toUpperCase().indexOf("SELECT ", selectBoundary) + "SELECT ".length();
		assertThat(selectStart).as("SELECT boundary in %s", sql).isGreaterThan("SELECT ".length() - 1);
		int fromStart = sql.toUpperCase().indexOf(" FROM ", selectStart);
		assertThat(fromStart).as("outer FROM boundary in %s", sql).isGreaterThan(selectStart);
		return sql.substring(selectStart, fromStart);
	}

	private List<String> semanticChunkMapperMethodNames() {
		return Arrays.stream(LawChunkMapper.class.getMethods())
			.filter(method -> isListOfSemanticChunks(method.getGenericReturnType()))
			.map(method -> method.getName())
			.distinct()
			.sorted(Comparator.naturalOrder())
			.toList();
	}

	private boolean isListOfSemanticChunks(Type returnType) {
		if (!(returnType instanceof ParameterizedType parameterizedType)) {
			return false;
		}
		return parameterizedType.getRawType().equals(List.class)
			&& Arrays.equals(parameterizedType.getActualTypeArguments(), new Type[] { LawSemanticChunkRow.class });
	}

	private Configuration parseMapper() throws Exception {
		Configuration configuration = new Configuration();
		try (InputStream input = Resources.getResourceAsStream(MAPPER_RESOURCE)) {
			new XMLMapperBuilder(input, configuration, MAPPER_RESOURCE, configuration.getSqlFragments()).parse();
		}
		return configuration;
	}
}
