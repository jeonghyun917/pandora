package com.kaces.pandora.rag.recovery;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RagRecoveryWorkflowService {
    public record Status(RecoverySnapshot pointer,RecoveryBaselineAssets baseline,List<RecoveryGeneration> generations,boolean mutationsEnabled,String productSha) { }
    private final RagRecoveryCandidateService candidates;
    private final RagRecoveryValidationService validation;
    private final RagRecoveryIndexService index;
    private final RagRecoveryPromotionService promotion;
    private final RagRecoveryRollbackService rollback;
    private final RagRecoveryBaselineService baseline;
    private final RagRecoveryMapper mapper;
    private final String productSha;
    private final boolean enabled;
    public RagRecoveryWorkflowService(RagRecoveryCandidateService candidates,RagRecoveryValidationService validation,RagRecoveryIndexService index,
            RagRecoveryPromotionService promotion,RagRecoveryRollbackService rollback,RagRecoveryBaselineService baseline,RagRecoveryMapper mapper,
            @Value("${pandora.rag.recovery.product-sha:}") String productSha,@Value("${pandora.rag.recovery.mutations-enabled:false}") boolean enabled) {
        this.candidates=candidates;this.validation=validation;this.index=index;this.promotion=promotion;this.rollback=rollback;
        this.baseline=baseline;this.mapper=mapper;this.productSha=productSha;this.enabled=enabled;
    }
    public RagRecoveryCandidateService.Preview preview(long documentId) { requireProduct();return candidates.preview(documentId,productSha); }
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Status status(long documentId) {
        if(documentId<=0)throw new IllegalArgumentException("Positive document required");
        var generations=mapper.listGenerations(documentId);
        if(generations==null || generations.size()>100)throw new IllegalStateException("Recovery history bound exceeded");
        return new Status(mapper.findPointer(documentId,false),mapper.findBaselineAssets(documentId),List.copyOf(generations),enabled&&validProduct(),validProduct()?productSha:null);
    }
    public RecoveryGeneration create(long documentId,String expectedPreviewHash) {
        requireMutation();var generation=candidates.create(documentId,expectedPreviewHash,productSha);
        baseline.capture(documentId);return generation;
    }
    public RecoveryValidation validate(String generationId) { requireMutation();requireId(generationId);return validation.validate(generationId); }
    public RecoveryAssets prepare(String generationId) {
        requireMutation();requireId(generationId);var generation=mapper.findGeneration(generationId,false);
        if(generation==null)throw new IllegalStateException("Generation unavailable");
        baseline.verify(generation.documentId());return index.prepare(generationId);
    }
    public RecoverySnapshot promote(String generationId,long revision,String manifest) { requireMutation();return promotion.promote(generationId,revision,manifest); }
    public RecoverySnapshot rollback(long documentId,String active,long revision,String restore) { requireMutation();return rollback.rollback(documentId,active,revision,restore); }
    private boolean validProduct() { return productSha!=null && productSha.matches("[0-9a-f]{40}"); }
    private void requireProduct() { if(!validProduct())throw new IllegalStateException("Verified product identity unavailable"); }
    private void requireMutation() { if(!enabled)throw new IllegalStateException("Recovery mutation release gate is closed");requireProduct(); }
    private static void requireId(String id) { if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Generation UUID required"); }
}
