package com.kaces.pandora.rag.document;

import java.util.Set;

public final class RagDocumentType {
	public static final String OFFICIAL_DOC = "official_doc";
	public static final String INTERNAL_DOC = "internal_doc";
	public static final String REFERENCE_DOC = "reference_doc";
	// 메소드 설명: of 처리 흐름을 수행합니다.
	public static final Set<String> VALUES = Set.of(OFFICIAL_DOC, INTERNAL_DOC, REFERENCE_DOC);

	// 메소드 설명: RagDocumentType 처리 흐름을 수행합니다.
	private RagDocumentType() {
	}

	// 메소드 설명: normalize 처리 흐름을 수행합니다.
	public static String normalize(String value) {
		String normalized = value == null ? "" : value.trim();
		if (normalized.isBlank()) {
			return OFFICIAL_DOC;
		}
		if (!VALUES.contains(normalized)) {
			throw new IllegalArgumentException("Unsupported RAG document type: " + normalized);
		}
		return normalized;
	}
}
