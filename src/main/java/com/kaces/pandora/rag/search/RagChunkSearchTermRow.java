package com.kaces.pandora.rag.search;

public record RagChunkSearchTermRow(
	long chunkId,
	long documentId,
	String term,
	String fieldKind,
	int weight
) {
}
