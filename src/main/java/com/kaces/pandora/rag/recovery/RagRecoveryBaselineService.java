package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

@Service
public class RagRecoveryBaselineService {
    private final RagDocumentMapper documents;
    private final RagRecoveryMapper recovery;
    private final RagRecoveryCandidateService candidates;
    private final QdrantClient qdrant;
    private final TransactionTemplate transaction;
    private final String model,collection;
    public RagRecoveryBaselineService(RagDocumentMapper documents,RagRecoveryMapper recovery,RagRecoveryCandidateService candidates,
            QdrantClient qdrant,PlatformTransactionManager manager,LawAiProperties properties) {
        this.documents=documents;this.recovery=recovery;this.candidates=candidates;this.qdrant=qdrant;
        model=properties.openai().embeddingModel();collection=properties.qdrant().ragCollection();
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    public RecoveryBaselineAssets capture(long documentId) { return inspect(documentId,true); }
    public RecoveryBaselineAssets verify(long documentId) { return inspect(documentId,false); }
    private RecoveryBaselineAssets inspect(long documentId,boolean allowCreate) {
        if(documentId<=0)throw new IllegalArgumentException("Positive document required");
        var initial=transaction.execute(status->snapshot(documentId));
        if(initial.assets()==null && (!allowCreate || initial.pointer().revision()!=0
                || !initial.pointer().activeGeneration().equals(initial.baseline())))throw new IllegalStateException("Preserved baseline receipt unavailable");
        String fingerprint=qdrant.baselineRagFingerprint(initial.rows()); // Outside every pointer transaction.
        var expected=new RecoveryBaselineAssets(documentId,initial.baseline(),initial.hash(),collection,model,fingerprint,initial.rows().size());
        if(initial.assets()!=null && !initial.assets().equals(expected))throw new IllegalStateException("Preserved baseline assets changed");
        return transaction.execute(status->{
            var current=snapshot(documentId);
            if(!current.hash().equals(initial.hash()) || !current.baseline().equals(initial.baseline())
                    || current.rows().size()!=initial.rows().size())throw new IllegalStateException("Baseline changed during inspection");
            if(current.assets()!=null) {
                if(!current.assets().equals(expected))throw new IllegalStateException("Baseline receipt conflict");
            } else {
                if(!allowCreate || current.pointer().revision()!=0 || !current.pointer().activeGeneration().equals(current.baseline())
                        || recovery.insertBaselineAssets(expected)!=1)throw new IllegalStateException("Baseline reservation conflict");
            }
            return expected;
        });
    }
    void recheckStored(long documentId,RecoveryBaselineAssets expected) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Baseline recheck requires transaction");
        var current=snapshot(documentId);
        if(expected==null || !expected.equals(current.assets()) || !expected.baselineHash().equals(current.hash())
                || !expected.generationId().equals(current.baseline()) || expected.chunkCount()!=current.rows().size())
            throw new IllegalStateException("Baseline receipt changed");
    }
    private Snapshot snapshot(long documentId) {
        if(recovery.lockRoutingEpoch()==null)throw new IllegalStateException("Routing epoch unavailable");
        String hash=candidates.lockedStoredBaselineHash(documentId);
        var baseline=recovery.findBaseline(documentId);
        var pointer=recovery.findPointer(documentId,true);
        var generation=baseline==null?null:recovery.findGeneration(baseline,true);
        if(pointer==null || generation==null || generation.documentId()!=documentId || !Set.of("ACTIVE","RETIRED").contains(generation.state()) || !hash.equals(generation.baselineHash())
                || !hash.equals(pointer.baselineHash()))throw new IllegalStateException("Preserved baseline unavailable");
        var rows=documents.findSemanticChunksByDocumentId(documentId).stream()
            .filter(row->Set.of("PASS","REVIEW").contains(row.qualityStatus())).toList();
        if(rows.isEmpty() || rows.size()>10000)throw new IllegalStateException("Baseline searchable chunk bound");
        var embeddingRows=recovery.embeddingSnapshot(documentId);
        for(var row:rows) {
            long matches=embeddingRows.stream().filter(e->e.get("chunk_id") instanceof Number id && id.longValue()==row.chunkId()
                && model.equals(e.get("embedding_model")) && collection.equals(e.get("vector_store")) && "INDEXED".equals(e.get("status"))
                && row.contentHash().equals(e.get("content_hash"))
                && String.valueOf(QdrantClient.ragPointId(row.chunkId())).equals(String.valueOf(e.get("vector_point_id")))).count();
            if(matches!=1)throw new IllegalStateException("Baseline embedding link unavailable");
        }
        return new Snapshot(baseline,hash,pointer,rows,recovery.findBaselineAssets(documentId));
    }
    private record Snapshot(String baseline,String hash,RecoverySnapshot pointer,List<LawSemanticChunkRow> rows,RecoveryBaselineAssets assets) { }
}
