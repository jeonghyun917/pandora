package com.kaces.pandora.law.sync;
public record StoredAsset(
	long documentId,
	long detailId,
	String assetType,
	String sourceUrl,
	String proxyUrl,
	String fileName,
	String fileExtension,
	String mimeType,
	String altText,
	String rawJson,
	int sortOrder
) {
}
