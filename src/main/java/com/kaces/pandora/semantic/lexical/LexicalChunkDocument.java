package com.kaces.pandora.semantic.lexical;

public record LexicalChunkDocument(
	String target,
	long chunkId,
	long documentId,
	String parentKey,
	String contentHash,
	String documentTitle,
	String parentTitle,
	String chunkTitle,
	String body
) {
	public LexicalChunkDocument {
		if (target == null || target.isBlank()) {
			throw new IllegalArgumentException("target is required");
		}
		if (chunkId <= 0 || documentId <= 0) {
			throw new IllegalArgumentException("chunkId and documentId must be positive");
		}
		parentKey = blankToNull(parentKey);
		contentHash = blankToNull(contentHash);
		documentTitle = nullToEmpty(documentTitle);
		parentTitle = nullToEmpty(parentTitle);
		chunkTitle = nullToEmpty(chunkTitle);
		body = nullToEmpty(body);
	}

	private static String blankToNull(String value) {
		return value == null || value.isBlank() ? null : value.trim();
	}

	private static String nullToEmpty(String value) {
		return value == null ? "" : value;
	}
}
