package com.kaces.pandora.lawdata.sync;

public record StoredDetail(
	long documentId,
	String detailTitle,
	String metaJson,
	String sectionsJson,
	String rawJson,
	String contentHash
) {
}
