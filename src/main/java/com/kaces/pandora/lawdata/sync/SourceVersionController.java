package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.rag.recovery.RagRecoveryAccessInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/law-data/source-versions")
public class SourceVersionController {
    public record Session(String csrfToken) { }
    public record Stage(long baselineDocumentId,long expectedRevision,String externalId) { }
    public record Candidate(String candidateId) { }
    public record PrepareRollback(String groupKey,long expectedRevision,long restoreDocumentId) { }
    public record Apply(String transitionId,long expectedRevision) { }
    private final SourceVersionWorkflowService workflow;
    public SourceVersionController(SourceVersionWorkflowService workflow) {this.workflow=workflow;}
    @GetMapping("/session") public ResponseEntity<Session> session(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Session(RagRecoveryAccessInterceptor.csrfToken(request)));
    }
    @GetMapping("/status") public SourceVersionWorkflowService.Status status(@RequestParam String groupKey) {return workflow.status(groupKey);}
    @PostMapping(path="/stage",consumes=MediaType.APPLICATION_JSON_VALUE) public Object stage(@RequestBody Stage request) {return workflow.stage(request.baselineDocumentId(),request.expectedRevision(),request.externalId());}
    @PostMapping(path="/validate",consumes=MediaType.APPLICATION_JSON_VALUE) public Object validate(@RequestBody Candidate request) {return workflow.validate(request.candidateId());}
    @PostMapping(path="/prepare",consumes=MediaType.APPLICATION_JSON_VALUE) public Object prepare(@RequestBody Candidate request) {return workflow.prepare(request.candidateId());}
    @PostMapping(path="/prepare-rollback",consumes=MediaType.APPLICATION_JSON_VALUE) public Object prepareRollback(@RequestBody PrepareRollback request) {return workflow.prepareRollback(request.groupKey(),request.expectedRevision(),request.restoreDocumentId());}
    @PostMapping(path="/promote",consumes=MediaType.APPLICATION_JSON_VALUE) public Object promote(@RequestBody Apply request) {return workflow.promote(request.transitionId(),request.expectedRevision());}
    @PostMapping(path="/rollback",consumes=MediaType.APPLICATION_JSON_VALUE) public Object rollback(@RequestBody Apply request) {return workflow.rollback(request.transitionId(),request.expectedRevision());}
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<?> unavailable(RuntimeException failure) {
        int code=failure instanceof IllegalArgumentException?400:failure instanceof IllegalStateException?409:503;
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Source version operation rejected failureType={}",failure.getClass().getSimpleName());
        return ResponseEntity.status(code).body(java.util.Map.of("status",code,"message","원문 버전 작업을 완료하지 못했습니다. 최신 상태와 서버 검증 기록을 확인하세요."));
    }
}
