package com.kaces.pandora.law.sync;
public record SearchDocument(
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String detailLink,
	String rawJson
) {
}
