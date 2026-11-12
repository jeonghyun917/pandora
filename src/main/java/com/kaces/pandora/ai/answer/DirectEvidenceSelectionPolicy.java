package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.QuestionIntentProfile;
import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.DocumentSearchAnchorExtractor;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.chunk.RagChunkQualityStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class DirectEvidenceSelectionPolicy {

	private static final String OMISSION_TRIGGER = "사전협의신청누락이있는경우에는?";
	private static final String CONSULTING_AUTHORITY = "협의기관의장은";
	private static final Pattern CONSULTATION_OMISSION_DUTIES = Pattern.compile(
		"(?:(?:정보화사업의)?" + OMISSION_TRIGGER + CONSULTING_AUTHORITY + "|" + CONSULTING_AUTHORITY
			+ "(?:국가종합전자조달시스템상발주현황을상시모니터링하여)?(?:정보화사업의)?"
			+ OMISSION_TRIGGER + ")"
			+ "(?:해당중앙행정기관등또는지방자치단체등|해당기관)의장에게이를통보하고,"
			+ "해당기관의장은즉시신청하여야한다\\.");

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

	String openDutyTopic(String question, QuestionIntentProfile profile) {
		return templateFactory.from(question, profile).openDutyTopic();
	}

	List<String> openDutyBodySentences(String question, String body) {
		var template = templateFactory.from(question, null);
		if (template.openDutyTopic() == null) return List.of();
		return atomizer.atomizeForAlignment(body).stream()
			.filter(sentence -> semanticMatcher.match(template, parser.parse(sentence)).status()
				== ClaimEvidenceMatcher.Status.SUPPORTED)
			.toList();
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
		if (asksConsultationOmissionProcedure(question)) {
			return provesConsultationOmissionRoles(candidate)
				&& matchesExplicitAuthority(candidate, DocumentSearchAnchorExtractor.explicitDocumentTitles(question))
				? null : "DIRECT_ATOM_REJECTED_SEMANTIC_MISMATCH";
		}
		if (atoms.isEmpty() || atoms.stream().noneMatch(atom ->
			semanticMatcher.match(template, atom).status() == ClaimEvidenceMatcher.Status.SUPPORTED)) {
			return "DIRECT_ATOM_REJECTED_SEMANTIC_MISMATCH";
		}
		return null;
	}

	static boolean provesConsultationOmissionRoles(LawSemanticChunkRow candidate) {
		return !consultationOmissionClause(candidate).isEmpty();
	}

	static boolean matchesExplicitAuthority(LawSemanticChunkRow candidate, List<String> titles) {
		return titles.isEmpty() || (titles.size() == 1 && candidate != null
			&& KoreanQueryNormalizer.normalizeForMatch(titles.get(0))
				.equals(KoreanQueryNormalizer.normalizeForMatch(candidate.title())));
	}

	static String consultationOmissionClause(LawSemanticChunkRow candidate) {
		if (candidate == null || !("law".equals(candidate.target()) || "admrul".equals(candidate.target()))
			|| !"CURRENT".equals(candidate.effectiveStatus()) || candidate.chunkText() == null) return "";
		String owned = "";
		for (String raw : candidate.chunkText().split("(?<=[.!?])|(?=[①-⑳])")) {
			String clause = raw.strip().replaceFirst("^[①-⑳]\\s*", "");
			if (clause.length() > 460
				|| !CONSULTATION_OMISSION_DUTIES.matcher(clause.replaceAll("\\s+", "")).matches()) continue;
			if (!owned.isEmpty()) return "";
			owned = clause;
		}
		return owned;
	}

	static boolean asksConsultationOmissionProcedure(String question) {
		String text = KoreanQueryNormalizer.normalizeForMatch(question);
		return text.contains("사전협의") && text.contains("신청") && text.contains("누락")
			&& text.contains("통보") && (text.contains("누가") || text.contains("주체")
				|| (text.contains("절차") && text.contains("통보와신청")))
			&& !text.contains("제재") && !text.contains("처벌");
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
		return chunks == null ? List.of() : chunks.stream()
			.filter(chunk -> chunk != null && RagChunkQualityStatus.from(chunk.qualityStatus()).searchable())
			.toList();
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
