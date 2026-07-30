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
	String runtimeInstanceId,
	String runtimeConfigSha256,
	String indexRevision,
	boolean qdrantReady,
	long qdrantSearchFailureCount
) {
}
