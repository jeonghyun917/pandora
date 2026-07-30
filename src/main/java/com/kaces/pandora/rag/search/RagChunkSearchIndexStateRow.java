package com.kaces.pandora.rag.search;

public record RagChunkSearchIndexStateRow(
	long chunkId,
	long documentId,
	String contentHash,
	int termCount
) {
}
