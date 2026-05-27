package com.kaces.pandora.ai.answer;

import com.kaces.pandora.infra.openai.OpenAiAnswerClient;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.search.LawSearchQuery;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class LawAiAnswerService {

	private static final Logger log = LoggerFactory.getLogger(LawAiAnswerService.class);
	private static final int DEFAULT_LIMIT = 8;
	private static final int MAX_LIMIT = 15;
	private static final int VECTOR_CANDIDATE_LIMIT = 50;
	private static final int KEYWORD_CANDIDATE_LIMIT = 30;
	private static final int FOCUSED_RAG_KEYWORD_FETCH_LIMIT = 120;
	private static final int GENERIC_RAG_KEYWORD_FETCH_LIMIT = 45;
	private static final int LAW_TITLE_KEYWORD_FETCH_LIMIT = 20;
	private static final int JUDGE_CANDIDATE_LIMIT = 30;
	private static final int MAX_USEFUL_TEXT_CHECK_CHARS = 900;
	private static final int MAX_ANSWER_CONTEXT_CHARS_PER_GROUND = 620;
	private static final int MIN_ANSWER_CONTEXT_GROUNDS = 4;
	private static final int DEFAULT_ANSWER_CONTEXT_GROUNDS = 5;
	private static final int MAX_ANSWER_CONTEXT_GROUNDS = DEFAULT_LIMIT;
	private static final long ANSWER_CACHE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
	private static final int MAX_ANSWER_CACHE_ENTRIES = 200;

	private final LawChunkMapper lawChunkMapper;
	private final RagDocumentMapper ragDocumentMapper;
	private final OpenAiEmbeddingClient embeddingClient;
	private final QdrantClient qdrantClient;
	private final OpenAiAnswerClient answerClient;
	private final EvidenceJudge evidenceJudge;
	private final AnswerGuard answerGuard;
	private final LawAiProperties properties;
	private final Map<String, CachedAnswer> answerCache = new ConcurrentHashMap<>();
	private final ExecutorService streamExecutor;
	private final ExecutorService searchExecutor;

	public LawAiAnswerService(
		LawChunkMapper lawChunkMapper,
		RagDocumentMapper ragDocumentMapper,
		OpenAiEmbeddingClient embeddingClient,
		QdrantClient qdrantClient,
		OpenAiAnswerClient answerClient,
		EvidenceJudge evidenceJudge,
		AnswerGuard answerGuard,
		LawAiProperties properties
	) {
		this.lawChunkMapper = lawChunkMapper;
		this.ragDocumentMapper = ragDocumentMapper;
		this.embeddingClient = embeddingClient;
		this.qdrantClient = qdrantClient;
		this.answerClient = answerClient;
		this.evidenceJudge = evidenceJudge;
		this.answerGuard = answerGuard;
		this.properties = properties;
		this.streamExecutor = Executors.newFixedThreadPool(4, namedThreadFactory("law-ai-stream-"));
		this.searchExecutor = Executors.newFixedThreadPool(8, namedThreadFactory("law-ai-search-"));
	}

	@PreDestroy
	// 메소드 설명: shutdownExecutors 처리 흐름을 수행합니다.
	public void shutdownExecutors() {
		streamExecutor.shutdownNow();
		searchExecutor.shutdownNow();
	}

	// 메소드 설명: answer 처리 흐름을 수행합니다.
	public LawAiAnswerResponse answer(LawAiAnswerRequest request) {
		TimingProbe timing = TimingProbe.started();
		String cacheKey = answerCacheKey(request);
		LawAiAnswerResponse cached = cachedAnswer(cacheKey, timing);
		if (cached != null) {
			logTiming("cache", cached.question(), requestTargetsForLog(request), cached.totalCnt(), cached.timing());
			return cached;
		}

		RetrievalResult retrieval = retrieve(request, timing);
		if (!"OK".equals(retrieval.resultMsg())) {
			LawAiAnswerResponse response = new LawAiAnswerResponse(
				"00",
				retrieval.resultMsg(),
				retrieval.target(),
				retrieval.query(),
				properties.openai().answerModel(),
				retrieval.message(),
				0,
				List.of(),
				timing.snapshot(false)
			);
			logTiming("answer", retrieval.query(), retrieval.targets(), 0, response.timing());
			return response;
		}

		long answerStart = System.nanoTime();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		String answer = answerClient.answer(retrieval.query(), buildAnswerContext(retrieval));
		timing.answerMs.set(elapsedMillis(answerStart));
		String guardedAnswer = answerGuard.guard(answer, retrieval.grounds());
		LawAiAnswerResponse response = new LawAiAnswerResponse(
			"00",
			"OK",
			retrieval.target(),
			retrieval.query(),
			properties.openai().answerModel(),
			guardedAnswer,
			retrieval.grounds().size(),
			retrieval.grounds(),
			timing.snapshot(false)
		);
		cacheAnswer(cacheKey, response);
		logTiming("answer", retrieval.query(), retrieval.targets(), retrieval.grounds().size(), response.timing());
		return response;
	}

	// 메소드 설명: answerStream 처리 흐름을 수행합니다.
	public SseEmitter answerStream(LawAiAnswerRequest request) {
		SseEmitter emitter = new SseEmitter(Duration.ofMinutes(4).toMillis());
		CompletableFuture.runAsync(() -> streamAnswer(request, emitter), streamExecutor);
		return emitter;
	}

	// 메소드 설명: streamAnswer 처리 흐름을 수행합니다.
	private void streamAnswer(LawAiAnswerRequest request, SseEmitter emitter) {
		TimingProbe timing = TimingProbe.started();
		String cacheKey = answerCacheKey(request);
		try {
			LawAiAnswerResponse cached = cachedAnswer(cacheKey, timing);
			if (cached != null) {
				sendEvent(emitter, "grounds", cached);
				sendEvent(emitter, "answer", cached);
				sendEvent(emitter, "done", Map.of("ok", true));
				logTiming("stream-cache", cached.question(), requestTargetsForLog(request), cached.totalCnt(), cached.timing());
				emitter.complete();
				return;
			}

			RetrievalResult retrieval = retrieve(request, timing);
			if (!"OK".equals(retrieval.resultMsg())) {
				LawAiAnswerResponse response = new LawAiAnswerResponse(
					"00",
					retrieval.resultMsg(),
					retrieval.target(),
					retrieval.query(),
					properties.openai().answerModel(),
					retrieval.message(),
					0,
					List.of(),
					timing.snapshot(false)
				);
				sendEvent(emitter, "answer", response);
				sendEvent(emitter, "done", Map.of("ok", true));
				logTiming("stream", retrieval.query(), retrieval.targets(), 0, response.timing());
				emitter.complete();
				return;
			}

			LawAiAnswerResponse groundsResponse = new LawAiAnswerResponse(
				"00",
				"RETRIEVED",
				retrieval.target(),
				retrieval.query(),
				properties.openai().answerModel(),
				"",
				retrieval.grounds().size(),
				retrieval.grounds(),
				timing.snapshot(false)
			);
			sendEvent(emitter, "grounds", groundsResponse);

			long answerStart = System.nanoTime();
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			String answer = answerClient.answerStreaming(
				retrieval.query(),
				buildAnswerContext(retrieval),
				delta -> sendEvent(emitter, "delta", Map.of("text", delta))
			);
			timing.answerMs.set(elapsedMillis(answerStart));
			String guardedAnswer = answerGuard.guard(answer, retrieval.grounds());
			LawAiAnswerResponse response = new LawAiAnswerResponse(
				"00",
				"OK",
				retrieval.target(),
				retrieval.query(),
				properties.openai().answerModel(),
				guardedAnswer,
				retrieval.grounds().size(),
				retrieval.grounds(),
				timing.snapshot(false)
			);
			cacheAnswer(cacheKey, response);
			sendEvent(emitter, "answer", response);
			sendEvent(emitter, "done", Map.of("ok", true));
			logTiming("stream", retrieval.query(), retrieval.targets(), retrieval.grounds().size(), response.timing());
			emitter.complete();
		} catch (RuntimeException exception) {
			try {
				sendEvent(emitter, "error", Map.of("message", exception.getMessage() == null ? "AI 답변 생성 중 오류가 발생했습니다." : exception.getMessage()));
			} finally {
				emitter.completeWithError(exception);
			}
		}
	}

	// 메소드 설명: sendEvent 처리 흐름을 수행합니다.
	private void sendEvent(SseEmitter emitter, String name, Object data) {
		try {
			emitter.send(SseEmitter.event().name(name).data(data));
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("Failed to send AI answer stream event.", exception);
		}
	}

	// 메소드 설명: debug 처리 흐름을 수행합니다.
	public LawAiDebugResponse debug(LawAiDebugRequest request) {
		TimingProbe timing = TimingProbe.started();
		RetrievalResult retrieval = retrieve(new LawAiAnswerRequest(
			request == null ? null : request.target(),
			request == null ? null : request.targets(),
			request == null ? null : request.question(),
			request == null ? null : request.limit()
		), timing);
		LawAiTiming snapshot = timing.snapshot(false);
		logTiming("debug", retrieval.query(), retrieval.targets(), retrieval.grounds().size(), snapshot);
		return toDebugResponse(retrieval, snapshot);
	}

	// 메소드 설명: defaultEvaluationCases 처리 흐름을 수행합니다.
	public List<LawAiEvalRequest.EvalCase> defaultEvaluationCases() {
		return List.of(
			new LawAiEvalRequest.EvalCase(
				"project-review-target",
				"과업심의 대상은?",
				List.of("official_doc", "internal_doc"),
				List.of("대상사업", "국가기관등의 장이 발주하는 소프트웨어사업", "소프트웨어사업"),
				2
			),
			new LawAiEvalRequest.EvalCase(
				"simple-hardware-exclusion",
				"공공소프트웨어사업에서 단순 하드웨어 구매는 소프트웨어사업에 포함되나요?",
				List.of("official_doc", "internal_doc"),
				List.of("단순 H/W", "소프트웨어사업으로 볼 수 없는", "비대상"),
				1
			),
			new LawAiEvalRequest.EvalCase(
				"pre-consultation-target",
				"기타공공기관 사전협의 대상 알려줘",
				List.of("official_doc", "internal_doc", "admrul", "law"),
				List.of("사전협의의 대상사업", "대상기관", "추진하는 모든 정보화사업"),
				2
			),
			new LawAiEvalRequest.EvalCase(
				"rfp-required-items",
				"공공기관 제안요청서 작성할때 필수요소가 있나?",
				List.of("official_doc", "internal_doc", "admrul", "law"),
				List.of("제안요청서에는", "과업내용", "요구사항", "계약조건"),
				2
			)
		);
	}

	// 메소드 설명: evaluate 처리 흐름을 수행합니다.
	public LawAiEvalResponse evaluate(LawAiEvalRequest request) {
		List<LawAiEvalRequest.EvalCase> cases = request == null || request.cases() == null || request.cases().isEmpty()
			? defaultEvaluationCases()
			: request.cases();
		List<LawAiEvalResponse.CaseResult> results = cases.stream()
			.map(this::evaluateCaseSafely)
			.toList();
		int passed = (int) results.stream().filter(LawAiEvalResponse.CaseResult::passed).count();
		return new LawAiEvalResponse(results.size(), passed, results.size() - passed, results);
	}

	// 메소드 설명: retrieve 처리 흐름을 수행합니다.
	private RetrievalResult retrieve(LawAiAnswerRequest request) {
		return retrieve(request, TimingProbe.started());
	}

	// 메소드 설명: retrieve 처리 흐름을 수행합니다.
	private RetrievalResult retrieve(LawAiAnswerRequest request, TimingProbe timing) {
		String question = request == null ? "" : request.question();
		int limit = request == null || request.limit() == null ? DEFAULT_LIMIT : request.limit();
		LawSearchQuery normalized = LawSearchQuery.normalize(
			request == null ? null : request.target(),
			question,
			1,
			Math.max(1, Math.min(limit, MAX_LIMIT))
		);
		if (normalized.searchAll()) {
			throw new IllegalArgumentException("Question is required.");
		}

		List<String> targets = answerTargets(request == null ? null : request.targets(), normalized.target());
		List<String> lexicalKeywords = lexicalKeywords(normalized.query());
		CompletableFuture<List<LawSemanticChunkRow>> lexicalFuture = CompletableFuture.supplyAsync(() -> {
			long start = System.nanoTime();
			try {
				return findLexicalChunks(normalized.query(), targets);
			} finally {
				timing.dbMs.addAndGet(elapsedMillis(start));
			}
		}, searchExecutor);
		CompletableFuture<List<Double>> embeddingFuture = CompletableFuture.supplyAsync(() -> {
			long start = System.nanoTime();
			try {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				return embeddingClient.embed(List.of(normalized.query())).get(0);
			} finally {
				timing.embeddingMs.set(elapsedMillis(start));
			}
		}, searchExecutor);

		List<Double> queryVector = joinFuture(embeddingFuture);
		long qdrantStart = System.nanoTime();
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<QdrantSearchHit> hits = qdrantClient.search(queryVector, targets, VECTOR_CANDIDATE_LIMIT);
		timing.qdrantMs.set(elapsedMillis(qdrantStart));

		Map<String, Double> vectorScoreByChunkId = new HashMap<>();
		for (QdrantSearchHit hit : hits) {
			vectorScoreByChunkId.put(scoreKey(hit.target(), hit.chunkId()), hit.score());
		}
		Map<String, LawSemanticChunkRow> chunkById = new HashMap<>();
		long vectorDbStart = System.nanoTime();
		List<Long> lawChunkIds = hits.stream()
			.filter(hit -> isLawTarget(hit.target()))
			.map(QdrantSearchHit::chunkId)
			.distinct()
			.toList();
		if (!lawChunkIds.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			for (LawSemanticChunkRow chunk : lawChunkMapper.findSemanticChunksByIds(lawChunkIds)) {
				chunkById.put(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			}
		}
		List<Long> ragChunkIds = hits.stream()
			.filter(hit -> isRagTarget(hit.target()))
			.map(QdrantSearchHit::chunkId)
			.distinct()
			.toList();
		if (!ragChunkIds.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			for (LawSemanticChunkRow chunk : ragDocumentMapper.findSemanticChunksByIds(ragChunkIds)) {
				chunkById.put(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			}
		}
		timing.dbMs.addAndGet(elapsedMillis(vectorDbStart));
		List<LawSemanticChunkRow> lexicalChunks = joinFuture(lexicalFuture);
		Map<String, Double> keywordScoreByChunkId = new HashMap<>();
		for (LawSemanticChunkRow chunk : lexicalChunks) {
			chunkById.put(scoreKey(chunk.target(), chunk.chunkId()), chunk);
			keywordScoreByChunkId.put(scoreKey(chunk.target(), chunk.chunkId()), keywordScore(chunk, normalized.query()));
		}

		List<LawSemanticChunkRow> searchedChunks = hits.stream()
			.map(hit -> chunkById.get(scoreKey(hit.target(), hit.chunkId())))
			.filter(chunk -> chunk != null)
			.toList();
		List<LawSemanticChunkRow> vectorChunks = searchedChunks;
		if (!lexicalChunks.isEmpty()) {
			searchedChunks = java.util.stream.Stream.concat(lexicalChunks.stream(), searchedChunks.stream())
				.collect(java.util.stream.Collectors.toMap(
					chunk -> scoreKey(chunk.target(), chunk.chunkId()),
					chunk -> chunk,
					(first, second) -> first,
					java.util.LinkedHashMap::new
				))
				.values()
				.stream()
				.toList();
		}
		Map<String, Double> baseScoreByChunkId = baseScoreMap(searchedChunks, vectorScoreByChunkId, keywordScoreByChunkId);
		if (searchedChunks.isEmpty()) {
			return RetrievalResult.empty(
				"NO_GROUNDS",
				normalized.target(),
				normalized.query(),
				targets,
				lexicalKeywords,
				hits,
				vectorChunks,
				lexicalChunks,
				vectorScoreByChunkId,
				keywordScoreByChunkId,
				baseScoreByChunkId,
				"현재 DB에서 질문과 관련된 근거 문서를 찾지 못했습니다. 법령명이나 상황을 조금 더 구체적으로 입력해 주세요.",
				List.of()
			);
		}
		List<LawSemanticChunkRow> rankedChunks = rerankChunks(searchedChunks, normalized.query(), baseScoreByChunkId);
		Map<String, Double> combinedScoreByChunkId = adjustedScoreMap(rankedChunks, normalized.query(), baseScoreByChunkId);
		Map<String, Double> metadataScoreByChunkId = metadataScoreMap(rankedChunks, baseScoreByChunkId, combinedScoreByChunkId);
		List<LawSemanticChunkRow> intentFilteredChunks = filterByQuestionIntent(rankedChunks, normalized.query());
		List<LawSemanticChunkRow> judgeCandidateChunks = intentFilteredChunks.stream()
			.limit(JUDGE_CANDIDATE_LIMIT)
			.toList();
		long judgeStart = System.nanoTime();
		EvidenceJudge.Result judgedEvidence = evidenceJudge.judge(
			normalized.query(),
			judgeCandidateChunks,
			combinedScoreByChunkId,
			normalized.display()
		);
		timing.judgeMs.set(elapsedMillis(judgeStart));
		Map<String, Double> finalScoreByChunkId = judgedEvidence.scoreByChunkId();
		List<LawSemanticChunkRow> evidenceChunks = judgedEvidence.chunks();
		List<LawSemanticChunkRow> orderedChunks = diversifyChunks(evidenceChunks.stream()
			.filter(this::hasUsefulText)
			.toList(), normalized.display());
		if (orderedChunks.isEmpty() && !judgedEvidence.directEvidenceRequired()) {
			orderedChunks = diversifyChunks(judgeCandidateChunks, normalized.display());
		}
		List<LawAiAnswerGround> grounds = toGrounds(orderedChunks, finalScoreByChunkId, normalized.query());
		if (grounds.isEmpty()) {
			String noGroundMessage = judgedEvidence.directEvidenceRequired() && !judgedEvidence.directEvidenceFound()
				? "후보 문서는 찾았지만 질문에 직접 답하는 근거를 확정하지 못했습니다. 질문 범위나 문서 종류를 조금 더 구체적으로 지정해 주세요."
				: "관련 벡터 검색 결과는 있었지만 DB에서 근거 본문을 찾지 못했습니다. 인덱스를 다시 생성해 주세요.";
			return new RetrievalResult(
				"NO_GROUNDS",
				normalized.target(),
				normalized.query(),
				targets,
				lexicalKeywords,
				hits,
				vectorChunks,
				lexicalChunks,
				searchedChunks,
				rankedChunks,
				intentFilteredChunks,
				judgedEvidence.chunks(),
				orderedChunks,
				vectorScoreByChunkId,
				keywordScoreByChunkId,
				metadataScoreByChunkId,
				combinedScoreByChunkId,
				baseScoreByChunkId,
				finalScoreByChunkId,
				List.of(),
				noGroundMessage
			);
		}
		List<LawSemanticChunkRow> answerChunks = selectAnswerContextChunks(orderedChunks, normalized.query());

		return new RetrievalResult(
			"OK",
			normalized.target(),
			normalized.query(),
			targets,
			lexicalKeywords,
			hits,
			vectorChunks,
			lexicalChunks,
			searchedChunks,
			rankedChunks,
			intentFilteredChunks,
			judgedEvidence.chunks(),
			answerChunks,
			vectorScoreByChunkId,
			keywordScoreByChunkId,
			metadataScoreByChunkId,
			combinedScoreByChunkId,
			baseScoreByChunkId,
			finalScoreByChunkId,
			grounds,
			"OK"
		);
	}

	// 메소드 설명: toDebugResponse 처리 흐름을 수행합니다.
	private LawAiDebugResponse toDebugResponse(RetrievalResult retrieval, LawAiTiming timing) {
		Set<String> selectedKeys = retrieval.answerChunks().stream()
			.map(chunk -> scoreKey(chunk.target(), chunk.chunkId()))
			.collect(java.util.stream.Collectors.toSet());
		return new LawAiDebugResponse(
			"00",
			retrieval.resultMsg(),
			retrieval.query(),
			retrieval.target(),
			retrieval.targets(),
			retrieval.lexicalKeywords(),
			List.of(
				new LawAiDebugResponse.Stage("vector", retrieval.vectorChunks().size(), "Qdrant vector search hits loaded from DB"),
				new LawAiDebugResponse.Stage("keyword", retrieval.lexicalChunks().size(), "Lexical keyword candidates"),
				new LawAiDebugResponse.Stage("merged", retrieval.searchedChunks().size(), "Merged vector and keyword candidates"),
				new LawAiDebugResponse.Stage("reranked", retrieval.rankedChunks().size(), "Heuristic rerank result"),
				new LawAiDebugResponse.Stage("intent", retrieval.intentFilteredChunks().size(), "Question intent filtered candidates"),
				new LawAiDebugResponse.Stage("judge", retrieval.judgedChunks().size(), "Evidence Judge accepted candidates"),
				new LawAiDebugResponse.Stage("grounds", retrieval.grounds().size(), "Grounds returned to the UI"),
				new LawAiDebugResponse.Stage("selected", retrieval.answerChunks().size(), "Grounds compressed for answer generation")
			),
			toDebugItems(retrieval.vectorChunks(), retrieval, selectedKeys),
			toDebugItems(retrieval.lexicalChunks(), retrieval, selectedKeys),
			toDebugItems(retrieval.searchedChunks(), retrieval, selectedKeys),
			toDebugItems(retrieval.rankedChunks(), retrieval, selectedKeys),
			toDebugItems(retrieval.intentFilteredChunks(), retrieval, selectedKeys),
			toDebugItems(retrieval.judgedChunks(), retrieval, selectedKeys),
			toDebugItems(retrieval.answerChunks(), retrieval, selectedKeys),
			retrieval.message(),
			timing
		);
	}

	// 메소드 설명: matchedTerms 처리 흐름을 수행합니다.
	private List<String> matchedTerms(LawSemanticChunkRow chunk, String query) {
		String text = normalizeForMatch(
			nullToEmpty(chunk.title()) + " "
				+ nullToEmpty(chunk.chunkTitle()) + " "
				+ nullToEmpty(chunk.chunkText())
		);
		return queryTerms(query).stream()
			.filter(text::contains)
			.toList();
	}

	private List<LawAiDebugResponse.Item> toDebugItems(
		List<LawSemanticChunkRow> chunks,
		RetrievalResult retrieval,
		Set<String> selectedKeys
	) {
		int[] rank = {1};
		return chunks.stream()
			.map(chunk -> {
				String key = scoreKey(chunk.target(), chunk.chunkId());
				return new LawAiDebugResponse.Item(
					rank[0]++,
					chunk.chunkId(),
					chunk.documentId(),
					chunk.target(),
					cleanHwpxText(chunk.title()),
					chunk.categoryName(),
					chunk.agencyName(),
					chunk.chunkNo(),
					cleanHwpxText(chunk.chunkTitle()),
					chunk.pageNo(),
					chunk.sourcePath(),
					retrieval.vectorScoreByChunkId().getOrDefault(key, 0.0),
					retrieval.keywordScoreByChunkId().getOrDefault(key, 0.0),
					retrieval.metadataScoreByChunkId().getOrDefault(key, 0.0),
					retrieval.combinedScoreByChunkId().getOrDefault(key, retrieval.baseScoreByChunkId().getOrDefault(key, 0.0)),
					retrieval.baseScoreByChunkId().getOrDefault(key, 0.0),
					retrieval.finalScoreByChunkId().getOrDefault(key, retrieval.baseScoreByChunkId().getOrDefault(key, 0.0)),
					selectedKeys.contains(key),
					matchedTerms(chunk, retrieval.query()),
					snippet(chunk.chunkText(), retrieval.query())
				);
			})
			.toList();
	}

	// 메소드 설명: evaluateCaseSafely 처리 흐름을 수행합니다.
	private LawAiEvalResponse.CaseResult evaluateCaseSafely(LawAiEvalRequest.EvalCase evalCase) {
		try {
			return evaluateCase(evalCase);
		} catch (RuntimeException ex) {
			return failedEvaluationCase(evalCase, "EVALUATION_ERROR", ex.getMessage());
		}
	}

	private LawAiEvalResponse.CaseResult failedEvaluationCase(
		LawAiEvalRequest.EvalCase evalCase,
		String resultMsg,
		String message
	) {
		List<String> targets = evalCase == null || evalCase.targets() == null ? List.of() : evalCase.targets();
		List<String> missing = evalCase == null || evalCase.expectedTerms() == null
			? List.of()
			: evalCase.expectedTerms().stream()
				.filter(term -> term != null && !term.isBlank())
				.toList();
		int requiredMatches = evalCase == null || evalCase.requiredMatches() == null
			? (missing.isEmpty() ? 0 : 1)
			: Math.max(0, evalCase.requiredMatches());
		return new LawAiEvalResponse.CaseResult(
			evalCase == null ? "invalid-case" : evalCase.id(),
			evalCase == null ? "" : evalCase.question(),
			targets,
			false,
			requiredMatches,
			List.of(),
			missing,
			List.of(),
			resultMsg,
			message == null || message.isBlank() ? "Evaluation failed." : message,
			List.of()
		);
	}

	// 메소드 설명: evaluateCase 처리 흐름을 수행합니다.
	private LawAiEvalResponse.CaseResult evaluateCase(LawAiEvalRequest.EvalCase evalCase) {
		if (evalCase == null || evalCase.question() == null || evalCase.question().isBlank()) {
			return failedEvaluationCase(evalCase, "INVALID_CASE", "Evaluation question is required.");
		}
		List<String> targets = evalCase.targets() == null || evalCase.targets().isEmpty()
			? List.of("law", "admrul", "official_doc", "internal_doc")
			: evalCase.targets();
		RetrievalResult retrieval = retrieve(new LawAiAnswerRequest(null, targets, evalCase.question(), 8));
		List<String> expectedTerms = evalCase.expectedTerms() == null ? List.of() : evalCase.expectedTerms().stream()
			.filter(term -> term != null && !term.isBlank())
			.toList();
		String selectedText = retrieval.answerChunks().stream()
			.map(this::textForEvaluation)
			.reduce("", (left, right) -> left + "\n" + right);
		List<String> matched = matchedExpectedTerms(selectedText, expectedTerms);
		List<String> missing = expectedTerms.stream()
			.filter(term -> !matched.contains(term))
			.toList();
		String topText = retrieval.answerChunks().stream()
			.findFirst()
			.map(this::textForEvaluation)
			.orElse("");
		List<String> topMatched = matchedExpectedTerms(topText, expectedTerms);
		int requiredMatches = evalCase.requiredMatches() == null
			? (expectedTerms.isEmpty() ? 0 : 1)
			: Math.max(0, evalCase.requiredMatches());
		boolean passed = "OK".equals(retrieval.resultMsg())
			&& !retrieval.answerChunks().isEmpty()
			&& matched.size() >= requiredMatches
			&& (expectedTerms.isEmpty() || !topMatched.isEmpty());
		List<LawAiDebugResponse.Item> selected = toDebugItems(
			retrieval.answerChunks(),
			retrieval,
			retrieval.answerChunks().stream()
				.map(chunk -> scoreKey(chunk.target(), chunk.chunkId()))
				.collect(java.util.stream.Collectors.toSet())
		);
		return new LawAiEvalResponse.CaseResult(
			evalCase.id(),
			evalCase.question(),
			targets,
			passed,
			requiredMatches,
			matched,
			missing,
			topMatched,
			retrieval.resultMsg(),
			retrieval.message(),
			selected
		);
	}

	// 메소드 설명: textForEvaluation 처리 흐름을 수행합니다.
	private String textForEvaluation(LawSemanticChunkRow chunk) {
		return String.join("\n",
			nullToEmpty(chunk.title()),
			nullToEmpty(chunk.chunkTitle()),
			nullToEmpty(chunk.chunkText())
		);
	}

	// 메소드 설명: matchedExpectedTerms 처리 흐름을 수행합니다.
	private List<String> matchedExpectedTerms(String text, List<String> expectedTerms) {
		String normalizedText = normalizeForMatch(text);
		return expectedTerms.stream()
			.filter(term -> normalizedText.contains(normalizeForMatch(term)))
			.toList();
	}

	// 메소드 설명: toGrounds 처리 흐름을 수행합니다.
	private List<LawAiAnswerGround> toGrounds(List<LawSemanticChunkRow> chunks, Map<String, Double> scoreByChunkId, String query) {
		int[] number = {1};
		return chunks.stream()
			.map(chunk -> new LawAiAnswerGround(
				number[0]++,
				chunk.chunkId(),
				chunk.documentId(),
				chunk.target(),
				chunk.title(),
				chunk.agencyName(),
				chunk.categoryName(),
				chunk.sourceDate(),
				chunk.chunkNo(),
				cleanHwpxText(chunk.chunkTitle()),
				chunk.pageNo(),
				snippet(chunk.chunkText(), query),
				chunk.sourcePath(),
				chunk.sourceUrl(),
				scoreByChunkId.getOrDefault(scoreKey(chunk.target(), chunk.chunkId()), 0.0)
			))
			.toList();
	}

	// 메소드 설명: buildAnswerContext 처리 흐름을 수행합니다.
	private String buildAnswerContext(RetrievalResult retrieval) {
		String context = buildContext(
			retrieval.answerChunks(),
			retrieval.finalScoreByChunkId(),
			retrieval.query(),
			groundNumberByChunkId(retrieval.grounds())
		);
		String focus = answerFocusInstruction(retrieval.query());
		if (focus.isBlank()) {
			return context;
		}
		return "답변 초점:\n" + focus + "\n\n" + context;
	}

	private String buildContext(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> scoreByChunkId,
		String query,
		Map<String, Integer> groundNumberByChunkId
	) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < chunks.size(); i++) {
			LawSemanticChunkRow chunk = chunks.get(i);
			String key = scoreKey(chunk.target(), chunk.chunkId());
			int groundNumber = groundNumberByChunkId.getOrDefault(key, i + 1);
			builder.append('[').append(groundNumber).append("] ");
			builder.append(nullToEmpty(chunk.categoryName())).append(" | ");
			builder.append(nullToEmpty(chunk.title()));
			if (chunk.pageNo() != null) {
				builder.append(" | p.").append(chunk.pageNo());
			}
			String location = (nullToEmpty(chunk.chunkNo()) + " " + cleanHwpxText(chunk.chunkTitle())).trim();
			if (!location.isBlank()) {
				builder.append(" | ").append(location);
			}
			double score = scoreByChunkId.getOrDefault(key, 0.0);
			builder.append(" | score=").append(String.format(java.util.Locale.ROOT, "%.3f", score)).append('\n');
			builder.append(contextSnippet(chunk.chunkText(), query, MAX_ANSWER_CONTEXT_CHARS_PER_GROUND)).append("\n\n");
		}
		return builder.toString();
	}

	// 메소드 설명: groundNumberByChunkId 처리 흐름을 수행합니다.
	private Map<String, Integer> groundNumberByChunkId(List<LawAiAnswerGround> grounds) {
		Map<String, Integer> numbers = new HashMap<>();
		for (LawAiAnswerGround ground : grounds == null ? List.<LawAiAnswerGround>of() : grounds) {
			numbers.put(scoreKey(ground.target(), ground.chunkId()), ground.number());
		}
		return numbers;
	}

	// 메소드 설명: contextSnippet 처리 흐름을 수행합니다.
	private String contextSnippet(String text, String query, int limit) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String normalized = cleanHwpxText(text).replaceAll("\\s+", " ").trim();
		int index = bestSnippetIndex(normalized, query);
		if (index < 0) {
			return limitText(normalized, limit);
		}
		int leadChars = Math.min(180, Math.max(90, limit / 3));
		int start = Math.max(0, index - leadChars);
		int end = Math.min(normalized.length(), index + limit - leadChars);
		start = moveToReadableBoundary(normalized, start, -1);
		end = moveToReadableBoundary(normalized, end, 1);
		String value = normalized.substring(start, Math.max(start, end)).trim();
		return (start > 0 ? "..." : "") + value + (end < normalized.length() ? "..." : "");
	}

	// 메소드 설명: answerFocusInstruction 처리 흐름을 수행합니다.
	private String answerFocusInstruction(String query) {
		String normalized = normalizeForMatch(query);
		if (normalized.contains("대상")) {
			if (isSecurityReviewQuestion(queryTerms(query))) {
				return """
					- 질문 의도는 보안성 검토의 대상 시스템 또는 대상 사업입니다. 국가정보원 검토 대상, 부처 검토 대상, 생략 대상을 구분해 먼저 답하세요.
					- 누출금지 대상정보, AI모델 공격유형, 보안관리 항목은 보안대책/검토내용입니다. 사용자가 검토 항목을 묻지 않았다면 대상 시스템의 결론으로 쓰지 마세요.
					- 근거에 대상 범위가 불충분하면 단정하지 말고 확인이 필요한 범위를 짧게 말하세요.
					""".stripIndent().trim();
			}
			return """
				- 질문 의도는 대상 또는 적용 범위입니다. 대상사업, 대상기관, 적용대상을 먼저 답하세요.
				- 과업내용의 적정성, 비용 산정, 적정 사업기간, SW영향평가 같은 표현은 심의 항목입니다. 사용자가 '무엇을 심의하나'라고 묻지 않았다면 결론으로 쓰지 마세요.
				- 비대상, 제외, 예외는 대상 설명 뒤에 보조적으로만 덧붙이세요.
				""".stripIndent().trim();
		}
		if (normalized.contains("필수") || normalized.contains("요소") || normalized.contains("항목")) {
			return "- 질문 의도는 필수 기재사항 또는 요구 항목입니다. 제출서류나 심의 절차보다 반드시 포함해야 하는 항목을 먼저 답하세요.";
		}
		if (normalized.contains("절차") || normalized.contains("방법")) {
			return "- 질문 의도는 절차 또는 처리 방법입니다. 신청, 제출, 검토, 통보 흐름을 순서대로 답하세요.";
		}
		return "";
	}

	// 메소드 설명: selectAnswerContextChunks 처리 흐름을 수행합니다.
	private List<LawSemanticChunkRow> selectAnswerContextChunks(List<LawSemanticChunkRow> displayChunks, String query) {
		if (displayChunks == null || displayChunks.isEmpty()) {
			return List.of();
		}
		int max = Math.min(MAX_ANSWER_CONTEXT_GROUNDS, displayChunks.size());
		int target = Math.min(preferredAnswerContextLimit(query), max);
		int minimum = Math.min(MIN_ANSWER_CONTEXT_GROUNDS, max);
		List<LawSemanticChunkRow> selected = new java.util.ArrayList<>(displayChunks.subList(0, target));
		while (selected.size() < max && shouldExpandAnswerContext(selected, query, minimum)) {
			selected.add(displayChunks.get(selected.size()));
		}
		return List.copyOf(selected);
	}

	// 메소드 설명: preferredAnswerContextLimit 처리 흐름을 수행합니다.
	private int preferredAnswerContextLimit(String query) {
		String normalized = normalizeForMatch(query);
		boolean narrowQuestion = normalized.contains("포함")
			|| normalized.contains("해당")
			|| normalized.contains("가능")
			|| normalized.contains("해야")
			|| normalized.contains("되나")
			|| normalized.contains("될까");
		boolean broadQuestion = normalized.contains("대상")
			|| normalized.contains("절차")
			|| normalized.contains("서류")
			|| normalized.contains("필수")
			|| normalized.contains("요소")
			|| normalized.contains("예외")
			|| normalized.contains("금액")
			|| normalized.contains("기한");
		if (narrowQuestion && !broadQuestion) {
			return 4;
		}
		if (broadQuestion) {
			return 6;
		}
		return DEFAULT_ANSWER_CONTEXT_GROUNDS;
	}

	// 메소드 설명: shouldExpandAnswerContext 처리 흐름을 수행합니다.
	private boolean shouldExpandAnswerContext(List<LawSemanticChunkRow> selected, String query, int minimum) {
		if (selected.size() < minimum) {
			return true;
		}
		String selectedText = selected.stream()
			.map(chunk -> normalizeForMatch(
				nullToEmpty(chunk.title()) + " "
					+ nullToEmpty(chunk.chunkTitle()) + " "
					+ nullToEmpty(chunk.chunkText())
			))
			.reduce("", (left, right) -> left + " " + right);
		List<String> terms = queryTerms(query);
		long matchedTerms = terms.stream()
			.filter(term -> selectedText.contains(term))
			.count();
		long matchedCues = prioritySnippetCues(query).stream()
			.map(this::normalizeForMatch)
			.filter(cue -> !cue.isBlank() && selectedText.contains(cue))
			.count();
		return matchedCues == 0 && matchedTerms < Math.min(2, Math.max(1, terms.size()));
	}

	// 메소드 설명: moveToReadableBoundary 처리 흐름을 수행합니다.
	private int moveToReadableBoundary(String text, int index, int direction) {
		if (index <= 0 || index >= text.length()) {
			return Math.max(0, Math.min(index, text.length()));
		}
		int limit = direction < 0 ? Math.max(0, index - 120) : Math.min(text.length(), index + 120);
		for (int cursor = index; direction < 0 ? cursor >= limit : cursor < limit; cursor += direction) {
			char ch = text.charAt(cursor);
			if (ch == '.' || ch == '?' || ch == '!' || ch == '\n') {
				return direction < 0 ? Math.min(text.length(), cursor + 1) : cursor + 1;
			}
		}
		return index;
	}

	// 메소드 설명: snippet 처리 흐름을 수행합니다.
	private String snippet(String text, String query) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String normalized = cleanHwpxText(text).replaceAll("\\s+", " ").trim();
		int index = bestSnippetIndex(normalized, query);
		if (index < 0) {
			return limitText(normalized, 320);
		}
		int start = Math.max(0, index - 90);
		int end = Math.min(normalized.length(), index + 360);
		String value = normalized.substring(start, end).trim();
		return (start > 0 ? "..." : "") + value + (end < normalized.length() ? "..." : "");
	}

	// 메소드 설명: bestSnippetIndex 처리 흐름을 수행합니다.
	private int bestSnippetIndex(String text, String query) {
		String normalizedText = normalizeForMatch(text);
		for (String cue : prioritySnippetCues(query)) {
			int index = normalizedText.indexOf(normalizeForMatch(cue));
			if (index >= 0) {
				return approximateOriginalIndex(text, normalizedText, index);
			}
		}
		for (String term : queryTerms(query)) {
			int index = normalizedText.indexOf(term);
			if (index >= 0) {
				return approximateOriginalIndex(text, normalizedText, index);
			}
		}
		return -1;
	}

	// 메소드 설명: prioritySnippetCues 처리 흐름을 수행합니다.
	private List<String> prioritySnippetCues(String query) {
		String normalized = normalizeForMatch(query);
		List<String> cues = new java.util.ArrayList<>();
		if (normalized.contains("대상")) {
			if (isSecurityReviewQuestion(queryTerms(query))) {
				cues.addAll(List.of(
					"대상 사업 및 시기",
					"국가정보원 검토 대상",
					"문화체육관광부 검토 대상",
					"정보통신망 또는 정보시스템 구축",
					"보안성 검토 절차 이행 생략 대상"
				));
			}
			if (normalized.contains("과업심의")) {
				cues.addAll(List.of(
					"대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업",
					"국가기관등의 장이 발주하는 소프트웨어사업",
					"국가기관 등이 발주하는 모든 SW사업",
					"SW개발, 제작, 생산, 유통, 운영 및 유지",
					"소프트웨어와 관련된 서비스"
				));
			}
			cues.addAll(List.of("대상사업", "대상 기관", "대상기관", "적용 대상", "비대상", "제외"));
		}
		if (normalized.contains("제안요청서") || normalized.contains("필수")) {
			cues.addAll(List.of("제안요청서에는 다음 각 호의 사항", "제안요청서 기재사항", "필수", "제출서류"));
		}
		if (normalized.contains("하드웨어")) {
			cues.addAll(List.of("소프트웨어사업으로 볼 수 없는", "단순 H/W", "비대상", "하드웨어", "상용소프트웨어", "소프트웨어사업"));
		}
		return cues;
	}

	// 메소드 설명: approximateOriginalIndex 처리 흐름을 수행합니다.
	private int approximateOriginalIndex(String original, String normalized, int normalizedIndex) {
		if (normalizedIndex <= 0) {
			return 0;
		}
		int normalizedCount = 0;
		for (int i = 0; i < original.length(); i++) {
			char ch = original.charAt(i);
			if (String.valueOf(ch).matches("[\\p{IsHangul}\\p{Alnum}]")) {
				if (normalizedCount >= normalizedIndex) {
					return i;
				}
				normalizedCount++;
			}
		}
		return Math.min(original.length(), normalizedIndex);
	}

	// 메소드 설명: limitText 처리 흐름을 수행합니다.
	private String limitText(String text, int limit) {
		if (text == null || text.isBlank()) {
			return "";
		}
		String normalized = text.replaceAll("\\s+", " ").trim();
		if (normalized.length() <= limit) {
			return normalized;
		}
		return normalized.substring(0, limit) + "...";
	}

	private List<LawSemanticChunkRow> rerankChunks(
		List<LawSemanticChunkRow> chunks,
		String query,
		Map<String, Double> scoreByChunkId
	) {
		List<String> terms = queryTerms(query);
		return chunks.stream()
			.sorted(Comparator
				.comparingDouble((LawSemanticChunkRow chunk) -> adjustedScore(chunk, terms, scoreByChunkId))
				.reversed())
			.toList();
	}

	private Map<String, Double> adjustedScoreMap(
		List<LawSemanticChunkRow> chunks,
		String query,
		Map<String, Double> scoreByChunkId
	) {
		List<String> terms = queryTerms(query);
		Map<String, Double> adjustedScores = new HashMap<>();
		for (LawSemanticChunkRow chunk : chunks) {
			adjustedScores.put(scoreKey(chunk.target(), chunk.chunkId()), adjustedScore(chunk, terms, scoreByChunkId));
		}
		return adjustedScores;
	}

	private Map<String, Double> baseScoreMap(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> vectorScoreByChunkId,
		Map<String, Double> keywordScoreByChunkId
	) {
		Map<String, Double> scores = new HashMap<>();
		for (LawSemanticChunkRow chunk : chunks) {
			String key = scoreKey(chunk.target(), chunk.chunkId());
			double vectorScore = vectorScoreByChunkId.getOrDefault(key, 0.0);
			double keywordScore = keywordScoreByChunkId.getOrDefault(key, 0.0);
			scores.put(key, Math.max(vectorScore, keywordScore));
		}
		return scores;
	}

	private Map<String, Double> metadataScoreMap(
		List<LawSemanticChunkRow> chunks,
		Map<String, Double> baseScoreByChunkId,
		Map<String, Double> combinedScoreByChunkId
	) {
		Map<String, Double> scores = new HashMap<>();
		for (LawSemanticChunkRow chunk : chunks) {
			String key = scoreKey(chunk.target(), chunk.chunkId());
			double metadataScore = combinedScoreByChunkId.getOrDefault(key, 0.0)
				- baseScoreByChunkId.getOrDefault(key, 0.0);
			scores.put(key, metadataScore);
		}
		return scores;
	}

	// 메소드 설명: diversifyChunks 처리 흐름을 수행합니다.
	private List<LawSemanticChunkRow> diversifyChunks(List<LawSemanticChunkRow> chunks, int limit) {
		List<LawSemanticChunkRow> selected = new java.util.ArrayList<>();
		Set<String> exactKeys = new HashSet<>();
		Set<String> textKeys = new HashSet<>();

		for (LawSemanticChunkRow chunk : chunks) {
			if (selected.size() >= limit) {
				break;
			}
			String exactKey = duplicateExactKey(chunk);
			String textKey = duplicateTextKey(chunk);

			if (exactKeys.contains(exactKey) || textKeys.contains(textKey)) {
				continue;
			}

			selected.add(chunk);
			exactKeys.add(exactKey);
			textKeys.add(textKey);
		}

		if (selected.size() < limit) {
			for (LawSemanticChunkRow chunk : chunks) {
				if (selected.size() >= limit) {
					break;
				}
				String exactKey = duplicateExactKey(chunk);
				String textKey = duplicateTextKey(chunk);
				if (exactKeys.contains(exactKey) || textKeys.contains(textKey)) {
					continue;
				}
				selected.add(chunk);
				exactKeys.add(exactKey);
				textKeys.add(textKey);
			}
		}

		return selected;
	}

	// 메소드 설명: duplicateExactKey 처리 흐름을 수행합니다.
	private String duplicateExactKey(LawSemanticChunkRow chunk) {
		String page = chunk.pageNo() == null ? "" : String.valueOf(chunk.pageNo());
		return String.join("|",
			nullToEmpty(chunk.target()),
			String.valueOf(chunk.documentId()),
			normalizeForMatch(nullToEmpty(chunk.title())),
			normalizeForMatch(nullToEmpty(chunk.chunkNo())),
			page
		);
	}

	// 메소드 설명: duplicateTextKey 처리 흐름을 수행합니다.
	private String duplicateTextKey(LawSemanticChunkRow chunk) {
		String normalized = normalizeForMatch(chunk.chunkText());
		return normalized.length() <= 140 ? normalized : normalized.substring(0, 140);
	}

	private double adjustedScore(
		LawSemanticChunkRow chunk,
		List<String> terms,
		Map<String, Double> scoreByChunkId
	) {
		double score = scoreByChunkId.getOrDefault(scoreKey(chunk.target(), chunk.chunkId()), 0.0);
		String body = normalizeForMatch(chunk.chunkText());
		String title = normalizeForMatch(chunk.title() + " " + chunk.chunkTitle());
		boolean projectReviewQuestion = isProjectReviewQuestion(terms);
		boolean targetQuestion = terms.stream().anyMatch(term -> term.contains("대상"));
		boolean preConsultationQuestion = isPreConsultationQuestion(terms);
		boolean hardwareSoftwareQuestion = isHardwareSoftwareQuestion(terms);
		boolean rfpRequiredItemsQuestion = isRfpRequiredItemsQuestion(terms);
		boolean securityReviewQuestion = isSecurityReviewQuestion(terms);
		if (body.contains("적용대상사업")) {
			score += 0.18;
		}
		if (body.contains("국가기관등이발주하는모든sw사업") || body.contains("소프트웨어와관련된서비스")) {
			score += 0.18;
		}
		if (projectReviewQuestion) {
			boolean projectReviewChunk = body.contains("과업심의")
				|| title.contains("과업심의")
				|| body.contains("소프트웨어와관련된서비스")
				|| body.contains("sw사업");
			boolean projectReviewTargetChunk = isProjectReviewTargetChunk(chunk);
			boolean reviewItemChunk = isProjectReviewReviewItemChunk(chunk);
			if (isRagTarget(chunk.target())) {
				score += projectReviewChunk ? 0.42 : 0.08;
				if (projectReviewTargetChunk) {
					score += 0.92;
				}
				if (title.contains("과업심의")) {
					score += 0.22;
				}
			} else if (!projectReviewChunk) {
				score -= 0.38;
			}
			if (projectReviewTargetChunk) {
				score += 0.36;
			}
			if (targetQuestion) {
				if (projectReviewTargetChunk) {
					score += 1.15;
				}
				if (reviewItemChunk && !projectReviewTargetChunk) {
					score -= 0.95;
				}
			}
		}
		if (preConsultationQuestion) {
			boolean preConsultationChunk = body.contains("사전협의") || title.contains("사전협의");
			boolean targetChunk = body.contains("대상사업")
				|| body.contains("대상기관")
				|| body.contains("추진하는모든정보화사업")
				|| body.contains("중앙공공기관")
				|| body.contains("공공기관");
			if (preConsultationChunk && targetChunk) {
				score += 0.72;
			} else if (preConsultationChunk) {
				score += 0.22;
			} else {
				score -= 0.45;
			}
			if (body.contains("사전협의의대상사업")) {
				score += 0.45;
			}
			if (body.contains("추진하는모든정보화사업")) {
				score += 0.35;
			}
			if (body.contains("중앙공공기관")) {
				score += 0.18;
			}
			if (terms.stream().anyMatch(term -> term.contains("기타공공기관")) && body.contains("공공기관")) {
				score += 0.16;
			}
			if ((body.contains("서식") || body.contains("작성예시") || body.contains("검토결과"))
				&& !body.contains("사전협의의대상사업")) {
				score -= 0.55;
			}
			if (!preConsultationChunk && (body.contains("제안요청서") || title.contains("제안요청서"))) {
				score -= 0.35;
			}
			if (isLawTarget(chunk.target()) && !preConsultationChunk) {
				score -= 0.22;
			}
		}
		if (securityReviewQuestion) {
			boolean securityReviewChunk = body.contains("보안성검토")
				|| title.contains("보안성검토")
				|| body.contains("정보화사업보안성검토")
				|| title.contains("정보화사업보안성검토");
			boolean targetChunk = isSecurityReviewTargetChunk(chunk);
			if (securityReviewChunk) {
				score += 0.34;
			} else {
				score -= 0.58;
			}
			if (targetQuestion && targetChunk) {
				score += 1.1;
			}
			if (targetQuestion && securityReviewChunk && !targetChunk
				&& (body.contains("누출금지대상정보") || body.contains("ai모델대상") || body.contains("검토항목"))) {
				score -= 0.45;
			}
		}
		if (hardwareSoftwareQuestion) {
			boolean hardwareCue = body.contains("단순hw")
				|| body.contains("appliance")
				|| body.contains("하드웨어")
				|| body.contains("hw");
			boolean softwareProjectExclusion = body.contains("소프트웨어사업으로볼수없는")
				|| (hardwareCue && body.contains("비대상"));
			boolean simpleHardwareExclusion = hardwareCue && softwareProjectExclusion;
			if (simpleHardwareExclusion) {
				score += 0.62;
			}
			if (body.contains("소프트웨어사업으로볼수없는경우는비대상")) {
				score += 0.35;
			}
			if (body.contains("단순hw") && title.contains("과업심의")) {
				score += 0.2;
			}
			if (body.contains("하드웨어구매") && !simpleHardwareExclusion) {
				score -= 0.12;
			}
		}
		if (rfpRequiredItemsQuestion) {
			boolean requiredItemsChunk = body.contains("제안요청서에는다음각호의사항")
				|| (body.contains("제안요청서") && body.contains("명시하여야한다"))
				|| (body.contains("과업내용") && body.contains("요구사항") && body.contains("계약조건"));
			if (requiredItemsChunk) {
				score += 0.58;
			}
			if (body.contains("제안요청서작성") || title.contains("제안요청서")) {
				score += 0.12;
			}
			if (body.contains("작성예시") && !body.contains("명시하여야한다")) {
				score -= 0.16;
			}
		}
		for (String term : terms) {
			if (body.contains(term)) {
				score += 0.035;
			} else if (title.contains(term)) {
				score += 0.012;
			}
		}
		List<String> coreTerms = coreConceptTerms(terms);
		if (!coreTerms.isEmpty()) {
			long coreMatches = coreTerms.stream()
				.filter(term -> body.contains(term) || title.contains(term))
				.count();
			if (coreMatches == 0) {
				score -= 0.62;
			} else {
				score += 0.16 * coreMatches;
			}
		}
		if (isRagTarget(chunk.target()) && !body.isBlank()) {
			score += 0.015;
		}
		if (body.length() < 80) {
			score -= 0.04;
		}
		return score;
	}

	// 메소드 설명: filterByQuestionIntent 처리 흐름을 수행합니다.
	private List<LawSemanticChunkRow> filterByQuestionIntent(List<LawSemanticChunkRow> chunks, String query) {
		String normalizedQuery = normalizeForMatch(query);
		if (isHardwareSoftwareQuestion(queryTerms(query))) {
			List<LawSemanticChunkRow> filtered = chunks.stream()
				.filter(this::isHardwareSoftwareAnswerChunk)
				.toList();
			if (!filtered.isEmpty()) {
				return filtered;
			}
		}
		if (isProjectReviewQuestion(queryTerms(query)) && normalizedQuery.contains("대상")) {
			List<LawSemanticChunkRow> filtered = chunks.stream()
				.filter(this::isProjectReviewTargetChunk)
				.toList();
			if (!filtered.isEmpty()) {
				return filtered;
			}
			return filterByCoreConceptIfUseful(chunks, query);
		}
		if (isSecurityReviewQuestion(queryTerms(query)) && normalizedQuery.contains("대상")) {
			List<LawSemanticChunkRow> filtered = chunks.stream()
				.filter(this::isSecurityReviewTargetChunk)
				.toList();
			if (!filtered.isEmpty()) {
				return filtered;
			}
			return filterByCoreConceptIfUseful(chunks, query);
		}
		if (!normalizedQuery.contains("사전협의") || !normalizedQuery.contains("대상")) {
			return filterByCoreConceptIfUseful(chunks, query);
		}
		List<LawSemanticChunkRow> filtered = chunks.stream()
			.filter(this::isPreConsultationTargetChunk)
			.toList();
		return filtered.isEmpty() ? filterByCoreConceptIfUseful(chunks, query) : filtered;
	}

	// 메소드 설명: filterByCoreConceptIfUseful 처리 흐름을 수행합니다.
	private List<LawSemanticChunkRow> filterByCoreConceptIfUseful(List<LawSemanticChunkRow> chunks, String query) {
		List<String> coreTerms = coreConceptTerms(queryTerms(query));
		if (coreTerms.isEmpty()) {
			return chunks;
		}
		List<LawSemanticChunkRow> filtered = chunks.stream()
			.filter(chunk -> matchesAnyCoreConcept(chunk, coreTerms))
			.toList();
		int minimum = Math.min(2, Math.max(1, chunks.size()));
		return filtered.size() >= minimum ? filtered : chunks;
	}

	// 메소드 설명: matchesAnyCoreConcept 처리 흐름을 수행합니다.
	private boolean matchesAnyCoreConcept(LawSemanticChunkRow chunk, List<String> coreTerms) {
		String text = normalizeForMatch(
			nullToEmpty(chunk.title()) + " "
				+ nullToEmpty(chunk.chunkTitle()) + " "
				+ nullToEmpty(chunk.chunkText())
		);
		return coreTerms.stream().anyMatch(text::contains);
	}

	// 메소드 설명: isProjectReviewTargetChunk 처리 흐름을 수행합니다.
	private boolean isProjectReviewTargetChunk(LawSemanticChunkRow chunk) {
		String body = normalizeForMatch(chunk.chunkText());
		String title = normalizeForMatch(chunk.title() + " " + chunk.chunkTitle());
		boolean targetSignal = body.contains("적용대상사업")
			|| body.contains("대상사업국가기관등의장이발주하는소프트웨어사업")
			|| body.contains("대상사업")
			|| title.contains("대상사업");
		boolean softwareSignal = body.contains("국가기관등의장이발주하는소프트웨어사업")
			|| body.contains("국가기관등이발주하는모든sw사업")
			|| body.contains("국가기관등의장이발주")
			|| body.contains("sw개발제작생산유통운영및유지관리")
			|| body.contains("소프트웨어와관련된서비스")
			|| (body.contains("국가기관등") && (body.contains("소프트웨어사업") || body.contains("sw사업")))
			|| body.contains("모든sw사업");
		boolean excludesExampleOrForm = body.contains("작성예시")
			|| body.contains("제안서평가방법")
			|| body.contains("기술평가방법");
		return targetSignal && softwareSignal && !excludesExampleOrForm && !isProjectReviewReviewItemChunk(chunk);
	}

	// 메소드 설명: isProjectReviewReviewItemChunk 처리 흐름을 수행합니다.
	private boolean isProjectReviewReviewItemChunk(LawSemanticChunkRow chunk) {
		String text = normalizeForMatch(chunk.title() + " " + chunk.chunkTitle() + " " + chunk.chunkText());
		boolean reviewItems = text.contains("과업내용의적정성검토")
			|| text.contains("과업내용상세요구사항과비용산정의적정성")
			|| text.contains("비용산정의적정성")
			|| text.contains("적정사업기간의산정")
			|| text.contains("sw영향평가의재평가")
			|| text.contains("심의결과통지")
			|| text.contains("과업내용심의결과서");
		boolean targetScope = text.contains("대상사업국가기관등의장이발주하는소프트웨어사업")
			|| text.contains("국가기관등의장이발주하는소프트웨어사업")
			|| text.contains("국가기관등이발주하는모든sw사업")
			|| text.contains("sw개발제작생산유통운영및유지관리")
			|| text.contains("소프트웨어와관련된서비스");
		return reviewItems && !targetScope;
	}

	// 메소드 설명: isPreConsultationTargetChunk 처리 흐름을 수행합니다.
	private boolean isPreConsultationTargetChunk(LawSemanticChunkRow chunk) {
		String body = normalizeForMatch(chunk.chunkText());
		String title = normalizeForMatch(chunk.title() + " " + chunk.chunkTitle());
		if (!body.contains("사전협의") && !title.contains("사전협의")) {
			return false;
		}
		if (body.contains("제안서평가방법") || body.contains("기술평가방법")) {
			return false;
		}
		return body.contains("사전협의의대상사업")
			|| body.contains("대상기관")
			|| body.contains("대상사업")
			|| body.contains("추진하는모든정보화사업")
			|| body.contains("중앙공공기관");
	}

	// 메소드 설명: isSecurityReviewTargetChunk 처리 흐름을 수행합니다.
	private boolean isSecurityReviewTargetChunk(LawSemanticChunkRow chunk) {
		String text = normalizeForMatch(
			nullToEmpty(chunk.title()) + " "
				+ nullToEmpty(chunk.chunkTitle()) + " "
				+ nullToEmpty(chunk.chunkText())
		);
		boolean securityReviewContext = text.contains("보안성검토")
			|| text.contains("정보화사업보안성검토")
			|| text.contains("국가정보보안기본지침");
		boolean targetScope = text.contains("대상사업및시기")
			|| text.contains("보안성검토대상")
			|| text.contains("국가정보원검토대상")
			|| text.contains("문화체육관광부검토대상")
			|| text.contains("보안성검토절차이행생략대상");
		boolean systemScope = text.contains("정보통신망또는정보시스템구축")
			|| text.contains("정보시스템구축")
			|| text.contains("주요데이터베이스구축")
			|| text.contains("민감정보")
			|| text.contains("고유식별정보")
			|| text.contains("주요정보통신기반시설")
			|| text.contains("제어시스템")
			|| text.contains("웹기반정보시스템구축")
			|| text.contains("백업시스템구축")
			|| text.contains("콜센터시스템구축")
			|| text.contains("기관인터넷망")
			|| text.contains("클라우드컴퓨팅서비스");
		boolean unrelatedSecurityItem = text.contains("누출금지대상정보")
			|| text.contains("ai모델대상")
			|| text.contains("보안위협예시")
			|| text.contains("용역업체보안관리");
		return securityReviewContext && targetScope && systemScope && !unrelatedSecurityItem;
	}

	// 메소드 설명: isHardwareSoftwareAnswerChunk 처리 흐름을 수행합니다.
	private boolean isHardwareSoftwareAnswerChunk(LawSemanticChunkRow chunk) {
		String body = normalizeForMatch(chunk.chunkText());
		String title = normalizeForMatch(chunk.title() + " " + chunk.chunkTitle());
		boolean hardwareCue = body.contains("단순hw")
			|| body.contains("appliance")
			|| body.contains("하드웨어")
			|| body.contains("hw구매")
			|| body.contains("hw");
		boolean judgmentCue = body.contains("소프트웨어사업으로볼수없는")
			|| body.contains("비대상")
			|| body.contains("포함되지않");
		boolean softwareContext = body.contains("소프트웨어사업")
			|| body.contains("sw사업")
			|| title.contains("상용소프트웨어")
			|| title.contains("과업심의");
		return hardwareCue && judgmentCue && softwareContext;
	}

	// 메소드 설명: findLexicalChunks 처리 흐름을 수행합니다.
	private List<LawSemanticChunkRow> findLexicalChunks(String query, List<String> targets) {
		List<String> keywords = lexicalKeywords(query);
		if (keywords.isEmpty()) {
			return List.of();
		}
		List<String> ragTargets = targets.stream()
			.filter(this::isRagTarget)
			.toList();
		List<String> lawTargets = targets.stream()
			.filter(this::isLawTarget)
			.toList();
		List<LawSemanticChunkRow> chunks = new java.util.ArrayList<>();
		boolean guideFocusedQuestion = isGuideFocusedQuestion(queryTerms(query));
		boolean focusedLookup = false;
		if (isHardwareSoftwareQuestion(queryTerms(query)) && !ragTargets.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			chunks.addAll(ragDocumentMapper.findSemanticChunksByText(
				ragTargets,
				List.of("단순 H/W", "Appliance", "소프트웨어사업으로 볼 수 없는"),
				FOCUSED_RAG_KEYWORD_FETCH_LIMIT
			));
			focusedLookup = true;
		}
		if (isProjectReviewQuestion(queryTerms(query)) && normalizeForMatch(query).contains("대상") && !ragTargets.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			chunks.addAll(ragDocumentMapper.findSemanticChunksByText(
				ragTargets,
				List.of(
					"대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업",
					"국가기관등의 장이 발주하는 소프트웨어사업",
					"대상기관 : SW진흥법 시행령 제21조",
					"SW개발, 제작, 생산, 유통, 운영 및 유지",
					"소프트웨어와 관련된 서비스"
				),
				FOCUSED_RAG_KEYWORD_FETCH_LIMIT
			));
			focusedLookup = true;
		}
		if (isSecurityReviewQuestion(queryTerms(query)) && normalizeForMatch(query).contains("대상") && !ragTargets.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			chunks.addAll(ragDocumentMapper.findSemanticChunksByText(
				ragTargets,
				List.of(
					"대상 사업 및 시기",
					"보안성 검토 대상",
					"국가정보원 검토 대상",
					"문화체육관광부 검토 대상",
					"정보통신망 또는 정보시스템 구축",
					"보안성 검토 절차 이행 생략 대상"
				),
				FOCUSED_RAG_KEYWORD_FETCH_LIMIT
			));
			focusedLookup = true;
		}
		List<String> genericKeywords = focusedLookup ? genericLexicalKeywords(query) : keywords;
		if (!ragTargets.isEmpty() && !genericKeywords.isEmpty()) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			chunks.addAll(ragDocumentMapper.findSemanticChunksByText(
				ragTargets,
				genericKeywords,
				focusedLookup ? GENERIC_RAG_KEYWORD_FETCH_LIMIT : FOCUSED_RAG_KEYWORD_FETCH_LIMIT
			));
		}
		if (!lawTargets.isEmpty() && !(guideFocusedQuestion && !ragTargets.isEmpty())) {
			List<String> lawTitleKeywords = lawTitleKeywords(query);
			if (!lawTitleKeywords.isEmpty()) {
				// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
				chunks.addAll(lawChunkMapper.findSemanticChunksByDocumentTitle(
					lawTargets,
					lawTitleKeywords,
					LAW_TITLE_KEYWORD_FETCH_LIMIT
				));
			}
		}
		return chunks.stream()
			.collect(java.util.stream.Collectors.toMap(
				chunk -> scoreKey(chunk.target(), chunk.chunkId()),
				chunk -> chunk,
				(first, second) -> first,
				java.util.LinkedHashMap::new
			))
			.values()
			.stream()
			.sorted(Comparator.comparingDouble((LawSemanticChunkRow chunk) -> keywordScore(chunk, query)).reversed())
			.limit(KEYWORD_CANDIDATE_LIMIT)
			.toList();
	}

	// 메소드 설명: lexicalKeywords 처리 흐름을 수행합니다.
	private List<String> lexicalKeywords(String query) {
		String normalized = normalizeForMatch(query);
		List<String> keywords = new java.util.ArrayList<>();
		if (normalized.contains("과업심의") && normalized.contains("대상")) {
			keywords.addAll(List.of(
				"대상사업 : 국가기관등의 장이 발주하는 소프트웨어사업",
				"국가기관등의 장이 발주하는 소프트웨어사업",
				"적용 대상 사업",
				"대상사업",
				"대상 사업",
				"국가기관 등이 발주하는 모든 SW사업",
				"국가기관등이 발주하는 모든 SW사업",
				"국가기관등의 장이 발주하는 소프트웨어사업",
				"SW개발, 제작, 생산, 유통, 운영 및 유지",
				"소프트웨어와 관련된 서비스",
				"적용SW포함"
			));
		}
		if (normalized.contains("사전협의") && normalized.contains("대상")) {
			keywords.addAll(List.of(
				"사전협의의 대상사업",
				"대상기관이 추진하는 모든 정보화사업",
				"중앙·공공기관",
				"중앙 공공기관",
				"사전협의 대상 사업",
				"대상 기관"
			));
		}
		if (isSecurityReviewQuestion(queryTerms(query)) && normalized.contains("대상")) {
			keywords.addAll(List.of(
				"대상 사업 및 시기",
				"보안성 검토 대상",
				"국가정보원 검토 대상",
				"문화체육관광부 검토 대상",
				"정보통신망 또는 정보시스템 구축",
				"보안성 검토 절차 이행 생략 대상"
			));
		}
		if (normalized.contains("하드웨어") || normalized.contains("hw") || normalized.contains("appliance")) {
			keywords.addAll(List.of(
				"단순 H/W",
				"Appliance",
				"소프트웨어사업으로 볼 수 없는",
				"비대상",
				"하드웨어 구매"
			));
		}
		for (String token : String.valueOf(query).split("\\s+")) {
			String cleaned = token.replaceAll("[^\\p{IsHangul}\\p{Alnum}]", "").trim();
			if (cleaned.length() >= 2 && !isWeakQueryToken(cleaned)) {
				keywords.add(cleaned);
			}
		}
		String compact = String.valueOf(query)
			.replaceAll("\\s+", "")
			.replaceAll("[^\\p{IsHangul}\\p{Alnum}]", "");
		if (compact.length() >= 4) {
			keywords.add(compact);
		}
		return keywords.stream()
			.map(String::trim)
			.filter(keyword -> keyword.length() >= 2)
			.distinct()
			.limit(12)
			.toList();
	}

	// 메소드 설명: genericLexicalKeywords 처리 흐름을 수행합니다.
	private List<String> genericLexicalKeywords(String query) {
		List<String> keywords = queryTerms(query).stream()
			.map(this::stripTrailingJosa)
			.map(this::stripIntentSuffix)
			.filter(term -> term.length() >= 2)
			.filter(term -> !isWeakQueryToken(term))
			.filter(term -> !isIntentLikeTerm(term))
			.distinct()
			.collect(java.util.stream.Collectors.toCollection(java.util.ArrayList::new));
		if (keywords.size() <= 1) {
			String compact = stripIntentSuffix(stripTrailingJosa(normalizeForMatch(query)));
			if (compact.length() >= 3 && compact.length() <= 18
				&& !isWeakQueryToken(compact)
				&& !isIntentLikeTerm(compact)
				&& !keywords.contains(compact)) {
				keywords.add(compact);
			}
		}
		return keywords.stream()
			.distinct()
			.limit(5)
			.toList();
	}

	// 메소드 설명: lawTitleKeywords 처리 흐름을 수행합니다.
	private List<String> lawTitleKeywords(String query) {
		List<String> coreTerms = coreConceptTerms(queryTerms(query));
		List<String> keywords = coreTerms.isEmpty() ? genericLexicalKeywords(query) : coreTerms;
		return keywords.stream()
			.filter(term -> term.length() >= 2)
			.filter(term -> !isIntentLikeTerm(term))
			.distinct()
			.limit(4)
			.toList();
	}

	// 메소드 설명: isWeakQueryToken 처리 흐름을 수행합니다.
	private boolean isWeakQueryToken(String value) {
		String normalized = normalizeForMatch(value);
		return Set.of(
			"알려줘",
			"알수있어",
			"알수있나요",
			"어떻게",
			"어떤",
			"무엇",
			"뭐야",
			"뭔가요",
			"있나",
			"있나요",
			"있어",
			"되나요",
			"된다",
			"아니야",
			"관련",
			"대해",
			"대한"
		).contains(normalized);
	}

	// 메소드 설명: keywordScore 처리 흐름을 수행합니다.
	private double keywordScore(LawSemanticChunkRow chunk, String query) {
		List<String> terms = queryTerms(query);
		List<String> expandedTerms = lexicalKeywords(query).stream()
			.map(this::normalizeForMatch)
			.filter(term -> term.length() >= 2)
			.filter(term -> !terms.contains(term))
			.toList();
		String body = normalizeForMatch(chunk.chunkText());
		String title = normalizeForMatch(chunk.title() + " " + chunk.chunkTitle());
		double score = 0.28;
		for (String term : terms) {
			if (title.contains(term)) {
				score += 0.08;
			}
			if (body.contains(term)) {
				score += 0.055;
			}
		}
		for (String term : expandedTerms) {
			if (title.contains(term)) {
				score += 0.055;
			}
			if (body.contains(term)) {
				score += 0.04;
			}
		}
		if (isRagTarget(chunk.target())) {
			score += 0.02;
		}
		return Math.min(score, 0.82);
	}

	// 메소드 설명: queryTerms 처리 흐름을 수행합니다.
	private List<String> queryTerms(String query) {
		return List.of(String.valueOf(query).split("\\s+")).stream()
			.map(this::normalizeForMatch)
			.map(this::stripTrailingJosa)
			.filter(term -> term.length() >= 2)
			.filter(term -> !isWeakQueryToken(term))
			.distinct()
			.toList();
	}

	// 메소드 설명: coreConceptTerms 처리 흐름을 수행합니다.
	private List<String> coreConceptTerms(List<String> terms) {
		if (terms == null || terms.isEmpty()) {
			return List.of();
		}
		return terms.stream()
			.map(this::stripTrailingJosa)
			.map(this::stripIntentSuffix)
			.filter(term -> term.length() >= 3)
			.filter(term -> !isWeakQueryToken(term))
			.filter(term -> !isIntentLikeTerm(term))
			.distinct()
			.limit(4)
			.toList();
	}

	// 메소드 설명: isIntentLikeTerm 처리 흐름을 수행합니다.
	private boolean isIntentLikeTerm(String term) {
		return Set.of(
			"대상",
			"대상사업",
			"대상기관",
			"적용대상",
			"시스템",
			"정보시스템",
			"사업",
			"기관",
			"필수",
			"필수요소",
			"요소",
			"항목",
			"절차",
			"방법",
			"서류",
			"제출서류",
			"신청방법",
			"검토내용",
			"검토",
			"추진절차",
			"가능",
			"해야",
			"되나",
			"될까",
			"작성",
			"작성할때",
			"내용"
		).contains(term);
	}

	// 메소드 설명: stripTrailingJosa 처리 흐름을 수행합니다.
	private String stripTrailingJosa(String term) {
		if (term == null || term.length() < 3) {
			return term;
		}
		for (String suffix : List.of("으로", "에서", "에게", "까지", "부터", "하고", "하면", "은", "는", "이", "가", "을", "를", "에", "의", "와", "과", "도")) {
			if (term.endsWith(suffix) && term.length() > suffix.length() + 1) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	// 메소드 설명: stripIntentSuffix 처리 흐름을 수행합니다.
	private String stripIntentSuffix(String term) {
		if (term == null || term.length() < 4) {
			return term;
		}
		for (String suffix : List.of("대상사업", "대상시스템", "대상기관", "적용대상", "필수요소", "검토내용", "추진절차", "신청방법", "제출서류", "대상", "시스템", "사업", "기관", "요소", "항목", "절차", "방법", "서류")) {
			if (term.endsWith(suffix) && term.length() > suffix.length() + 2) {
				return term.substring(0, term.length() - suffix.length());
			}
		}
		return term;
	}

	// 메소드 설명: isProjectReviewQuestion 처리 흐름을 수행합니다.
	private boolean isProjectReviewQuestion(List<String> terms) {
		return terms.stream().anyMatch(term -> term.contains("과업심의"));
	}

	// 메소드 설명: isPreConsultationQuestion 처리 흐름을 수행합니다.
	private boolean isPreConsultationQuestion(List<String> terms) {
		return terms.stream().anyMatch(term -> term.contains("사전협의"));
	}

	// 메소드 설명: isSecurityReviewQuestion 처리 흐름을 수행합니다.
	private boolean isSecurityReviewQuestion(List<String> terms) {
		String joined = String.join("", terms);
		boolean compactMatch = joined.contains("보안성검토");
		boolean splitMatch = terms.stream().anyMatch(term -> term.contains("보안성"))
			&& terms.stream().anyMatch(term -> term.contains("검토"));
		return compactMatch || splitMatch;
	}

	// 메소드 설명: isHardwareSoftwareQuestion 처리 흐름을 수행합니다.
	private boolean isHardwareSoftwareQuestion(List<String> terms) {
		boolean hasHardware = terms.stream().anyMatch(term ->
			term.contains("하드웨어") || term.contains("hw") || term.contains("appliance")
		);
		boolean hasSoftwareProject = terms.stream().anyMatch(term ->
			term.contains("소프트웨어사업") || term.contains("sw사업") || term.contains("공공소프트웨어")
		);
		return hasHardware && hasSoftwareProject;
	}

	// 메소드 설명: isRfpRequiredItemsQuestion 처리 흐름을 수행합니다.
	private boolean isRfpRequiredItemsQuestion(List<String> terms) {
		boolean hasRfp = terms.stream().anyMatch(term -> term.contains("제안요청서"));
		boolean asksRequiredItems = terms.stream().anyMatch(term ->
			term.contains("필수") || term.contains("요소") || term.contains("항목") || term.contains("작성")
		);
		return hasRfp && asksRequiredItems;
	}

	// 메소드 설명: isGuideFocusedQuestion 처리 흐름을 수행합니다.
	private boolean isGuideFocusedQuestion(List<String> terms) {
		return isProjectReviewQuestion(terms)
			|| isPreConsultationQuestion(terms)
			|| isSecurityReviewQuestion(terms)
			|| isHardwareSoftwareQuestion(terms)
			|| isRfpRequiredItemsQuestion(terms)
			|| terms.stream().anyMatch(term -> term.contains("정보화사업") || term.contains("제안요청서"));
	}

	// 메소드 설명: normalizeForMatch 처리 흐름을 수행합니다.
	private String normalizeForMatch(String value) {
		return String.valueOf(value == null ? "" : value)
			.replaceAll("[^\\p{IsHangul}\\p{Alnum}]", "")
			.toLowerCase();
	}

	// 메소드 설명: hasUsefulText 처리 흐름을 수행합니다.
	private boolean hasUsefulText(LawSemanticChunkRow chunk) {
		String text = limitText(cleanHwpxText(chunk.chunkText()), MAX_USEFUL_TEXT_CHECK_CHARS)
			.replace("<개정", "")
			.replaceAll("\\d{4}\\.\\d+\\.\\d+>", "")
			.replaceAll("[\\s.ㆍ·<>]", "");
		return text.length() >= 20;
	}

	// 메소드 설명: nullToEmpty 처리 흐름을 수행합니다.
	private String nullToEmpty(String value) {
		return value == null ? "" : value;
	}

	// 메소드 설명: cleanHwpxText 처리 흐름을 수행합니다.
	private String cleanHwpxText(String value) {
		return HwpxTextCleaner.clean(value);
	}

	// 메소드 설명: answerTargets 처리 흐름을 수행합니다.
	private List<String> answerTargets(List<String> requestedTargets, String requestedTarget) {
		List<String> normalizedTargets = requestedTargets == null ? List.of() : requestedTargets.stream()
			.filter(target -> target != null && !target.isBlank())
			.map(String::trim)
			.filter(this::isAnswerTarget)
			.distinct()
			.toList();
		if (!normalizedTargets.isEmpty()) {
			return normalizedTargets;
		}
		if (requestedTarget == null || requestedTarget.isBlank() || "law".equals(requestedTarget)) {
			return List.of("law", "admrul", "official_doc", "internal_doc");
		}
		return List.of(requestedTarget);
	}

	// 메소드 설명: isAnswerTarget 처리 흐름을 수행합니다.
	private boolean isAnswerTarget(String target) {
		return Set.of("law", "admrul", "official_doc", "internal_doc", "reference_doc").contains(target);
	}

	// 메소드 설명: isLawTarget 처리 흐름을 수행합니다.
	private boolean isLawTarget(String target) {
		return "law".equals(target) || "admrul".equals(target);
	}

	// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
	private boolean isRagTarget(String target) {
		return "official_doc".equals(target) || "internal_doc".equals(target) || "reference_doc".equals(target);
	}

	// 메소드 설명: scoreKey 처리 흐름을 수행합니다.
	private String scoreKey(String target, long chunkId) {
		return target + ":" + chunkId;
	}

	// 메소드 설명: requestTargetsForLog 처리 흐름을 수행합니다.
	private List<String> requestTargetsForLog(LawAiAnswerRequest request) {
		return answerTargets(request == null ? null : request.targets(), request == null ? null : request.target());
	}

	// 메소드 설명: answerCacheKey 처리 흐름을 수행합니다.
	private String answerCacheKey(LawAiAnswerRequest request) {
		String question = request == null || request.question() == null ? "" : request.question().trim().replaceAll("\\s+", " ");
		if (question.isBlank()) {
			return null;
		}
		int limit = request == null || request.limit() == null ? DEFAULT_LIMIT : request.limit();
		int safeLimit = Math.max(1, Math.min(limit, MAX_LIMIT));
		List<String> targets = answerTargets(request == null ? null : request.targets(), request == null ? null : request.target())
			.stream()
			.sorted()
			.toList();
		return String.join("|",
			String.valueOf(safeLimit),
			String.join(",", targets),
			normalizeForMatch(question)
		);
	}

	// 메소드 설명: cachedAnswer 처리 흐름을 수행합니다.
	private LawAiAnswerResponse cachedAnswer(String cacheKey, TimingProbe timing) {
		if (cacheKey == null) {
			return null;
		}
		CachedAnswer cached = answerCache.get(cacheKey);
		if (cached == null) {
			return null;
		}
		if (cached.expiresAtMillis() < System.currentTimeMillis()) {
			answerCache.remove(cacheKey);
			return null;
		}
		return copyWithTiming(cached.response(), LawAiTiming.cacheHit(timing.totalElapsedMs()));
	}

	// 메소드 설명: cacheAnswer 처리 흐름을 수행합니다.
	private void cacheAnswer(String cacheKey, LawAiAnswerResponse response) {
		if (cacheKey == null || response == null || !"OK".equals(response.resultMsg())) {
			return;
		}
		pruneAnswerCache();
		answerCache.put(cacheKey, new CachedAnswer(response, System.currentTimeMillis() + ANSWER_CACHE_TTL_MILLIS));
	}

	// 메소드 설명: pruneAnswerCache 처리 흐름을 수행합니다.
	private void pruneAnswerCache() {
		long now = System.currentTimeMillis();
		answerCache.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() < now);
		int overflow = answerCache.size() - MAX_ANSWER_CACHE_ENTRIES;
		if (overflow <= 0) {
			return;
		}
		answerCache.entrySet().stream()
			.sorted(Comparator.comparingLong(entry -> entry.getValue().expiresAtMillis()))
			.limit(overflow)
			.map(Map.Entry::getKey)
			.toList()
			.forEach(answerCache::remove);
	}

	// 메소드 설명: copyWithTiming 처리 흐름을 수행합니다.
	private LawAiAnswerResponse copyWithTiming(LawAiAnswerResponse response, LawAiTiming timing) {
		return new LawAiAnswerResponse(
			response.resultCode(),
			response.resultMsg(),
			response.target(),
			response.question(),
			response.model(),
			response.answer(),
			response.totalCnt(),
			response.grounds(),
			timing
		);
	}

	// 메소드 설명: joinFuture 처리 흐름을 수행합니다.
	private <T> T joinFuture(CompletableFuture<T> future) {
		try {
			return future.join();
		} catch (CompletionException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new IllegalStateException("Async AI search task failed.", cause);
		}
	}

	// 메소드 설명: elapsedMillis 처리 흐름을 수행합니다.
	private static long elapsedMillis(long startNanos) {
		return Math.max(0, (System.nanoTime() - startNanos) / 1_000_000);
	}

	// 메소드 설명: logTiming 처리 흐름을 수행합니다.
	private void logTiming(String mode, String question, List<String> targets, int grounds, LawAiTiming timing) {
		log.info(
			"Law AI {} timing question=\"{}\" targets={} grounds={} cacheHit={} embeddingMs={} qdrantMs={} dbMs={} judgeMs={} answerMs={} totalMs={}",
			mode,
			limitLogText(question),
			targets == null ? List.of() : targets,
			grounds,
			timing != null && timing.cacheHit(),
			timing == null ? 0 : timing.embeddingMs(),
			timing == null ? 0 : timing.qdrantMs(),
			timing == null ? 0 : timing.dbMs(),
			timing == null ? 0 : timing.judgeMs(),
			timing == null ? 0 : timing.answerMs(),
			timing == null ? 0 : timing.totalMs()
		);
	}

	// 메소드 설명: limitLogText 처리 흐름을 수행합니다.
	private String limitLogText(String value) {
		String normalized = String.valueOf(value == null ? "" : value).replaceAll("\\s+", " ").trim();
		return normalized.length() <= 80 ? normalized : normalized.substring(0, 80) + "...";
	}

	// 메소드 설명: namedThreadFactory 처리 흐름을 수행합니다.
	private static ThreadFactory namedThreadFactory(String prefix) {
		AtomicInteger counter = new AtomicInteger();
		return runnable -> {
			Thread thread = new Thread(runnable, prefix + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		};
	}

	private record CachedAnswer(
		LawAiAnswerResponse response,
		long expiresAtMillis
	) {
	}

	private static final class TimingProbe {
		private final long totalStartNanos;
		private final AtomicLong embeddingMs = new AtomicLong();
		private final AtomicLong qdrantMs = new AtomicLong();
		private final AtomicLong dbMs = new AtomicLong();
		private final AtomicLong judgeMs = new AtomicLong();
		private final AtomicLong answerMs = new AtomicLong();

		// 메소드 설명: TimingProbe 처리 흐름을 수행합니다.
		private TimingProbe(long totalStartNanos) {
			this.totalStartNanos = totalStartNanos;
		}

		// 메소드 설명: started 처리 흐름을 수행합니다.
		private static TimingProbe started() {
			return new TimingProbe(System.nanoTime());
		}

		// 메소드 설명: totalElapsedMs 처리 흐름을 수행합니다.
		private long totalElapsedMs() {
			return elapsedMillis(totalStartNanos);
		}

		// 메소드 설명: snapshot 처리 흐름을 수행합니다.
		private LawAiTiming snapshot(boolean cacheHit) {
			return new LawAiTiming(
				embeddingMs.get(),
				qdrantMs.get(),
				dbMs.get(),
				judgeMs.get(),
				answerMs.get(),
				totalElapsedMs(),
				cacheHit
			);
		}
	}

	private record RetrievalResult(
		String resultMsg,
		String target,
		String query,
		List<String> targets,
		List<String> lexicalKeywords,
		List<QdrantSearchHit> qdrantHits,
		List<LawSemanticChunkRow> vectorChunks,
		List<LawSemanticChunkRow> lexicalChunks,
		List<LawSemanticChunkRow> searchedChunks,
		List<LawSemanticChunkRow> rankedChunks,
		List<LawSemanticChunkRow> intentFilteredChunks,
		List<LawSemanticChunkRow> judgedChunks,
		List<LawSemanticChunkRow> answerChunks,
		Map<String, Double> vectorScoreByChunkId,
		Map<String, Double> keywordScoreByChunkId,
		Map<String, Double> metadataScoreByChunkId,
		Map<String, Double> combinedScoreByChunkId,
		Map<String, Double> baseScoreByChunkId,
		Map<String, Double> finalScoreByChunkId,
		List<LawAiAnswerGround> grounds,
		String message
	) {
		static RetrievalResult empty(
			String resultMsg,
			String target,
			String query,
			List<String> targets,
			List<String> lexicalKeywords,
			List<QdrantSearchHit> qdrantHits,
			List<LawSemanticChunkRow> vectorChunks,
			List<LawSemanticChunkRow> lexicalChunks,
			Map<String, Double> vectorScoreByChunkId,
			Map<String, Double> keywordScoreByChunkId,
			Map<String, Double> baseScoreByChunkId,
			String message,
			List<LawSemanticChunkRow> rankedChunks
		) {
			List<LawSemanticChunkRow> safeRankedChunks = rankedChunks == null ? List.of() : rankedChunks;
			Map<String, Double> safeVectorScores = vectorScoreByChunkId == null ? Map.of() : vectorScoreByChunkId;
			Map<String, Double> safeKeywordScores = keywordScoreByChunkId == null ? Map.of() : keywordScoreByChunkId;
			Map<String, Double> safeBaseScores = baseScoreByChunkId == null ? Map.of() : baseScoreByChunkId;
			return new RetrievalResult(
				resultMsg,
				target,
				query,
				targets == null ? List.of() : targets,
				lexicalKeywords == null ? List.of() : lexicalKeywords,
				qdrantHits == null ? List.of() : qdrantHits,
				vectorChunks == null ? List.of() : vectorChunks,
				lexicalChunks == null ? List.of() : lexicalChunks,
				safeRankedChunks,
				safeRankedChunks,
				List.of(),
				List.of(),
				List.of(),
				safeVectorScores,
				safeKeywordScores,
				Map.of(),
				safeBaseScores,
				safeBaseScores,
				safeBaseScores,
				List.of(),
				message
			);
		}
	}
}
