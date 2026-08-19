package com.kaces.pandora.semantic.lexical;

import java.util.List;

public record LexicalSearchHit(
	String target,
	long chunkId,
	long documentId,
	double score,
	int rank,
	List<String> matchedTerms
) {
	public LexicalSearchHit {
		matchedTerms = matchedTerms == null ? List.of() : List.copyOf(matchedTerms);
	}
}
