package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.common.text.QuestionSearchPlan;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FailureLoggingService {

	private static final Logger log = LoggerFactory.getLogger(FailureLoggingService.class);

	private final LawAiSearchFailureMapper searchFailureMapper;

	public FailureLoggingService(LawAiSearchFailureMapper searchFailureMapper) {
		this.searchFailureMapper = searchFailureMapper;
	}

	public void record(
		LawAiSearchFailureSnapshot snapshot,
		String publicMessage,
		LawAiSearchFailureClassification classification
	) {
		if (snapshot == null || snapshot.question() == null || snapshot.question().isBlank()) {
			return;
		}
		if (searchFailureMapper == null) {
			return;
		}
		try {
			QuestionSearchPlan plan = QuestionSearchPlan.from(snapshot.question());
			QuestionIntentProfile profile = plan.profile();
			LawAiSearchFailureClassification safeClassification = classification == null
				? classify(snapshot)
				: classification;
			searchFailureMapper.insertFailure(new LawAiSearchFailureLog(
				snapshot.question(),
				joinValues(snapshot.targets()),
				joinValues(profile.intentTypes()),
				joinValues(profile.entities().stream().map(entity -> entity.id()).toList()),
				joinValues(plan.lexicalKeywords()),
				joinValues(plan.expandedQueries()),
				safeClassification.failureType(),
				safeClassification.failureStage(),
				safeClassification.retryable(),
				safeClassification.evalCandidate(),
				snapshot.qdrantHitCount(),
				snapshot.vectorChunkCount(),
				snapshot.lexicalChunkCount(),
				snapshot.mergedCount(),
				snapshot.rankedCount(),
				snapshot.intentFilteredCount(),
				snapshot.judgeCandidateCount(),
				snapshot.judgedCount(),
				snapshot.finalGroundCount(),
				snapshot.resultMsg(),
				publicMessage,
				snapshot.diagnosticMessage()
			));
		} catch (RuntimeException exception) {
			log.warn("AI search failure log skipped. question={}", snapshot.question(), exception);
		}
	}

	LawAiSearchFailureClassification classify(LawAiSearchFailureSnapshot snapshot) {
		return LawAiSearchFailureClassification.classify(
			snapshot.resultMsg(),
			snapshot.diagnosticMessage(),
			snapshot.qdrantHitCount(),
			snapshot.vectorChunkCount(),
			snapshot.lexicalChunkCount(),
			snapshot.mergedCount(),
			snapshot.rankedCount(),
			snapshot.intentFilteredCount(),
			snapshot.judgeCandidateCount(),
			snapshot.judgedCount(),
			snapshot.finalGroundCount(),
			snapshot.topicAlignedCount(),
			snapshot.relevantCount(),
			snapshot.directEvidenceCount(),
			snapshot.evidenceSelectionPolicy()
		);
	}

	private String joinValues(Iterable<String> values) {
		if (values == null) {
			return "";
		}
		java.util.LinkedHashSet<String> filtered = new java.util.LinkedHashSet<>();
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				filtered.add(value.trim());
			}
		}
		return String.join("|", filtered);
	}
}
