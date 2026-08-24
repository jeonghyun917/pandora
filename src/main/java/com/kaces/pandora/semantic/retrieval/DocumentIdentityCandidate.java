package com.kaces.pandora.semantic.retrieval;

public record DocumentIdentityCandidate(
	long documentId,
	String target,
	String title,
	String normalizedTitle,
	int matchedTitleTermCount,
	boolean exactTitleMatch,
	boolean provisionAnchorMatch
) {
}
