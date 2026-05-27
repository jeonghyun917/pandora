package com.kaces.pandora.rag.document;

public record RagDocumentChunkRow(
	long chunkId,
	long documentId,
	String chunkNo,
	String chunkTitle,
	String chunkText,
	Integer pageNo,
	String sourcePath,
	String sourceUrl,
	int sortOrder,
	String contentHash
) {
}
