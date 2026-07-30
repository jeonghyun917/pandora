package com.kaces.pandora.rag.storage.migration;

public record RagObjectStorageManifestEntry(
	long documentId,
	String filePath,
	String fileHash,
	String fileName,
	String mimeType,
	long byteSize,
	String objectKey,
	String status,
	String reason
) {
}
