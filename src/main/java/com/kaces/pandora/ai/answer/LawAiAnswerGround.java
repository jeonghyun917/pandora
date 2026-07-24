package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiAnswerGround(
	int number,
	long chunkId,
	long documentId,
	String target,
	String title,
	String agencyName,
	String categoryName,
	String sourceDate,
	String effectiveStatus,
	String chunkNo,
	String chunkTitle,
	Integer pageNo,
	String snippet,
	String sourcePath,
	String sourceUrl,
	double score,
	String matchedChildText,
	String parentContextText,
	List<Long> contextChunkIds,
	String contextPolicy,
	String evidenceRole
) {
	public LawAiAnswerGround(
		int number,
		long chunkId,
		long documentId,
		String target,
		String title,
		String agencyName,
		String categoryName,
		String sourceDate,
		String effectiveStatus,
		String chunkNo,
		String chunkTitle,
		Integer pageNo,
		String snippet,
		String sourcePath,
		String sourceUrl,
		double score,
		String matchedChildText,
		String parentContextText,
		List<Long> contextChunkIds,
		String contextPolicy
	) {
		this(
			number, chunkId, documentId, target, title, agencyName, categoryName,
			sourceDate, effectiveStatus, chunkNo, chunkTitle, pageNo, snippet,
			sourcePath, sourceUrl, score, matchedChildText, parentContextText,
			contextChunkIds, contextPolicy, "direct"
		);
	}

	public LawAiAnswerGround(
		int number,
		long chunkId,
		long documentId,
		String target,
		String title,
		String agencyName,
		String categoryName,
		String sourceDate,
		String effectiveStatus,
		String chunkNo,
		String chunkTitle,
		Integer pageNo,
		String snippet,
		String sourcePath,
		String sourceUrl,
		double score
	) {
		this(
			number,
			chunkId,
			documentId,
			target,
			title,
			agencyName,
			categoryName,
			sourceDate,
			effectiveStatus,
			chunkNo,
			chunkTitle,
			pageNo,
			snippet,
			sourcePath,
			sourceUrl,
			score,
			snippet,
			null,
			List.of(chunkId),
			"matched_child_only",
			"direct"
		);
	}
}
