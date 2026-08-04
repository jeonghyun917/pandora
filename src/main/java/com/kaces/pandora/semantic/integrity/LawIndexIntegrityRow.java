package com.kaces.pandora.semantic.integrity;

public record LawIndexIntegrityRow(
	long chunkId,
	long documentId,
	boolean active,
	String chunkContentHash,
	String embeddingContentHash,
	String embeddingStatus,
	String vectorPointId
) {
	public LawIndexIntegrityRow(
		long chunkId,
		boolean active,
		String chunkContentHash,
		String embeddingContentHash,
		String embeddingStatus,
		String vectorPointId
	) {
		this(chunkId, 0L, active, chunkContentHash, embeddingContentHash, embeddingStatus, vectorPointId);
	}
}
