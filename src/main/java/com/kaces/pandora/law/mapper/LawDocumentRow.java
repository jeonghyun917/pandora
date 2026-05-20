package com.kaces.pandora.law.mapper;
public record LawDocumentRow(
	long documentId,
	String target,
	String externalId,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String detailLink
) {
}
