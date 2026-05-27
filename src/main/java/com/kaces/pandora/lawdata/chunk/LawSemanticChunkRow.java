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
	String chunkNo,
	String chunkTitle,
	String chunkText,
	Integer pageNo,
	String sourcePath,
	String sourceUrl,
	int sortOrder,
	String contentHash
) {
	// 메소드 설명: embeddingInput 처리 흐름을 수행합니다.
	public String embeddingInput() {
		return String.join("\n",
			nullToEmpty(title),
			nullToEmpty(chunkNo),
			nullToEmpty(chunkTitle),
			nullToEmpty(chunkText)
		).trim();
	}

	// 메소드 설명: nullToEmpty 처리 흐름을 수행합니다.
	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
