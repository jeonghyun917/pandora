package com.kaces.pandora.law.sync;

/**
 * ?곸꽭 ?먮Ц?먯꽌 諛쒓껄???대?吏/?뚯씪 ?먯궛?낅땲??
 */
public record SyncAsset(
	String type,
	String sourceUrl,
	String proxyUrl,
	String fileName,
	String fileExtension,
	String altText
) {
}
