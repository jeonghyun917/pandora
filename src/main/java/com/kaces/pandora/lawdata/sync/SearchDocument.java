package com.kaces.pandora.lawdata.sync;

public record SearchDocument(
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String canonicalKey,
	String effectiveDate,
	String effectiveStatus,
	String detailLink,
	String rawJson
) {
}
