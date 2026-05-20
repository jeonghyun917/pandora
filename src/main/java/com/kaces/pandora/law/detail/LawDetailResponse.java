package com.kaces.pandora.law.detail;

import java.util.List;

public record LawDetailResponse(
	boolean htmlDetail,
	String source,
	long documentId,
	String title,
	List<String> meta,
	List<LawDetailSectionResponse> sections
) {
}
