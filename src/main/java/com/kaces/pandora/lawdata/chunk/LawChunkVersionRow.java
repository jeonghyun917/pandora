package com.kaces.pandora.lawdata.chunk;

public record LawChunkVersionRow(
	long documentId,
	int chunkVersion,
	String activationStatus,
	int expectedChunkCount
) {
}
