package com.kaces.pandora.law.sync;

/**
 * ?곸꽭 ?뚯씠釉붿뿉 upsert??DB ???媛믪엯?덈떎.
 */
public record StoredDetail(
	long documentId,
	String detailTitle,
	String metaJson,
	String sectionsJson,
	String rawJson,
	String contentHash
) {
}
