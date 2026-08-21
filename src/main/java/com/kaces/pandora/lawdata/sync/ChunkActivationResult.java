package com.kaces.pandora.lawdata.sync;

import java.util.List;

public record ChunkActivationResult(
	long documentId,
	int activatedVersion,
	boolean activated,
	String reason,
	List<Long> retiredChunkIds
) {
	public static ChunkActivationResult blocked(long documentId, int candidateVersion, String reason) {
		return new ChunkActivationResult(documentId, candidateVersion, false, reason, List.of());
	}
}
