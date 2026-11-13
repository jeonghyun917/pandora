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
	void correctionPermissionBindsAuthorityRecipientAndTrigger() {
		var service = new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier());
		String source = "협의기관의 장은 사전협의 미이행, 사전협의 신청 미등록 등에 대하여 신청기관에 시정을 요구할 수 있으며, "
			+ "신청기관의 장은 이를 반영하고 이행결과를 협의기관의 장에게 제출하여야 한다.";
		var ground = new LawAiAnswerGround(1, 1, 1, "admrul", "전자정부 성과관리 지침", null, null,
			null, "CURRENT", "제18조", "사전협의 이행관리", null, source, null, null, 0);
		var cases = java.util.Map.of(
			"사전협의를 이행하지 않은 신청기관에 협의기관의 장이 시정을 요구할 수 있나요?", true,
			"사전협의 미이행에 대하여 협의기관의 장은 신청기관에 시정을 요구할 수 있나요?", true,
			"사전협의 미이행에 대하여 신청기관의 장은 협의기관에 시정을 요구할 수 있나요?", false,
			"사전협의 이행에 대하여 협의기관의 장은 신청기관에 시정을 요구할 수 있나요?", false);
		org.assertj.core.api.SoftAssertions.assertSoftly(check -> cases.forEach((question, aligned) ->
			check.assertThat(!service.verify(question, source, List.of(ground)).insufficientEvidence())
				.as(question).isEqualTo(aligned)));
	}

	@Test
	void restoresOnlyListMarkerWhitespaceFromOneExactLegalParagraph() {
		var service = new AnswerVerificationService(new AnswerGuard(), new ClaimVerifier());
		String source = "③ 제안요청서에는 다음 각 호의 사항을 명시하여야 한다.1. 계약조건2. 평가요소와 평가방법";
		var ground = new LawAiAnswerGround(1, 1, 1, "admrul", "계약 업무 예규", null, null,
			null, "CURRENT", "제5조", "제안요청서", null, source, null, null, 0);
		String question = "제안요청서의 평가요소와 평가방법 기재 의무를 알려주세요.";
		String formatted = source.replace("1. ", "\n1. ").replace("2. ", " 2. ");
		assertThat(service.verify(question, formatted, List.of(ground)).insufficientEvidence()).isFalse();
		for (String changed : List.of(formatted.replace("평가방법", "배점표"),
			formatted.replace("2.", "3."), formatted + "\n3. 별도 배점표")) {
			assertThat(service.verify(question, changed, List.of(ground)).insufficientEvidence()).isTrue();
		}
	}

    @Test void boundVerifierUsesTheSameCapturedRoutingWithoutThreadLocalState() {
        var claims=mock(ClaimVerifier.class);
        var routing=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),null,null);
        var expected=new ClaimVerifier().verifyDetailed("",List.of());
        when(claims.verifyDetailed(org.mockito.ArgumentMatchers.anyString(),anyList(),org.mockito.ArgumentMatchers.eq(routing))).thenReturn(expected);
        var bound=new AnswerVerificationService(new AnswerGuard(),claims).withRouting(routing);
        assertThat(bound.verify("문장",List.of()).claimResult()).isSameAs(expected);
        verify(claims).verifyDetailed(org.mockito.ArgumentMatchers.anyString(),anyList(),org.mockito.ArgumentMatchers.eq(routing));
        verify(claims,never()).verifyDetailed(org.mockito.ArgumentMatchers.anyString(),anyList());
    }

	@Test
	void specificProcurementRecommendationKeepsItsQuestionScopeBoundary() {
		var service = new AnswerVerificationService(
			new AnswerGuard(), new ClaimVerifier(), new AnswerQuestionAlignmentVerifier());
		String source = "상기 특별한 경우를 제외한 지명경쟁 또는 수의계약의 경우에는 특정규격 명시 금지 관련 법·제도 개선권고를 받을 수 있으므로, 지명경쟁 또는 수의계약에서 특정규격을 명시하는 사유를 입찰서류에 명시하거나 권고기관에 수의계약 적용 근거자료를 제시하여 불필요한 행정소요를 예방한다.";
		var ground = new LawAiAnswerGround(1, 1, 1, "official_doc", "SW사업 법령준수 권고", "기관", "공식 문서",
			null, null, "page 2", "특정규격 명시 금지", 2, source, null, null, 0.9);
		var result = service.verify("정보화시스템 법제도 준수안하면 어떤 불이익?", source, List.of(ground));
		assertThat(result.claimResult().insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.alignmentResult().aligned()).isFalse();
		assertThat(result.alignmentResult().missingGroups()).contains("SUBJECT", "RELATION", "CONDITION");
		assertThat(result.insufficientEvidence()).isTrue();
	}

	@Test
	void recommendationRecheckDoesNotEstablishGeneralLegalNoncompliancePenalty() {
		AnswerVerificationService service = new AnswerVerificationService(
			new AnswerGuard(), new ClaimVerifier(), new AnswerQuestionAlignmentVerifier());
		String source = "귀 기관의 SW사업 공고에 대하여, SW관련 법령의 준수를 권고 드립니다. "
			+ "향후 입찰공고(RFP)시 반영여부를 재확인하여 미준수 항목 권고(2차발송) 예정";
		LawAiAnswerGround ground = new LawAiAnswerGround(
			1, 1, 1, "official_doc", "SW사업 법령준수 권고", "기관", "공식 문서",
			null, null, "page 1", "협조요청", 1, source, null, null, 0.9);
		var exact = service.verify(source, List.of(ground));
		assertThat(exact.claimResult().insufficientEvidence()).as(exact.toString()).isFalse();
		var generalized = service.verify(
			"정보화시스템 법제도 준수안하면 어떤 불이익?",
			"정보화시스템 관련 법령을 준수하지 않으면 기관은 개선권고를 받고 재통보받습니다.",
			List.of(ground));
		assertThat(generalized.insufficientEvidence()).as(generalized.toString()).isTrue();
		var questionAware = service.verify(
			"정보화시스템 법제도 준수안하면 어떤 불이익?", source, List.of(ground));
		assertThat(questionAware.claimResult().insufficientEvidence()).as(questionAware.toString()).isFalse();
		assertThat(questionAware.alignmentResult().aligned()).as(questionAware.toString()).isFalse();
		assertThat(questionAware.alignmentResult().missingGroups()).contains("SUBJECT", "RELATION", "CONDITION");
		String bounded = "향후 입찰공고(RFP)시 반영여부를 재확인하여 미준수 항목을 2차 권고할 예정입니다.";
		var boundedResult = service.verify(
			"정보화시스템 법제도 준수안하면 어떤 불이익?", bounded, List.of(ground));
		assertThat(boundedResult.claimResult().insufficientEvidence()).isTrue();
		assertThat(boundedResult.alignmentResult().evaluated()).isFalse();
		String preserved = "향후 입찰공고(RFP)시 반영여부를 재확인하여 미준수 항목 권고(2차발송) 예정입니다.";
		var preservedResult = service.verify(
			"정보화시스템 법제도 준수안하면 어떤 불이익?", preserved, List.of(ground));
		assertThat(preservedResult.claimResult().insufficientEvidence()).as(preservedResult.toString()).isFalse();
		assertThat(preservedResult.alignmentResult().aligned()).as(preservedResult.toString()).isFalse();
		assertThat(preservedResult.insufficientEvidence()).isTrue();
		assertThat(preservedResult.alignmentResult().missingGroups()).contains("SUBJECT", "CONDITION");
	}

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
