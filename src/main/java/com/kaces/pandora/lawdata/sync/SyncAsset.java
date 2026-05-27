package com.kaces.pandora.lawdata.sync;

public record SyncAsset(
	String type,
	String sourceUrl,
	String proxyUrl,
	String fileName,
	String fileExtension,
	String altText
) {
}
