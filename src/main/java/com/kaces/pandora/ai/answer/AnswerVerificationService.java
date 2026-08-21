package com.kaces.pandora.ai.answer;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class AnswerVerificationService {

	private final AnswerGuard answerGuard;
	private final ClaimVerifier claimVerifier;
	private final AnswerQuestionAlignmentVerifier alignmentVerifier;

	public AnswerVerificationService(AnswerGuard answerGuard, ClaimVerifier claimVerifier) {
		this(answerGuard, claimVerifier, new AnswerQuestionAlignmentVerifier());
	}

	@Autowired
	public AnswerVerificationService(
		AnswerGuard answerGuard,
		ClaimVerifier claimVerifier,
		AnswerQuestionAlignmentVerifier alignmentVerifier
	) {
		this.answerGuard = answerGuard;
		this.claimVerifier = claimVerifier;
		this.alignmentVerifier = alignmentVerifier;
	}

	public Result verify(String answer, List<LawAiAnswerGround> grounds) {
		String guardedAnswer = answerGuard.guard(answer, grounds);
		ClaimVerifier.VerificationResult claimResult = claimVerifier.verifyDetailed(guardedAnswer, grounds);
		return new Result(
			guardedAnswer,
			claimResult,
			AnswerQuestionAlignmentVerifier.AlignmentResult.claimOnly()
		);
	}

	public Result verify(String question, String answer, List<LawAiAnswerGround> grounds) {
		String guardedAnswer = answerGuard.guard(answer, grounds);
		ClaimVerifier.VerificationResult claimResult = claimVerifier.verifyDetailed(guardedAnswer, grounds);
		AnswerQuestionAlignmentVerifier.AlignmentResult alignmentResult = claimResult.insufficientEvidence()
			? AnswerQuestionAlignmentVerifier.AlignmentResult.claimInsufficient()
			: alignmentVerifier.verify(question, claimResult, grounds);
		return new Result(guardedAnswer, claimResult, alignmentResult);
	}

	boolean isInsufficientEvidenceAnswer(String answer) {
		return claimVerifier.isInsufficientEvidenceAnswer(answer);
	}

	public record Result(
		String guardedAnswer,
		ClaimVerifier.VerificationResult claimResult,
		AnswerQuestionAlignmentVerifier.AlignmentResult alignmentResult
	) {
		public Result(String guardedAnswer, ClaimVerifier.VerificationResult claimResult) {
			this(
				guardedAnswer,
				claimResult,
				AnswerQuestionAlignmentVerifier.AlignmentResult.claimOnly()
			);
		}

		public String verifiedAnswer() {
			if (alignmentResult.evaluated() && !alignmentResult.aligned()) {
				return ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE;
			}
			return claimResult.verifiedAnswer();
		}

		public boolean insufficientEvidence() {
			return claimResult.insufficientEvidence()
				|| (alignmentResult.evaluated() && !alignmentResult.aligned());
		}

		public boolean changed() {
			return !verifiedAnswer().equals(guardedAnswer)
				|| claimResult.changed();
		}
	}
}
