package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class DirectEvidenceSelectionPolicy {

	private final KoreanEvidenceAtomParser parser;
	private final QuestionPropositionTemplateFactory templateFactory;
	private final SemanticEvidenceMatcher semanticMatcher;
	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();

	public DirectEvidenceSelectionPolicy() {
		this(new KoreanEvidenceAtomParser());
	}

	DirectEvidenceSelectionPolicy(KoreanEvidenceAtomParser parser) {
		this.parser = parser;
		this.templateFactory = new QuestionPropositionTemplateFactory(parser);
		this.semanticMatcher = new SemanticEvidenceMatcher(parser);
	}

	public Result apply(
		String question,
		QuestionIntentProfile profile,
		List<LawSemanticChunkRow> judgedChunks,
		List<LawSemanticChunkRow> candidates,
		Map<String, Double> scoreByCandidateKey,
		Set<String> allowedTargets,
		int limit
	) {
		List<LawSemanticChunkRow> selected = safeChunks(judgedChunks);
		PropositionTemplate template = templateFactory.from(question, profile);
		if (template.isEmpty()) {
			return Result.unchanged(selected, scoreByCandidateKey);
		}
		int boundedLimit = Math.max(1, Math.min(limit, 20));
		Map<String, Double> scores = new LinkedHashMap<>(safeScores(scoreByCandidateKey));
		Map<String, String> reasons = new LinkedHashMap<>();
		Set<String> selectedKeys = new LinkedHashSet<>();
		selected.forEach(chunk -> selectedKeys.add(candidateKey(chunk)));
		List<LawSemanticChunkRow> preserved = new ArrayList<>();
		EvidenceAtom questionAtom = parser.parse(question);
		double bestScore = scores.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0d);

		for (LawSemanticChunkRow candidate : safeChunks(candidates)) {
			String key = candidateKey(candidate);
			if (selectedKeys.contains(key)) {
				continue;
			}
			String rejection = rejectionReason(candidate, question, template, questionAtom, allowedTargets);
			if (rejection != null) {
				reasons.put(key, rejection);
				continue;
			}
			if (preserved.size() >= boundedLimit) {
				reasons.put(key, "DIRECT_ATOM_REJECTED_LIMIT");
				continue;
			}
			preserved.add(candidate);
			selectedKeys.add(key);
			reasons.put(key, "DIRECT_ATOM_PRESERVED");
			double sourceScore = scores.getOrDefault(key, 0.0d);
			scores.put(key, Math.max(sourceScore + 2.0d, bestScore + 1.0d));
		}

		if (preserved.isEmpty()) {
			return new Result(selected, Map.copyOf(scores), Map.copyOf(reasons), false);
		}
		List<LawSemanticChunkRow> merged = new ArrayList<>(preserved);
		merged.addAll(selected);
		return new Result(List.copyOf(merged), Map.copyOf(scores), Map.copyOf(reasons), true);
	}

	private String rejectionReason(
		LawSemanticChunkRow candidate,
		String question,
		PropositionTemplate template,
		EvidenceAtom questionAtom,
		Set<String> allowedTargets
	) {
		if (candidate == null || (allowedTargets != null && !allowedTargets.isEmpty()
			&& !allowedTargets.contains(candidate.target()))) {
			return "DIRECT_ATOM_REJECTED_TARGET";
		}
		if (isObsolete(candidate.effectiveStatus())) {
			return "DIRECT_ATOM_REJECTED_OBSOLETE";
		}
		if (EvidenceNoiseClassifier.shouldSuppressAsEvidence(candidate, question)
			|| EvidenceNoiseClassifier.shouldDownrankAsContextOnly(candidate)) {
			return "DIRECT_ATOM_REJECTED_NOISE";
		}
		List<EvidenceAtom> atoms = evidenceAtoms(candidate);
		if (questionAtom.parseStatus() == EvidenceAtom.ParseStatus.COMPLETE) {
			SemanticEvidenceMatcher.SemanticMatch alignment = semanticMatcher.match(
				questionAtom,
				SemanticEvidenceMatcher.EvidenceIndex.of(atoms.toArray(EvidenceAtom[]::new))
			);
			if (alignment.status() == ClaimEvidenceMatcher.Status.CONTRADICTED
				|| alignment.status() == ClaimEvidenceMatcher.Status.CONFLICTED) {
				return "DIRECT_ATOM_REJECTED_CONTRADICTION";
			}
		}
		if (atoms.isEmpty() || atoms.stream().noneMatch(atom ->
			semanticMatcher.match(template, atom).status() == ClaimEvidenceMatcher.Status.SUPPORTED)) {
			return "DIRECT_ATOM_REJECTED_SEMANTIC_MISMATCH";
		}
		return null;
	}

	private List<EvidenceAtom> evidenceAtoms(LawSemanticChunkRow chunk) {
		String evidence = String.join("\n",
			value(chunk.chunkTitle()), value(chunk.parentSectionTitle()), value(chunk.chunkText()));
		return atomizer.atomizeForAlignment(evidence).stream().map(parser::parse).toList();
	}

	private boolean isObsolete(String status) {
		String normalized = value(status).trim().toUpperCase(Locale.ROOT);
		return Set.of("EXPIRED", "REPEALED", "ABOLISHED", "DELETED", "PAST").contains(normalized);
	}

	private List<LawSemanticChunkRow> safeChunks(List<LawSemanticChunkRow> chunks) {
		return chunks == null ? List.of() : List.copyOf(chunks);
	}

	private Map<String, Double> safeScores(Map<String, Double> scores) {
		return scores == null ? Map.of() : scores;
	}

	private String candidateKey(LawSemanticChunkRow chunk) {
		return value(chunk.target()) + ":" + chunk.chunkId();
	}

	private String value(String value) {
		return value == null ? "" : value;
	}

	public record Result(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> scoreByCandidateKey,
		Map<String, String> reasonByCandidateKey,
		boolean changed
	) {
		public Result {
			chunks = chunks == null ? List.of() : List.copyOf(chunks);
			scoreByCandidateKey = scoreByCandidateKey == null ? Map.of() : Map.copyOf(scoreByCandidateKey);
			reasonByCandidateKey = reasonByCandidateKey == null ? Map.of() : Map.copyOf(reasonByCandidateKey);
		}

		static Result unchanged(List<LawSemanticChunkRow> chunks, Map<String, Double> scores) {
			return new Result(chunks, scores, Map.of(), false);
		}
	}
}
