package com.kaces.pandora.semantic.retrieval;

import java.util.List;

public record DocumentExpansionSeed(
	String target,
	long documentId,
	String title,
	List<String> matchedTitleTerms,
	double bm25Score,
	int bm25Rank,
	String anchorType,
	String reason
) {
	public DocumentExpansionSeed {
		target = target == null ? "" : target.trim().toLowerCase();
		title = title == null ? "" : title;
		matchedTitleTerms = matchedTitleTerms == null ? List.of() : List.copyOf(matchedTitleTerms);
		anchorType = anchorType == null ? "" : anchorType;
		reason = reason == null ? "" : reason;
	}
}
