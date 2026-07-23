package com.kaces.pandora.ai.answer;

import com.fasterxml.jackson.annotation.JsonInclude;
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
	List<Item> merged,
	List<Item> reranked,
	List<Item> intentFiltered,
	List<Item> judgeCandidates,
	List<Item> judged,
	List<Item> selected,
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
		double vectorScore,
		double keywordScore,
		double metadataScore,
		double combinedScore,
		double baseScore,
		double finalScore,
		boolean selected,
		List<String> matchedTerms,
		String snippet,
		@JsonInclude(JsonInclude.Include.NON_NULL) String matchedChildText
	) {
	}
}
