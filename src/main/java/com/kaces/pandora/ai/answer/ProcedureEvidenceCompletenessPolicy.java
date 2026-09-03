package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionEntity;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;

final class ProcedureEvidenceCompletenessPolicy {

	private static final List<List<String>> PROCEDURE_STAGE_GROUPS = List.of(
		List.of("검토요청", "검토를요청", "검토신청", "검토를신청", "신청", "제출"),
		List.of("검토수행", "검토를수행", "검토실시", "검토를실시", "총괄검토", "검토기관"),
		List.of("결과통보", "결과를통보", "검토결과통보", "결과회신", "결과를회신")
	);
	private static final Set<String> GENERIC_QUERY_TERMS = Set.of(
		"절차", "방법", "어떻게", "언제", "시기", "기한", "기간", "처리", "진행",
		"요청", "신청", "제출", "검토", "심사", "협의", "통보", "결과", "회신"
	);

	Result apply(
		String query,
		List<LawSemanticChunkRow> selectedChunks,
		List<LawSemanticChunkRow> candidateChunks,
		Map<String, Double> scoreByCandidateKey,
		int limit
	) {
		List<LawSemanticChunkRow> selected = selectedChunks == null ? List.of() : List.copyOf(selectedChunks);
		Map<String, Double> scores = scoreByCandidateKey == null ? Map.of() : Map.copyOf(scoreByCandidateKey);
		if (limit <= 0 || candidateChunks == null || candidateChunks.isEmpty()) {
			return Result.unchanged(selected, scores);
		}
		QuestionIntentProfile profile = QuestionIntentProfile.from(query);
		if (!profile.intentTypes().contains("procedure")
			|| selected.stream().limit(limit).anyMatch(this::coversCompleteProcedure)) {
			return Result.unchanged(selected, scores);
		}

		LawSemanticChunkRow completeCandidate = candidateChunks.stream()
			.filter(this::hasUsefulText)
			.filter(this::coversCompleteProcedure)
			.filter(chunk -> alignsWithQuestionDomain(chunk, profile))
			.findFirst()
			.orElse(null);
		if (completeCandidate == null) {
			return Result.unchanged(selected, scores);
		}

		LinkedHashMap<String, LawSemanticChunkRow> merged = new LinkedHashMap<>();
		merged.put(candidateKey(completeCandidate), completeCandidate);
		selected.stream()
			.filter(this::hasUsefulText)
			.forEach(chunk -> merged.putIfAbsent(candidateKey(chunk), chunk));
		List<LawSemanticChunkRow> preserved = merged.values().stream().limit(limit).toList();

		Map<String, Double> updatedScores = new LinkedHashMap<>(scores);
		double bestScore = updatedScores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
		String preservedKey = candidateKey(completeCandidate);
		updatedScores.put(preservedKey, Math.max(updatedScores.getOrDefault(preservedKey, 0.0), bestScore + 2.0));
		return new Result(preserved, Map.copyOf(updatedScores), true, "COMPLETE_PROCEDURE_GROUND_PRESERVED");
	}

	private boolean coversCompleteProcedure(LawSemanticChunkRow chunk) {
		String text = normalizedChunkText(chunk);
		int searchFrom = 0;
		for (List<String> group : PROCEDURE_STAGE_GROUPS) {
			int matchedEnd = earliestMatchEnd(text, group, searchFrom);
			if (matchedEnd < 0) {
				return false;
			}
			searchFrom = matchedEnd;
		}
		return true;
	}

	private int earliestMatchEnd(String text, List<String> phrases, int searchFrom) {
		int earliestEnd = Integer.MAX_VALUE;
		for (String phrase : phrases) {
			String normalizedPhrase = normalize(phrase);
			int index = text.indexOf(normalizedPhrase, searchFrom);
			if (index >= 0) {
				earliestEnd = Math.min(earliestEnd, index + normalizedPhrase.length());
			}
		}
		return earliestEnd == Integer.MAX_VALUE ? -1 : earliestEnd;
	}

	private boolean alignsWithQuestionDomain(LawSemanticChunkRow chunk, QuestionIntentProfile profile) {
		String text = normalizedChunkText(chunk);
		if (profile.entities() != null && !profile.entities().isEmpty()) {
			return profile.entities().stream().allMatch(entity -> matchesEntity(text, entity));
		}
		List<String> domainTerms = new ArrayList<>();
		for (String term : profile.terms()) {
			String normalized = normalize(term);
			if (normalized.length() >= 2 && !GENERIC_QUERY_TERMS.contains(normalized)) {
				domainTerms.add(normalized);
			}
		}
		return domainTerms.isEmpty() || domainTerms.stream().anyMatch(text::contains);
	}

	private boolean matchesEntity(String text, QuestionEntity entity) {
		List<String> names = new ArrayList<>();
		if (entity.label() != null) {
			names.add(entity.label());
		}
		if (entity.aliases() != null) {
			names.addAll(entity.aliases());
		}
		return names.stream()
			.map(this::normalize)
			.filter(value -> value.length() >= 2)
			.anyMatch(text::contains);
	}

	private String normalizedChunkText(LawSemanticChunkRow chunk) {
		if (chunk == null) {
			return "";
		}
		return normalize(String.join(" ",
			nullToEmpty(chunk.title()),
			nullToEmpty(chunk.parentSectionTitle()),
			nullToEmpty(chunk.chunkTitle()),
			nullToEmpty(chunk.chunkText())
		));
	}

	private boolean hasUsefulText(LawSemanticChunkRow chunk) {
		return chunk != null && chunk.chunkText() != null && !chunk.chunkText().isBlank();
	}

	private String candidateKey(LawSemanticChunkRow chunk) {
		return chunk.target() + ":" + chunk.chunkId();
	}

	private String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(value == null ? "" : value).replaceAll("\\s+", "");
	}

	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	record Result(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> scoreByCandidateKey,
		boolean changed,
		String reason
	) {
		static Result unchanged(List<LawSemanticChunkRow> chunks, Map<String, Double> scores) {
			return new Result(chunks, scores, false, "UNCHANGED");
		}
	}
}
