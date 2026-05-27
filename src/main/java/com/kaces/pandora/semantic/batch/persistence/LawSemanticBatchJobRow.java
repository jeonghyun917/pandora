package com.kaces.pandora.semantic.batch.persistence;

public record LawSemanticBatchJobRow(
	long batchJobId,
	String openaiBatchId,
	String inputFileId,
	String outputFileId,
	String errorFileId,
	String status,
	String target,
	String queryText,
	String embeddingModel,
	String vectorStore,
	String inputFilePath,
	String outputFilePath,
	int requestedCount,
	int submittedCount,
	int completedCount,
	int failedCount,
	int ingestedCount,
	String lastErrorMessage
) {
}
