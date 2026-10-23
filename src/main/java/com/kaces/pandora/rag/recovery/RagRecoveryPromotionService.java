package com.kaces.pandora.rag.recovery;

import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** External readiness first; only DB reads and atomic CAS in the authoritative transition. */
@Service
public class RagRecoveryPromotionService {
    private final RagRecoveryIndexService index;
    private final RagRecoveryMapper recovery;
    private final RagRecoveryBaselineService baseline;
    private final TransactionTemplate transaction;
    public RagRecoveryPromotionService(RagRecoveryIndexService index,RagRecoveryMapper recovery,RagRecoveryBaselineService baseline,PlatformTransactionManager manager) {
        this.index=index;this.recovery=recovery;this.baseline=baseline;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    public RecoverySnapshot promote(String generationId,long expectedRevision,String expectedManifest) {
        if(generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || expectedRevision<0 || expectedRevision==Long.MAX_VALUE || expectedManifest==null || !expectedManifest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact promotion identity required");
        var generation=recovery.findGeneration(generationId,false);
        var assets=recovery.findAssets(generationId);
        if(generation==null || !"INDEX_READY".equals(generation.state()) || !expectedManifest.equals(generation.manifest())
                || assets==null || !expectedManifest.equals(assets.manifest()))throw new IllegalStateException("Candidate not ready");
        var preserved=baseline.verify(generation.documentId());
        index.verify(assets);
        return transaction.execute(status->{
            var prepared=index.recheckForPromotion(generationId);
            baseline.recheckStored(generation.documentId(),preserved);
            var current=recovery.findPointer(generation.documentId(),true);
            var candidate=recovery.findGeneration(generationId,true);
            if(current==null || current.revision()!=expectedRevision || current.activeGeneration().equals(generationId)
                    || !current.sourceHash().equals(generation.sourceHash()) || !current.baselineHash().equals(generation.baselineHash())
                    || candidate==null || !candidate.equals(generation) || !"INDEX_READY".equals(candidate.state())
                    || !assets.equals(recovery.findAssets(generationId)))throw new IllegalStateException("Stale promotion");
            var next=new RecoverySnapshot(current.documentId(),current.revision()+1,generationId,current.sourceHash(),current.baselineHash());
            if(recovery.changeGenerationState(current.activeGeneration(),"ACTIVE","RETIRED")!=1
                    || recovery.changeGenerationState(generationId,"INDEX_READY","ACTIVE")!=1
                    || recovery.casPointer(current,next)!=1
                    || recovery.casEpoch(prepared.baseRevision(),generationId,assets.lexicalFingerprint())!=1)
                throw new IllegalStateException("Promotion compare-and-set failed");
            return next;
        });
    }
}
