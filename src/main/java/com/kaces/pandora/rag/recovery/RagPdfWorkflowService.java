package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import java.nio.file.Path;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/** Default-off admin workflow. Requests select existing identities, never source bytes or review authority. */
@Service
public final class RagPdfWorkflowService {
    public record Review(long documentId,String title,String sourceUrl,String sourceHash,String baselineHash,
            String productSha,String reviewHash,int units,boolean mutationsEnabled) { }
    public record Preparation(String draftId,String reviewHash,String manifest,int chunks,int units) { }
    private final JdbcTemplate db;
    private final PlatformTransactionManager transactions;
    private final RagDocumentMapper documents;
    private final RagRecoveryMapper recovery;
    private final RagRecoveryCandidateService candidates;
    private final RagRecoveryBaselineService baseline;
    private final RagRecoveryIndexService index;
    private final RagRecoveryPromotionService promotion;
    private final RagRecoveryRollbackService rollback;
    private final String productSha;
    private final boolean enabled;
    private final RagPdfTrustedInput trusted;
    public RagPdfWorkflowService(JdbcTemplate db,PlatformTransactionManager transactions,RagDocumentMapper documents,
            RagRecoveryMapper recovery,RagRecoveryCandidateService candidates,RagRecoveryBaselineService baseline,
            RagRecoveryIndexService index,RagRecoveryPromotionService promotion,RagRecoveryRollbackService rollback,
            @Value("${pandora.rag.recovery.product-sha:}") String productSha,
            @Value("${pandora.rag.recovery.mutations-enabled:false}") boolean recoveryEnabled,
            @Value("${pandora.rag.reviewed-pdf.mutations-enabled:false}") boolean enabled,
            @Value("${pandora.rag.reviewed-pdf.manifest-path:}") String manifestPath,
            @Value("${pandora.rag.reviewed-pdf.manifest-sha256:}") String manifestHash) {
        this.db=db;this.transactions=transactions;this.documents=documents;this.recovery=recovery;
        this.candidates=candidates;this.baseline=baseline;this.index=index;this.promotion=promotion;this.rollback=rollback;
        this.productSha=productSha;this.enabled=enabled&&recoveryEnabled;this.trusted=new RagPdfTrustedInput(manifestPath,manifestHash,productSha);
    }
    public Review review() {
        var draft=trusted.read().draft();
        return new Review(draft.documentId(),draft.title(),draft.sourceUrl(),draft.sourceHash(),draft.baselineHash(),
                draft.productSha(),draft.reviewHash(),draft.units().size(),enabled);
    }
    public RecoveryBaselineAssets register() {
        var input=input();var draft=input.draft();
        var original=documents.findSourceProofDocument(draft.documentId(),false);
        if(original==null)throw new IllegalStateException("Preserved original unavailable");
        return baseline.registerPreserved(draft.documentId(),original.fileHash(),draft.baselineHash(),productSha);
    }
    public RagPdfDraftStore.Staged stage() {
        var input=input();var draft=input.draft();
        var receipt=baseline.verify(draft.documentId());
        if(!draft.baselineHash().equals(receipt.baselineHash()))throw new IllegalStateException("Preserved baseline changed");
        return store(input).stage(draft,Path.of(input.originalPath()));
    }
    public Preparation inspect(String draftId) {
        var input=input();var store=store(input);requireDraft(store,draftId,input.draft());
        var prepared=new RagPdfDraftPreparation(store).prepare(draftId);
        return new Preparation(draftId,prepared.reviewHash(),prepared.manifest(),prepared.chunks().size(),prepared.units().size());
    }
    public RecoveryGeneration create(String draftId,String manifest) {
        var input=input();var store=store(input);requireDraft(store,draftId,input.draft());
        return new RagPdfGenerationStore(db,transactions,candidates,recovery,store).create(draftId,manifest);
    }
    public RecoveryGeneration validate(String generationId,String manifest) {
        var input=input();owned(input,generationId);
        return new RagPdfGenerationStore(db,transactions,candidates,recovery,store(input)).validate(generationId,manifest);
    }
    public RecoveryAssets prepare(String generationId) {
        var input=input();owned(input,generationId);
        var receipt=baseline.verify(input.draft().documentId());
        if(!input.draft().baselineHash().equals(receipt.baselineHash()))throw new IllegalStateException("Preserved baseline changed");
        return index.prepareReviewedPdf(generationId);
    }
    public RecoverySnapshot promote(String generationId,long revision,String manifest) {
        var input=input();owned(input,generationId);return promotion.promoteReviewedPdf(generationId,revision,manifest);
    }
    public RecoverySnapshot rollback(long documentId,String active,long revision,String restore) {
        var input=input();
        if(documentId!=input.draft().documentId())throw new IllegalArgumentException("Configured reviewed document required");
        owned(input,active);return rollback.rollbackReviewedPdf(documentId,active,revision,restore);
    }
    private RagPdfTrustedInput.Input input() {
        if(!enabled)throw new IllegalStateException("Reviewed PDF mutation release gate is closed");
        return trusted.read();
    }
    private RagPdfDraftStore store(RagPdfTrustedInput.Input input) {
        return new RagPdfDraftStore(db,transactions,candidates,productSha,Set.of(input.draft().reviewHash()));
    }
    private static void requireDraft(RagPdfDraftStore store,String id,ReviewedPdfDraft draft) {
        if(!store.load(id).draft().equals(draft))throw new IllegalStateException("Configured reviewed draft required");
    }
    private void owned(RagPdfTrustedInput.Input input,String generationId) {
        if(generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Exact reviewed generation required");
        var generation=recovery.findGeneration(generationId,false);var draft=input.draft();
        if(generation==null || generation.documentId()!=draft.documentId() || !draft.productSha().equals(generation.productSha())
                || !draft.sourceHash().equals(generation.sourceHash()) || !draft.baselineHash().equals(generation.baselineHash()))
            throw new IllegalStateException("Configured reviewed generation required");
        var bindings=db.queryForList("SELECT draft_id FROM rag_pdf_generation_sources WHERE generation_id=?",generationId);
        if(bindings.size()!=1)throw new IllegalStateException("Reviewed generation binding unavailable");
        requireDraft(store(input),(String)bindings.get(0).get("draft_id"),draft);
    }
}
