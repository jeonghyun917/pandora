package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class AnswerVerificationServiceTests {

	@Test
	void questionAwareVerificationFailsClosedButRetainsClaimAndAlignmentDiagnostics() {
		AnswerGuard answerGuard = mock(AnswerGuard.class);
		ClaimVerifier claimVerifier = mock(ClaimVerifier.class);
		AnswerQuestionAlignmentVerifier alignmentVerifier = mock(AnswerQuestionAlignmentVerifier.class);
		AnswerVerificationService service = new AnswerVerificationService(
			answerGuard,
			claimVerifier,
			alignmentVerifier
		);
		ClaimVerifier.VerificationResult claimResult = supportedClaimResult();
		AnswerQuestionAlignmentVerifier.AlignmentResult alignmentResult = new AnswerQuestionAlignmentVerifier.AlignmentResult(
			true,
			false,
			"MISSING_SUBJECT",
			List.of("SUBJECT"),
			""
		);
		when(answerGuard.guard("raw answer", List.of())).thenReturn("guarded answer");
		when(claimVerifier.verifyDetailed("guarded answer", List.of())).thenReturn(claimResult);
		when(alignmentVerifier.verify("question", claimResult, List.of())).thenReturn(alignmentResult);

		AnswerVerificationService.Result result = service.verify("question", "raw answer", List.of());

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.claimResult()).isSameAs(claimResult);
		assertThat(result.alignmentResult()).isSameAs(alignmentResult);
		assertThat(result.changed()).isTrue();
	}

	@Test
	void questionAwareVerificationDoesNotRunAlignmentAfterClaimFailure() {
		AnswerGuard answerGuard = mock(AnswerGuard.class);
		ClaimVerifier claimVerifier = mock(ClaimVerifier.class);
		AnswerQuestionAlignmentVerifier alignmentVerifier = mock(AnswerQuestionAlignmentVerifier.class);
		AnswerVerificationService service = new AnswerVerificationService(
			answerGuard,
			claimVerifier,
			alignmentVerifier
		);
		ClaimVerifier.VerificationResult claimResult = insufficientClaimResult();
		when(answerGuard.guard("raw answer", List.of())).thenReturn("guarded answer");
		when(claimVerifier.verifyDetailed("guarded answer", List.of())).thenReturn(claimResult);

		AnswerVerificationService.Result result = service.verify("question", "raw answer", List.of());

		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.alignmentResult().reasonCode()).isEqualTo("NOT_EVALUATED_CLAIM_INSUFFICIENT");
		verify(alignmentVerifier, never()).verify(
			org.mockito.ArgumentMatchers.anyString(),
			anyListResult(),
			anyList()
		);
	}

	@Test
	void twoArgumentOverloadRemainsClaimOnlyCompatible() {
		AnswerGuard answerGuard = mock(AnswerGuard.class);
		ClaimVerifier claimVerifier = mock(ClaimVerifier.class);
		AnswerQuestionAlignmentVerifier alignmentVerifier = mock(AnswerQuestionAlignmentVerifier.class);
		AnswerVerificationService service = new AnswerVerificationService(
			answerGuard,
			claimVerifier,
			alignmentVerifier
		);
		ClaimVerifier.VerificationResult claimResult = supportedClaimResult();
		when(answerGuard.guard("raw answer", List.of())).thenReturn("guarded answer");
		when(claimVerifier.verifyDetailed("guarded answer", List.of())).thenReturn(claimResult);

		AnswerVerificationService.Result result = service.verify("raw answer", List.of());

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(claimResult.verifiedAnswer());
		assertThat(result.alignmentResult().reasonCode()).isEqualTo("NOT_EVALUATED_CLAIM_ONLY");
		verify(alignmentVerifier, never()).verify(
			org.mockito.ArgumentMatchers.anyString(),
			anyListResult(),
			anyList()
		);
	}

	@Test
	void questionAwareVerificationRejectsSupportedCheckingFrameAsNonresponsive() {
		AnswerVerificationService service = new AnswerVerificationService(
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerQuestionAlignmentVerifier()
		);
		LawAiAnswerGround ground = new LawAiAnswerGround(
			1,
			1,
			1,
			"official_doc",
			"공공소프트웨어사업 과업심의 안내",
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page 1",
			"적용 대상 사업",
			1,
			"공공소프트웨어사업은 과업심의 대상입니다.",
			null,
			null,
			0.9
		);

		AnswerVerificationService.Result result = service.verify(
			"공공소프트웨어사업은 과업심의 대상인가?",
			"공공소프트웨어사업은 과업심의 대상인지 확인합니다.",
			List.of(ground)
		);

		assertThat(result.claimResult().evidenceLinks())
			.extracting(ClaimVerifier.ClaimEvidenceLink::relation)
			.contains("SUPPORTED");
		assertThat(result.alignmentResult().aligned()).isFalse();
		assertThat(result.alignmentResult().missingGroups()).contains("DIRECT_CONCLUSION");
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void questionAwareVerificationAcceptsAnExactDocumentTitleIdentityAnswer() {
		AnswerVerificationService service = new AnswerVerificationService(
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerQuestionAlignmentVerifier()
		);
		LawAiAnswerGround ground = new LawAiAnswerGround(
			1,
			1,
			1,
			"official_doc",
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page 1",
			"적용 대상 사업",
			1,
			"국가기관 등이 발주하는 모든 SW사업은 과업심의 대상이다.",
			null,
			null,
			0.9
		);
		for (String answer : List.of(
			"찾으시는 문서는 공공소프트웨어사업 과업심의 가이드(2022. 12.)입니다.",
			"요약하면, 요청하신 문서는 제목이 "
				+ "\"공공소프트웨어사업 과업심의 가이드(2022. 12.)\"인 공식 가이드 문서입니다."
		)) {
			AnswerVerificationService.Result result = service.verify(
				"공공소프트웨어사업 과업심의의 가이드 문서 찾아줘",
				answer,
				List.of(ground)
			);

			assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
			assertThat(result.verifiedAnswer()).isEqualTo(answer);
			assertThat(result.alignmentResult().aligned()).isTrue();
		}
	}

	@Test
	void documentIdentityAlignmentRejectsASelectedTitleThatMissesTheQuestionAnchors() {
		AnswerVerificationService service = new AnswerVerificationService(
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerQuestionAlignmentVerifier()
		);
		LawAiAnswerGround wrongGround = new LawAiAnswerGround(
			1,
			1,
			1,
			"official_doc",
			"개인정보 처리 가이드",
			"기관",
			"공식 가이드 문서",
			null,
			null,
			"page 1",
			"본문",
			1,
			"본문 근거",
			null,
			null,
			0.9
		);

		AnswerVerificationService.Result result = service.verify(
			"공공소프트웨어사업 과업심의의 가이드 문서 찾아줘",
			"찾으시는 문서는 개인정보 처리 가이드입니다.",
			List.of(wrongGround)
		);

		assertThat(result.claimResult().insufficientEvidence()).isFalse();
		assertThat(result.alignmentResult().aligned()).isFalse();
		assertThat(result.insufficientEvidence()).isTrue();
	}

	private ClaimVerifier.VerificationResult supportedClaimResult() {
		return new ClaimVerifier.VerificationResult(
			"직접 답변입니다.",
			false,
			false,
			List.of(),
			List.of(),
			List.of(),
			List.of(new ClaimVerifier.ClaimEvidenceLink(
				"직접 답변입니다.",
				"SUPPORTED",
				1,
				"직접 답변입니다.",
				2,
				1.0,
				1.0
			)),
			1,
			1
		);
	}

	private ClaimVerifier.VerificationResult insufficientClaimResult() {
		return new ClaimVerifier.VerificationResult(
			ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE,
			true,
			true,
			List.of("unsupported"),
			List.of(),
			List.of(),
			List.of(),
			1,
			0
		);
	}

	@SuppressWarnings("unchecked")
	private ClaimVerifier.VerificationResult anyListResult() {
		return org.mockito.ArgumentMatchers.any(ClaimVerifier.VerificationResult.class);
	}
}
