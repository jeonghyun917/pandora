package com.kaces.pandora.rag.collecting;

public record RagCollectedAttachmentRow(
	long attachmentId,
	long articleId,
	String url,
	String fileName,
	String extension,
	String mimeType,
	String fileHash,
	String localPath,
	Long documentId,
	String status
) {
}
