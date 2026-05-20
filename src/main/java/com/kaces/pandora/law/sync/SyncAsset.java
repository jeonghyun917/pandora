package com.kaces.pandora.law.sync;
public record SyncAsset(
	String type,
	String sourceUrl,
	String proxyUrl,
	String fileName,
	String fileExtension,
	String altText
) {
}
