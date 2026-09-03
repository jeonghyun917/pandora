package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class GroundedAnswerRepairServiceTests {

	private static final String QUESTION = "연차 유급휴가는 누구에게 어떤 조건으로 부여해야 하나?";
	private static final String REJECTED_DRAFT = "근거에 없는 30일 휴가를 누구에게나 부여해야 합니다.";
	private static final String REPAIRED_ANSWER = "사용자는 법정 요건을 충족한 근로자에게 연차 유급휴가를 부여해야 합니다.";
	private static final String EVIDENCE =
		"사용자는 법정 요건을 충족한 근로자에게 연차 유급휴가를 주어야 한다.";

	@Test
	void successfulRepairUsesSupportedAtomOnceAndReverifiesFromTheBeginning() {
		LawAiAnswerGround ground = ground(1, EVIDENCE);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		AnswerVerificationService.Result initial = alignmentFailure(
			REJECTED_DRAFT,
			List.of(link("지원되는 초안 문장", "SUPPORTED", 1, EVIDENCE))
		);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds)).thenReturn(initial);
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, true, "REWRITE_ACCEPTED", 1)
		);
		assertThat(rewriter.calls()).isEqualTo(1);
		assertThat(rewriter.questions()).containsExactly(QUESTION);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(EVIDENCE));
	}

	@Test
	void configuredLawPolicyCanRewriteFromClaimSupportedDirectGroundWhenRawAtomIsNotQuestionAligned() {
		String question = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918";
		String evidence = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";
		String rejected = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC774\uBA74 \uBB34\uC870\uAC74 \uACB0\uACFC\uBCF4\uACE0\uB97C \uC81C\uCD9C\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.";
		String repaired = "\uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD569\uB2C8\uB2E4.";
		LawAiAnswerGround ground = ground(1, evidence);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(question, rejected, grounds))
			.thenReturn(claimFailure(rejected, List.of(), List.of()));
		when(verifier.verify(question, evidence, grounds))
			.thenReturn(alignmentFailure(
				evidence,
				List.of(link(evidence, "SUPPORTED", 1, evidence))
			));
		when(verifier.verify(question, repaired, grounds)).thenReturn(supported(repaired));
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			rejected,
			grounds
		);

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(repaired);
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, true, "REWRITE_ACCEPTED", 1)
		);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredLawPolicyRepairPassesTheRealClaimAndAlignmentVerifiers() {
		String question = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918";
		String completion = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";
		String payment = "\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uB54C\uC5D0\uB294 \uC18C\uC815\uC758 \uC808\uCC28\uC5D0 \uB530\uB77C \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.";
		String repaired = completion + " " + payment;
		String rejected = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC774\uBA74 \uBB34\uC870\uAC74 \uACB0\uACFC\uBCF4\uACE0\uB97C \uC81C\uCD9C\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, completion),
			ground(2, payment)
		);
		AnswerVerificationService realVerifier = new AnswerVerificationService(
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerQuestionAlignmentVerifier()
		);
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(realVerifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			rejected,
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).contains(completion, payment);
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, true, "REWRITE_ACCEPTED", 2)
		);
	}

	@Test
	void configuredLawPolicyFallsBackToVerifiedAtomsWhenRewriterDropsARequiredStage() {
		String question = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918";
		String completion = "\uACC4\uC57D\uC0C1\uB300\uC790\uB294 \uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uADF8 \uC0AC\uC2E4\uC744 \uACC4\uC57D\uB2F4\uB2F9\uACF5\uBB34\uC6D0\uC5D0\uAC8C \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uD544\uC694\uD55C \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";
		String payment = "\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uB54C\uC5D0\uB294 \uC18C\uC815\uC758 \uC808\uCC28\uC5D0 \uB530\uB77C \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.";
		String rejected = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC774\uBA74 \uBB34\uC870\uAC74 \uACB0\uACFC\uBCF4\uACE0\uB97C \uC81C\uCD9C\uD560 \uC218 \uC788\uC2B5\uB2C8\uB2E4.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, completion),
			ground(2, payment)
		);
		AnswerVerificationService realVerifier = new AnswerVerificationService(
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerQuestionAlignmentVerifier()
		);
		RecordingRewriter rewriter = RecordingRewriter.returning(completion);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(realVerifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			rejected,
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).contains(completion, payment);
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, true, "ATOM_FALLBACK_ACCEPTED", 2)
		);
	}

	@Test
	void contradictionOrConflictFailsClosedWithoutCallingTheRewriter() {
		for (String relation : List.of("CONTRADICTED", "CONFLICTED")) {
			LawAiAnswerGround ground = ground(1, EVIDENCE);
			List<LawAiAnswerGround> grounds = List.of(ground);
			AnswerVerificationService verifier = mock(AnswerVerificationService.class);
			AnswerVerificationService.Result initial = claimFailure(
				REJECTED_DRAFT,
				List.of(link(REJECTED_DRAFT, relation, 1, EVIDENCE)),
				List.of(REJECTED_DRAFT)
			);
			when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds)).thenReturn(initial);
			RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
			GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

			GroundedAnswerRepairService.Result result = service.verifyAndRepair(
				QUESTION,
				REJECTED_DRAFT,
				grounds
			);

			assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
			assertThat(result.insufficientEvidence()).isTrue();
			assertThat(result.diagnostics().reason()).isEqualTo("CONTRADICTION_OR_CONFLICT");
			assertThat(result.diagnostics().attempted()).isFalse();
			assertThat(rewriter.calls()).isZero();
		}
	}

	@Test
	void noQuestionAlignedSupportedAtomFailsClosedWithoutCallingTheRewriter() {
		LawAiAnswerGround ground = ground(1, "문서의 시행일은 2026년 1월 1일이다.");
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(claimFailure(REJECTED_DRAFT, List.of(), List.of()));
		when(verifier.verify(eq(QUESTION), anyString(), eq(grounds)))
			.thenAnswer(invocation -> invocation.getArgument(1).equals(REJECTED_DRAFT)
				? claimFailure(REJECTED_DRAFT, List.of(), List.of())
				: alignmentFailure(invocation.getArgument(1), List.of()));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(false, false, "NO_ALIGNED_SUPPORTED_ATOM", 0)
		);
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void blankRewriterResultFailsClosedAfterExactlyOneCall() {
		GroundedAnswerRepairService.Result result = runRepairWithRewrite("   ", null, supported(EVIDENCE));

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, false, "REWRITER_BLANK", 1)
		);
	}

	@Test
	void everyRepairFailureNormalizesEvenAnIrregularInitialFailureToTheExactStandardResponse() {
		LawAiAnswerGround ground = ground(1, EVIDENCE);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		ClaimVerifier.VerificationResult irregularClaimFailure = new ClaimVerifier.VerificationResult(
			"비표준 실패 문구",
			true,
			true,
			List.of(REJECTED_DRAFT),
			List.of(),
			List.of(),
			List.of(link(REJECTED_DRAFT, "SUPPORTED", 1, EVIDENCE)),
			2,
			1
		);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(new AnswerVerificationService.Result(
				REJECTED_DRAFT,
				irregularClaimFailure,
				AnswerQuestionAlignmentVerifier.AlignmentResult.claimInsufficient()
			));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		RecordingRewriter rewriter = RecordingRewriter.returning(" ");
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.diagnostics().reason()).isEqualTo("REWRITER_BLANK");
		assertThat(rewriter.calls()).isEqualTo(1);
	}

	@Test
	void rewriterExceptionFailsClosedAfterExactlyOneCall() {
		GroundedAnswerRepairService.Result result = runRepairWithRewrite(
			null,
			new IllegalStateException("secret provider failure"),
			supported(EVIDENCE)
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, false, "REWRITER_EXCEPTION", 1)
		);
		assertThat(result.diagnostics().toString()).doesNotContain("secret provider failure");
	}

	@Test
	void unsupportedOrMisalignedRewriteFailsClosedWithoutASecondRewrite() {
		LawAiAnswerGround ground = ground(1, EVIDENCE);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(alignmentFailure(
				REJECTED_DRAFT,
				List.of(link("지원되는 초안 문장", "SUPPORTED", 1, EVIDENCE))
			));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds))
			.thenReturn(alignmentFailure(
				REPAIRED_ANSWER,
				List.of(link(REPAIRED_ANSWER, "SUPPORTED", 1, EVIDENCE))
			));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.diagnostics().reason()).isEqualTo("REWRITE_VERIFICATION_FAILED");
		assertThat(result.diagnostics().attempted()).isTrue();
		assertThat(result.diagnostics().accepted()).isFalse();
		assertThat(rewriter.calls()).isEqualTo(1);
	}

	@Test
	void selectedAtomsFollowGroundOrderAreDeduplicatedBoundedAndNeverContainDraftTitlesOrParentContext() {
		List<LawAiAnswerGround> grounds = new ArrayList<>();
		for (int number = 1; number <= GroundedAnswerRepairService.MAX_SELECTED_ATOMS + 3; number++) {
			String atom = number == 2 ? "첫 번째 직접 근거이다." : number + "번째 직접 근거이다.";
			grounds.add(ground(
				number,
				atom,
				"유출 금지 제목 " + number,
				"유출 금지 상위 문맥 " + number
			));
		}
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		List<ClaimVerifier.ClaimEvidenceLink> supportedLinks = new ArrayList<>();
		supportedLinks.add(link("지원 문장 2", "SUPPORTED", 2, "첫 번째 직접 근거이다."));
		supportedLinks.add(link("지원 문장 1", "SUPPORTED", 1, "1번째 직접 근거이다."));
		supportedLinks.add(link("중복 지원 문장", "SUPPORTED", 1, "1번째 직접 근거이다."));
		for (int number = 3; number <= GroundedAnswerRepairService.MAX_SELECTED_ATOMS + 3; number++) {
			supportedLinks.add(link(
				"지원 문장 " + number,
				"SUPPORTED",
				number,
				number + "번째 직접 근거이다."
			));
		}
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(alignmentFailure(REJECTED_DRAFT, supportedLinks));
		when(verifier.verify(eq(QUESTION), anyString(), eq(grounds)))
			.thenAnswer(invocation -> invocation.getArgument(1).equals(REJECTED_DRAFT)
				? alignmentFailure(REJECTED_DRAFT, supportedLinks)
				: supported(invocation.getArgument(1)));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		List<String> atoms = rewriter.atomCalls().get(0);
		assertThat(atoms).hasSize(GroundedAnswerRepairService.MAX_SELECTED_ATOMS);
		assertThat(atoms).containsExactly(
			"1번째 직접 근거이다.",
			"첫 번째 직접 근거이다.",
			"3번째 직접 근거이다.",
			"4번째 직접 근거이다.",
			"5번째 직접 근거이다.",
			"6번째 직접 근거이다."
		);
		assertThat(atoms).doesNotHaveDuplicates();
		assertThat(atoms).allMatch(atom -> atom.length() <= GroundedAnswerRepairService.MAX_ATOM_CHARACTERS);
		assertThat(atoms.stream().mapToInt(String::length).sum())
			.isLessThanOrEqualTo(GroundedAnswerRepairService.MAX_TOTAL_ATOM_CHARACTERS);
		assertThat(String.join("\n", atoms))
			.doesNotContain(REJECTED_DRAFT)
			.doesNotContain("유출 금지 제목")
			.doesNotContain("유출 금지 상위 문맥");
		assertThat(result.diagnostics().selectedAtomCount()).isEqualTo(GroundedAnswerRepairService.MAX_SELECTED_ATOMS);
		assertThat(rewriter.calls()).isEqualTo(1);
	}

	@Test
	void fallbackAtomsRoundRobinAcrossGroundsBeforeTakingLaterSentences() {
		String firstGround = String.join(" ", List.of(
			"\uCCAB \uBC88\uC9F8 \uAC80\uC0AC \uC808\uCC28\uC774\uB2E4.",
			"\uB450 \uBC88\uC9F8 \uAC80\uC0AC \uC808\uCC28\uC774\uB2E4.",
			"\uC138 \uBC88\uC9F8 \uAC80\uC0AC \uC808\uCC28\uC774\uB2E4.",
			"\uB124 \uBC88\uC9F8 \uAC80\uC0AC \uC808\uCC28\uC774\uB2E4.",
			"\uB2E4\uC12F \uBC88\uC9F8 \uAC80\uC0AC \uC808\uCC28\uC774\uB2E4.",
			"\uC5EC\uC12F \uBC88\uC9F8 \uAC80\uC0AC \uC808\uCC28\uC774\uB2E4."
		));
		String secondGround = "\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD55C \uB54C\uC5D0\uB294 \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, firstGround),
			ground(2, secondGround)
		);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(claimFailure(REJECTED_DRAFT, List.of(), List.of()));
		when(verifier.verify(eq(QUESTION), anyString(), eq(grounds)))
			.thenAnswer(invocation -> invocation.getArgument(1).equals(REJECTED_DRAFT)
				? claimFailure(REJECTED_DRAFT, List.of(), List.of())
				: supported(invocation.getArgument(1)));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		service.verifyAndRepair(QUESTION, REJECTED_DRAFT, grounds);

		assertThat(rewriter.atomCalls().get(0))
			.hasSize(GroundedAnswerRepairService.MAX_SELECTED_ATOMS)
			.contains(secondGround);
		assertThat(rewriter.atomCalls().get(0).indexOf(secondGround)).isEqualTo(1);
	}

	@Test
	void configuredLawPolicyBoundsFallbackToOneAtomPerGround() {
		String question = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC5D0 \uACB0\uACFC\uBCF4\uACE0\uB97C \uBA3C\uC800 \uB0B4\uB3C4 \uB418\uB294\uC9C0 \uC54C\uB824\uC918";
		String rejected = "\uC6A9\uC5ED\uAE30\uAC04 \uC804\uC774\uBA74 \uBB34\uC870\uAC74 \uC81C\uCD9C\uD560 \uC218 \uC788\uB2E4.";
		String repaired = "\uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uC600\uC744 \uB54C\uC5D0\uB294 \uC11C\uBA74\uC73C\uB85C \uD1B5\uC9C0\uD558\uACE0 \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";
		String repeatedArticleHeading = "\uC81C20\uC870(\uAC80\uC0AC) \uC81C20\uC870(\uAC80\uC0AC)";
		String partialSupported = "\uC6A9\uC5ED\uC744 \uC644\uC131\uD558\uBA74 \uAC80\uC0AC\uB97C \uBC1B\uC544\uC57C \uD55C\uB2E4.";
		String duplicateFirstGround = "\uACC4\uC57D\uC11C\uC5D0 \uB530\uB77C \uAC80\uC0AC\uD55C\uB2E4.";
		String payment = "\uAC80\uC0AC\uC5D0 \uD569\uACA9\uD558\uBA74 \uB300\uAC00\uC9C0\uAE09\uC744 \uCCAD\uAD6C\uD560 \uC218 \uC788\uB2E4.";
		String delayedPaymentGround = "\uC124\uBA85".repeat(200) + "\uC774\uB2E4. " + payment;
		String completionNotice = "\uC774\uD589 \uC644\uB8CC \uD1B5\uC9C0 \uD6C4 \uAC80\uC0AC\uD55C\uB2E4.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, repeatedArticleHeading + ". " + partialSupported + " " + duplicateFirstGround),
			ground(2, delayedPaymentGround),
			ground(3, completionNotice)
		);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(eq(question), anyString(), eq(grounds)))
			.thenAnswer(invocation -> {
				String answer = invocation.getArgument(1);
				if (answer.equals(rejected)) {
					return alignmentFailure(
						rejected,
						List.of(link(partialSupported, "SUPPORTED", 1, partialSupported))
					);
				}
				if (answer.equals(repaired)) {
					return supported(repaired);
				}
				return alignmentFailure(
					answer,
					List.of(link(answer, "SUPPORTED", 1, answer))
				);
			});
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		service.verifyAndRepair(question, rejected, grounds);

		assertThat(rewriter.atomCalls().get(0))
			.hasSize(grounds.size())
			.contains(partialSupported, payment, completionNotice)
			.doesNotContain(repeatedArticleHeading, duplicateFirstGround);
	}

	@Test
	void rejectedDraftCannotBeReusedAsAnExactEvidenceAtom() {
		LawAiAnswerGround ground = ground(1, REJECTED_DRAFT);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(claimFailure(REJECTED_DRAFT, List.of(), List.of()), supported(REJECTED_DRAFT));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.diagnostics().reason()).isEqualTo("NO_ALIGNED_SUPPORTED_ATOM");
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void evidenceAtomContainingTheEntireRejectedDraftIsExcluded() {
		String shortDraft = "연차 유급휴가를 부여해야 합니다.";
		String candidate = "사용자는 근로자에게 연차 유급휴가를 부여해야 합니다.";
		LawAiAnswerGround ground = ground(1, candidate);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, shortDraft, grounds))
			.thenReturn(claimFailure(shortDraft, List.of(), List.of()));
		when(verifier.verify(QUESTION, candidate, grounds)).thenReturn(supported(candidate));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			shortDraft,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.diagnostics().reason()).isEqualTo("NO_ALIGNED_SUPPORTED_ATOM");
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void supportedClauseContainedInsideTheRejectedDraftRemainsEligibleForSalvage() {
		String draftContainingSafeClause = "근거 없는 설명입니다. " + EVIDENCE;
		LawAiAnswerGround ground = ground(1, EVIDENCE);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, draftContainingSafeClause, grounds))
			.thenReturn(claimFailure(draftContainingSafeClause, List.of(), List.of()));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			draftContainingSafeClause,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(EVIDENCE));
	}

	@Test
	void validatedSupportedLinksSuppressFallbackAtomsEvenFromEarlierGrounds() {
		String earlierFallback = "첫 번째 fallback 근거입니다.";
		String laterSupported = "두 번째 SUPPORTED 근거입니다.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, earlierFallback),
			ground(2, laterSupported)
		);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		List<ClaimVerifier.ClaimEvidenceLink> links = List.of(
			link("지원되는 초안 문장", "SUPPORTED", 2, laterSupported)
		);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(alignmentFailure(REJECTED_DRAFT, links));
		when(verifier.verify(eq(QUESTION), anyString(), eq(grounds)))
			.thenAnswer(invocation -> invocation.getArgument(1).equals(REJECTED_DRAFT)
				? alignmentFailure(REJECTED_DRAFT, links)
				: supported(invocation.getArgument(1)));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		service.verifyAndRepair(QUESTION, REJECTED_DRAFT, grounds);

		assertThat(rewriter.atomCalls()).containsExactly(List.of(laterSupported));
	}

	@Test
	void unusableSupportedLinksAllowASeparateMatchedChildFallbackPhase() {
		String unusableSupported = "문서 시행일은 2026년 1월 1일입니다.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, EVIDENCE),
			ground(2, unusableSupported)
		);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		List<ClaimVerifier.ClaimEvidenceLink> links = List.of(
			link("지원되는 초안 문장", "SUPPORTED", 2, unusableSupported)
		);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(alignmentFailure(REJECTED_DRAFT, links));
		when(verifier.verify(QUESTION, unusableSupported, grounds))
			.thenReturn(alignmentFailure(unusableSupported, List.of(
				link(unusableSupported, "SUPPORTED", 2, unusableSupported)
			)));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(EVIDENCE));
	}

	@Test
	void realVerificationAllowsRewriteOnlyForSupportedQuestionAlignedMatchedChildAtom() {
		String question = "공공소프트웨어사업은 과업심의 대상인가?";
		String alignedEvidence = "공공소프트웨어사업은 과업심의 대상입니다.";
		List<LawAiAnswerGround> grounds = List.of(ground(1, alignedEvidence));
		RecordingRewriter rewriter = RecordingRewriter.returning(alignedEvidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"오늘은 비가 옵니다.",
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(alignedEvidence);
		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(alignedEvidence));
	}

	@Test
	void realVerificationRepairsSupportedNominalPeriodGroundWithoutInventingExtraClaims() {
		String question = "IRM 성과측정은 언제해?";
		String period =
			"평가기간 : 2025. 12. 17 ~ 2026. 10. 31";
		String aggregation =
			"등록요청 수 및 등록완료 수는 평가기간 동안 집계된 요청 수와 완료 수를 모두 합산하여 산정한다.";
		String evidence = period + ". " + aggregation;
		String rejectedDraft =
			"IRM 성과측정 기간은 2025년 12월 17일부터 2026년 10월 31일까지이며 "
				+ "모든 시스템은 21일 이내 처리를 완료해야 합니다.";
		String repaired = period + ". " + aggregation;
		List<LawAiAnswerGround> grounds = List.of(ground(
			1,
			evidence,
			"2026년도 정보자원관리시스템 기반 정보자원 관리 수준측정 해설서",
			null
		));
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);
		AnswerVerificationService.Result directVerification =
			realVerificationService().verify(question, repaired, grounds);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			rejectedDraft,
			grounds
		);

		assertThat(directVerification.insufficientEvidence())
			.withFailMessage(directVerification.toString())
			.isFalse();
		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(repaired);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredAnswerCoverageSelectsOnlyTheRequiredSupportedAtoms() {
		String question = "IRM 성과측정은 언제해?";
		String period = "평가기간 : 2025. 12. 17 ~ 2026. 10. 31";
		String aggregation =
			"평가기간 동안 집계된 요청 수와 완료 수를 모두 합산하여 산정한다.";
		String unrelatedDate = "해설서는 2026년 3월에 개정될 예정입니다.";
		String title =
			"2026년도 정보자원관리시스템 기반 정보자원 관리 수준측정 해설서";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, period, title, null),
			ground(2, aggregation, title, null),
			ground(3, unrelatedDate, title, null)
		);
		String repaired = period + "\n" + aggregation;
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"IRM 성과측정은 매년 1월에 시작합니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(repaired);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(period, aggregation));
	}

	@Test
	void configuredAnswerCoverageCanUseOnlyRequiredAtomsFromDirectGroundParentContext() {
		String question = "개인정보 수집 동의 받을 때 거부권도 알려야 해?";
		String consent =
			"개인정보처리자는 개인정보 수집·이용 동의를 받을 때 다음 사항을 알려야 한다.";
		String refusal =
			"동의를 거부할 권리가 있다는 사실을 정보주체에게 알려야 한다.";
		String unrelated = "이 안내서는 2024년에 발간되었다.";
		String parentContext = consent + " " + refusal + " " + unrelated;
		List<LawAiAnswerGround> grounds = List.of(ground(
			1,
			consent,
			"개인정보 처리 통합 안내서",
			parentContext
		));
		String repaired = consent + " " + refusal;
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"개인정보 동의를 받을 때 모든 항목을 생략해도 됩니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(consent + "\n" + refusal);
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, true, "REWRITE_ACCEPTED", 2)
		);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(consent, refusal));
		assertThat(rewriter.atomCalls().get(0)).doesNotContain(unrelated);
	}

	@Test
	void configuredProjectReviewPurchaseScopeRepairsFromRuleAndBoundaryAtoms() {
		String question = "단순 소프트웨어 구매면 과업심의 안해도 돼?";
		String rule = "과업심의 적용 대상은 국가기관 등이 발주하는 모든 소프트웨어사업이다.";
		String boundary = "단순 H/W 도입·설치처럼 소프트웨어사업으로 볼 수 없는 경우는 비대상이다.";
		String evidence = rule + " " + boundary;
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"과업심의 적용 여부는 구매 금액만으로 정합니다.",
			List.of(ground(1, evidence, "공공소프트웨어사업 과업심의 가이드", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0)).contains(rule, boundary);
	}

	@Test
	void genericProjectReviewTargetFailsClosedWhenGenericBusinessScopeOmitsAskedRelation() {
		String question = "과업심의 대상은?";
		String evidence =
			"대상사업 사례 — 대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업 "
				+ "[국가기관등에는 국가기관, 지방자치단체, 공공기관 등이 포함된다.]";
		String genericAnswer =
			"대상사업은 국가기관등(국가기관, 지방자치단체, 공공기관 등)이 발주하는 소프트웨어사업입니다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(genericAnswer);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			genericAnswer,
			List.of(ground(
				1,
				evidence,
				"공공SW사업 법제도 관리감독 및 지원 가이드(2024. 12.)",
				null
			))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
	}

	@Test
	void genericProjectReviewTargetAcceptsRewriteThatStatesTheRelationAndBusinessScope() {
		String question = "과업심의 대상은?";
		String evidence = "공공소프트웨어사업은 과업심의 대상입니다.";
		String repaired = evidence;
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"과업심의 대상은 담당자가 임의로 정합니다.",
			List.of(ground(
				1,
				evidence,
				"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
				null
			))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(repaired);
	}

	@Test
	void genericProjectReviewTargetRepairsFromOfficialGuideTargetHeadingAndScopeAtom() {
		String question = "과업심의 대상은?";
		String evidence =
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW 포함)";
		List<LawAiAnswerGround> grounds = List.of(ground(
			1,
			evidence,
			"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
			null
		));
		AnswerVerificationService verifier = realVerificationService();
		AnswerVerificationService.Result atomVerification = verifier.verify(
			question,
			evidence,
			grounds
		);
		assertThat(atomVerification.claimResult().unsupportedClaims())
			.as(atomVerification.toString())
			.isEmpty();
		assertThat(atomVerification.alignmentResult().aligned())
			.as(atomVerification.toString())
			.isTrue();
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			verifier,
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"과업심의 적용 여부는 소프트웨어사업인지 여부만으로 판단합니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(evidence);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void projectReviewHardwareExclusionFallsBackToVerifiedSourceAtomWhenParaphraseIsRejected() {
		String question = "하드웨어만 사는 사업도 공공SW 과업심의를 해야 해?";
		String evidence =
			"적용 대상 사업 국가기관 등이 발주하는 모든 SW사업(상용SW포함) - "
				+ "소프트웨어의 개발, 제작, 생산, 유통, 운영 및 유지·관리 등과 그 밖에 "
				+ "소프트웨어와 관련된 서비스를 제공하는 산업과 관련된 경제활동. "
				+ "※ 단순 H/W(Appliance 포함) 도입·설치, 단순 동영상 제작, "
				+ "네트워크 등 인프라 수수료와 같이 소프트웨어사업으로 볼 수 없는 경우는 비대상";
		String rejectedDraft = "하드웨어 구매는 금액과 관계없이 과업심의 대상입니다.";
		String rejectedParaphrase =
			"단순 H/W(어플라이언스 포함) 도입·설치 등 소프트웨어사업으로 볼 수 없는 경우에는 "
				+ "과업심의 대상이 아닙니다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(rejectedParaphrase);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			rejectedDraft,
			List.of(ground(
				1,
				evidence,
				"공공소프트웨어사업 과업심의 가이드(2022. 12.)",
				null
			))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.diagnostics().reason()).isEqualTo("ATOM_FALLBACK_ACCEPTED");
		assertThat(result.verifiedAnswer())
			.contains("소프트웨어사업으로 볼 수 없는 경우는 비대상")
			.doesNotContain("금액과 관계없이");
	}

	@Test
	void configuredPreConsultationTargetScopeRepairsFromInstitutionAndProjectAtoms() {
		String question = "기타공공기관 사전협의 대상 알려줘";
		String institution = "사전협의 대상기관에는 공공기관이 포함된다.";
		String projectScope = "사전협의의 대상사업은 대상기관이 추진하는 모든 정보화사업이다.";
		String evidence = institution + " " + projectScope;
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"사전협의 대상은 담당자 직급에 따라 정합니다.",
			List.of(ground(1, evidence, "전자정부 사전협의 매뉴얼", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0)).contains(institution, projectScope);
	}

	@Test
	void configuredSecurityReviewTargetRepairsFromThreeConcreteOfficialTargetAtoms() {
		String question = "보안성검토 대상 시스템은?";
		String system = "비밀·대외비를 유통·관리하기 위한 정보통신망 또는 정보시스템 구축.";
		String sensitive = "100만명 이상의 민감정보 또는 고유식별정보를 처리하는 정보시스템 구축.";
		String infrastructure = "주요정보통신기반시설로 지정이 필요한 정보통신기반시설 구축.";
		String evidence = String.join(" ", system, sensitive, infrastructure);
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"보안성 검토 담당자와 먼저 사전 협의해야 합니다.",
			List.of(ground(1, evidence, "2026년 정보화사업 보안성 검토 가이드", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).hasSize(1);
		assertThat(rewriter.atomCalls().get(0))
			.anySatisfy(atom -> assertThat(atom).contains("정보통신망 또는 정보시스템 구축"))
			.anySatisfy(atom -> assertThat(atom).contains("민감정보 또는 고유식별정보"))
			.anySatisfy(atom -> assertThat(atom).contains("주요정보통신기반시설"));
	}

	@Test
	void autonomyProcedureFallsBackToTheThreeVerifiedOfficialProcedureAtoms() {
		String question = "자치분권 사전협의 요청할 때 어떤 절차로 검토돼?";
		String request =
			"법령안 마련 후 사전협의 요청서를 작성하여 관계기관 협의 또는 입법예고 할 때 "
				+ "공문으로 행정안전부장관에게 제출한다.";
		String review =
			"행정안전부장관은 제·개정 법령안의 지방자치 관련성을 검토한다.";
		String result =
			"지방자치 관련성이 없으면 중앙행정기관장에게 결과 통보서를 송부한다.";
		String evidence = String.join(" ", request, review, result);
		String rejectedDraft = "사전협의는 제출 없이 자동으로 승인됩니다.";
		String rejectedRewrite = "행정안전부가 모든 법령안을 자동 승인합니다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(rejectedRewrite);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result repair = service.verifyAndRepair(
			question,
			rejectedDraft,
			List.of(ground(
				1,
				evidence,
				"자치분권 사전협의 지침(2024년판)",
				null
			))
		);

		assertThat(repair.insufficientEvidence()).as(repair.toString()).isFalse();
		assertThat(repair.diagnostics().reason()).isEqualTo("ATOM_FALLBACK_ACCEPTED");
		assertThat(repair.verifiedAnswer())
			.contains("사전협의 요청서")
			.contains("지방자치 관련성")
			.contains("결과 통보서")
			.doesNotContain("자동 승인");
	}

	@Test
	void configuredTrafficCrosswalkStopRepairsOnlyWithPedestrianConditionAndStopDuty() {
		String question = "운전중 우회전할때 횡단보도에서 멈춰야 하나?";
		String evidence =
			"보행자가 횡단보도를 통행하고 있거나 통행하려고 하는 때에는 "
				+ "횡단보도 앞에서 일시정지하여야 한다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"횡단보도 규칙은 지역별 안내문에만 나옵니다.",
			List.of(ground(1, evidence, "도로교통법", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredRfpRequiredItemsRepairsWithBothCoreItemPairs() {
		String question = "공공기관 제안요청서 작성할때 필수요소가 있나?";
		String evidence = "제안요청서에는 과업내용, 요구사항, 계약조건, 평가요소와 평가방법, "
			+ "제안서의 규격, 기타 필요한 사항 등을 기술하여야 합니다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"계약담당공무원은 제안요청서 교부를 언제나 생략할 수 있습니다.",
			List.of(ground(1, evidence, "공공정보화사업 유형별 제안요청서 작성 가이드", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).contains("과업내용, 요구사항, 계약조건, 평가요소");
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredTrafficCrosswalkStopPreservesTheOfficialParentheticalConditions() {
		String question = "운전중 우회전할때 횡단보도에서 멈춰야 하나?";
		String evidence =
			"모든 차 또는 노면전차의 운전자는 보행자"
				+ "(제13조의2제6항에 따라 자전거등에서 내려서 자전거등을 끌거나 들고 통행하는 자전거등의 운전자를 포함한다)"
				+ "가 횡단보도를 통행하고 있거나 통행하려고 하는 때에는 보행자의 횡단을 방해하거나 위험을 주지 아니하도록 "
				+ "그 횡단보도 앞(정지선이 설치되어 있는 곳에서는 그 정지선을 말한다)에서 일시정지하여야 한다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"횡단보도에서는 보행자와 관계없이 서행하면 됩니다.",
			List.of(ground(1, evidence, "도로교통법", "제27조(보행자의 보호)"))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredCctvPublicPlaceExceptionRepairsOnlyWithPrincipleAndLegalException() {
		String question =
			"개인정보보호위원회 CCTV 안내서에서 공개된 장소에 CCTV를 설치할 수 있는 예외는?";
		String evidence =
			"공개된 장소에서 고정형 영상정보처리기기 설치는 원칙적으로 금지되고, "
				+ "법 제25조에서 정하는 사유에 해당하는 경우에만 설치할 수 있다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"CCTV 안내서에는 카메라 제품 사양만 적혀 있습니다.",
			List.of(ground(1, evidence, "고정형 영상정보처리기기 설치·운영 안내서", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredCctvPublicPlacePrincipleQuestionRepairsWithTheSameLegalBoundary() {
		String question = "공개된 장소에 CCTV를 설치하는 건 원칙적으로 가능한가?";
		String evidence =
			"공개된 장소에서 고정형 영상정보처리기기 설치는 원칙적으로 금지되고, "
				+ "법 제25조에서 정하는 사유에 해당하는 경우에만 설치할 수 있다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"공개된 장소에는 CCTV를 자유롭게 설치할 수 있습니다.",
			List.of(ground(1, evidence, "고정형 영상정보처리기기 설치·운영 안내서", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredPrivacyMinimumCollectionRepairsFromPurposeAndMinimumScopeAtom() {
		String question = "개인정보는 필요한 만큼만 수집해야 해?";
		String evidence =
			"개인정보처리자는 개인정보의 처리 목적을 명확하게 하여야 하고 "
				+ "그 목적에 필요한 범위에서 최소한의 개인정보만을 수집하여야 한다.";
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"개인정보는 나중에 쓸 수 있으므로 가능한 한 많이 수집해도 됩니다.",
			List.of(ground(1, evidence, "개인정보 보호법", "제3조(개인정보 보호 원칙)"))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(evidence);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(evidence));
	}

	@Test
	void configuredPrivacyMinimumCollectionPrioritizesTopDirectAtomsOverPartialDistractors() {
		String question = "개인정보는 필요한 만큼만 수집해야 해?";
		String purpose =
			"개인정보처리자는 개인정보의 처리 목적을 명확하게 하여야 한다.";
		String collection =
			"개인정보처리자는 그 목적에 필요한 범위에서 최소한의 개인정보만을 "
				+ "적법하고 정당하게 수집하여야 한다.";
		String direct = purpose + " " + collection;
		String purposeOnly =
			"Ⅱ. 문제점 개인정보처리자는 개인정보의 처리 목적을 명확하게 하여야 한다.";
		String unrelatedMinimum =
			"국정감사와 관련하여 필요한 최소한의 개인정보를 제출하여야 한다.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(
				1,
				"제3조(개인정보 보호 원칙) 등 ○ " + direct,
				"개인정보 보호법",
				null
			),
			ground(2, purposeOnly, "개인정보 처리 안내서", null),
			ground(3, unrelatedMinimum, "국회에서의 증언·감정 등에 관한 법률", null)
		);
		RecordingRewriter rewriter = RecordingRewriter.returning(
			String.join("\n", purpose, collection)
		);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"개인정보는 가능한 한 많이 수집해도 됩니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(purpose, collection));
		assertThat(String.join("\n", rewriter.atomCalls().get(0)))
			.doesNotContain("Ⅱ. 문제점", "국정감사");
	}

	@Test
	void configuredPseudonymAdditionalInformationKeepsStorageAndDestructionConditions() {
		String question = "개보위 가명정보 자료에서 추가정보는 분리보관해야 해?";
		String processing = "가명정보를 처리하는 경우 안전성 확보조치를 하여야 한다.";
		String storage = "추가정보를 가명정보와 분리하여 별도 보관하여야 한다.";
		String destruction = "추가정보가 불필요한 경우 파기하여야 한다.";
		String evidence = String.join(" ", processing, storage, destruction);
		RecordingRewriter rewriter = RecordingRewriter.returning(evidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"추가정보는 가명정보와 함께 계속 보관해도 됩니다.",
			List.of(ground(1, evidence, "가명정보 처리 가이드라인", null))
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0)).contains(processing, storage, destruction);
	}

	@Test
	void configuredPseudonymAdditionalInformationSkipsHeadingLikeProcessingText() {
		String question = "개보위 가명정보 자료에서 추가정보는 분리보관해야 해?";
		String heading = "가명처리한 개인정보의 항목";
		String destruction = "다만, 불필요한 경우 파기해야 함";
		String processing =
			"평가대상기관은 가명처리 수행에 따라 추가정보를 일정기간 보관한다.";
		String storage =
			"개인정보처리자는 추가정보를 가명정보와 분리하여 별도로 저장관리한다.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, heading + ". " + destruction),
			ground(4, processing),
			ground(6, storage)
		);
		RecordingRewriter rewriter = RecordingRewriter.returning(
			String.join(" ", processing, storage, destruction)
		);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"추가정보는 가명정보와 결합하여 보관해도 됩니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0))
			.contains(processing, storage, destruction)
			.doesNotContain(heading);
	}

	@Test
	void configuredPreConsultationTimingSelectsTheOfficialSequenceOnly() {
		String question =
			"정보화사업 사전협의는 예산 편성 전에 하는 거야 사업계획 후에 하는 거야?";
		String sequence =
			"중앙행정기관등의 장은 사업계획을 수립한 후 지체 없이 행정안전부장관에게 "
				+ "사업계획서 등의 자료를 제출하여 사전협의를 요청하여야 한다.";
		String unrelated = "사전협의 대상사업은 사업금액 기준에 따라 달라질 수 있다.";
		List<LawAiAnswerGround> grounds = List.of(ground(1, sequence + " " + unrelated));
		RecordingRewriter rewriter = RecordingRewriter.returning(sequence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"예산이 편성된 뒤 아무 때나 사전협의를 신청하면 됩니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(sequence));
		assertThat(rewriter.atomCalls().get(0)).doesNotContain(unrelated);
	}

	@Test
	void configuredEgovPreliminaryReviewScopeSelectsOnlyTheOfficialTargetRelationship() {
		String question = "지능정보사회 실행계획의 예비검토는 어떤 사업을 대상으로 하는거야?";
		String targetRelationship =
			"다음 해에 정보화사업을 추진하고자 하는 중앙행정기관의 장, 시ㆍ도지사 및 시ㆍ도 교육감은 "
				+ "예비검토를 신청하여야 한다.";
		String unrelated =
			"예비검토 대상사업에는 모든 공공 AI 사업과 구축비 추가 사업이 포함된다.";
		List<LawAiAnswerGround> grounds = List.of(ground(1, targetRelationship + " " + unrelated));
		RecordingRewriter rewriter = RecordingRewriter.returning(targetRelationship);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"예비검토 대상에는 공공 AI 사업이 모두 포함되고 별도 금액 기준도 적용됩니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(result.verifiedAnswer()).isEqualTo(targetRelationship);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(targetRelationship));
		assertThat(String.join("\n", rewriter.atomCalls().get(0))).doesNotContain(unrelated);
	}

	@Test
	void configuredWhistleblowerScopeSelectsOnlyDirectProtectionAtoms() {
		String question = "공익신고자 보호는 어디까지 가능해?";
		String confidentiality = "공익신고자의 신분비밀을 보장합니다.";
		String physical = "공익신고자는 신변보호조치를 권익위에 요구할 수 있습니다.";
		String protection = "공익신고자는 보호조치를 권익위에 신청할 수 있습니다.";
		String unrelated = "보상금 지급요건은 별도로 정합니다.";
		String evidence = String.join(" ", List.of(
			"□ 공익신고자 보호제도",
			"○ " + confidentiality,
			"○ " + physical,
			"○ " + protection,
			"□ 공익신고자 보상제도",
			unrelated
		));
		String repaired = String.join(" ", List.of(confidentiality, physical, protection));
		List<LawAiAnswerGround> grounds = List.of(ground(1, evidence));
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"공익신고자는 아무 보호도 받을 수 없습니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0))
			.contains(confidentiality, physical, protection)
			.doesNotContain(unrelated);
	}

	@Test
	void configuredWhistleblowerDisadvantageSkipsArticleHeadingOnlyAtoms() {
		String question = "공익신고자에게 불이익을 주면 어떤 보호를 받을 수 있어?";
		String confidentiality = "공익신고자의 신분비밀을 보장합니다.";
		String heading = "제22조(불이익조치 금지 신청) 등 제22조(불이익조치 금지 신청) 등";
		String protection =
			"공익신고자등이 공익신고등을 이유로 불이익 조치를 받은 때에는 "
				+ "권익위에 보호조치를 신청할 수 있다.";
		List<LawAiAnswerGround> grounds = List.of(
			ground(1, confidentiality),
			ground(2, heading),
			ground(3, protection)
		);
		String repaired = protection + " " + confidentiality;
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"신고자에게 불이익을 계속 부과해도 됩니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0))
			.anySatisfy(atom -> assertThat(atom).contains(protection))
			.anySatisfy(atom -> assertThat(atom).contains(confidentiality))
			.noneSatisfy(atom -> assertThat(atom).isEqualTo(heading));
	}

	@Test
	void configuredNationalSafetyPlanScopeSelectsPeriodAndDirectionAtoms() {
		String question = "제5차 국가안전관리 기본계획의 적용 기간과 주요 내용은 뭐야?";
		String period = "(적용 기간) 2025년 ~ 2029년";
		String direction = "우리나라 재난·안전관리의 중장기 목표 및 기본방향";
		String unrelated = "기초자료 조사 및 연구 용역은 2023년에 수행했다.";
		String evidence = String.join(" ", List.of(
			"가. 계획의 범위",
			"❍ " + period,
			"나. 계획의 주요 내용",
			"❍ " + direction,
			"다. 수립 경과",
			"❍ " + unrelated
		));
		String repaired = period + ". " + direction + "입니다.";
		List<LawAiAnswerGround> grounds = List.of(ground(1, evidence));
		RecordingRewriter rewriter = RecordingRewriter.returning(repaired);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"적용 기간은 2030년까지입니다.",
			grounds
		);

		assertThat(result.insufficientEvidence()).as(result.toString()).isFalse();
		assertThat(rewriter.atomCalls().get(0))
			.anySatisfy(atom -> assertThat(atom).contains(period))
			.anySatisfy(atom -> assertThat(atom).contains(direction))
			.noneSatisfy(atom -> assertThat(atom).contains(unrelated));
	}

	@Test
	void realVerificationRepairsFromDirectEmailPersonalInformationGround() {
		String question = "이메일 만으로도 개인정보라고 볼 수 있나?";
		String directEvidence =
			"신청인의 회사 이메일 주소는 그 자체로 혹은 다른 정보와 쉽게 결합하여 신청인을 알아볼 수 있는 정보로서 "
				+ "「개인정보 보호법」 제2조 제1호에 따른 개인정보에 해당한다.";
		String rejectedDraft =
			"이메일 주소도 다른 정보와 결합하면 자연인을 알아볼 수 있으면 개인정보에 해당한다.";
		List<LawAiAnswerGround> grounds = List.of(ground(1, directEvidence));
		RecordingRewriter rewriter = RecordingRewriter.returning(directEvidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);
		AnswerVerificationService.Result directVerification =
			realVerificationService().verify(question, directEvidence, grounds);

		assertThat(directVerification.insufficientEvidence())
			.withFailMessage(directVerification.toString())
			.isFalse();

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			rejectedDraft,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(directEvidence);
		assertThat(result.insufficientEvidence()).isFalse();
		assertThat(rewriter.atomCalls()).containsExactly(List.of(directEvidence));
	}

	@Test
	void realVerificationRejectsSupportedButQuestionMisalignedMatchedChildAtom() {
		String question = "공공소프트웨어사업은 과업심의 대상인가?";
		String unrelatedEvidence = "정보화사업 사전협의 대상기관은 중앙행정기관입니다.";
		List<LawAiAnswerGround> grounds = List.of(ground(1, unrelatedEvidence));
		RecordingRewriter rewriter = RecordingRewriter.returning(unrelatedEvidence);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(
			realVerificationService(),
			rewriter
		);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			question,
			"오늘은 비가 옵니다.",
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.diagnostics().reason()).isEqualTo("NO_ALIGNED_SUPPORTED_ATOM");
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void unsupportedOnlyDraftCanUseAlignedAtomsFromMatchedChildText() {
		LawAiAnswerGround ground = ground(1, EVIDENCE, "제목은 입력 금지", "상위 문맥도 입력 금지");
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(claimFailure(REJECTED_DRAFT, List.of(), List.of()));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(rewriter.atomCalls()).containsExactly(List.of(EVIDENCE));
		assertThat(String.join("\n", rewriter.atomCalls().get(0)))
			.doesNotContain("제목은 입력 금지", "상위 문맥도 입력 금지");
	}

	@Test
	void displaySnippetTitleAndParentContextNeverSubstituteForMissingMatchedChildEvidence() {
		LawAiAnswerGround metadataOnlyGround = new LawAiAnswerGround(
			1,
			1L,
			1L,
			"law",
			"제목에만 있는 직접 결론",
			"",
			"",
			"20260101",
			"CURRENT",
			"제1조",
			"청크 제목에만 있는 직접 결론",
			null,
			"표시 스니펫에만 있는 직접 결론이다.",
			"",
			"",
			1.0,
			null,
			"상위 문맥에만 있는 직접 결론이다.",
			List.of(1L),
			"parent_context_expanded"
		);
		List<LawAiAnswerGround> grounds = List.of(metadataOnlyGround);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(claimFailure(REJECTED_DRAFT, List.of(), List.of()));
		when(verifier.verify(eq(QUESTION), anyString(), eq(grounds)))
			.thenAnswer(invocation -> invocation.getArgument(1).equals(REJECTED_DRAFT)
				? claimFailure(REJECTED_DRAFT, List.of(), List.of())
				: supported(invocation.getArgument(1)));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.diagnostics().reason()).isEqualTo("NO_ALIGNED_SUPPORTED_ATOM");
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void initiallyVerifiedAnswerBypassesRepair() {
		List<LawAiAnswerGround> grounds = List.of(ground(1, EVIDENCE));
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds)).thenReturn(supported(REPAIRED_ANSWER));
		RecordingRewriter rewriter = RecordingRewriter.returning("호출되면 안 됩니다.");
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REPAIRED_ANSWER,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(false, false, "INITIAL_OK", 0)
		);
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void initialVerificationExceptionFailsClosedWithoutCallingTheRewriter() {
		List<LawAiAnswerGround> grounds = List.of(ground(1, EVIDENCE));
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenThrow(new IllegalStateException("initial verifier secret"));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(
				false,
				false,
				"INITIAL_VERIFICATION_EXCEPTION",
				0
			)
		);
		assertThat(result.diagnostics().toString()).doesNotContain("initial verifier secret");
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void nullInitialVerificationFailsClosedWithoutCallingTheRewriter() {
		List<LawAiAnswerGround> grounds = List.of(ground(1, EVIDENCE));
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds)).thenReturn(null);
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);
		AtomicReference<GroundedAnswerRepairService.Result> result = new AtomicReference<>();

		assertThatCode(() -> result.set(service.verifyAndRepair(
				QUESTION,
				REJECTED_DRAFT,
				grounds
			)))
			.doesNotThrowAnyException();

		assertThat(result.get().verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.get().insufficientEvidence()).isTrue();
		assertThat(result.get().diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(false, false, "INITIAL_VERIFICATION_NULL", 0)
		);
		assertThat(rewriter.calls()).isZero();
	}

	@Test
	void reverifyExceptionFailsClosedWithoutASecondRewrite() {
		LawAiAnswerGround ground = ground(1, EVIDENCE);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(alignmentFailure(
				REJECTED_DRAFT,
				List.of(link("지원되는 초안 문장", "SUPPORTED", 1, EVIDENCE))
			));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(supported(EVIDENCE));
		when(verifier.verify(QUESTION, REPAIRED_ANSWER, grounds))
			.thenThrow(new IllegalStateException("reverify secret"));
		RecordingRewriter rewriter = RecordingRewriter.returning(REPAIRED_ANSWER);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(result.verifiedAnswer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(result.insufficientEvidence()).isTrue();
		assertThat(result.diagnostics()).isEqualTo(
			new GroundedAnswerRepairService.Diagnostics(true, false, "REVERIFY_EXCEPTION", 1)
		);
		assertThat(result.diagnostics().toString()).doesNotContain("reverify secret");
		assertThat(rewriter.calls()).isEqualTo(1);
	}

	private AnswerVerificationService realVerificationService() {
		return new AnswerVerificationService(
			new AnswerGuard(),
			new ClaimVerifier(),
			new AnswerQuestionAlignmentVerifier()
		);
	}

	private GroundedAnswerRepairService.Result runRepairWithRewrite(
		String rewritten,
		RuntimeException exception,
		AnswerVerificationService.Result atomVerification
	) {
		LawAiAnswerGround ground = ground(1, EVIDENCE);
		List<LawAiAnswerGround> grounds = List.of(ground);
		AnswerVerificationService verifier = mock(AnswerVerificationService.class);
		when(verifier.verify(QUESTION, REJECTED_DRAFT, grounds))
			.thenReturn(alignmentFailure(
				REJECTED_DRAFT,
				List.of(link("지원되는 초안 문장", "SUPPORTED", 1, EVIDENCE))
			));
		when(verifier.verify(QUESTION, EVIDENCE, grounds)).thenReturn(atomVerification);
		RecordingRewriter rewriter = exception == null
			? RecordingRewriter.returning(rewritten)
			: RecordingRewriter.throwing(exception);
		GroundedAnswerRepairService service = new GroundedAnswerRepairService(verifier, rewriter);

		GroundedAnswerRepairService.Result result = service.verifyAndRepair(
			QUESTION,
			REJECTED_DRAFT,
			grounds
		);

		assertThat(rewriter.calls()).isEqualTo(1);
		return result;
	}

	private AnswerVerificationService.Result supported(String answer) {
		ClaimVerifier.VerificationResult claimResult = new ClaimVerifier.VerificationResult(
			answer,
			false,
			false,
			List.of(),
			List.of(),
			List.of(),
			List.of(link(answer, "SUPPORTED", 1, EVIDENCE)),
			1,
			1
		);
		return new AnswerVerificationService.Result(
			answer,
			claimResult,
			new AnswerQuestionAlignmentVerifier.AlignmentResult(true, true, "ALIGNED", List.of(), answer)
		);
	}

	private AnswerVerificationService.Result alignmentFailure(
		String guardedAnswer,
		List<ClaimVerifier.ClaimEvidenceLink> links
	) {
		ClaimVerifier.VerificationResult claimResult = new ClaimVerifier.VerificationResult(
			guardedAnswer,
			false,
			false,
			List.of(),
			List.of(),
			List.of(),
			links,
			Math.max(1, links.size()),
			Math.max(1, links.size())
		);
		return new AnswerVerificationService.Result(
			guardedAnswer,
			claimResult,
			new AnswerQuestionAlignmentVerifier.AlignmentResult(
				true,
				false,
				"MISSING_SUBJECT",
				List.of("SUBJECT"),
				""
			)
		);
	}

	private AnswerVerificationService.Result claimFailure(
		String guardedAnswer,
		List<ClaimVerifier.ClaimEvidenceLink> links,
		List<String> contradictedClaims
	) {
		ClaimVerifier.VerificationResult claimResult = new ClaimVerifier.VerificationResult(
			ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE,
			true,
			true,
			List.of(guardedAnswer),
			List.of(),
			contradictedClaims,
			links,
			1,
			0
		);
		return new AnswerVerificationService.Result(
			guardedAnswer,
			claimResult,
			AnswerQuestionAlignmentVerifier.AlignmentResult.claimInsufficient()
		);
	}

	private ClaimVerifier.ClaimEvidenceLink link(
		String claim,
		String relation,
		int groundNumber,
		String evidenceSentence
	) {
		return new ClaimVerifier.ClaimEvidenceLink(
			claim,
			relation,
			groundNumber,
			evidenceSentence,
			2,
			1.0,
			1.0
		);
	}

	private LawAiAnswerGround ground(int number, String matchedChildText) {
		return ground(number, matchedChildText, "제목 " + number, null);
	}

	private LawAiAnswerGround ground(
		int number,
		String matchedChildText,
		String title,
		String parentContextText
	) {
		return new LawAiAnswerGround(
			number,
			number,
			number,
			"law",
			title,
			"",
			"",
			"20260101",
			"CURRENT",
			"제" + number + "조",
			"청크 제목 " + number,
			null,
			matchedChildText,
			"",
			"",
			1.0,
			matchedChildText,
			parentContextText,
			List.of((long) number),
			parentContextText == null ? "matched_child_only" : "parent_context_expanded"
		);
	}

	private static final class RecordingRewriter extends GroundedAnswerRewriter {
		private final String result;
		private final RuntimeException exception;
		private final List<String> questions = new ArrayList<>();
		private final List<List<String>> atomCalls = new ArrayList<>();

		private RecordingRewriter(String result, RuntimeException exception) {
			this.result = result;
			this.exception = exception;
		}

		static RecordingRewriter returning(String result) {
			return new RecordingRewriter(result, null);
		}

		static RecordingRewriter throwing(RuntimeException exception) {
			return new RecordingRewriter(null, exception);
		}

		@Override
		public String rewrite(String question, List<String> supportedEvidenceAtoms) {
			questions.add(question);
			atomCalls.add(List.copyOf(supportedEvidenceAtoms));
			if (exception != null) {
				throw exception;
			}
			return result;
		}

		int calls() {
			return atomCalls.size();
		}

		List<String> questions() {
			return List.copyOf(questions);
		}

		List<List<String>> atomCalls() {
			return List.copyOf(atomCalls);
		}
	}
}
