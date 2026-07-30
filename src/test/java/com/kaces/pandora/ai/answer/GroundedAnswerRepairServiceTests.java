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
