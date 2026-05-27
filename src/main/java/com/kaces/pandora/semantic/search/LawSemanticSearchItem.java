package com.kaces.pandora.semantic.search;

public record LawSemanticSearchItem(
	long chunkId,
	long documentId,
	String target,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String chunkNo,
	String chunkTitle,
	String snippet,
	String sourcePath,
	double score
) {
}
