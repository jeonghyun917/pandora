package com.kaces.pandora.law.mapper;

/**
 * DB 紐⑸줉 ?뚯씠釉붿뿉???쎌뼱 ??踰뺣졊/?됱젙洹쒖튃 臾몄꽌 ???됱엯?덈떎.
 */
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
