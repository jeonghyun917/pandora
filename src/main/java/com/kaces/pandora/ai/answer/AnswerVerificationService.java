package com.kaces.pandora.ai.answer;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class AnswerVerificationService {

	private final AnswerGuard answerGuard;
	private final ClaimVerifier claimVerifier;

	public AnswerVerificationService(AnswerGuard answerGuard, ClaimVerifier claimVerifier) {
		this.answerGuard = answerGuard;
		this.claimVerifier = claimVerifier;
	}

	public Result verify(String answer, List<LawAiAnswerGround> grounds) {
		String guardedAnswer = answerGuard.guard(answer, grounds);
		ClaimVerifier.VerificationResult claimResult = claimVerifier.verifyDetailed(guardedAnswer, grounds);
		return new Result(guardedAnswer, claimResult);
	}

	boolean isInsufficientEvidenceAnswer(String answer) {
		return claimVerifier.isInsufficientEvidenceAnswer(answer);
	}

	public record Result(
		String guardedAnswer,
		ClaimVerifier.VerificationResult claimResult
	) {
		public String verifiedAnswer() {
			return claimResult.verifiedAnswer();
		}

		public boolean insufficientEvidence() {
			return claimResult.insufficientEvidence();
		}

		public boolean changed() {
			return !verifiedAnswer().equals(guardedAnswer)
				|| claimResult.changed();
		}
	}
}
