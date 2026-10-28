package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.lexical.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Prepares isolated assets. Does not expose an API or change the authoritative search pointer. */
@Service
public class RagRecoveryIndexService {
    private final RagDocumentMapper documents;
    private final RagRecoveryMapper recovery;
    private final RagRecoveryCandidateService candidates;
    private final RagRecoveryReceiptService receipts;
    private final OpenAiEmbeddingClient embeddings;
    private final QdrantClient qdrant;
    private final SemanticLexicalMapper lexical;
    private final SemanticLexicalIndexService lexicalIndex;
    private final TransactionTemplate transaction;
    private final RagRecoveryRoutingService routing;
    private final String model;
    private final String productSha;
    private com.kaces.pandora.lawdata.version.SourceVersionCorpusService sources;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureSourceVersions(com.kaces.pandora.lawdata.version.SourceVersionCorpusService sources,
            com.kaces.pandora.lawdata.version.SourceVersionRoutingReader reader) {
        this.sources=Objects.requireNonNull(sources);routing.configureSourceVersionRouting(reader);
    }

    public RagRecoveryIndexService(RagDocumentMapper documents, RagRecoveryMapper recovery,
            RagRecoveryCandidateService candidates, RagRecoveryReceiptService receipts, OpenAiEmbeddingClient embeddings,
            QdrantClient qdrant, SemanticLexicalMapper lexical, SemanticLexicalIndexService lexicalIndex,
            PlatformTransactionManager manager, LawAiProperties properties,
            @Value("${pandora.rag.recovery.product-sha:}") String productSha) {
        this.documents=documents; this.recovery=recovery; this.candidates=candidates; this.receipts=receipts;
        this.embeddings=embeddings; this.qdrant=qdrant; this.lexical=lexical; this.lexicalIndex=lexicalIndex;
        this.model=properties.openai().embeddingModel(); this.productSha=productSha;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        routing=new RagRecoveryRoutingService(recovery,lexical,manager);
    }

    public RecoveryAssets prepare(String generationId) {
        try { return prepareBound(generationId); }
        catch(RecoveryStaleException stale) {
            transaction.executeWithoutResult(status->{
                if(sources!=null)sources.lockSourceEpoch();
                recovery.lockRoutingEpoch();
                var generation=recovery.findGeneration(generationId,true);
                if(generation!=null && productSha.equals(generation.productSha()) && Set.of("VALIDATED","INDEX_READY").contains(generation.state()))
                    if(recovery.changeGenerationState(generationId,generation.state(),"STALE")!=1)throw new IllegalStateException("Stale state conflict");
            });
            throw stale;
        }
    }
    private RecoveryAssets prepareBound(String generationId) {
        Snapshot initial=snapshot(generationId,true);
        if ("INDEX_READY".equals(initial.generation().state())) {
            var existing=recovery.findAssets(generationId);
            if(existing==null)throw new IllegalStateException("Recovery assets missing");
            verify(existing); return existing;
        }
        Map<Long,List<Double>> vectors;
        if(recovery.findEmbeddingReceipts(generationId).isEmpty()) {
            var prepared=new TreeMap<Long,List<Double>>();
            for(int start=0;start<initial.chunks().size();start+=128) {
                var batch=initial.chunks().subList(start,Math.min(start+128,initial.chunks().size()));
                var result=embeddings.embed(batch.stream().map(c->c.embeddingText()==null || c.embeddingText().isBlank()?c.chunkText():c.embeddingText()).toList());
                if(result==null || result.size()!=batch.size())throw new IllegalStateException("Embedding response incomplete");
                for(int n=0;n<batch.size();n++)prepared.put(batch.get(n).chunkId(),result.get(n));
            }
            receipts.save(generationId,prepared);
        }
        vectors=receipts.load(generationId);
        // Reconcile the actual namespace before every write, including after a lost response or process restart.
        for(int start=0;start<initial.chunks().size();start+=128) {
            var batch=initial.chunks().subList(start,Math.min(start+128,initial.chunks().size()));
            var inspected=qdrant.inspectRagRecoveryPoints(generationId,model,initial.generation().manifest(),batch,
                    batch.stream().map(c->vectors.get(c.chunkId())).toList());
            var requested=batch.stream().map(RagDocumentChunkRow::chunkId).collect(java.util.stream.Collectors.toSet());
            if(!requested.containsAll(inspected.missingChunkIds()))throw new IllegalStateException("Unexpected missing point identity");
            var missing=batch.stream().filter(c->inspected.missingChunkIds().contains(c.chunkId())).toList();
            if(!missing.isEmpty()) {
                recovery.markEmbeddingUncertain(generationId,missing.stream().map(RagDocumentChunkRow::chunkId).toList());
                qdrant.upsertRagRecovery(generationId,model,initial.generation().manifest(),missing,
                        missing.stream().map(c->vectors.get(c.chunkId())).toList());
            }
        }
        String vectorFingerprint=vectorFingerprint(initial,vectors);
        String lexicalVersion="recovery_"+generationId;
        if(lexical.findRecoveryState(lexicalVersion)==null) {
            // A duplicate/unfinished namespace fails closed; no deletion or blind rebuild is performed.
            lexicalIndex.rebuildRecovery(generationId,initial.corpus());
        }
        String lexicalFingerprint=lexicalIndex.verifyRecovery(generationId,initial.corpus());
        var assets=new RecoveryAssets(generationId,initial.generation().manifest(),qdrant.ragRecoveryCollection(generationId),
                vectorFingerprint,lexicalFingerprint,initial.chunks().size());
        checkedAssets(assets);
        return transaction.execute(status->{
            var current=captureLocked(generationId);
            if(!current.corpusHash().equals(initial.corpusHash()) || !current.generation().manifest().equals(initial.generation().manifest())) {
                throw new RecoveryStaleException("Recovery corpus changed during preparation");
            }
            if("INDEX_READY".equals(current.generation().state())) {
                if(!assets.equals(recovery.findAssets(generationId)))throw new IllegalStateException("Recovery asset receipt conflict");
                return assets;
            }
            recovery.insertAssets(assets);
            recovery.markEmbeddingsVerified(generationId);
            if(recovery.verifiedEmbeddingCount(generationId)!=assets.chunkCount() || recovery.markIndexReady(current.generation())!=1) {
                throw new IllegalStateException("Recovery preparation snapshot changed");
            }
            return assets;
        });
    }

    public void verify(RecoveryAssets assets) {
        checkedAssets(assets);
        var current=snapshot(assets.generationId(),false);
        if(!"INDEX_READY".equals(current.generation().state()) || !assets.equals(recovery.findAssets(assets.generationId()))
                || !assets.manifest().equals(current.generation().manifest()) || assets.chunkCount()!=current.chunks().size()) {
            throw new IllegalStateException("Recovery receipt is not current");
        }
        var vectors=receipts.load(assets.generationId());
        if(!assets.vectorFingerprint().equals(vectorFingerprint(current,vectors))
                || !assets.lexicalFingerprint().equals(lexicalIndex.verifyRecovery(assets.generationId(),current.corpus()))) {
            throw new IllegalStateException("Recovery assets changed or disappeared");
        }
    }

    private String vectorFingerprint(Snapshot snapshot,Map<Long,List<Double>> vectors) {
        return qdrant.verifyRagRecoveryCollection(snapshot.generation().id(),model,snapshot.generation().manifest(),snapshot.chunks(),
                snapshot.chunks().stream().map(c->vectors.get(c.chunkId())).toList());
    }
    private Snapshot snapshot(String id,boolean reserve) {
        return transaction.execute(status->{
            var snapshot=captureLocked(id);
            var existing=recovery.findPreparation(id);
            if(existing==null) {
                if(!reserve || recovery.findAssets(id)!=null || !recovery.findEmbeddingReceipts(id).isEmpty()
                        || lexical.findRecoveryState("recovery_"+id)!=null)
                    throw new IllegalStateException("Unbound recovery assets require diagnosis");
                if(recovery.insertPreparation(snapshot.preparation())!=1)throw new IllegalStateException("Preparation reservation conflict");
            }
            return snapshot;
        });
    }
    RecoveryPreparationReceipt recheckForPromotion(String id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Promotion recheck requires a transaction");
        var snapshot=captureLocked(id,false);
        if(!snapshot.preparation().equals(recovery.findPreparation(id)))throw new IllegalStateException("Preparation unavailable");
        return snapshot.preparation();
    }
    private Snapshot captureLocked(String id) { return captureLocked(id,true); }
    private Snapshot captureLocked(String id,boolean checkOriginal) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || productSha==null || !productSha.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("Verified generation/product required");
        if(sources!=null)sources.lockSourceEpoch();
        if(recovery.lockRoutingEpoch()==null)throw new IllegalStateException("Routing epoch unavailable");
        var captured=routing.captureInTransaction();
        var initial=recovery.findGeneration(id,false);
        if(initial==null)throw new IllegalStateException("Generation unavailable");
        var document=documents.findSourceProofDocument(initial.documentId(),true);
        var generation=recovery.findGeneration(id,true);
        if(document==null || generation==null || !productSha.equals(generation.productSha())
                || !Set.of("VALIDATED","INDEX_READY").contains(generation.state()))throw new IllegalStateException("Generation not validated");
        if(checkOriginal) {
            var preview=candidates.lockedPreview(generation.documentId(),productSha);
            if(!preview.sourceHash().equals(generation.sourceHash()) || !preview.baselineHash().equals(generation.baselineHash())
                    || !preview.manifest().equals(generation.manifest()))throw new IllegalStateException("Source or baseline changed");
        } else if(!document.fileHash().equals(generation.sourceHash())
                || !candidates.lockedStoredBaselineHash(generation.documentId()).equals(generation.baselineHash())) {
            throw new IllegalStateException("Stored source or baseline changed");
        }
        var pointer=recovery.findPointer(generation.documentId(),true);
        if(pointer==null || !pointer.equals(captured.documents().get(generation.documentId())))
            throw new IllegalStateException("Routing pointer changed");
        var chunks=List.copyOf(recovery.findCandidateChunks(id));
        if(chunks.isEmpty() || chunks.size()>10000 || !RecoveryCandidatePlan.manifestFor(generation.documentId(),generation.sourceHash(),productSha,
                chunks.stream().map(RagRecoverySourceValidator::unassigned).toList()).equals(generation.manifest()))throw new IllegalStateException("Candidate manifest changed");
        var replacements=new TreeMap<Long,List<LexicalChunkDocument>>();
        for(var documentId:captured.replacedDocumentIds()) {
            if(documentId==generation.documentId())continue;
            var active=captured.documents().get(documentId);
            var rows=List.copyOf(recovery.findGenerationLexicalChunks(active.activeGeneration()));
            if(rows.isEmpty() || rows.size()>10000)throw new IllegalStateException("Active lexical membership unavailable");
            replacements.put(documentId,rows);
        }
        var replacement=new ArrayList<LexicalChunkDocument>();
        for(var row:chunks)if(Set.of("PASS","REVIEW").contains(row.qualityStatus())) {
            String parent=row.parentSectionTitle()==null || row.parentSectionTitle().isEmpty()?document.title():row.parentSectionTitle();
            replacement.add(new LexicalChunkDocument(document.documentType(),row.chunkId(),row.documentId(),
                    "rag:"+row.documentId()+":"+LawHashUtils.sha256(parent),row.contentHash(),document.title(),row.parentSectionTitle(),row.chunkTitle(),row.chunkText()));
        }
        replacements.put(generation.documentId(),replacement);
        var corpus=RecoveryCorpus.overlay(captured.revision(),lexical.findActiveSearchableChunks(),replacements);
        if(sources!=null)corpus=sources.overlay(corpus,captured.sources());
        var preparation=new RecoveryPreparationReceipt(id,generation.manifest(),captured.revision(),captured.cacheIdentity(),corpus.fingerprint());
        var existing=recovery.findPreparation(id);
        if(existing!=null && !existing.equals(preparation))throw new RecoveryStaleException("Recovery preparation is stale or changed");
        return new Snapshot(generation,chunks,corpus.chunks(),corpus.fingerprint(),preparation);
    }
    private void checkedAssets(RecoveryAssets assets) {
        if(assets==null || !qdrant.ragRecoveryCollection(assets.generationId()).equals(assets.vectorCollection())
                || assets.manifest()==null || !assets.manifest().matches("[0-9a-f]{64}")
                || assets.vectorFingerprint()==null || !assets.vectorFingerprint().matches("[0-9a-f]{64}")
                || assets.lexicalFingerprint()==null || !assets.lexicalFingerprint().matches("[0-9a-f]{64}")
                || assets.chunkCount()<=0 || assets.chunkCount()>10000)throw new IllegalArgumentException("Bounded manifest-bound asset receipt required");
    }
    private record Snapshot(RecoveryGeneration generation,List<RagDocumentChunkRow> chunks,List<LexicalChunkDocument> corpus,String corpusHash,
        RecoveryPreparationReceipt preparation) { }
}
