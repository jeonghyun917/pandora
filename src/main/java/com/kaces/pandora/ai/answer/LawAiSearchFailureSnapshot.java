package com.kaces.pandora.ai.answer;

import java.util.List;

record LawAiSearchFailureSnapshot(
	String resultMsg,
	String question,
	List<String> targets,
	List<String> lexicalKeywords,
	int qdrantHitCount,
	int vectorChunkCount,
	int lexicalChunkCount,
	int mergedCount,
	int rankedCount,
	int intentFilteredCount,
	int judgeCandidateCount,
	int judgedCount,
	int finalGroundCount,
	String diagnosticMessage,
	int topicAlignedCount,
	int relevantCount,
	int directEvidenceCount,
	String evidenceSelectionPolicy
) {
	static LawAiSearchFailureSnapshot empty() {
		return new LawAiSearchFailureSnapshot(
			"",
			"",
			List.of(),
			List.of(),
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			"",
			0,
			0,
			0,
			"empty"
		);
	}
}
