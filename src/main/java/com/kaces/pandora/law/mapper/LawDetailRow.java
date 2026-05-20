package com.kaces.pandora.law.mapper;
public record LawDetailRow(
	long documentId,
	String title,
	String agencyName,
	String sourceDate,
	String detailTitle,
	String sectionsJson,
	String rawJson
) {
}
