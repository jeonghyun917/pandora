package com.kaces.pandora.rag.recovery;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/law-data/rag-recovery")
public class RagRecoveryController {
    public record Session(String csrfToken) { }
    public record Create(long documentId,String expectedPreviewHash) { }
    public record Generation(String generationId) { }
    public record Promote(String generationId,long expectedRevision,String expectedManifest) { }
    public record Rollback(long documentId,String expectedActiveGeneration,long expectedRevision,String restoreGeneration) { }
    private final RagRecoveryWorkflowService workflow;
    public RagRecoveryController(RagRecoveryWorkflowService workflow) { this.workflow=workflow; }
    @GetMapping("/session") public ResponseEntity<Session> session(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Session(RagRecoveryAccessInterceptor.csrfToken(request)));
    }
    @GetMapping("/preview") public RagRecoveryCandidateService.Preview preview(@RequestParam long documentId) { return workflow.preview(documentId); }
    @GetMapping("/status") public RagRecoveryWorkflowService.Status status(@RequestParam long documentId) { return workflow.status(documentId); }
    @PostMapping(path="/create",consumes=MediaType.APPLICATION_JSON_VALUE) public RecoveryGeneration create(@RequestBody Create request) {
        return workflow.create(request.documentId(),request.expectedPreviewHash());
    }
    @PostMapping(path="/validate",consumes=MediaType.APPLICATION_JSON_VALUE) public RecoveryValidation validate(@RequestBody Generation request) { return workflow.validate(request.generationId()); }
    @PostMapping(path="/prepare",consumes=MediaType.APPLICATION_JSON_VALUE) public RecoveryAssets prepare(@RequestBody Generation request) { return workflow.prepare(request.generationId()); }
    @PostMapping(path="/promote",consumes=MediaType.APPLICATION_JSON_VALUE) public RecoverySnapshot promote(@RequestBody Promote request) {
        return workflow.promote(request.generationId(),request.expectedRevision(),request.expectedManifest());
    }
    @PostMapping(path="/rollback",consumes=MediaType.APPLICATION_JSON_VALUE) public RecoverySnapshot rollback(@RequestBody Rollback request) {
        return workflow.rollback(request.documentId(),request.expectedActiveGeneration(),request.expectedRevision(),request.restoreGeneration());
    }
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<?> unavailable(RuntimeException failure) {
        int code=failure instanceof IllegalArgumentException?400:failure instanceof IllegalStateException?409:503;
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Recovery operation rejected failureType={}",failure.getClass().getSimpleName());
        return ResponseEntity.status(code).body(java.util.Map.of("status",code,"message","복구 작업을 완료하지 못했습니다. 최신 상태와 서버 검증 기록을 확인하세요."));
    }
}
