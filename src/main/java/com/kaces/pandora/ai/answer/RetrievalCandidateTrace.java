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
	public RetrievalCandidateTrace {
		sourceRanks = sourceRanks == null
			? Map.of()
			: Collections.unmodifiableMap(new LinkedHashMap<>(sourceRanks));
		enteredStages = enteredStages == null ? List.of() : List.copyOf(enteredStages);
		reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
	}
}
