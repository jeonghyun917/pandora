package com.kaces.pandora.semantic.search;

public record QdrantSearchHit(
	String target,
	long chunkId,
	double score
) {
}
