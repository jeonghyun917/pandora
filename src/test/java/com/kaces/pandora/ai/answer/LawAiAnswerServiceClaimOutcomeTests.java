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
	private static final String GENERATED_ANSWER = "사용자는 언제나 30일의 휴가를 주어야 합니다.";
	private static final String SAFE_ANSWER = "제공된 근거만으로는 30일의 휴가 의무를 확인할 수 없습니다.";

	private OpenAiAnswerClient answerClient;
	private QdrantClient qdrantClient;
	private AnswerVerificationService answerVerificationService;
	private LawAiAnswerService service;

	@BeforeEach
	void setUp() {
		LawSemanticChunkRow chunk = chunk();
		LawChunkMapper lawChunkMapper = mock(LawChunkMapper.class);
		RagDocumentMapper ragDocumentMapper = mock(RagDocumentMapper.class);
		OpenAiEmbeddingClient embeddingClient = mock(OpenAiEmbeddingClient.class);
		qdrantClient = mock(QdrantClient.class);
		answerClient = mock(OpenAiAnswerClient.class);
		EvidenceJudge evidenceJudge = mock(EvidenceJudge.class);
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
		assertThat(response.answer()).isEqualTo(SAFE_ANSWER);
		assertThat(response.totalCnt()).isEqualTo(1);
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
				assertThat(response.answer()).isEqualTo(SAFE_ANSWER);
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
		Method method = LawAiAnswerService.class.getDeclaredMethod(
			"streamAnswer",
			LawAiAnswerRequest.class,
			SseEmitter.class
		);
		method.setAccessible(true);
		method.invoke(service, request(), emitter);
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

	private AnswerVerificationService.Result verificationResult(
		String guardedAnswer,
		String verifiedAnswer,
		boolean insufficientEvidence
	) {
		return new AnswerVerificationService.Result(
			guardedAnswer,
			new ClaimVerifier.VerificationResult(
				verifiedAnswer,
				insufficientEvidence,
				!verifiedAnswer.equals(guardedAnswer),
				insufficientEvidence ? List.of(GENERATED_ANSWER) : List.of(),
				List.of(),
				List.of(),
				List.of(),
				1,
				insufficientEvidence ? 0 : 1
			)
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
