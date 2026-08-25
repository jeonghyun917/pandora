package com.kaces.pandora.ai.answer;

import com.kaces.pandora.semantic.config.LawAiLexicalProperties;
import com.kaces.pandora.semantic.config.LawAiCoverageAwareProperties;
import com.kaces.pandora.semantic.config.LawAiDocumentExpansionProperties;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.config.LawAiRrfProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class RuntimeConfigurationIdentity {

	private static final String INSTANCE_ID = UUID.randomUUID().toString();

	private RuntimeConfigurationIdentity() {
	}

	public static String instanceId() {
		return INSTANCE_ID;
	}

	static String sha256(LawAiProperties properties) {
		return sha256(
			properties,
			new LawAiLexicalProperties(1.2, 0.75, 8, 6, 7, 1, 24, 100),
			new LawAiRrfProperties(false, false, 60, 1.0, 1.0, 100),
			new LawAiCoverageAwareProperties(false, 0, 1, 30),
			disabledDocumentExpansion()
		);
	}

	static String sha256(
		LawAiProperties properties,
		LawAiLexicalProperties lexical,
		LawAiRrfProperties rrf
	) {
		return sha256(properties, lexical, rrf, new LawAiCoverageAwareProperties(false, 0, 1, 30), disabledDocumentExpansion());
	}

	static String sha256(
		LawAiProperties properties,
		LawAiLexicalProperties lexical,
		LawAiRrfProperties rrf,
		LawAiCoverageAwareProperties coverage
	) {
		return sha256(properties, lexical, rrf, coverage, disabledDocumentExpansion());
	}

	static String sha256(
		LawAiProperties properties,
		LawAiLexicalProperties lexical,
		LawAiRrfProperties rrf,
		LawAiCoverageAwareProperties coverage,
		LawAiDocumentExpansionProperties documentExpansion
	) {
		LawAiProperties.OpenAi openAi = properties.openai();
		LawAiProperties.Qdrant qdrant = properties.qdrant();
		String canonical = String.join("\n",
			"openai.embeddingModel=" + value(openAi.embeddingModel()),
			"openai.answerModel=" + value(openAi.answerModel()),
			"openai.answerReasoningEffort=" + value(openAi.answerReasoningEffort()),
			"openai.answerVerbosity=" + value(openAi.answerVerbosity()),
			"openai.answerMaxOutputTokens=" + openAi.answerMaxOutputTokens(),
			"qdrant.baseUrl=" + value(qdrant.baseUrl()),
			"qdrant.collection=" + value(qdrant.collection()),
			"qdrant.ragCollection=" + value(qdrant.ragCollection()),
			"qdrant.vectorSize=" + qdrant.vectorSize(),
			"lexical.k1=" + lexical.k1(),
			"lexical.b=" + lexical.b(),
			"lexical.documentTitleWeight=" + lexical.documentTitleWeight(),
			"lexical.parentTitleWeight=" + lexical.parentTitleWeight(),
			"lexical.chunkTitleWeight=" + lexical.chunkTitleWeight(),
			"lexical.bodyWeight=" + lexical.bodyWeight(),
			"lexical.maxQueryTerms=" + lexical.maxQueryTerms(),
			"lexical.maxResultLimit=" + lexical.maxResultLimit(),
			"rrf.shadowEnabled=" + rrf.rrfShadowEnabled(),
			"rrf.authoritative=" + rrf.rrfAuthoritative(),
			"rrf.k=" + rrf.rrfK(),
			"rrf.vectorWeight=" + rrf.rrfVectorWeight(),
			"rrf.lexicalWeight=" + rrf.rrfLexicalWeight(),
			"rrf.fusedLimit=" + rrf.rrfFusedLimit(),
			"coverage.enabled=" + coverage.enabled(),
			"coverage.maxRescues=" + coverage.maxRescues(),
			"coverage.maxRescuesPerDocument=" + coverage.maxRescuesPerDocument(),
			"coverage.sourceRankLimit=" + coverage.sourceRankLimit(),
			"documentExpansion.enabled=" + documentExpansion.enabled(),
			"documentExpansion.authoritative=" + documentExpansion.authoritative(),
			"documentExpansion.maxDocuments=" + documentExpansion.maxDocuments(),
			"documentExpansion.maxChunksPerDocument=" + documentExpansion.maxChunksPerDocument(),
			"documentExpansion.maxTotalChunks=" + documentExpansion.maxTotalChunks(),
			"documentExpansion.bm25TitleEnabled=" + documentExpansion.bm25TitleEnabled(),
			"documentExpansion.bm25TitleMaxHits=" + documentExpansion.bm25TitleMaxHits(),
			"documentExpansion.bm25TitleMinimumTerms=" + documentExpansion.bm25TitleMinimumTerms(),
			"documentExpansion.bm25TitleAmbiguityRatio=" + documentExpansion.bm25TitleAmbiguityRatio()
		);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static LawAiDocumentExpansionProperties disabledDocumentExpansion() {
		return new LawAiDocumentExpansionProperties(false, false, 0, 0, 0);
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}
}
