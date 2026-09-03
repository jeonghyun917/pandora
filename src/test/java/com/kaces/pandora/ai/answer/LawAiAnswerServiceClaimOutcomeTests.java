package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.kaces.pandora.infra.openai.OpenAiAnswerClient;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class LawAiAnswerServiceClaimOutcomeTests {

	private static final String QUESTION = "연차 유급휴가의 기준을 알려줘";
	private static final String DISCOVERY_QUESTION = "연차휴가 관련 법령";
	private static final String GENERATED_ANSWER = "사용자는 언제나 30일의 휴가를 주어야 합니다.";
	private static final String SAFE_ANSWER = "제공된 근거만으로는 30일의 휴가 의무를 확인할 수 없습니다.";
	private static final String ALIGNED_EVIDENCE =
		"사용자는 근로자에게 법정 요건에 따른 연차 유급휴가를 주어야 한다.";
	private static final String REPAIRED_ANSWER =
		"사용자는 법정 요건을 충족한 근로자에게 연차 유급휴가를 부여해야 합니다.";

	private OpenAiAnswerClient answerClient;
	private QdrantClient qdrantClient;
	private AnswerVerificationService answerVerificationService;
	private LawChunkMapper lawChunkMapper;
	private RagDocumentMapper ragDocumentMapper;
	private EvidenceJudge evidenceJudge;
	private LawAiAnswerService service;

	@BeforeEach
	void setUp() {
		LawSemanticChunkRow chunk = chunk();
		lawChunkMapper = mock(LawChunkMapper.class);
		ragDocumentMapper = mock(RagDocumentMapper.class);
		OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
		qdrantClient = mock(QdrantClient.class);
		answerClient = mock(OpenAiAnswerClient.class);
		evidenceJudge = mock(EvidenceJudge.class);
		answerVerificationService = mock(AnswerVerificationService.class);

		when(embeddingClient.embed(anyList())).thenReturn(List.of(List.of(0.1d)));
		when(qdrantClient.searchBalanced(anyList(), anyList(), anyInt(), anyInt()))
			.thenReturn(List.of(new QdrantSearchHit("law", chunk.chunkId(), 0.95d)));
		when(lawChunkMapper.findSemanticChunksByIds(anyList(), anyBoolean())).thenReturn(List.of(chunk));
		when(lawChunkMapper.findSemanticContextChunks(chunk.documentId(), chunk.sortOrder(), 18))
			.thenReturn(List.of(chunk));
		when(evidenceJudge.judge(anyString(), anyList(), anyMap(), anyInt())).thenReturn(new EvidenceJudge.Result(
			List.of(chunk),
			Map.of("law:" + chunk.chunkId(), 0.95d),
			false,
			false,
			false,
			false,
			1,
			1,
			1,
			"direct"
		));

		service = new LawAiAnswerService(
			lawChunkMapper,
			ragDocumentMapper,
			embeddingClient,
			qdrantClient,
			answerClient,
			evidenceJudge,
			new AnswerGuard(),
			new ClaimVerifier(),
			answerVerificationService,
			new ParentContextAssembler(),
			new EvidenceCandidateDiversifier(),
			mock(FailureLoggingService.class),
			null,
			new LawAiProperties(null, null, null, null)
		);
	}

	@AfterEach
	void tearDown() {
		service.shutdownExecutors();
	}

	@Test
	void claimRejectionReturnsExplicitNonOkOutcomeAndSafeVerifiedAnswer() {
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(verificationResult(GENERATED_ANSWER, SAFE_ANSWER, true));

		LawAiAnswerResponse response = service.answer(request());

		assertThat(response.resultCode()).isEqualTo("00");
		assertThat(response.resultMsg()).isEqualTo("ANSWER_CLAIM_UNSUPPORTED");
		assertThat(response.answer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(response.totalCnt()).isEqualTo(1);
	}

	@Test
	void documentDiscoveryUsesSelectedGroundMetadataWithoutGeneratingALegalAnswer() {
		LawAiAnswerResponse response = service.answer(discoveryRequest());

		assertThat(response.resultCode()).isEqualTo("00");
		assertThat(response.resultMsg()).isEqualTo("OK");
		assertThat(response.answer())
			.contains("관련 문서 검색 결과입니다.")
			.contains("[법령] 근로기준법")
			.contains("[근거 1]")
			.doesNotContain(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(response.grounds()).extracting(LawAiAnswerGround::title)
			.containsExactly("근로기준법");
		verifyNoInteractions(answerClient, answerVerificationService);
	}

	@Test
	void completeProcedureUsesTheValidatedGroundViewWithoutGenerativeRepair() {
		stubCompleteProcedureRetrieval();

		LawAiAnswerResponse response = service.answer(procedureRequest());

		assertThat(response.resultMsg()).isEqualTo("OK");
		assertThat(response.answer())
			.startsWith("절차는 다음 순서입니다.")
			.contains("② 보안성 검토요청", "③ 보안성 검토", "④ 검토결과 통보");
		verifyNoInteractions(answerClient, answerVerificationService);
	}

	@Test
	void evaluationUsesTheSameValidatedCompleteProcedureWithoutGenerativeRepair() {
		stubCompleteProcedureRetrieval();
		LawAiEvalRequest.EvalCase evalCase = new LawAiEvalRequest.EvalCase(
			"complete-procedure-eval-path",
			"보안성검토 절차는 어떻게 돼?",
			List.of("official_doc"),
			List.of("보안성 검토", "절차"),
			1,
			List.of("보안성 검토"),
			List.of("procedure"),
			List.of(),
			List.of("보안성 검토"),
			List.of(),
			List.of("절차"),
			"검토 요청부터 결과 통보까지 절차 순서로 답한다",
			List.of("OK"),
			true,
			List.of("보안성 검토요청", "검토결과 통보"),
			List.of(),
			List.of(
				List.of("보안성 검토요청"),
				List.of("보안성 검토"),
				List.of("검토결과 통보")
			),
			List.of()
		);

		LawAiEvalResponse.CaseResult result = service.evaluate(
			new LawAiEvalRequest(List.of(evalCase), List.of(), null)
		).results().get(0);

		assertThat(result.passed()).isTrue();
		assertThat(result.verifiedAnswer())
			.startsWith("절차는 다음 순서입니다.")
			.contains("② 보안성 검토요청", "③ 보안성 검토", "④ 검토결과 통보");
		verifyNoInteractions(answerClient, answerVerificationService);
	}

	@Test
	void streamingDocumentDiscoveryEmitsTheSameDeterministicAnswer() throws Exception {
		CapturingEmitter emitter = new CapturingEmitter();

		invokeStream(discoveryRequest(), emitter);

		assertThat(deltaTexts(emitter))
			.singleElement()
			.asString()
			.contains("[법령] 근로기준법", "[근거 1]");
		assertThat(emitter.data()).filteredOn(LawAiAnswerResponse.class::isInstance)
			.map(LawAiAnswerResponse.class::cast)
			.anySatisfy(response -> {
				assertThat(response.resultMsg()).isEqualTo("OK");
				assertThat(response.answer()).contains("관련 문서 검색 결과입니다.");
			});
		assertThat(emitter.data()).filteredOn(Map.class::isInstance)
			.map(Map.class::cast)
			.anySatisfy(done -> {
				assertThat(done.get("ok")).isEqualTo(true);
				assertThat(done.get("resultMsg")).isEqualTo("OK");
			});
		verifyNoInteractions(answerClient, answerVerificationService);
	}

	@Test
	void evaluationUsesTheSameDeterministicDocumentDiscoveryAnswer() {
		LawAiEvalRequest.EvalCase evalCase = new LawAiEvalRequest.EvalCase(
			"document-discovery-eval-path",
			DISCOVERY_QUESTION,
			List.of("law"),
			List.of("연차 유급휴가"),
			1,
			List.of("근로기준법"),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			"검색된 문서 목록을 출처 유형과 함께 제시",
			List.of("OK"),
			true,
			List.of("관련 문서 검색 결과입니다", "[법령] 근로기준법"),
			List.of(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE)
		);

		LawAiEvalResponse.CaseResult result = service.evaluate(
			new LawAiEvalRequest(List.of(evalCase), List.of(), null)
		).results().get(0);

		assertThat(result.passed()).isTrue();
		assertThat(result.answerVerified()).isTrue();
		assertThat(result.verifiedAnswer())
			.contains("관련 문서 검색 결과입니다", "[법령] 근로기준법", "[근거 1]");
		assertThat(result.unsupportedAnswerClaims()).isEmpty();
		verifyNoInteractions(answerClient, answerVerificationService);
	}

	@Test
	void claimRejectionIsNotCachedAcrossIdenticalRequests() {
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(verificationResult(GENERATED_ANSWER, SAFE_ANSWER, true));

		LawAiAnswerResponse first = service.answer(request());
		LawAiAnswerResponse second = service.answer(request());

		verify(answerClient, times(2)).answer(anyString(), anyString(), anyInt());
		assertThat(first.timing().cacheHit()).isFalse();
		assertThat(second.timing().cacheHit()).isFalse();
	}

	@Test
	void supportedRemainderAfterPartialSanitizationStaysOkAndIsCached() {
		String supportedAnswer = "근거에는 법정 요건에 따른 연차 유급휴가 부여 의무가 명시되어 있습니다.";
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(verificationResult(GENERATED_ANSWER, supportedAnswer, false));

		LawAiAnswerResponse first = service.answer(request());
		LawAiAnswerResponse second = service.answer(request());

		assertThat(first.resultMsg()).isEqualTo("OK");
		assertThat(first.answer()).isEqualTo(supportedAnswer);
		assertThat(second.resultMsg()).isEqualTo("OK");
		assertThat(second.timing().cacheHit()).isTrue();
		verify(answerClient).answer(anyString(), anyString(), anyInt());
	}

	@Test
	void successfulRepairReturnsOnlyTheReverifiedAnswerAndCachesThatFinalOkOutcome() {
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerClient.rewrite(eq(QUESTION), anyList())).thenReturn(REPAIRED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(repairableVerificationResult());
		when(answerVerificationService.verify(eq(QUESTION), eq(ALIGNED_EVIDENCE), anyList()))
			.thenReturn(verificationResult(ALIGNED_EVIDENCE, ALIGNED_EVIDENCE, false));
		when(answerVerificationService.verify(eq(QUESTION), eq(REPAIRED_ANSWER), anyList()))
			.thenReturn(verificationResult(REPAIRED_ANSWER, REPAIRED_ANSWER, false));

		LawAiAnswerResponse first = service.answer(request());
		LawAiAnswerResponse second = service.answer(request());

		assertThat(first.resultMsg()).isEqualTo("OK");
		assertThat(first.answer()).isEqualTo(REPAIRED_ANSWER).doesNotContain(GENERATED_ANSWER);
		assertThat(first.timing().cacheHit()).isFalse();
		assertThat(second.answer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(second.timing().cacheHit()).isTrue();
		verify(answerClient).answer(anyString(), anyString(), anyInt());
		verify(answerClient).rewrite(eq(QUESTION), eq(List.of(ALIGNED_EVIDENCE)));
	}

	@Test
	void failedRepairReturnsTheExactStandardResponseAndIsNeverCached() {
		String unsupportedRewrite = "모든 근로자는 조건 없이 30일의 휴가를 받습니다.";
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerClient.rewrite(eq(QUESTION), anyList())).thenReturn(unsupportedRewrite);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(repairableVerificationResult());
		when(answerVerificationService.verify(eq(QUESTION), eq(ALIGNED_EVIDENCE), anyList()))
			.thenReturn(verificationResult(ALIGNED_EVIDENCE, ALIGNED_EVIDENCE, false));
		when(answerVerificationService.verify(eq(QUESTION), eq(unsupportedRewrite), anyList()))
			.thenReturn(verificationResult(
				unsupportedRewrite,
				ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE,
				true
			));

		LawAiAnswerResponse first = service.answer(request());
		LawAiAnswerResponse second = service.answer(request());

		assertThat(first.resultMsg()).isEqualTo("ANSWER_CLAIM_UNSUPPORTED");
		assertThat(first.answer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
		assertThat(second.resultMsg()).isEqualTo("ANSWER_CLAIM_UNSUPPORTED");
		assertThat(second.timing().cacheHit()).isFalse();
		verify(answerClient, times(2)).answer(anyString(), anyString(), anyInt());
		verify(answerClient, times(2)).rewrite(eq(QUESTION), eq(List.of(ALIGNED_EVIDENCE)));
	}

	@Test
	void explicitOracleEvaluationDoesNotMixLegacyFallbackAndReportsMissingAndForbiddenDiagnostics() {
		String explicitAnswer = "법정 요건에 따른 연차 유급휴가입니다. 언제나 30일입니다.";
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(explicitAnswer);
		when(answerVerificationService.verify(eq(QUESTION), eq(explicitAnswer), anyList()))
			.thenReturn(verificationResult(explicitAnswer, explicitAnswer, false));
		LawAiEvalRequest.EvalCase evalCase = new LawAiEvalRequest.EvalCase(
			"explicit-service-path",
			QUESTION,
			List.of("law"),
			List.of("연차 유급휴가"),
			1,
			List.of("근로기준법"),
			List.of("requirement"),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			"직접 결론과 조건을 답한다",
			List.of("OK"),
			true,
			List.of("레거시 필수어"),
			List.of("언제나 30일"),
			List.of(List.of("법정 요건에 따른 연차 유급휴가")),
			List.of(List.of("근로기간을 충족한 경우"))
		);

		LawAiEvalResponse.CaseResult result = service.evaluate(
			new LawAiEvalRequest(List.of(evalCase), List.of(), null)
		).results().get(0);

		assertThat(result.answerVerificationRequired()).isTrue();
		assertThat(result.answerVerified()).isFalse();
		assertThat(result.matchedAnswerTerms()).containsExactly("법정 요건에 따른 연차 유급휴가");
		assertThat(result.missingAnswerTerms()).containsExactly("근로기간을 충족한 경우");
		assertThat(result.missingAnswerTerms()).doesNotContain("레거시 필수어");
		assertThat(result.forbiddenAnswerMatchedTerms()).containsExactly("언제나 30일");
		assertThat(result.message())
			.contains("missing condition groups=근로기간을 충족한 경우")
			.contains("matched forbidden expressions=언제나 30일");
	}

	@Test
	void streamingClaimRejectionEmitsNonOkAnswerAndFailedDoneEvent() throws Exception {
		when(answerClient.answerStreaming(anyString(), anyString(), any(), anyInt()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				Consumer<String> onDelta = invocation.getArgument(2);
				onDelta.accept(GENERATED_ANSWER);
				return GENERATED_ANSWER;
			});
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(verificationResult(GENERATED_ANSWER, SAFE_ANSWER, true));
		CapturingEmitter emitter = new CapturingEmitter();

		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"streamAnswer",
			LawAiAnswerRequest.class,
			SseEmitter.class
		);
		method.setAccessible(true);
		method.invoke(service, request(), emitter);

		assertThat(deltaTexts(emitter)).isEmpty();
		assertThat(emitter.data()).filteredOn(LawAiAnswerResponse.class::isInstance)
			.map(LawAiAnswerResponse.class::cast)
			.anySatisfy(response -> {
				assertThat(response.resultMsg()).isEqualTo("ANSWER_CLAIM_UNSUPPORTED");
				assertThat(response.answer()).isEqualTo(ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE);
			});
		assertThat(emitter.data()).filteredOn(Map.class::isInstance)
			.map(Map.class::cast)
			.anySatisfy(done -> {
				assertThat(done.get("ok")).isEqualTo(false);
				assertThat(done.get("resultMsg")).isEqualTo("ANSWER_CLAIM_UNSUPPORTED");
			});
	}

	@Test
	void streamingPartialSanitizationEmitsOnlyVerifiedAnswerDelta() throws Exception {
		String verifiedAnswer = "The evidence supports only this safe remainder.";
		when(answerClient.answerStreaming(anyString(), anyString(), any(), anyInt()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				Consumer<String> onDelta = invocation.getArgument(2);
				onDelta.accept(GENERATED_ANSWER);
				return GENERATED_ANSWER;
			});
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(verificationResult(GENERATED_ANSWER, verifiedAnswer, false));
		CapturingEmitter emitter = new CapturingEmitter();

		invokeStream(emitter);

		assertThat(deltaTexts(emitter)).containsExactly(verifiedAnswer);
	}

	@Test
	void streamingRepairWithholdsRawDeltasAndEmitsOnlyTheFinalReverifiedAnswer() throws Exception {
		when(answerClient.answerStreaming(anyString(), anyString(), any(), anyInt()))
			.thenAnswer(invocation -> {
				@SuppressWarnings("unchecked")
				Consumer<String> onDelta = invocation.getArgument(2);
				onDelta.accept(GENERATED_ANSWER);
				return GENERATED_ANSWER;
			});
		when(answerClient.rewrite(eq(QUESTION), anyList())).thenReturn(REPAIRED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(repairableVerificationResult());
		when(answerVerificationService.verify(eq(QUESTION), eq(ALIGNED_EVIDENCE), anyList()))
			.thenReturn(verificationResult(ALIGNED_EVIDENCE, ALIGNED_EVIDENCE, false));
		when(answerVerificationService.verify(eq(QUESTION), eq(REPAIRED_ANSWER), anyList()))
			.thenReturn(verificationResult(REPAIRED_ANSWER, REPAIRED_ANSWER, false));
		CapturingEmitter emitter = new CapturingEmitter();

		invokeStream(emitter);

		assertThat(deltaTexts(emitter)).containsExactly(REPAIRED_ANSWER);
		assertThat(deltaTexts(emitter)).doesNotContain(GENERATED_ANSWER);
		assertThat(emitter.data()).filteredOn(LawAiAnswerResponse.class::isInstance)
			.map(LawAiAnswerResponse.class::cast)
			.anySatisfy(response -> {
				assertThat(response.resultMsg()).isEqualTo("OK");
				assertThat(response.answer()).isEqualTo(REPAIRED_ANSWER);
			});
		verify(answerClient).rewrite(eq(QUESTION), eq(List.of(ALIGNED_EVIDENCE)));
	}

	@Test
	void answerLevelEvaluationUsesTheSameRepairedAndReverifiedAnswer() {
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerClient.rewrite(eq(QUESTION), anyList())).thenReturn(REPAIRED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(repairableVerificationResult());
		when(answerVerificationService.verify(eq(QUESTION), eq(ALIGNED_EVIDENCE), anyList()))
			.thenReturn(verificationResult(ALIGNED_EVIDENCE, ALIGNED_EVIDENCE, false));
		when(answerVerificationService.verify(eq(QUESTION), eq(REPAIRED_ANSWER), anyList()))
			.thenReturn(verificationResult(REPAIRED_ANSWER, REPAIRED_ANSWER, false));
		LawAiEvalRequest.EvalCase evalCase = new LawAiEvalRequest.EvalCase(
			"repair-eval-path",
			QUESTION,
			List.of("law"),
			List.of("연차 유급휴가"),
			1,
			List.of("근로기준법"),
			List.of("requirement"),
			List.of(),
			List.of(),
			List.of(),
			List.of(),
			"법정 요건에 따른 부여 의무를 답한다",
			List.of("OK"),
			true,
			List.of("법정 요건", "연차 유급휴가"),
			List.of()
		);

		LawAiEvalResponse.CaseResult result = service.evaluate(
			new LawAiEvalRequest(List.of(evalCase), List.of(), null)
		).results().get(0);

		assertThat(result.answerVerificationRequired()).isTrue();
		assertThat(result.answerVerified()).isTrue();
		assertThat(result.verifiedAnswer()).isEqualTo(REPAIRED_ANSWER);
		assertThat(result.forbiddenAnswerMatchedTerms()).isEmpty();
		verify(answerClient).rewrite(eq(QUESTION), eq(List.of(ALIGNED_EVIDENCE)));
	}

	@Test
	void streamingNoGroundsEmitsFailedDoneEvent() throws Exception {
		when(qdrantClient.searchBalanced(anyList(), anyList(), anyInt(), anyInt())).thenReturn(List.of());
		CapturingEmitter emitter = new CapturingEmitter();

		invokeStream(emitter);

		assertThat(emitter.data()).filteredOn(Map.class::isInstance)
			.map(Map.class::cast)
			.anySatisfy(done -> {
				assertThat(done.get("ok")).isEqualTo(false);
				assertThat(done.get("resultMsg")).isEqualTo("NO_GROUNDS");
			});
	}

	@Test
	void streamingCacheHitDoneIncludesCachedResultMessage() throws Exception {
		when(answerClient.answer(anyString(), anyString(), anyInt())).thenReturn(GENERATED_ANSWER);
		when(answerVerificationService.verify(eq(QUESTION), eq(GENERATED_ANSWER), anyList()))
			.thenReturn(verificationResult(GENERATED_ANSWER, SAFE_ANSWER, false));
		LawAiAnswerResponse seeded = service.answer(request());
		CapturingEmitter emitter = new CapturingEmitter();

		invokeStream(emitter);

		assertThat(seeded.resultMsg()).isEqualTo("OK");
		assertThat(emitter.data()).filteredOn(Map.class::isInstance)
			.map(Map.class::cast)
			.anySatisfy(done -> {
				assertThat(done.get("ok")).isEqualTo(true);
				assertThat(done.get("resultMsg")).isEqualTo("OK");
			});
	}

	private void invokeStream(CapturingEmitter emitter) throws Exception {
		invokeStream(request(), emitter);
	}

	private void invokeStream(LawAiAnswerRequest request, CapturingEmitter emitter) throws Exception {
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"streamAnswer",
			LawAiAnswerRequest.class,
			SseEmitter.class
		);
		method.setAccessible(true);
		method.invoke(service, request, emitter);
	}

	private List<String> deltaTexts(CapturingEmitter emitter) {
		return emitter.data().stream()
			.filter(Map.class::isInstance)
			.map(Map.class::cast)
			.map(event -> event.get("text"))
			.filter(String.class::isInstance)
			.map(String.class::cast)
			.toList();
	}

	private LawAiAnswerRequest request() {
		return new LawAiAnswerRequest("law", List.of("law"), QUESTION, 1, false);
	}

	private LawAiAnswerRequest discoveryRequest() {
		return new LawAiAnswerRequest("law", List.of("law"), DISCOVERY_QUESTION, 1, false);
	}

	private LawAiAnswerRequest procedureRequest() {
		return new LawAiAnswerRequest(
			"official_doc",
			List.of("official_doc"),
			"보안성검토 절차는 어떻게 돼?",
			1,
			false
		);
	}

	private void stubCompleteProcedureRetrieval() {
		LawSemanticChunkRow procedure = procedureChunk();
		when(qdrantClient.searchBalanced(anyList(), anyList(), anyInt(), anyInt()))
			.thenReturn(List.of(new QdrantSearchHit("official_doc", procedure.chunkId(), 0.99d)));
		when(ragDocumentMapper.findSemanticChunksByIds(anyList())).thenReturn(List.of(procedure));
		when(ragDocumentMapper.findSemanticContextChunks(procedure.documentId(), procedure.sortOrder(), 18))
			.thenReturn(List.of(procedure));
		when(evidenceJudge.judge(anyString(), anyList(), anyMap(), anyInt())).thenReturn(new EvidenceJudge.Result(
			List.of(procedure),
			Map.of("official_doc:" + procedure.chunkId(), 0.99d),
			false,
			true,
			false,
			false,
			1,
			1,
			1,
			"direct"
		));
	}

	private AnswerVerificationService.Result verificationResult(
		String guardedAnswer,
		String verifiedAnswer,
		boolean insufficientEvidence
	) {
		ClaimVerifier.VerificationResult claimResult = new ClaimVerifier.VerificationResult(
			verifiedAnswer,
			insufficientEvidence,
			!verifiedAnswer.equals(guardedAnswer),
			insufficientEvidence ? List.of(GENERATED_ANSWER) : List.of(),
			List.of(),
			List.of(),
			insufficientEvidence ? List.of() : List.of(new ClaimVerifier.ClaimEvidenceLink(
				verifiedAnswer,
				"SUPPORTED",
				1,
				ALIGNED_EVIDENCE,
				2,
				1.0,
				1.0
			)),
			1,
			insufficientEvidence ? 0 : 1
		);
		return new AnswerVerificationService.Result(
			guardedAnswer,
			claimResult,
			insufficientEvidence
				? AnswerQuestionAlignmentVerifier.AlignmentResult.claimInsufficient()
				: new AnswerQuestionAlignmentVerifier.AlignmentResult(
					true,
					true,
					"ALIGNED",
					List.of(),
					verifiedAnswer
				)
		);
	}

	private AnswerVerificationService.Result repairableVerificationResult() {
		return new AnswerVerificationService.Result(
			GENERATED_ANSWER,
			new ClaimVerifier.VerificationResult(
				ClaimVerifier.INSUFFICIENT_EVIDENCE_MESSAGE,
				true,
				true,
				List.of(GENERATED_ANSWER),
				List.of(GENERATED_ANSWER),
				List.of(),
				List.of(new ClaimVerifier.ClaimEvidenceLink(
					GENERATED_ANSWER,
					"SUPPORTED",
					1,
					ALIGNED_EVIDENCE,
					3,
					1.0,
					1.0
				)),
				2,
				1
			),
			AnswerQuestionAlignmentVerifier.AlignmentResult.claimInsufficient()
		);
	}

	private LawSemanticChunkRow chunk() {
		return new LawSemanticChunkRow(
			101L,
			201L,
			"law",
			"law-201",
			"근로기준법",
			"",
			"법률",
			"20260101",
			"CURRENT",
			"제60조",
			"연차 유급휴가",
			"사용자는 근로자에게 법정 요건에 따른 연차 유급휴가를 주어야 한다.",
			null,
			"",
			"",
			1,
			"hash-101",
			"제60조 연차 유급휴가",
			"requirement"
		);
	}

	private LawSemanticChunkRow procedureChunk() {
		return new LawSemanticChunkRow(
			84923L,
			8L,
			"official_doc",
			"official-8",
			"2026년 정보화사업 보안성 검토 가이드",
			"행정안전부",
			"공식 가이드 문서",
			"20260101",
			"CURRENT",
			"page 2",
			"보안성 검토 절차",
			"② 보안성 검토요청: 신청서를 제출한다. "
				+ "③ 보안성 검토: 보안대책의 적절성을 검토한다. "
				+ "④ 검토결과 통보: 결과서를 사업부서에 통보한다.",
			2,
			"",
			"",
			1,
			"hash-84923",
			"보안성 검토 절차",
			"procedure"
		);
	}

	private static final class CapturingEmitter extends SseEmitter {
		private final List<Object> data = new ArrayList<>();

		@Override
		public void send(SseEventBuilder builder) {
			builder.build().stream()
				.map(DataWithMediaType::getData)
				.forEach(data::add);
		}

		List<Object> data() {
			return List.copyOf(data);
		}
	}
}
