package com.kaces.pandora.ai.answer;

import java.util.List;
import java.util.Objects;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;

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
		return attachCommencement(proof, matchedChildText);
	}

	private LawAiAnswerGround attachCommencement(OfficialCommencementEvidence proof, String evidenceBody) {
		return new LawAiAnswerGround(number, chunkId, documentId, target, title, agencyName, categoryName,
			sourceDate, effectiveStatus, null, "시행일", null, proof.clause(),
			proof.sourcePath() == null ? sourcePath : proof.sourcePath(), sourceUrl, score,
			evidenceBody, null, List.of(), "official_commencement", evidenceRole, proof);
	}

	LawAiAnswerGround withOfficialCommencement(OfficialCommencementEvidence proof,
		LawSemanticChunkRow exactSource) {
		if (proof == null || exactSource == null || !"law".equals(target) || !target.equals(exactSource.target())
			|| !"CURRENT".equals(effectiveStatus) || !effectiveStatus.equals(exactSource.effectiveStatus())
			|| chunkId != exactSource.chunkId() || documentId != exactSource.documentId()
			|| !Objects.equals(title, exactSource.title()) || !Objects.equals(sourcePath, exactSource.sourcePath())) return this;
		return proof.projectCitation(exactSource.chunkText())
			.map(citation -> attachCommencement(citation, citation.clause())).orElse(this);
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
