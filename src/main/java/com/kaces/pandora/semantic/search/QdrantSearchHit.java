package com.kaces.pandora.semantic.search;

public record QdrantSearchHit(
	String target,
	long chunkId,
	double score,
    String contentHash
) {
    public QdrantSearchHit(String target,long chunkId,double score) { this(target,chunkId,score,null); }
}
