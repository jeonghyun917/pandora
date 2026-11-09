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
	String evidenceRole,
	OfficialCommencementEvidence officialCommencement
) {
	public LawAiAnswerGround(int number, long chunkId, long documentId, String target, String title,
		String agencyName, String categoryName, String sourceDate, String effectiveStatus, String chunkNo,
		String chunkTitle, Integer pageNo, String snippet, String sourcePath, String sourceUrl, double score,
		String matchedChildText, String parentContextText, List<Long> contextChunkIds, String contextPolicy,
		String evidenceRole) {
		this(number, chunkId, documentId, target, title, agencyName, categoryName, sourceDate, effectiveStatus,
			chunkNo, chunkTitle, pageNo, snippet, sourcePath, sourceUrl, score, matchedChildText, parentContextText,
			contextChunkIds, contextPolicy, evidenceRole, null);
	}

	LawAiAnswerGround withOfficialCommencement(OfficialCommencementEvidence proof) {
		if (proof == null || !"law".equals(target) || !proof.covers(matchedChildText)) {
			return this;
		}
		return new LawAiAnswerGround(number, chunkId, documentId, target, title, agencyName, categoryName,
			sourceDate, effectiveStatus, null, "시행일", null, proof.clause(),
			proof.sourcePath() == null ? sourcePath : proof.sourcePath(), sourceUrl, score,
			matchedChildText, null, List.of(), "official_commencement", evidenceRole, proof);
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
