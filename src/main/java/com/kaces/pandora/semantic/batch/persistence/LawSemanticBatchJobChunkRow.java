package com.kaces.pandora.semantic.batch.persistence;

public record LawSemanticBatchJobChunkRow(
	long batchJobChunkId,
	long batchJobId,
	String openaiBatchId,
	String target,
	long chunkId,
	String customId,
	String status,
	String errorCode,
	String errorMessage
) {
	public static LawSemanticBatchJobChunkRow submitted(
		long batchJobId,
		String openaiBatchId,
		String target,
		long chunkId
	) {
		return new LawSemanticBatchJobChunkRow(
			0,
			batchJobId,
			openaiBatchId,
			target,
			chunkId,
			"chunk:" + chunkId,
			"SUBMITTED",
			null,
			null
		);
	}
}
