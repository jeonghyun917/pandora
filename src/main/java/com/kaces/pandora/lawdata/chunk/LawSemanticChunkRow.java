package com.kaces.pandora.lawdata.chunk;

public record LawSemanticChunkRow(
	long chunkId,
	long documentId,
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String effectiveStatus,
	String chunkNo,
	String chunkTitle,
	String chunkText,
	Integer pageNo,
	String sourcePath,
	String sourceUrl,
	int sortOrder,
	String contentHash,
	String parentSectionTitle,
	String sectionType,
	String qualityStatus
) {
	public LawSemanticChunkRow {
		qualityStatus = qualityStatus == null || qualityStatus.isBlank() ? "PASS" : qualityStatus;
	}

	public LawSemanticChunkRow(
		long chunkId,
		long documentId,
		String target,
		String externalId,
		String title,
		String agencyName,
		String categoryName,
		String sourceDate,
		String effectiveStatus,
		String chunkNo,
		String chunkTitle,
		String chunkText,
		Integer pageNo,
		String sourcePath,
		String sourceUrl,
		int sortOrder,
		String contentHash,
		String parentSectionTitle,
		String sectionType
	) {
		this(
			chunkId, documentId, target, externalId, title, agencyName, categoryName,
			sourceDate, effectiveStatus, chunkNo, chunkTitle, chunkText, pageNo,
			sourcePath, sourceUrl, sortOrder, contentHash, parentSectionTitle, sectionType, "PASS"
		);
	}

	// 메소드 설명: embeddingInput 처리 흐름을 수행합니다.
	public String embeddingInput() {
		return String.join("\n",
			nullToEmpty(title),
			nullToEmpty(chunkNo),
			nullToEmpty(parentSectionTitle),
			nullToEmpty(chunkTitle),
			nullToEmpty(sectionType),
			nullToEmpty(chunkText)
		).trim();
	}

	// 메소드 설명: nullToEmpty 처리 흐름을 수행합니다.
	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
