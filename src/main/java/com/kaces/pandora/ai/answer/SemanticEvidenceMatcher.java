package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SemanticEvidenceMatcher {

	private static final double MIN_SLOT_COVERAGE = 0.5d;
	private final KoreanEvidenceAtomParser parser;

	public SemanticEvidenceMatcher() {
		this(new KoreanEvidenceAtomParser());
	}

	@Autowired
	public SemanticEvidenceMatcher(KoreanEvidenceAtomParser parser) {
		this.parser = parser;
	}

	public EvidenceIndex index(List<LawAiAnswerGround> grounds) {
		List<IndexedAtom> atoms = new ArrayList<>();
		ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();
		for (LawAiAnswerGround ground : grounds == null ? List.<LawAiAnswerGround>of() : grounds) {
			String evidence = String.join("\n",
				value(ground.matchedChildText()), value(ground.snippet()), value(ground.parentContextText()));
			for (String clause : atomizer.atomizeForAlignment(evidence)) {
				atoms.add(new IndexedAtom(ground.number(), clause, parser.parse(clause)));
			}
		}
		return new EvidenceIndex(atoms);
	}

	public SemanticMatch match(EvidenceAtom claim, EvidenceIndex evidenceIndex) {
		if (claim == null || claim.parseStatus() != EvidenceAtom.ParseStatus.COMPLETE) {
			return SemanticMatch.insufficient("CLAIM_PARSE_INCOMPLETE");
		}
		List<SemanticMatch> supported = new ArrayList<>();
		List<SemanticMatch> contradicted = new ArrayList<>();
		for (IndexedAtom evidence : evidenceIndex == null ? List.<IndexedAtom>of() : evidenceIndex.atoms()) {
			SemanticMatch result = align(claim, evidence);
			if (result.status() == ClaimEvidenceMatcher.Status.SUPPORTED) {
				supported.add(result);
			} else if (result.status() == ClaimEvidenceMatcher.Status.CONTRADICTED) {
				contradicted.add(result);
			}
		}
		if (!supported.isEmpty() && !contradicted.isEmpty()) {
			SemanticMatch conflict = contradicted.get(0);
			return new SemanticMatch(
				ClaimEvidenceMatcher.Status.CONFLICTED, conflict.alignedSlots(), conflict.groundNumber(),
				conflict.evidenceSentence(), conflict.coverage(), "ALIGNED_POLARITY_CONFLICT"
			);
		}
		if (!supported.isEmpty()) {
			return supported.get(0);
		}
		if (!contradicted.isEmpty()) {
			return contradicted.get(0);
		}
		return SemanticMatch.insufficient("NO_ALIGNED_EVIDENCE_ATOM");
	}

	public SemanticMatch match(PropositionTemplate template, EvidenceAtom evidence) {
		if (template == null || template.isEmpty() || evidence == null
			|| evidence.parseStatus() == EvidenceAtom.ParseStatus.AMBIGUOUS) {
			return SemanticMatch.insufficient("TEMPLATE_OR_EVIDENCE_INCOMPLETE");
		}
		Set<String> aligned = new LinkedHashSet<>();
		if (!requiredAligned(template.requiredSlots().contains(PropositionTemplate.RequiredSlot.SUBJECT),
			template.subjects(), evidence.subjects(), "subject", aligned)
			|| !requiredAligned(template.requiredSlots().contains(PropositionTemplate.RequiredSlot.ACTION),
				template.actions(), evidence.actions(), "action", aligned)
			|| !requiredAligned(template.requiredSlots().contains(PropositionTemplate.RequiredSlot.RELATION),
				template.relations(), evidence.relations(), "relation", aligned)
			|| !requiredAligned(template.requiredSlots().contains(PropositionTemplate.RequiredSlot.TARGET_SCOPE),
				template.targetScopes(), evidence.targetScopes(), "targetScope", aligned)
			|| !requiredAligned(template.requiredSlots().contains(PropositionTemplate.RequiredSlot.CONDITION),
				template.conditions(), evidence.conditions(), "condition", aligned)) {
			return SemanticMatch.insufficient("REQUIRED_TEMPLATE_SLOT_MISSING");
		}
		if (template.requiredSlots().contains(PropositionTemplate.RequiredSlot.MODALITY)
			&& evidence.modality() == EvidenceAtom.Modality.UNSPECIFIED) {
			return SemanticMatch.insufficient("REQUIRED_MODALITY_MISSING");
		}
		return new SemanticMatch(
			ClaimEvidenceMatcher.Status.SUPPORTED, Set.copyOf(aligned), 0, evidence.sourceText(), 1.0d, "ALIGNED"
		);
	}

	private SemanticMatch align(EvidenceAtom claim, IndexedAtom indexed) {
		EvidenceAtom evidence = indexed.atom();
		if (evidence.parseStatus() == EvidenceAtom.ParseStatus.AMBIGUOUS) {
			return SemanticMatch.insufficient("EVIDENCE_PARSE_AMBIGUOUS");
		}
		if (!evidence.numericAnchors().containsAll(claim.numericAnchors())) {
			return SemanticMatch.insufficient("NUMERIC_ANCHOR_MISMATCH");
		}
		Set<String> aligned = new LinkedHashSet<>();
		if (!slotAligned(claim.actions(), evidence.actions(), "action", aligned)
			|| !slotAligned(claim.relations(), evidence.relations(), "relation", aligned)) {
			return SemanticMatch.insufficient("PROPOSITION_MISMATCH");
		}
		if (!slotAligned(claim.subjects(), evidence.subjects(), "subject", aligned)
			|| !slotAligned(claim.objects(), evidence.objects(), "object", aligned)
			|| !slotAligned(claim.recipients(), evidence.recipients(), "recipient", aligned)) {
			return SemanticMatch.insufficient("ROLE_MISMATCH");
		}
		if (!slotAligned(claim.targetScopes(), evidence.targetScopes(), "targetScope", aligned)) {
			return SemanticMatch.insufficient("TARGET_SCOPE_MISMATCH");
		}
		if (!slotAligned(claim.conditions(), evidence.conditions(), "condition", aligned)
			|| !slotAligned(claim.exceptions(), evidence.exceptions(), "exception", aligned)) {
			return SemanticMatch.insufficient("CONDITION_OR_EXCEPTION_MISMATCH");
		}
		double coverage = slotCoverage(claim, evidence);
		if (coverage < MIN_SLOT_COVERAGE) {
			return SemanticMatch.insufficient("LEXICAL_SLOT_COVERAGE_LOW");
		}
		boolean oppositePolarity = claim.polarity() != EvidenceAtom.Polarity.UNSPECIFIED
			&& evidence.polarity() != EvidenceAtom.Polarity.UNSPECIFIED
			&& claim.polarity() != evidence.polarity();
		boolean oppositeModality = (claim.modality() == EvidenceAtom.Modality.PERMITTED
			&& evidence.modality() == EvidenceAtom.Modality.PROHIBITED)
			|| (claim.modality() == EvidenceAtom.Modality.PROHIBITED
				&& evidence.modality() == EvidenceAtom.Modality.PERMITTED);
		ClaimEvidenceMatcher.Status status = oppositePolarity || oppositeModality
			? ClaimEvidenceMatcher.Status.CONTRADICTED
			: ClaimEvidenceMatcher.Status.SUPPORTED;
		return new SemanticMatch(
			status, Set.copyOf(aligned), indexed.groundNumber(), indexed.sentence(), coverage,
			status == ClaimEvidenceMatcher.Status.SUPPORTED ? "ALIGNED" : "ALIGNED_OPPOSITE_POLARITY"
		);
	}

	private boolean requiredAligned(
		boolean required, Set<String> expected, Set<String> actual, String name, Set<String> aligned
	) {
		if (!required) {
			return true;
		}
		return slotAligned(expected, actual, name, aligned);
	}

	private boolean slotAligned(Set<String> expected, Set<String> actual, String name, Set<String> aligned) {
		if (expected == null || expected.isEmpty()) {
			return true;
		}
		if (actual == null || actual.isEmpty() || !overlaps(expected, actual)) {
			return false;
		}
		aligned.add(name);
		return true;
	}

	private boolean overlaps(Set<String> left, Set<String> right) {
		return left.stream().anyMatch(expected -> right.stream().anyMatch(actual ->
			expected.equals(actual) || expected.contains(actual) || actual.contains(expected)));
	}

	private double slotCoverage(EvidenceAtom claim, EvidenceAtom evidence) {
		Set<String> expected = slots(claim);
		if (expected.isEmpty()) {
			return 0.0d;
		}
		long matched = expected.stream().filter(value -> slots(evidence).stream().anyMatch(other ->
			value.equals(other) || value.contains(other) || other.contains(value))).count();
		return (double) matched / expected.size();
	}

	private Set<String> slots(EvidenceAtom atom) {
		Set<String> values = new LinkedHashSet<>();
		values.addAll(atom.subjects());
		values.addAll(atom.objects());
		values.addAll(atom.recipients());
		values.addAll(atom.actions());
		values.addAll(atom.relations());
		values.addAll(atom.targetScopes());
		values.addAll(atom.conditions());
		values.addAll(atom.exceptions());
		values.addAll(atom.numericAnchors());
		return values;
	}

	private String value(String value) {
		return value == null ? "" : value;
	}

	public record EvidenceIndex(List<IndexedAtom> atoms) {
		public EvidenceIndex {
			atoms = atoms == null ? List.of() : List.copyOf(atoms);
		}

		public static EvidenceIndex of(EvidenceAtom... atoms) {
			List<IndexedAtom> indexed = new ArrayList<>();
			for (int index = 0; index < atoms.length; index++) {
				EvidenceAtom atom = atoms[index];
				indexed.add(new IndexedAtom(index + 1, atom.sourceText(), atom));
			}
			return new EvidenceIndex(indexed);
		}
	}

	public record IndexedAtom(int groundNumber, String sentence, EvidenceAtom atom) {
	}

	public record SemanticMatch(
		ClaimEvidenceMatcher.Status status,
		Set<String> alignedSlots,
		int groundNumber,
		String evidenceSentence,
		double coverage,
		String reasonCode
	) {
		public SemanticMatch {
			alignedSlots = alignedSlots == null ? Set.of() : Set.copyOf(alignedSlots);
			evidenceSentence = evidenceSentence == null ? "" : evidenceSentence;
		}

		static SemanticMatch insufficient(String reasonCode) {
			return new SemanticMatch(
				ClaimEvidenceMatcher.Status.INSUFFICIENT, Set.of(), 0, "", 0.0d, reasonCode
			);
		}

		ClaimEvidenceMatcher.Match toControlMatch() {
			return new ClaimEvidenceMatcher.Match(
				status, groundNumber, evidenceSentence, alignedSlots.size(), coverage,
				alignedSlots.size() + coverage
			);
		}
	}
}
