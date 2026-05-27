package com.kaces.pandora.semantic.batch;

public record OpenAiBatchStatus(
	String id,
	String status,
	String inputFileId,
	String outputFileId,
	String errorFileId,
	int total,
	int completed,
	int failed
) {
}
