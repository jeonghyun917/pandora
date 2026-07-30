package com.kaces.pandora.rag.document;

public record RagDocumentRow(
	long documentId,
	String documentType,
	String title,
	String sourceOrg,
	String documentCategory,
	String documentTopic,
	String publishedDate,
	String version,
	int trustLevel,
	String fileName,
	String filePath,
	String objectKey,
	String fileHash,
	String mimeType,
	String sourceUrl,
	String importStatus
) {
}
