package com.kaces.pandora.law.sync;

/**
 * 援??踰뺣졊 寃??API??????ぉ??DB ??μ슜?쇰줈 ?뺢퇋?뷀븳 媛믪엯?덈떎.
 */
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
