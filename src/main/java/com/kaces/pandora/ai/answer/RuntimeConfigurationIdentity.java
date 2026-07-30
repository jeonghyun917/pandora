package com.kaces.pandora.ai.answer;

import com.kaces.pandora.semantic.config.LawAiProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class RuntimeConfigurationIdentity {

	private static final String INSTANCE_ID = UUID.randomUUID().toString();

	private RuntimeConfigurationIdentity() {
	}

	static String instanceId() {
		return INSTANCE_ID;
	}

	static String sha256(LawAiProperties properties) {
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
			"qdrant.vectorSize=" + qdrant.vectorSize()
		);
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static String value(String value) {
		return value == null ? "" : value;
	}
}
