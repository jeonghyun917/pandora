package com.kaces.pandora.ai.answer;

public record LawAiRuntimeInfo(
	String indexVersion,
	String embeddingModel,
	String answerModel,
	String lawCollection,
	String ragCollection,
	String runtimeArtifactKind,
	String runtimeArtifactSha256,
	Long runtimeArtifactSize,
	String runtimeArtifactPath,
	String runtimeArtifactModifiedAt,
	String runtimeInstanceId,
	String runtimeConfigSha256,
	String indexRevision,
	String lexicalRevision,
	Long lawQdrantExactPointCount,
	Long ragQdrantExactPointCount,
	Long lawDatabaseIndexedCount,
	Long ragDatabaseIndexedCount,
	String lawDatabaseContentFingerprint,
	String ragDatabaseContentFingerprint,
	boolean qdrantReady,
	long qdrantSearchFailureCount
) {
}
