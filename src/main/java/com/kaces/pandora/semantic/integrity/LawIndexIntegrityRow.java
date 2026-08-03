package com.kaces.pandora.semantic.integrity;

public record LawIndexIntegrityRow(
	long chunkId,
	boolean active,
	String chunkContentHash,
	String embeddingContentHash,
	String embeddingStatus,
	String vectorPointId
) {
}
