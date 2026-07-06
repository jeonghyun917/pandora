package com.kaces.pandora.ai.answer;

public record LawAiSearchFailureLog(
	String question,
	String targets,
	String intentTypes,
	String entityIds,
	String lexicalKeywords,
	String expandedQueries,
	String failureType,
	String failureStage,
	boolean retryable,
	boolean evalCandidate,
	int qdrantHitCount,
	int vectorChunkCount,
	int lexicalChunkCount,
	int mergedCount,
	int rankedCount,
	int intentFilteredCount,
	int judgeCandidateCount,
	int judgedCount,
	int finalGroundCount,
	String resultMsg,
	String publicMessage,
	String diagnosticMessage
) {
}
