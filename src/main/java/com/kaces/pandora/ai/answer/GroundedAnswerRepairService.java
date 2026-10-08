package com.kaces.pandora.ai.answer;

import com.kaces.pandora.common.text.KoreanQueryNormalizer;
import com.kaces.pandora.common.text.QuestionIntentProfile;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class GroundedAnswerRepairService {
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GroundedAnswerRepairService.class);

	static final int MAX_SELECTED_ATOMS = 6;
	static final int MAX_ATOM_CHARACTERS = 360;
	static final int MAX_TOTAL_ATOM_CHARACTERS = 1_500;
	private static final Pattern LEADING_ARTICLE_HEADINGS = Pattern.compile(
		"^(?:\\s*\uC81C\\s*\\d+\\s*\uC870(?:\uC758\\s*\\d+)?\\s*\\([^\\r\\n)]{1,100}\\)"
			+ "\\s*[.\u00B7:\uFF1A-]?\\s*)+"
	);
	private static final Pattern ARTICLE_HEADING_ONLY = Pattern.compile(
		"^(?:\\s*\uC81C\\s*\\d+\\s*\uC870(?:\uC758\\s*\\d+)?\\s*\\([^\\r\\n)]{1,100}\\)"
			+ "\\s*(?:\uB4F1)?\\s*[.\u00B7:\uFF1A-]?\\s*)+$"
	);

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
		if (initial == null) {
			return result(
				syntheticFailure(draft, "INITIAL_VERIFICATION_NULL"),
				false,
				false,
				"INITIAL_VERIFICATION_NULL",
				0
			);
		}
		if (!initial.insufficientEvidence()) {
			return result(initial, false, false, "INITIAL_OK", 0);
		}
		if (hasContradictionOrConflict(initial.claimResult())) {
			return result(initial, false, false, "CONTRADICTION_OR_CONFLICT", 0);
		}

		List<String> selectedAtoms = selectSupportedAlignedAtoms(
			question,
			normalize(draft),
			initial,
			safeGrounds
		);
		boolean generalScopeBinding = false;
		if (selectedAtoms.isEmpty()) {
			selectedAtoms = selectGeneralSoftwareScopeAtoms(question, normalize(draft), safeGrounds);
			generalScopeBinding = !selectedAtoms.isEmpty();
		}
		if (selectedAtoms.isEmpty()) {
			return result(initial, false, false, "NO_ALIGNED_SUPPORTED_ATOM", 0);
		}
		if (rewriter == null) {
			return result(initial, false, false, "REWRITER_UNAVAILABLE", selectedAtoms.size());
		}

		String rewritten;
		try {
			log.info("Grounded answer rewrite boundary={} selectedAtomCount={}",
				generalScopeBinding ? "conditional" : "legacy", selectedAtoms.size());
			rewritten = generalScopeBinding
				? rewriter.rewriteConditional(question, selectedAtoms, safeGrounds.stream()
					.filter(ground -> ground != null && normalize(ground.title()).contains("과업심의"))
					.map(LawAiAnswerGround::title).distinct().toList())
				: rewriter.rewrite(question, selectedAtoms);
		} catch (RuntimeException exception) {
			log.warn("Grounded answer rewrite failed exceptionType={} causeType={} httpStatus={}",
				exception.getClass().getSimpleName(),
				exception.getCause() == null ? "none" : exception.getCause().getClass().getSimpleName(),
				exception instanceof org.springframework.web.client.RestClientResponseException responseException
					? responseException.getStatusCode().value() : 0);
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
		String normalizedRewrite = normalize(rewritten);
		boolean generalScopeConditionDropped = generalScopeBinding
			&& (!normalizedRewrite.contains("국가기관")
				|| !(normalizedRewrite.contains("소프트웨어사업") || normalizedRewrite.contains("sw사업")));
		if (generalScopeConditionDropped) {
			return result(initial, true, false, "SOURCE_CONDITION_NOT_PRESERVED", selectedAtoms.size());
		}
		boolean droppedTargetScope = selectedAtoms.stream()
			.filter(atom -> !explicitTargetScope(atom).isBlank())
			.anyMatch(atom -> !normalizedRewrite.contains(normalize(atom)));
		if (reverified.insufficientEvidence() || droppedTargetScope) {
			AnswerVerificationService.Result atomFallback = verifyConfiguredLawPolicyAtomFallback(
				question,
				rewritten,
				selectedAtoms,
				safeGrounds
			);
			if (atomFallback != null && !atomFallback.insufficientEvidence()) {
				return result(
					atomFallback,
					true,
					true,
					"ATOM_FALLBACK_ACCEPTED",
					selectedAtoms.size()
				);
			}
			return result(droppedTargetScope ? initial : reverified, true, false,
				droppedTargetScope ? "SOURCE_SCOPE_NOT_PRESERVED" : "REWRITE_VERIFICATION_FAILED", selectedAtoms.size());
		}
		return result(reverified, true, true, "REWRITE_ACCEPTED", selectedAtoms.size());
	}

	private AnswerVerificationService.Result verifyConfiguredLawPolicyAtomFallback(
		String question,
		String rewritten,
		List<String> selectedAtoms,
		List<LawAiAnswerGround> grounds
	) {
		if (!allowsConfiguredLawPolicyGroundFallback(question, grounds)
			&& QuestionIntentProfile.from(question).configuredAnswerCoverageGroups().isEmpty()) {
			return null;
		}
		String atomFallback = String.join("\n", selectedAtoms);
		if (atomFallback.isBlank() || normalize(atomFallback).equals(normalize(rewritten))) {
			return null;
		}
		try {
			return verificationService.verify(question, atomFallback, grounds);
		} catch (RuntimeException exception) {
			return null;
		}
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
		String normalizedRejectedDraft,
		AnswerVerificationService.Result initial,
		List<LawAiAnswerGround> grounds
	) {
		if (grounds.isEmpty()) {
			return List.of();
		}
		List<CandidateAtom> fallbackCandidates = fallbackCandidateAtoms(grounds);
		List<String> configuredCoverageAtoms = selectConfiguredAnswerCoverageAtoms(
			question,
			normalizedRejectedDraft,
			configuredCoverageCandidateAtoms(grounds, fallbackCandidates),
			grounds
		);
		if (!configuredCoverageAtoms.isEmpty()) {
			return configuredCoverageAtoms;
		}
		boolean configuredLawPolicyFallback = allowsConfiguredLawPolicyGroundFallback(question, grounds);
		int selectionLimit = configuredLawPolicyFallback
			? Math.min(MAX_SELECTED_ATOMS, grounds.size())
			: MAX_SELECTED_ATOMS;
		List<String> supportedAtoms = selectVerifiedAtoms(
			question,
			normalizedRejectedDraft,
			supportedCandidateAtoms(initial, grounds),
			grounds,
			true,
			selectionLimit,
			configuredLawPolicyFallback
		);
		if (!supportedAtoms.isEmpty()) {
			return supportedAtoms;
		}
		List<String> alignedFallback = selectVerifiedAtoms(
			question,
			normalizedRejectedDraft,
			fallbackCandidates,
			grounds,
			true,
			selectionLimit,
			configuredLawPolicyFallback
		);
		if (!alignedFallback.isEmpty()) {
			return alignedFallback;
		}
		if (!configuredLawPolicyFallback) {
			return List.of();
		}
		return selectVerifiedAtoms(
			question,
			normalizedRejectedDraft,
			fallbackCandidates,
			grounds,
			false,
			selectionLimit,
			configuredLawPolicyFallback
		);
	}

	private List<String> selectVerifiedAtoms(
		String question,
		String normalizedRejectedDraft,
		List<CandidateAtom> candidates,
		List<LawAiAnswerGround> grounds
	) {
		return selectVerifiedAtoms(
			question,
			normalizedRejectedDraft,
			candidates,
			grounds,
			true,
			MAX_SELECTED_ATOMS,
			false
		);
	}

	private List<String> selectConfiguredAnswerCoverageAtoms(
		String question,
		String normalizedRejectedDraft,
		List<CandidateAtom> candidates,
		List<LawAiAnswerGround> grounds
	) {
		List<List<String>> coverageGroups = QuestionIntentProfile.from(question)
			.configuredAnswerCoverageGroups()
			.stream()
			.map(group -> group.stream()
				.map(this::normalize)
				.filter(value -> !value.isBlank())
				.toList())
			.filter(group -> !group.isEmpty())
			.toList();
		if (coverageGroups.isEmpty()) {
			return List.of();
		}
		List<CandidateAtom> rankedCandidates = candidates.stream()
			.sorted(
				Comparator.comparingInt(CandidateAtom::groundIndex)
					.thenComparing(candidate -> explicitTargetScope(clean(candidate.text())).isBlank())
					.thenComparingInt(CandidateAtom::sourceOrder)
			)
			.toList();

		LinkedHashMap<String, String> selectedByKey = new LinkedHashMap<>();
		int totalCharacters = 0;
		for (List<String> group : coverageGroups) {
			boolean covered = false;
			for (String selected : selectedByKey.values()) {
				String normalizedSelected = normalize(selected);
				if (group.stream().anyMatch(normalizedSelected::contains)) {
					covered = true;
					break;
				}
			}
			if (covered) {
				continue;
			}
			for (CandidateAtom candidate : rankedCandidates) {
				String atom = clean(candidate.text());
				String normalizedAtom = normalize(atom);
				if (atom.isBlank()
					|| atom.length() > MAX_ATOM_CHARACTERS
					|| ARTICLE_HEADING_ONLY.matcher(atom).matches()
					|| reusesRejectedDraft(normalizedAtom, normalizedRejectedDraft)
					|| group.stream().noneMatch(normalizedAtom::contains)) {
					continue;
				}
				AnswerVerificationService.Result verification;
				try {
					verification = verificationService.verify(question, atom, grounds);
				} catch (RuntimeException exception) {
					continue;
				}
				if (!isFullyClaimSupported(verification)) {
					continue;
				}
				String verifiedAtom = clean(verification.claimResult().verifiedAnswer());
				String key = normalize(verifiedAtom);
				if (verifiedAtom.isBlank()
					|| verifiedAtom.length() > MAX_ATOM_CHARACTERS
					|| key.isBlank()) {
					continue;
				}
				if (!selectedByKey.containsKey(key)) {
					if (selectedByKey.size() >= MAX_SELECTED_ATOMS
						|| totalCharacters + verifiedAtom.length() > MAX_TOTAL_ATOM_CHARACTERS) {
						return List.of();
					}
					selectedByKey.put(key, verifiedAtom);
					totalCharacters += verifiedAtom.length();
				}
				covered = true;
				break;
			}
			if (!covered) {
				return List.of();
			}
		}

		Set<String> selectedScopes = new LinkedHashSet<>();
		selectedByKey.values().stream().map(this::explicitTargetScope)
			.filter(scope -> !scope.isBlank()).forEach(selectedScopes::add);
		if (!selectedScopes.isEmpty()) {
			for (CandidateAtom candidate : rankedCandidates) {
				String atom = clean(candidate.text());
				String scope = explicitTargetScope(atom);
				String normalizedAtom = normalize(atom);
				if (scope.isBlank() || selectedScopes.contains(scope)
					|| atom.length() > MAX_ATOM_CHARACTERS
					|| reusesRejectedDraft(normalizedAtom, normalizedRejectedDraft)
					|| coverageGroups.stream().flatMap(List::stream).noneMatch(normalizedAtom::contains)) {
					continue;
				}
				try {
					AnswerVerificationService.Result verified = verificationService.verify(question, atom, grounds);
					if (!isFullyClaimSupported(verified)) { continue; }
					String value = clean(verified.claimResult().verifiedAnswer());
					if (!scope.equals(explicitTargetScope(value)) || value.length() > MAX_ATOM_CHARACTERS) { continue; }
					if (selectedByKey.size() >= MAX_SELECTED_ATOMS
						|| totalCharacters + value.length() > MAX_TOTAL_ATOM_CHARACTERS) { break; }
					selectedByKey.put(normalize(value), value);
					selectedScopes.add(scope);
					totalCharacters += value.length();
				} catch (RuntimeException exception) {
					continue;
				}
			}
		}
		List<String> selected = List.copyOf(selectedByKey.values());
		if (selected.isEmpty()) {
			return List.of();
		}
		try {
			AnswerVerificationService.Result combined = verificationService.verify(
				question,
				String.join("\n", selected),
				grounds
			);
			return isFullySupportedAndAligned(combined) ? selected : List.of();
		} catch (RuntimeException exception) {
			return List.of();
		}
	}

	private String explicitTargetScope(String atom) {
		int separator = atom == null ? -1 : atom.indexOf(':');
		if (separator <= 0 || separator > 60) { return ""; }
		String heading = atom.substring(0, separator).trim();
		return heading.endsWith(" 대상") ? normalize(heading) : "";
	}

	private List<String> selectVerifiedAtoms(
		String question,
		String normalizedRejectedDraft,
		List<CandidateAtom> candidates,
		List<LawAiAnswerGround> grounds,
		boolean requireQuestionAlignment,
		int selectionLimit,
		boolean oneAtomPerGround
	) {
		LinkedHashMap<String, String> selectedByKey = new LinkedHashMap<>();
		Set<Integer> selectedGroundIndexes = new LinkedHashSet<>();
		int totalCharacters = 0;
		for (CandidateAtom candidate : candidates) {
			if (selectedByKey.size() >= selectionLimit) {
				break;
			}
			if (oneAtomPerGround && selectedGroundIndexes.contains(candidate.groundIndex())) {
				continue;
			}
			String atom = clean(candidate.text());
			if (oneAtomPerGround) {
				atom = stripLeadingArticleHeadings(atom);
			}
			if (atom.isBlank() || atom.length() > MAX_ATOM_CHARACTERS) {
				continue;
			}
			String normalizedAtom = normalize(atom);
			if (reusesRejectedDraft(normalizedAtom, normalizedRejectedDraft)) {
				continue;
			}
			AnswerVerificationService.Result verification;
			try {
				verification = verificationService.verify(question, atom, grounds);
			} catch (RuntimeException exception) {
				continue;
			}
			if (requireQuestionAlignment
				? !isFullySupportedAndAligned(verification)
				: !isFullyClaimSupported(verification)) {
				continue;
			}
			String verifiedAtom = clean(
				requireQuestionAlignment
					? verification.verifiedAnswer()
					: verification.claimResult().verifiedAnswer()
			);
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
			selectedGroundIndexes.add(candidate.groundIndex());
			totalCharacters += verifiedAtom.length();
		}
		return List.copyOf(selectedByKey.values());
	}

	private String stripLeadingArticleHeadings(String atom) {
		return clean(LEADING_ARTICLE_HEADINGS.matcher(clean(atom)).replaceFirst(""));
	}

	private boolean allowsConfiguredLawPolicyGroundFallback(
		String question,
		List<LawAiAnswerGround> grounds
	) {
		QuestionIntentProfile profile = QuestionIntentProfile.from(question);
		if (profile.matchedPolicyIds().isEmpty()
			|| profile.directEvidenceGroups().isEmpty()
			|| profile.preferredTargets().isEmpty()
			|| !profile.preferredTargets().stream().allMatch(this::isLawTarget)) {
			return false;
		}
		return grounds != null
			&& !grounds.isEmpty()
			&& grounds.stream().allMatch(ground ->
				ground != null
					&& isLawTarget(ground.target())
					&& "direct".equalsIgnoreCase(String.valueOf(ground.evidenceRole()))
			);
	}

	private boolean isLawTarget(String target) {
		return "law".equals(target) || "admrul".equals(target);
	}

	private boolean isGeneralSoftwareScope(String text) {
		String normalized = normalize(text);
		return normalized.startsWith("적용대상사업")
			&& normalized.contains("국가기관") && normalized.contains("발주")
			&& (normalized.contains("모든sw사업") || normalized.contains("모든소프트웨어사업"));
	}

	private List<String> selectGeneralSoftwareScopeAtoms(String question, String rejectedDraft,
		List<LawAiAnswerGround> grounds) {
		if (!normalize(question).contains("과업심의")) { return List.of(); }
		// Source criteria may seed a conditional rewrite, but are never returned
		// as an answer without the original question's full verification.
		List<CandidateAtom> candidates = fallbackCandidateAtoms(grounds).stream()
			.filter(candidate -> normalize(grounds.get(candidate.groundIndex()).title()).contains("과업심의"))
			.filter(candidate -> isGeneralSoftwareScope(candidate.text())
				|| (isGeneralSoftwareScope(matchedChildText(grounds.get(candidate.groundIndex())))
					&& normalize(candidate.text()).startsWith("국가기관")
					&& normalize(candidate.text()).contains("발주")
					&& (normalize(candidate.text()).contains("모든sw사업")
						|| normalize(candidate.text()).contains("모든소프트웨어사업"))))
			.toList();
		List<String> scopeAtoms = selectVerifiedAtoms(question, rejectedDraft, candidates, grounds,
			false, MAX_SELECTED_ATOMS, false);
		if (scopeAtoms.isEmpty()) { return scopeAtoms; }
		List<CandidateAtom> procedureCandidates = new ArrayList<>();
		for (int index = 0; index < grounds.size(); index++) {
			String duty = EvidenceJudge.softwareConfirmationReviewDutyText(matchedChildText(grounds.get(index)));
			if (!duty.isBlank()) { procedureCandidates.add(new CandidateAtom(index, 0, duty)); }
		}
		List<String> procedures = selectVerifiedAtoms(question, rejectedDraft, procedureCandidates, grounds,
			false, MAX_SELECTED_ATOMS - scopeAtoms.size(), false);
		List<String> selected = new ArrayList<>(scopeAtoms);
		int totalCharacters = scopeAtoms.stream().mapToInt(String::length).sum();
		for (String procedure : procedures) {
			if (!selected.contains(procedure) && totalCharacters + procedure.length() <= MAX_TOTAL_ATOM_CHARACTERS) {
				selected.add(procedure); totalCharacters += procedure.length();
			}
		}
		return List.copyOf(selected);
	}

	private boolean reusesRejectedDraft(String normalizedAtom, String normalizedRejectedDraft) {
		return !normalizedAtom.isBlank()
			&& !normalizedRejectedDraft.isBlank()
			&& (normalizedAtom.equals(normalizedRejectedDraft)
				|| normalizedAtom.contains(normalizedRejectedDraft));
	}

	private boolean answerVerificationServiceInsufficient(String answer) {
		return verificationService.isInsufficientEvidenceAnswer(answer);
	}

	private boolean isFullySupportedAndAligned(AnswerVerificationService.Result verification) {
		return isFullyClaimSupported(verification)
			&& verification.alignmentResult() != null
			&& verification.alignmentResult().evaluated()
			&& verification.alignmentResult().aligned();
	}

	private boolean isFullyClaimSupported(AnswerVerificationService.Result verification) {
		if (verification == null) {
			return false;
		}
		if (verification.insufficientEvidence()) {
			if (verification.claimResult() == null || verification.claimResult().insufficientEvidence()) {
				return false;
			}
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
		return true;
	}

	private List<CandidateAtom> supportedCandidateAtoms(
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
		return deduplicate(supported);
	}

	private List<CandidateAtom> fallbackCandidateAtoms(List<LawAiAnswerGround> grounds) {
		List<CandidateAtom> fallback = new ArrayList<>();
		for (int groundIndex = 0; groundIndex < grounds.size(); groundIndex++) {
			LawAiAnswerGround ground = grounds.get(groundIndex);
			List<String> atoms = atomizer.atomizeSource(matchedChildText(ground), ground.chunkTitle());
			for (int atomIndex = 0; atomIndex < atoms.size(); atomIndex++) {
				fallback.add(new CandidateAtom(groundIndex, atomIndex, atoms.get(atomIndex)));
			}
		}
		fallback.sort(Comparator
			.comparingInt(CandidateAtom::sourceOrder)
			.thenComparingInt(CandidateAtom::groundIndex));
		return deduplicate(fallback);
	}

	private List<CandidateAtom> configuredCoverageCandidateAtoms(
		List<LawAiAnswerGround> grounds,
		List<CandidateAtom> matchedChildCandidates
	) {
		List<CandidateAtom> candidates = new ArrayList<>(matchedChildCandidates);
		for (int groundIndex = 0; groundIndex < grounds.size(); groundIndex++) {
			LawAiAnswerGround ground = grounds.get(groundIndex);
			if (ground == null
				|| !"direct".equalsIgnoreCase(String.valueOf(ground.evidenceRole()))
				|| !"parent_context_expanded".equals(ground.contextPolicy())
				|| ground.parentContextText() == null
				|| ground.parentContextText().isBlank()) {
				continue;
			}
			List<String> parentAtoms = atomize(ground.parentContextText());
			for (int atomIndex = 0; atomIndex < parentAtoms.size(); atomIndex++) {
				candidates.add(new CandidateAtom(
					groundIndex,
					atomIndex,
					parentAtoms.get(atomIndex)
				));
			}
		}
		return deduplicate(candidates);
	}

	private List<CandidateAtom> deduplicate(List<CandidateAtom> candidates) {
		LinkedHashMap<String, CandidateAtom> deduplicated = new LinkedHashMap<>();
		for (CandidateAtom candidate : candidates) {
			String key = normalize(candidate.text());
			if (!key.isBlank()) {
				deduplicated.putIfAbsent(key, candidate);
			}
		}
		return List.copyOf(deduplicated.values());
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
