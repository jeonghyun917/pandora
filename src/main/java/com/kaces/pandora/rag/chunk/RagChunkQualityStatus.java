package com.kaces.pandora.rag.chunk;

import java.util.Locale;

public enum RagChunkQualityStatus {
	PASS,
	REVIEW,
	CONTEXT_ONLY,
	REJECT;

	public static RagChunkQualityStatus from(String value) {
		if (value == null || value.isBlank()) {
			return PASS;
		}
		try {
			return valueOf(value.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return REVIEW;
		}
	}

	public boolean searchable() {
		return this == PASS || this == REVIEW;
	}

	public boolean retainForContext() {
		return this != REJECT;
	}
}
