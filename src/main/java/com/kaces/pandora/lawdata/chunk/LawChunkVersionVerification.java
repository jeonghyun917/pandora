package com.kaces.pandora.lawdata.chunk;

public record LawChunkVersionVerification(
	int expectedChunkCount,
	int candidateChunkCount,
	int indexedCurrentCount,
	int blockingQualityCount
) {
	public boolean databaseGatesPass() {
		return expectedChunkCount > 0
			&& candidateChunkCount == expectedChunkCount
			&& indexedCurrentCount == expectedChunkCount
			&& blockingQualityCount == 0;
	}
}
