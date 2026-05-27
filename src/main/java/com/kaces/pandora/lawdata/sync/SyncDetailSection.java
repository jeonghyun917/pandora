package com.kaces.pandora.lawdata.sync;

public record SyncDetailSection(
	String type,
	String no,
	String title,
	String body,
	String sourcePath,
	int paragraphNo,
	int lineNo
) {
	// 메소드 설명: SyncDetailSection 처리 흐름을 수행합니다.
	public SyncDetailSection(String type, String no, String title, String body) {
		this(type, no, title, body, null, 0, 0);
	}
}
