package com.kaces.pandora.lawdata.chunk;

public record LawChunkSearchRow(
	long chunkId,
	long documentId,
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String chunkType,
	String chunkNo,
	String chunkTitle,
	String chunkText,
	String sourcePath,
	String sourceUrl,
	int sortOrder
) {
}
