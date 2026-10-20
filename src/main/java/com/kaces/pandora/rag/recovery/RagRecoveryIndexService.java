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
    private final String model;
    private final String productSha;

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
    }

    public RecoveryAssets prepare(String generationId) {
        Snapshot initial=snapshot(generationId);
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
                throw new IllegalStateException("Recovery corpus changed during preparation");
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
        var current=snapshot(assets.generationId());
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
    private Snapshot snapshot(String id) { return transaction.execute(status->captureLocked(id)); }
    private Snapshot captureLocked(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || productSha==null || !productSha.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("Verified generation/product required");
        var initial=recovery.findGeneration(id,false);
        if(initial==null)throw new IllegalStateException("Generation unavailable");
        var document=documents.findSourceProofDocument(initial.documentId(),true);
        var generation=recovery.findGeneration(id,true);
        if(document==null || generation==null || !productSha.equals(generation.productSha())
                || !Set.of("VALIDATED","INDEX_READY").contains(generation.state()))throw new IllegalStateException("Generation not validated");
        var preview=candidates.lockedPreview(generation.documentId(),productSha);
        if(!preview.sourceHash().equals(generation.sourceHash()) || !preview.baselineHash().equals(generation.baselineHash())
                || !preview.manifest().equals(generation.manifest()))throw new IllegalStateException("Source or baseline changed");
        var pointer=recovery.findPointer(generation.documentId(),true);
        // Task4 replaces this conservative capability fence with the global routing epoch snapshot.
        if(pointer==null || pointer.revision()!=0 || recovery.changedPointerCount()!=0)throw new IllegalStateException("Routing-aware corpus snapshot required");
        var chunks=List.copyOf(recovery.findCandidateChunks(id));
        if(chunks.isEmpty() || chunks.size()>10000 || !RecoveryCandidatePlan.manifestFor(generation.documentId(),generation.sourceHash(),productSha,
                chunks.stream().map(RagRecoverySourceValidator::unassigned).toList()).equals(generation.manifest()))throw new IllegalStateException("Candidate manifest changed");
        var corpus=new ArrayList<LexicalChunkDocument>();
        for(var row:lexical.findActiveSearchableChunks())if(!(row.target().equals(document.documentType()) && row.documentId()==generation.documentId()))corpus.add(row);
        for(var row:chunks)if(Set.of("PASS","REVIEW").contains(row.qualityStatus())) {
            String parent=row.parentSectionTitle()==null || row.parentSectionTitle().isEmpty()?document.title():row.parentSectionTitle();
            corpus.add(new LexicalChunkDocument(document.documentType(),row.chunkId(),row.documentId(),
                    "rag:"+row.documentId()+":"+LawHashUtils.sha256(parent),row.contentHash(),document.title(),row.parentSectionTitle(),row.chunkTitle(),row.chunkText()));
        }
        corpus.sort(Comparator.comparing(LexicalChunkDocument::target).thenComparingLong(LexicalChunkDocument::chunkId));
        var frozen=List.copyOf(corpus);
        return new Snapshot(generation,chunks,frozen,LawHashUtils.sha256(new ObjectMapper().writeValueAsString(frozen)));
    }
    private void checkedAssets(RecoveryAssets assets) {
        if(assets==null || !qdrant.ragRecoveryCollection(assets.generationId()).equals(assets.vectorCollection())
                || assets.manifest()==null || !assets.manifest().matches("[0-9a-f]{64}")
                || assets.vectorFingerprint()==null || !assets.vectorFingerprint().matches("[0-9a-f]{64}")
                || assets.lexicalFingerprint()==null || !assets.lexicalFingerprint().matches("[0-9a-f]{64}")
                || assets.chunkCount()<=0 || assets.chunkCount()>10000)throw new IllegalArgumentException("Bounded manifest-bound asset receipt required");
    }
    private record Snapshot(RecoveryGeneration generation,List<RagDocumentChunkRow> chunks,List<LexicalChunkDocument> corpus,String corpusHash) { }
}
