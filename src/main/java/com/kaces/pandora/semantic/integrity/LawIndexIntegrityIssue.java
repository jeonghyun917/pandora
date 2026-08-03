package com.kaces.pandora.semantic.integrity;

public record LawIndexIntegrityIssue(
	long chunkId,
	Cause cause,
	String chunkContentHash,
	String embeddingContentHash,
	String embeddingStatus,
	String vectorPointId
) {
	public enum Cause {
		MISSING_EMBEDDING_ROW,
		RETRYABLE_EMBEDDING_FAILURE,
		CONTENT_HASH_MISMATCH,
		QDRANT_POINT_MISSING,
		STALE_DATABASE_STATUS,
		INACTIVE_CHUNK_COUNTED
	}
}
