package com.kaces.pandora.semantic.indexing;

public record LawSemanticBatchFileResult(
	String filePath,
	String model,
	String target,
	int requested,
	int written
) {
}
