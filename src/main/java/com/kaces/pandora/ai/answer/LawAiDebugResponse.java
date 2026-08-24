package com.kaces.pandora.ai.answer;

import java.util.List;

public record LawAiDebugResponse(
	String resultCode,
	String resultMsg,
	String question,
	String target,
	List<String> targets,
	List<String> lexicalKeywords,
	List<String> focusedKeywords,
	List<String> expandedQueries,
	List<String> clarificationQuestions,
	List<Stage> stages,
	List<Item> vectorHits,
	List<Item> lexicalHits,
	List<Item> bm25Hits,
	List<Item> documentExpansionHits,
	List<Item> fused,
	List<Item> documentExpansionFused,
	List<Item> coverageFused,
	List<Item> merged,
	List<Item> reranked,
	List<Item> intentFiltered,
	List<Item> judgeCandidates,
	List<Item> judged,
	List<Item> selected,
	List<RetrievalCandidateTrace> candidateTraces,
	String message,
	String failureType,
	String failureStage,
	boolean retryable,
	boolean evalCandidate,
	LawAiTiming timing
) {
	public record Stage(
		String name,
		int count,
		String description
	) {
	}

	public record Item(
		int rank,
		long chunkId,
		long documentId,
		String target,
		String title,
		String categoryName,
		String agencyName,
		String chunkNo,
		String chunkTitle,
		String parentSectionTitle,
		String sectionType,
		Integer pageNo,
		String sourcePath,
		Integer vectorRank,
		Integer lexicalRank,
		Integer fusedRank,
		Integer coverageFusedRank,
		String coverageAnchorCandidateKey,
		String coverageReason,
		Integer documentExpansionRank,
		String documentExpansionAnchorType,
		String documentExpansionReason,
		Boolean documentExpansionOverlap,
		double vectorScore,
		double bm25Score,
		double rrfScore,
		double keywordScore,
		double metadataScore,
		double combinedScore,
		double baseScore,
		double finalScore,
		boolean selected,
		List<String> matchedTerms,
		List<Integer> matchedAuditGroupIndexes,
		List<String> matchedAuditAliases,
		String snippet
	) {
	}
}
