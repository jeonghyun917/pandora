package com.kaces.pandora.semantic.batch;

public record LawSemanticBatchJobResponse(
	long batchJobId,
	String openaiBatchId,
	String status,
	String inputFileId,
	String outputFileId,
	String errorFileId,
	String target,
	String query,
	String inputFilePath,
	String outputFilePath,
	int requested,
	int submitted,
	int completed,
	int failed,
	int ingested
) {
}
