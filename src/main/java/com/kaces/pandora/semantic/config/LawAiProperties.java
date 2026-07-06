package com.kaces.pandora.semantic.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "law-ai")
public record LawAiProperties(
	OpenAi openai,
	Qdrant qdrant,
	Batch batch,
	Rag rag
) {
	public LawAiProperties {
		if (openai == null) {
			openai = new OpenAi("", "text-embedding-3-small", "gpt-5-mini", "low", "low", 700);
		}
		if (qdrant == null) {
			qdrant = new Qdrant("http://127.0.0.1:6333", "law_chunks", "rag_chunks_v4", 1536);
		}
		if (batch == null) {
			batch = new Batch(false, false, true, "", "", 50_000, 1, 60_000, 360);
		}
		if (rag == null) {
			rag = new Rag("data/rag-upload", 64);
		}
	}

	public record OpenAi(
		String apiKey,
		String embeddingModel,
		String answerModel,
		String answerReasoningEffort,
		String answerVerbosity,
		int answerMaxOutputTokens
	) {
		public OpenAi {
			if (embeddingModel == null || embeddingModel.isBlank()) {
				embeddingModel = "text-embedding-3-small";
			}
			if (answerModel == null || answerModel.isBlank()) {
				answerModel = "gpt-5-mini";
			}
			if (answerReasoningEffort == null || answerReasoningEffort.isBlank()) {
				answerReasoningEffort = "low";
			}
			if (answerVerbosity == null || answerVerbosity.isBlank()) {
				answerVerbosity = "low";
			}
			if (answerMaxOutputTokens <= 0) {
				answerMaxOutputTokens = 700;
			}
		}
	}

	public record Qdrant(
		String baseUrl,
		String collection,
		String ragCollection,
		int vectorSize
	) {
		public Qdrant {
			if (ragCollection == null || ragCollection.isBlank()) {
				ragCollection = "rag_chunks_v4";
			}
		}
	}

	public record Batch(
		boolean schedulerEnabled,
		boolean autoEnabled,
		boolean autoIngestEnabled,
		String autoTarget,
		String autoQuery,
		int submitLimit,
		int maxActiveJobs,
		long pollDelayMillis,
		int staleSubmittedMinutes
	) {
		public Batch {
			if (submitLimit <= 0) {
				submitLimit = 50_000;
			}
			if (maxActiveJobs <= 0) {
				maxActiveJobs = 1;
			}
			if (pollDelayMillis <= 0) {
				pollDelayMillis = 60_000;
			}
			if (staleSubmittedMinutes <= 0) {
				staleSubmittedMinutes = 360;
			}
		}
	}

	public record Rag(
		String uploadRoot,
		int importBatchSize
	) {
		public Rag {
			if (uploadRoot == null || uploadRoot.isBlank()) {
				uploadRoot = "data/rag-upload";
			}
			if (importBatchSize <= 0) {
				importBatchSize = 64;
			}
		}
	}
}
