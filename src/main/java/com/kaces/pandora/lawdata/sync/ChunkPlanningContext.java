package com.kaces.pandora.lawdata.sync;

import org.springframework.util.StringUtils;

record ChunkPlanningContext(
	String documentTarget,
	long documentId,
	String documentTitle
) {
	ChunkPlanningContext {
		if (!StringUtils.hasText(documentTarget)) {
			throw new IllegalArgumentException("documentTarget is required");
		}
		if (documentId <= 0) {
			throw new IllegalArgumentException("documentId must be positive");
		}
	}
}
