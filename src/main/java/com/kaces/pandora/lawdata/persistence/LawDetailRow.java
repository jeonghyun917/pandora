package com.kaces.pandora.lawdata.persistence;

public record LawDetailRow(
	long documentId,
	String title,
	String agencyName,
	String sourceDate,
	String detailLink,
	String detailTitle,
	String sectionsJson,
	String rawJson
) {
}
