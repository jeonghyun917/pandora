package com.kaces.pandora.lawdata.persistence;

public record LawDocumentRow(
	long documentId,
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String effectiveStatus,
	String detailLink
) {
}
