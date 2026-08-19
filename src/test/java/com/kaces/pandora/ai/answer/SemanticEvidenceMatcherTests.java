package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;

import com.kaces.pandora.semantic.config.LawAiVerificationProperties;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SemanticEvidenceMatcherTests {

	private final KoreanEvidenceAtomParser parser = new KoreanEvidenceAtomParser();
	private final SemanticEvidenceMatcher matcher = new SemanticEvidenceMatcher();

	@Test
	void supportsSamePropositionAndContradictsOnlyAlignedOppositePolarity() {
		EvidenceAtom claim = parser.parse("계약상대자는 완료 후 통지해야 한다.");
		EvidenceAtom same = parser.parse("계약상대자는 완료 후 통지해야 한다.");
		EvidenceAtom opposite = parser.parse("계약상대자는 완료 후 통지하지 않는다.");

		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(same)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(opposite)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.CONTRADICTED);
	}

	@Test
	void reportsConflictWhenAlignedPositiveAndNegativeEvidenceBothExist() {
		EvidenceAtom claim = parser.parse("계약상대자는 통지해야 한다.");
		SemanticEvidenceMatcher.SemanticMatch result = matcher.match(
			claim,
			SemanticEvidenceMatcher.EvidenceIndex.of(
				parser.parse("계약상대자는 통지해야 한다."),
				parser.parse("계약상대자는 통지하지 않는다.")
			)
		);

		assertThat(result.status()).isEqualTo(ClaimEvidenceMatcher.Status.CONFLICTED);
	}

	@Test
	void failsClosedBeforePolarityForDifferentSubjectOrMissingCondition() {
		EvidenceAtom claim = parser.parse("계약상대자는 완료 후 통지해야 한다.");
		EvidenceAtom otherSubjectNegative = parser.parse("발주기관은 완료 후 통지하지 않는다.");
		EvidenceAtom missingCondition = parser.parse("계약상대자는 통지해야 한다.");

		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(otherSubjectNegative)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(claim, SemanticEvidenceMatcher.EvidenceIndex.of(missingCondition)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void failsClosedForAmbiguousParseAndNumericMismatch() {
		EvidenceAtom ambiguous = parser.parse("신고하지 않아도 되지 않는 것은 아니다.");
		EvidenceAtom deadline = parser.parse("신청인은 30일 이내에 신고해야 한다.");
		EvidenceAtom wrongDeadline = parser.parse("신청인은 60일 이내에 신고해야 한다.");

		assertThat(matcher.match(ambiguous, SemanticEvidenceMatcher.EvidenceIndex.of(ambiguous)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
		assertThat(matcher.match(deadline, SemanticEvidenceMatcher.EvidenceIndex.of(wrongDeadline)).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void shadowVerifierRecordsDisagreementWithoutChangingControlAnswer() {
		ClaimEvidenceMatcher control = new ClaimEvidenceMatcher();
		ClaimVerifier verifier = new ClaimVerifier(
			control,
			matcher,
			parser,
			new LawAiVerificationProperties(true, false, 20)
		);
		String answer = "계약상대자는 완료 후 통지해야 한다.";
		List<LawAiAnswerGround> grounds = List.of(ground("계약상대자는 통지해야 한다."));

		ClaimVerifier.VerificationResult shadow = verifier.verifyDetailed(answer, grounds);
		ClaimVerifier.VerificationResult controlOnly = new ClaimVerifier(control).verifyDetailed(answer, grounds);

		assertThat(shadow.verifiedAnswer()).isEqualTo(controlOnly.verifiedAnswer());
		assertThat(shadow.semanticShadowResults()).hasSize(1);
		assertThat(shadow.semanticShadowResults().get(0).semanticStatus())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	@Test
	void authoritativeModeUsesSemanticResultEvenWhenShadowCollectionIsDisabled() {
		ClaimVerifier verifier = new ClaimVerifier(
			new ClaimEvidenceMatcher(),
			matcher,
			parser,
			new LawAiVerificationProperties(false, true, 20)
		);

		ClaimVerifier.VerificationResult result = verifier.verifyDetailed(
			"계약상대자는 완료 후 통지해야 한다.",
			List.of(ground("계약상대자는 통지해야 한다."))
		);

		assertThat(result.unsupportedClaims()).containsExactly("계약상대자는 완료 후 통지해야 한다.");
		assertThat(result.semanticShadowResults()).isEmpty();
	}

	@Test
	void requiredTemplateSlotsCannotMatchWhenTheQuestionProvidedNoSlotValue() {
		PropositionTemplate incomplete = new PropositionTemplate(
			Set.of(), Set.of(), Set.of(), Set.of(), Set.of(),
			Set.of(PropositionTemplate.RequiredSlot.ACTION)
		);

		assertThat(matcher.match(incomplete, parser.parse("계약상대자는 통지해야 한다.")).status())
			.isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
	}

	private LawAiAnswerGround ground(String text) {
		return new LawAiAnswerGround(
			1, 10L, 20L, "law", "법령", "기관", "법률", "2026-01-01", "CURRENT",
			"1", "제1조", null, text, "source", "url", 1.0d
		);
	}
}
