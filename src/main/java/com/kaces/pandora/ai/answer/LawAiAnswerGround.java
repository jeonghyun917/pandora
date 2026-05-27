package com.kaces.pandora.ai.answer;

public record LawAiAnswerGround(
	int number,
	long chunkId,
	long documentId,
	String target,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String chunkNo,
	String chunkTitle,
	Integer pageNo,
	String snippet,
	String sourcePath,
	String sourceUrl,
	double score
) {
}
