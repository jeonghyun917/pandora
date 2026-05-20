package com.kaces.pandora.law.mapper;

/**
 * ?곸꽭 ?붾㈃ 援ъ꽦???꾩슂??臾몄꽌 湲곕낯 ?뺣낫? ??λ맂 ?곸꽭 ?먮Ц?낅땲??
 */
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
