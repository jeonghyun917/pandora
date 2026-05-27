package com.kaces.pandora.lawdata.detail;

public record LawDetailSectionResponse(
	String title,
	String body,
	Integer pageNo,
	String sourcePath
) {
	// 메소드 설명: LawDetailSectionResponse 처리 흐름을 수행합니다.
	public LawDetailSectionResponse(String title, String body) {
		this(title, body, null, null);
	}
}
