package com.kaces.pandora.lawdata.sync;

import java.util.List;

public record CandidateChunkVersionResult(
	long documentId,
	int chunkVersion,
	String activationStatus,
	int expectedChunkCount,
	boolean previewApproved,
	int unexplainedLossSpanCount,
	String previewTokenHash,
	List<Long> chunkIds
) {
}
