package com.kaces.pandora.law.sync;
public record StoredDetail(
	long documentId,
	String detailTitle,
	String metaJson,
	String sectionsJson,
	String rawJson,
	String contentHash
) {
}
