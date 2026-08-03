package com.kaces.pandora.lawdata.sync;

public record StoredChunk(
	long documentId,
	long detailId,
	String chunkType,
	String chunkNo,
	String chunkTitle,
	String chunkText,
	String sourcePath,
	String sourceUrl,
	int sortOrder,
	String contentHash,
	int chunkSchemaVersion,
	int chunkVersion,
	String activationStatus,
	String parentKey,
	String parentTitle,
	String parentSourcePath,
	int childOrder,
	String embeddingText,
	String qualityStatus,
	String qualityReason
) {
}
