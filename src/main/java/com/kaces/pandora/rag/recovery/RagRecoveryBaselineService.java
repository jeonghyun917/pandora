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
    /** Registers a previously unmanaged original without creating or publishing a replacement. */
    RecoveryBaselineAssets registerPreserved(long documentId,String sourceHash,String baselineHash,String productSha) {
        if(documentId<=0 || sourceHash==null || !sourceHash.matches("[0-9a-f]{64}") || baselineHash==null || !baselineHash.matches("[0-9a-f]{64}")
                || productSha==null || !productSha.matches("[0-9a-f]{40}"))
            throw new IllegalArgumentException("Exact preserved original and product identity required");
        var initial=transaction.execute(status->unmanaged(documentId,sourceHash,baselineHash));
        String fingerprint=qdrant.baselineRagFingerprint(initial.searchable());
        String id=UUID.randomUUID().toString();
        var receipt=new RecoveryBaselineAssets(documentId,id,baselineHash,collection,model,fingerprint,initial.searchable().size());
        return transaction.execute(status->{
            if(!initial.equals(unmanaged(documentId,sourceHash,baselineHash)))
                throw new RecoveryStaleException("Preserved baseline changed during registration");
            var generation=new RecoveryGeneration(id,documentId,sourceHash,productSha,baselineHash,baselineHash,
                    com.kaces.pandora.common.text.LawHashUtils.sha256Json(List.of("baseline",documentId,baselineHash)),"ACTIVE");
            if(recovery.insertGeneration(generation)!=1)throw new IllegalStateException("Preserved generation conflict");
            for(long member:initial.members()) {
                if(recovery.insertMembership(id,member)!=1)throw new IllegalStateException("Preserved membership conflict");
            }
            if(recovery.insertBaseline(documentId,id)!=1
                    || recovery.insertPointer(new RecoverySnapshot(documentId,0,id,sourceHash,baselineHash))!=1
                    || recovery.insertBaselineAssets(receipt)!=1)
                throw new IllegalStateException("Preserved registration conflict");
            return receipt;
        });
    }
    private Registration unmanaged(long documentId,String sourceHash,String baselineHash) {
        var epoch=recovery.lockRoutingEpoch();
        Long corpus=recovery.findCorpusRevision();
        if(epoch==null || corpus==null || corpus<0 || (epoch.revision()>0 && !Boolean.TRUE.equals(recovery.isCorpusCurrent())))
            throw new IllegalStateException("Current routing epoch required");
        requirePreservedSource(documentId,sourceHash);
        if(recovery.findPointer(documentId,true)!=null || recovery.findBaseline(documentId)!=null
                || recovery.findBaselineAssets(documentId)!=null || !recovery.listGenerations(documentId).isEmpty())
            throw new IllegalStateException("Document already has recovery state");
        if(!baselineHash.equals(candidates.lockedStoredBaselineHash(documentId)))
            throw new RecoveryStaleException("Preserved baseline identity changed");
        var active=documents.findActiveChunksForSourceProof(documentId,true);
        var rows=searchableRows(documentId);
        if(!rows.stream().map(LawSemanticChunkRow::chunkId).sorted().toList().equals(active.stream()
                .filter(c->Set.of("PASS","REVIEW").contains(c.qualityStatus())).map(c->c.chunkId()).sorted().toList()))
            throw new IllegalStateException("Preserved searchable membership changed");
        return new Registration(epoch,corpus,active.stream().map(c->c.chunkId()).toList(),rows);
    }
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
    void requirePreservedSource(long documentId,String expectedHash) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Original recheck requires transaction");
        candidates.requirePreservedSource(documentId,expectedHash);
    }
    private Snapshot snapshot(long documentId) {
        if(recovery.lockRoutingEpoch()==null)throw new IllegalStateException("Routing epoch unavailable");
        String hash=candidates.lockedStoredBaselineHash(documentId);
        var baseline=recovery.findBaseline(documentId);
        var pointer=recovery.findPointer(documentId,true);
        var generation=baseline==null?null:recovery.findGeneration(baseline,true);
        if(pointer==null || generation==null || generation.documentId()!=documentId || !Set.of("ACTIVE","RETIRED").contains(generation.state()) || !hash.equals(generation.baselineHash())
                || !hash.equals(pointer.baselineHash()))throw new IllegalStateException("Preserved baseline unavailable");
        return new Snapshot(baseline,hash,pointer,searchableRows(documentId),recovery.findBaselineAssets(documentId));
    }
    private List<LawSemanticChunkRow> searchableRows(long documentId) {
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
        return rows;
    }
    private record Registration(RecoveryRoutingEpoch epoch,long corpusRevision,List<Long> members,List<LawSemanticChunkRow> searchable) { }
    private record Snapshot(String baseline,String hash,RecoverySnapshot pointer,List<LawSemanticChunkRow> rows,RecoveryBaselineAssets assets) { }
}
