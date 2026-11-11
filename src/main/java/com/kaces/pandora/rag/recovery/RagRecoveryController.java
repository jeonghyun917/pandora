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
    public record PdfCreate(String draftId,String expectedPreparedManifest) { }
    public record PdfValidate(String generationId,String expectedPreparedManifest) { }
    private final RagRecoveryWorkflowService workflow;
    private RagPdfWorkflowService reviewedPdf;
    @org.springframework.beans.factory.annotation.Autowired
    void configureReviewedPdf(RagPdfWorkflowService reviewedPdf) {this.reviewedPdf=reviewedPdf;}
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
    @GetMapping("/reviewed-pdf/review") public RagPdfWorkflowService.Review reviewedPdfReview() {return pdf().review();}
    @PostMapping(path="/reviewed-pdf/register",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoveryBaselineAssets reviewedPdfRegister() {return pdf().register();}
    @PostMapping(path="/reviewed-pdf/stage",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RagPdfDraftStore.Staged reviewedPdfStage() {return pdf().stage();}
    @GetMapping("/reviewed-pdf/preparation")
    public RagPdfWorkflowService.Preparation reviewedPdfPreparation(@RequestParam String draftId) {return pdf().inspect(draftId);}
    @PostMapping(path="/reviewed-pdf/create",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoveryGeneration reviewedPdfCreate(@RequestBody PdfCreate request) {return pdf().create(request.draftId(),request.expectedPreparedManifest());}
    @PostMapping(path="/reviewed-pdf/validate",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoveryGeneration reviewedPdfValidate(@RequestBody PdfValidate request) {return pdf().validate(request.generationId(),request.expectedPreparedManifest());}
    @PostMapping(path="/reviewed-pdf/prepare",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoveryAssets reviewedPdfPrepare(@RequestBody Generation request) {return pdf().prepare(request.generationId());}
    @PostMapping(path="/reviewed-pdf/promote",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoverySnapshot reviewedPdfPromote(@RequestBody Promote request) {return pdf().promote(request.generationId(),request.expectedRevision(),request.expectedManifest());}
    @PostMapping(path="/reviewed-pdf/rollback",consumes=MediaType.APPLICATION_JSON_VALUE)
    public RecoverySnapshot reviewedPdfRollback(@RequestBody Rollback request) {
        return pdf().rollback(request.documentId(),request.expectedActiveGeneration(),request.expectedRevision(),request.restoreGeneration());
    }
    private RagPdfWorkflowService pdf() {
        if(reviewedPdf==null)throw new IllegalStateException("Reviewed PDF workflow unavailable");
        return reviewedPdf;
    }
    @ExceptionHandler(RuntimeException.class) public ResponseEntity<?> unavailable(RuntimeException failure) {
        int code=failure instanceof IllegalArgumentException?400:failure instanceof IllegalStateException?409:503;
        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Recovery operation rejected failureType={}",failure.getClass().getSimpleName());
        return ResponseEntity.status(code).body(java.util.Map.of("status",code,"message","복구 작업을 완료하지 못했습니다. 최신 상태와 서버 검증 기록을 확인하세요."));
    }
}
