package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class GroundedAnswerRepairService {

	static final int MAX_SELECTED_ATOMS = 6;
	static final int MAX_ATOM_CHARACTERS = 360;
	static final int MAX_TOTAL_ATOM_CHARACTERS = 1_500;

	private final AnswerVerificationService verificationService;
	private final GroundedAnswerRewriter rewriter;
	private final ClaimEvidenceAtomizer atomizer = new ClaimEvidenceAtomizer();

	public GroundedAnswerRepairService(
		AnswerVerificationService verificationService,
		GroundedAnswerRewriter rewriter
	) {
		this.verificationService = verificationService;
		this.rewriter = rewriter;
	}

	public Result verifyAndRepair(
		String question,
		String draft,
		List<LawAiAnswerGround> grounds
	) {
		List<LawAiAnswerGround> safeGrounds = grounds == null ? List.of() : List.copyOf(grounds);
		AnswerVerificationService.Result initial;
		try {
			initial = verificationService.verify(question, draft, safeGrounds);
		} catch (RuntimeException exception) {
			return result(
				syntheticFailure(draft, "INITIAL_VERIFICATION_EXCEPTION"),
				false,
				false,
				"INITIAL_VERIFICATION_EXCEPTION",
				0
			);
		}
		if (!initial.insufficientEvidence()) {
			return result(initial, false, false, "INITIAL_OK", 0);
		}
		if (hasContradictionOrConflict(initial.claimResult())) {
			return result(initial, false, false, "CONTRADICTION_OR_CONFLICT", 0);
		}

		List<String> selectedAtoms = selectSupportedAlignedAtoms(question, initial, safeGrounds);
		if (selectedAtoms.isEmpty()) {
			return result(initial, false, false, "NO_ALIGNED_SUPPORTED_ATOM", 0);
		}
		if (rewriter == null) {
			return result(initial, false, false, "REWRITER_UNAVAILABLE", selectedAtoms.size());
		}

		String rewritten;
		try {
			rewritten = rewriter.rewrite(question, selectedAtoms);
		} catch (RuntimeException exception) {
			return result(initial, true, false, "REWRITER_EXCEPTION", selectedAtoms.size());
		}
		if (rewritten == null || rewritten.isBlank()) {
			return result(initial, true, false, "REWRITER_BLANK", selectedAtoms.size());
		}

		AnswerVerificationService.Result reverified;
		try {
			reverified = verificationService.verify(question, rewritten, safeGrounds);
		} catch (RuntimeException exception) {
			return result(initial, true, false, "REVERIFY_EXCEPTION", selectedAtoms.size());
		}
		if (reverified.insufficientEvidence()) {
			return result(reverified, true, false, "REWRITE_VERIFICATION_FAILED", selectedAtoms.size());
		}
		return result(reverified, true, true, "REWRITE_ACCEPTED", selectedAtoms.size());
	}

	private Result result(
		AnswerVerificationService.Result verification,
		boolean attempted,
		boolean accepted,
		String reason,
		int selectedAtomCount
	) {
		AnswerVerificationService.Result finalVerification =
			!accepted && verification != null && verification.insufficientEvidence()
				? exactFailClosed(verification)
				: verification;
		return new Result(
			finalVerification,
			new Diagnostics(attempted, accepted, reason, selectedAtomCount)
		);
	}

	private AnswerVerificationService.Result exactFailClosed(
		AnswerVerificationService.Result verification
	) {
		AnswerQuestionAlignmentVerifier.AlignmentResult alignment = verification.alignmentResult();
		String reasonCode = alignment == null || alignment.reasonCode() == null || alignment.reasonCode().isBlank()
			? "REPAIR_FAILED"
			: alignment.reasonCode();
		List<String> missingGroups = alignment == null ? List.of() : alignment.missingGroups();
		return new AnswerVerificationService.Result(
			verification.guardedAnswer(),
			verification.claimResult(),
			new AnswerQuestionAlignmentVerifier.AlignmentResult(
				true,
				false,
				reasonCode,
				missingGroups,
				""
			)
		);
	}

	private AnswerVerificationService.Result syntheticFailure(String guardedAnswer, String reasonCode) {
		ClaimVerifier.VerificationResult claimResult = new ClaimVerifier.VerificationResult(
			ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE,
			true,
			true,
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			0,
			0
		);
		return new AnswerVerificationService.Result(
			guardedAnswer == null ? "" : guardedAnswer,
			claimResult,
			new AnswerQuestionAlignmentVerifier.AlignmentResult(
				true,
				false,
				reasonCode,
				List.of(),
				""
			)
		);
	}

	private boolean hasContradictionOrConflict(ClaimVerifier.VerificationResult claimResult) {
		if (claimResult == null) {
			return false;
		}
		if (!claimResult.contradictedClaims().isEmpty()) {
			return true;
		}
		return claimResult.evidenceLinks().stream()
			.map(ClaimVerifier.ClaimEvidenceLink::relation)
			.anyMatch(relation ->
				"CONTRADICTED".equals(relation) || "CONFLICTED".equals(relation)
			);
	}

	private List<String> selectSupportedAlignedAtoms(
		String question,
		AnswerVerificationService.Result initial,
		List<LawAiAnswerGround> grounds
	) {
		if (grounds.isEmpty()) {
			return List.of();
		}
		List<CandidateAtom> candidates = candidateAtoms(initial, grounds);
		LinkedHashMap<String, String> selectedByKey = new LinkedHashMap<>();
		int totalCharacters = 0;
		for (CandidateAtom candidate : candidates) {
			if (selectedByKey.size() >= MAX_SELECTED_ATOMS) {
				break;
			}
			String atom = clean(candidate.text());
			if (atom.isBlank() || atom.length() > MAX_ATOM_CHARACTERS) {
				continue;
			}
			AnswerVerificationService.Result verification;
			try {
				verification = verificationService.verify(question, atom, grounds);
			} catch (RuntimeException exception) {
				continue;
			}
			if (!isFullySupportedAndAligned(verification)) {
				continue;
			}
			String verifiedAtom = clean(verification.verifiedAnswer());
			if (verifiedAtom.isBlank()
				|| verifiedAtom.length() > MAX_ATOM_CHARACTERS
				|| answerVerificationServiceInsufficient(verifiedAtom)) {
				continue;
			}
			String key = normalize(verifiedAtom);
			if (key.isBlank() || selectedByKey.containsKey(key)) {
				continue;
			}
			if (totalCharacters + verifiedAtom.length() > MAX_TOTAL_ATOM_CHARACTERS) {
				continue;
			}
			selectedByKey.put(key, verifiedAtom);
			totalCharacters += verifiedAtom.length();
		}
		return List.copyOf(selectedByKey.values());
	}

	private boolean answerVerificationServiceInsufficient(String answer) {
		return verificationService.isInsufficientEvidenceAnswer(answer);
	}

	private boolean isFullySupportedAndAligned(AnswerVerificationService.Result verification) {
		if (verification == null || verification.insufficientEvidence()) {
			return false;
		}
		ClaimVerifier.VerificationResult claimResult = verification.claimResult();
		if (claimResult == null
			|| claimResult.strongClaimCount() <= 0
			|| claimResult.supportedStrongClaimCount() != claimResult.strongClaimCount()
			|| !claimResult.unsupportedClaims().isEmpty()
			|| !claimResult.unsupportedNumericClaims().isEmpty()
			|| !claimResult.contradictedClaims().isEmpty()) {
			return false;
		}
		AnswerQuestionAlignmentVerifier.AlignmentResult alignment = verification.alignmentResult();
		return alignment != null && alignment.evaluated() && alignment.aligned();
	}

	private List<CandidateAtom> candidateAtoms(
		AnswerVerificationService.Result initial,
		List<LawAiAnswerGround> grounds
	) {
		Map<Integer, IndexedGround> groundByNumber = new LinkedHashMap<>();
		for (int index = 0; index < grounds.size(); index++) {
			LawAiAnswerGround ground = grounds.get(index);
			groundByNumber.putIfAbsent(ground.number(), new IndexedGround(index, ground));
		}

		List<CandidateAtom> supported = new ArrayList<>();
		List<ClaimVerifier.ClaimEvidenceLink> links = initial.claimResult() == null
			? List.of()
			: initial.claimResult().evidenceLinks();
		for (int linkIndex = 0; linkIndex < links.size(); linkIndex++) {
			ClaimVerifier.ClaimEvidenceLink link = links.get(linkIndex);
			if (!"SUPPORTED".equals(link.relation())) {
				continue;
			}
			IndexedGround indexedGround = groundByNumber.get(link.groundNumber());
			if (indexedGround == null) {
				continue;
			}
			for (String atom : atomize(link.evidenceSentence())) {
				if (comesFromMatchedChild(atom, indexedGround.ground())) {
					supported.add(new CandidateAtom(
						indexedGround.index(),
						linkIndex,
						atom
					));
				}
			}
		}
		supported.sort(Comparator
			.comparingInt(CandidateAtom::groundIndex)
			.thenComparingInt(CandidateAtom::sourceOrder));

		List<CandidateAtom> fallback = new ArrayList<>();
		for (int groundIndex = 0; groundIndex < grounds.size(); groundIndex++) {
			LawAiAnswerGround ground = grounds.get(groundIndex);
			List<String> atoms = atomize(matchedChildText(ground));
			for (int atomIndex = 0; atomIndex < atoms.size(); atomIndex++) {
				fallback.add(new CandidateAtom(groundIndex, atomIndex, atoms.get(atomIndex)));
			}
		}

		LinkedHashMap<String, CandidateAtom> deduplicated = new LinkedHashMap<>();
		for (CandidateAtom candidate : concat(supported, fallback)) {
			String key = normalize(candidate.text());
			if (!key.isBlank()) {
				deduplicated.putIfAbsent(key, candidate);
			}
		}
		return List.copyOf(deduplicated.values());
	}

	private List<CandidateAtom> concat(List<CandidateAtom> first, List<CandidateAtom> second) {
		List<CandidateAtom> combined = new ArrayList<>(first.size() + second.size());
		combined.addAll(first);
		combined.addAll(second);
		return combined;
	}

	private List<String> atomize(String text) {
		return atomizer.atomize(text);
	}

	private boolean comesFromMatchedChild(String atom, LawAiAnswerGround ground) {
		String source = normalize(matchedChildText(ground));
		String candidate = normalize(atom);
		return !source.isBlank() && !candidate.isBlank() && source.contains(candidate);
	}

	private String matchedChildText(LawAiAnswerGround ground) {
		if (ground == null || ground.matchedChildText() == null) {
			return "";
		}
		return ground.matchedChildText();
	}

	private String clean(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("\\s+", " ")
			.trim();
	}

	private String normalize(String value) {
		return KoreanQueryNormalizer.normalizeForMatch(value == null ? "" : value);
	}

	public record Result(
		AnswerVerificationService.Result verification,
		Diagnostics diagnostics
	) {
		public String verifiedAnswer() {
			return verification.verifiedAnswer();
		}

		public boolean insufficientEvidence() {
			return verification.insufficientEvidence();
		}
	}

	public record Diagnostics(
		boolean attempted,
		boolean accepted,
		String reason,
		int selectedAtomCount
	) {
	}

	private record IndexedGround(int index, LawAiAnswerGround ground) {
	}

	private record CandidateAtom(
		int groundIndex,
		int sourceOrder,
		String text
	) {
	}
}
