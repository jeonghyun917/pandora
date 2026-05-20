package com.kaces.pandora.law.detail;

import java.util.List;

/**
 * DB ?먮Ц???꾨줎?몄뿏???곸꽭 ?붾㈃??諛붾줈 ?뚮뜑留곹븷 ???덇쾶 ?뺢퇋?뷀븳 ?묐떟 DTO?낅땲??
 */
public record LawDetailResponse(
	boolean htmlDetail,
	String source,
	long documentId,
	String title,
	List<String> meta,
	List<LawDetailSectionResponse> sections
) {
}
