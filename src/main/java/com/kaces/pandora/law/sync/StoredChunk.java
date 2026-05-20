package com.kaces.pandora.law.sync;

/**
 * 泥?겕 ?뚯씠釉붿뿉 ??ν븷 寃???됱씤 ?⑥쐞?낅땲??
 */
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
	String contentHash
) {
}
