package com.kaces.pandora.law.sync;

/**
 * ?먯궛 ?뚯씠釉붿뿉 ??ν븷 ?대?吏/?뚯씪 硫뷀??곗씠?곗엯?덈떎.
 */
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
