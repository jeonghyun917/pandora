package com.kaces.pandora.rag.document;

public record RagDocumentChunkRow(
	long chunkId,
	long documentId,
	int chunkVersion,
	String chunkNo,
	String parentSectionTitle,
	String chunkTitle,
	String sectionType,
	String chunkText,
	String embeddingText,
	Integer pageNo,
	String sourcePath,
	String sourceUrl,
	int sortOrder,
	String contentHash,
	String qualityStatus,
	String qualityReason
) {
	public RagDocumentChunkRow {
		qualityStatus = qualityStatus == null || qualityStatus.isBlank() ? "PASS" : qualityStatus;
	}

	public RagDocumentChunkRow(
		long chunkId,
		long documentId,
		int chunkVersion,
		String chunkNo,
		String parentSectionTitle,
		String chunkTitle,
		String sectionType,
		String chunkText,
		String embeddingText,
		Integer pageNo,
		String sourcePath,
		String sourceUrl,
		int sortOrder,
		String contentHash
	) {
		this(
			chunkId, documentId, chunkVersion, chunkNo, parentSectionTitle, chunkTitle,
			sectionType, chunkText, embeddingText, pageNo, sourcePath, sourceUrl,
			sortOrder, contentHash, "PASS", null
		);
	}

	public RagDocumentChunkRow(
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
		this(
			chunkId,
			documentId,
			1,
			chunkNo,
			null,
			chunkTitle,
			"body",
			chunkText,
			null,
			pageNo,
			sourcePath,
			sourceUrl,
			sortOrder,
			contentHash,
			"PASS",
			null
		);
	}
}
