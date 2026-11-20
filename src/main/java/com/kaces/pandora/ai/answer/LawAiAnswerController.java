package com.kaces.pandora.ai.answer;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/law-data/ai")
public class LawAiAnswerController {

	private static final String EVALUATION_CONTRACT_HEADER = "X-Pandora-Evaluation-Contract-SHA256";
	private final LawAiAnswerService answerService;

	// 메소드 설명: LawAiAnswerController 처리 흐름을 수행합니다.
	public LawAiAnswerController(LawAiAnswerService answerService) {
		this.answerService = answerService;
	}

	@PostMapping("/answer")
	// 메소드 설명: answer 처리 흐름을 수행합니다.
	public ResponseEntity<LawAiAnswerResponse> answer(@RequestBody LawAiAnswerRequest request) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.answer(request));
	}

	@PostMapping(value = "/answer-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	// 메소드 설명: answerStream 처리 흐름을 수행합니다.
	public SseEmitter answerStream(@RequestBody LawAiAnswerRequest request) {
		return answerService.answerStream(request);
	}

	@PostMapping("/debug/search")
	// 메소드 설명: debug 처리 흐름을 수행합니다.
	public ResponseEntity<LawAiDebugResponse> debug(@RequestBody LawAiDebugRequest request) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.debug(request));
	}

	@GetMapping("/debug/evaluation-cases")
	// 메소드 설명: evaluationCases 처리 흐름을 수행합니다.
	public ResponseEntity<java.util.List<LawAiEvalRequest.EvalCase>> evaluationCases() {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.defaultEvaluationCases());
	}

	@GetMapping("/debug/runtime-info")
	public ResponseEntity<LawAiRuntimeInfo> runtimeInfo() {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.runtimeInfo());
	}

	@GetMapping("/debug/evaluation-contracts")
	public ResponseEntity<EvaluationContractCapabilities> evaluationContracts() {
		// Keep in sync with the fail-closed evaluation dispatch below. No contract is active yet.
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
			.body(new EvaluationContractCapabilities(1,EVALUATION_CONTRACT_HEADER,List.of()));
	}

	public record EvaluationContractCapabilities(int schemaVersion,String contractHeader,List<String> registeredContracts) {}

	@GetMapping("/debug/failures")
	public ResponseEntity<java.util.List<LawAiSearchFailureRow>> failures(
		@RequestParam(required = false) Integer limit,
		@RequestParam(defaultValue = "false") boolean evalCandidateOnly,
		@RequestParam(required = false) String reviewStatus
	) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.recentSearchFailures(limit, evalCandidateOnly, reviewStatus));
	}

	@GetMapping("/debug/failures/evaluation-candidates")
	public ResponseEntity<java.util.List<LawAiSearchFailureCandidate>> failureEvaluationCandidates(
		@RequestParam(required = false) Integer limit,
		@RequestParam(required = false) Integer minOccurrences,
		@RequestParam(required = false) Integer days
	) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.failureEvaluationCandidates(limit, minOccurrences, days));
	}

	@PostMapping("/debug/failures/{failureId}/evaluation-case")
	public ResponseEntity<LawAiEvalRequest.EvalCase> promoteFailureToEvaluationCase(
		@PathVariable long failureId,
		@RequestBody(required = false) LawAiFailureEvalCaseRequest request
	) {
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.promoteFailureToEvaluationCase(failureId, request));
	}

	@PostMapping("/debug/evaluate")
	// 메소드 설명: evaluate 처리 흐름을 수행합니다.
	public ResponseEntity<LawAiEvalResponse> evaluate(@RequestBody(required = false) LawAiEvalRequest request,
		@RequestHeader(name = EVALUATION_CONTRACT_HEADER, required = false) List<String> contractHashes) {
		rejectUnregisteredEvaluationContract(contractHashes);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return ResponseEntity.ok()
			.contentType(MediaType.APPLICATION_JSON)
			.body(answerService.evaluate(request));
	}

	@PostMapping("/debug/evaluate/gate")
	public ResponseEntity<LawAiEvalResponse> evaluateGate(@RequestBody(required = false) LawAiEvalRequest request,
		@RequestHeader(name = EVALUATION_CONTRACT_HEADER, required = false) List<String> contractHashes) {
		rejectUnregisteredEvaluationContract(contractHashes);
		LawAiEvalResponse response = answerService.evaluate(request);
		return ResponseEntity.status(response.gatePassed() ? HttpStatus.OK : HttpStatus.CONFLICT)
			.contentType(MediaType.APPLICATION_JSON)
			.body(response);
	}

	private static void rejectUnregisteredEvaluationContract(List<String> hashes) {
		if (hashes == null) return;
		if (hashes.size() != 1 || !hashes.get(0).matches("[a-f0-9]{64}")) {
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Expected one canonical evaluation contract SHA-256");
		}
		// No versioned scoring contract is registered yet. Never score a tagged request as legacy.
		throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "Evaluation contract is not supported");
	}
}
