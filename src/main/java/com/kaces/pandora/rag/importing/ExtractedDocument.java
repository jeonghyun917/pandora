package com.kaces.pandora.rag.importing;

import java.util.List;

public record ExtractedDocument(
	List<ExtractedPage> pages
) {
	// 메소드 설명: text 처리 흐름을 수행합니다.
	public String text() {
		return pages.stream()
			.map(ExtractedPage::text)
			.filter(text -> text != null && !text.isBlank())
			.reduce("", (left, right) -> left.isBlank() ? right : left + "\n\n" + right);
	}
}
