package com.kaces.pandora.ai.answer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Collections;

public record RetrievalCandidateTrace(
	String candidateKey,
	String target,
	long chunkId,
	Map<String, Integer> sourceRanks,
	List<String> enteredStages,
	String firstLossStage,
	List<String> reasonCodes,
	boolean selected
) {
	public static final String COVERAGE_FUSED_STAGE = "coverage-fused";
	public static final String ABSENT_FROM_SOURCE_UNION = "ABSENT_FROM_SOURCE_UNION";
	public static final String SOURCE_RANK_LIMIT = "SOURCE_RANK_LIMIT";
	public static final String INVALID_DOCUMENT_IDENTITY = "INVALID_DOCUMENT_IDENTITY";
	public static final String TOP_K_DISPLACED = "TOP_K_DISPLACED";

	public RetrievalCandidateTrace {
		sourceRanks = sourceRanks == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(sourceRanks));
		enteredStages = enteredStages == null ? List.of() : List.copyOf(enteredStages);
		reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
	}
}
