package com.kaces.pandora.rag.chunk;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/law-data/rag-source-proof")
public class RagSourceProofBackfillController {
    public record ApplyRequest(long documentId, String expectedPlanToken) { }
    private final RagSourceProofBackfillService service;
    public RagSourceProofBackfillController(RagSourceProofBackfillService service) { this.service=service; }
    @GetMapping("/preview")
    public RagSourceProofBackfillService.Preview preview(@RequestParam(required=false) Long documentId,
        @RequestParam(required=false) String fileHash) {
        return service.preview(documentId,fileHash);
    }
    @PostMapping("/apply")
    public RagSourceProofBackfillService.Applied apply(@RequestBody ApplyRequest request) {
        return service.apply(request.documentId(),request.expectedPlanToken());
    }
}
