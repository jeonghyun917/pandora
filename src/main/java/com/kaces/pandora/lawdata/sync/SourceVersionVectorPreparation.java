package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Isolated vector preparation only. Lexical/source-reference/CAS gates still own INDEX_READY. */
public final class SourceVersionVectorPreparation {
    public record VectorReceipt(String candidateId,String manifest,String vectorCollection,String vectorFingerprint,
            String baselineFingerprint,String model,int chunkCount) { }
    private final JdbcTemplate db;
    private final SourceVersionPreparationService preparation;
    private final SourceVersionEmbeddingReceipts embeddings;
    private final LawChunkMapper chunks;
    private final OpenAiEmbeddingClient openai;
    private final QdrantClient qdrant;
    private final TransactionTemplate tx;
    private final String model,collection;
    public SourceVersionVectorPreparation(JdbcTemplate db,SourceVersionPreparationService preparation,SourceVersionEmbeddingReceipts embeddings,
            LawChunkMapper chunks,OpenAiEmbeddingClient openai,QdrantClient qdrant,PlatformTransactionManager manager,String model,String collection) {
        if(model==null || model.isBlank() || model.length()>200)throw new IllegalArgumentException("Server embedding model required");
        if(collection==null || collection.isBlank() || collection.length()>100)throw new IllegalArgumentException("Server baseline collection required");
        this.db=db;this.preparation=preparation;this.embeddings=embeddings;this.chunks=chunks;this.openai=openai;this.qdrant=qdrant;this.model=model;this.collection=collection;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public VectorReceipt prepare(String id) {
        var source=preparation.snapshotForAssets(id);
        var ownership=db.queryForMap("SELECT group_key,baseline_document_id FROM law_source_version_candidates WHERE candidate_id=?",id);
        String group=(String)ownership.get("group_key");long baseline=((Number)ownership.get("baseline_document_id")).longValue();
        String preserved=verifyPreserved(group,baseline);
        var existing=find(id);
        if(existing!=null) {
            requireIdentity(source,existing,preserved);
            var vectors=embeddings.load(id);
            if(!existing.vectorFingerprint().equals(qdrant.verifyLawSourceVersionCollection(id,model,source.validation().manifest(),source.chunks(),ordered(source,vectors))))
                throw new IllegalStateException("Prepared source vectors changed");
            if(!preserved.equals(verifyPreserved(group,baseline)))throw new IllegalStateException("Preserved baseline vectors changed during readback");
            return existing;
        }
        if(db.queryForObject("SELECT COUNT(*) FROM law_source_version_embedding_receipts WHERE candidate_id=?",Integer.class,id)==0) {
            var vectors=new TreeMap<Long,List<Double>>();
            for(int start=0;start<source.chunks().size();start+=128) {
                var batch=source.chunks().subList(start,Math.min(start+128,source.chunks().size()));
                var result=openai.embed(batch.stream().map(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::embeddingInput).toList());
                if(result==null || result.size()!=batch.size())throw new IllegalStateException("Source embeddings incomplete");
                for(int n=0;n<batch.size();n++)vectors.put(batch.get(n).chunkId(),result.get(n));
            }
            embeddings.save(id,vectors);
        }
        var vectors=embeddings.load(id);
        for(int start=0;start<source.chunks().size();start+=128) {
            var batch=source.chunks().subList(start,Math.min(start+128,source.chunks().size()));
            var inspected=qdrant.inspectLawSourceVersionPoints(id,model,source.validation().manifest(),batch,batch.stream().map(row->vectors.get(row.chunkId())).toList());
            var requested=batch.stream().map(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::chunkId).collect(java.util.stream.Collectors.toSet());
            if(inspected==null || !requested.containsAll(inspected.missingChunkIds()))throw new IllegalStateException("Unexpected source vector membership");
            var missing=batch.stream().filter(row->inspected.missingChunkIds().contains(row.chunkId())).toList();
            if(!missing.isEmpty())qdrant.upsertLawSourceVersion(id,model,source.validation().manifest(),missing,missing.stream().map(row->vectors.get(row.chunkId())).toList());
        }
        String fingerprint=qdrant.verifyLawSourceVersionCollection(id,model,source.validation().manifest(),source.chunks(),ordered(source,vectors));
        if(!preserved.equals(verifyPreserved(group,baseline)))throw new IllegalStateException("Preserved baseline vectors changed");
        var receipt=new VectorReceipt(id,source.validation().manifest(),qdrant.lawSourceVersionCollection(id),fingerprint,preserved,model,source.chunks().size());
        requireIdentity(source,receipt,preserved);
        return tx.execute(status->{
            if(!source.validation().equals(preparation.recheckForEmbeddings(id)))throw new IllegalStateException("Source changed during vector preparation");
            var saved=find(id);
            if(saved!=null) {if(!saved.equals(receipt))throw new IllegalStateException("Source vector receipt conflict");return saved;}
            if(db.update("INSERT INTO law_source_version_vector_receipts(candidate_id,validation_manifest,vector_collection,vector_fingerprint,baseline_vector_fingerprint,embedding_model,chunk_count) VALUES(?,?,?,?,?,?,?)",
                    id,receipt.manifest(),receipt.vectorCollection(),receipt.vectorFingerprint(),receipt.baselineFingerprint(),model,receipt.chunkCount())!=1)
                throw new IllegalStateException("Source vector receipt conflict");
            return receipt;
        });
    }
    /** Read-only external verification after INDEX_READY; never re-embeds or grants readiness. */
    public VectorReceipt verifyPrepared(String id) {
        var receipt=verifyTarget(id);
        var ownership=db.queryForMap("SELECT group_key,baseline_document_id FROM law_source_version_candidates WHERE candidate_id=?",id);
        if(!receipt.baselineFingerprint().equals(verifyPreserved((String)ownership.get("group_key"),((Number)ownership.get("baseline_document_id")).longValue())))
            throw new IllegalStateException("Preserved baseline vectors changed");
        return receipt;
    }
    public String verifyPreserved(String group,long document) {
        if(group==null || !group.matches("[0-9a-f]{64}") || document<=0)throw new IllegalArgumentException("Registered source identity required");
        var before=tx.execute(status->preservedSnapshot(group,document));
        if(before.candidate()!=null)return verifyTarget(before.candidate()).vectorFingerprint();
        String fingerprint=qdrant.baselineLawFingerprint(before.chunks());
        if(!before.equals(tx.execute(status->preservedSnapshot(group,document))))throw new IllegalStateException("Preserved baseline embedding link changed");
        return fingerprint;
    }
    private PreservedSnapshot preservedSnapshot(String group,long document) {
        db.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
        var owned=db.queryForList("SELECT source_candidate_id FROM law_source_version_history WHERE group_key=? AND document_id=?",group,document);
        if(owned.size()!=1)throw new IllegalStateException("Preserved source vector ownership missing");
        String candidate=(String)owned.get(0).get("source_candidate_id");
        if(candidate!=null)return new PreservedSnapshot(candidate,List.of());
        var baseline=List.copyOf(chunks.findPreservedSourceVersionChunks(group,document));
        if(baseline.isEmpty() || baseline.size()>10000)throw new IllegalStateException("Complete preserved baseline required");
        var links=db.queryForList("SELECT e.* FROM law_api_chunk_embeddings e JOIN law_api_document_chunks c ON c.chunk_id=e.chunk_id WHERE c.document_id=?",document);
        for(var row:baseline) {
            long matches=links.stream().filter(e->e.get("chunk_id") instanceof Number id && id.longValue()==row.chunkId()
                && model.equals(e.get("embedding_model")) && collection.equals(e.get("vector_store")) && "INDEXED".equals(e.get("status"))
                && row.contentHash()!=null && row.contentHash().equals(e.get("content_hash"))
                && String.valueOf(row.chunkId()).equals(String.valueOf(e.get("vector_point_id")))).count();
            if(matches!=1)throw new IllegalStateException("Preserved baseline embedding link unavailable");
        }
        return new PreservedSnapshot(null,baseline);
    }
    private record PreservedSnapshot(String candidate,List<com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow> chunks) { }
    private VectorReceipt verifyTarget(String id) {
        var receipt=find(id);if(receipt==null)throw new IllegalStateException("Prepared source vector receipt missing");
        String state=db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,id);
        var source="VALIDATED".equals(state)?preparation.snapshotForAssets(id):preparation.snapshotForRegisteredAssets(id);
        requireIdentity(source,receipt,receipt.baselineFingerprint());
        if(!receipt.vectorFingerprint().equals(qdrant.verifyLawSourceVersionCollection(id,model,source.validation().manifest(),source.chunks(),ordered(source,embeddings.load(id)))))
            throw new IllegalStateException("Prepared source vectors changed");
        if(!qdrant.findExistingLawPointIds(source.chunks().stream().map(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::chunkId).toList()).isEmpty())
            throw new IllegalStateException("Isolated source points leaked into the shared law collection");
        return receipt;
    }
    private List<List<Double>> ordered(SourceVersionPreparationService.AssetSnapshot source,Map<Long,List<Double>> vectors) {
        if(!vectors.keySet().equals(source.chunks().stream().map(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::chunkId).collect(java.util.stream.Collectors.toSet())))
            throw new IllegalStateException("Source vector membership changed");
        return source.chunks().stream().map(row->vectors.get(row.chunkId())).toList();
    }
    private VectorReceipt find(String id) {
        var rows=db.queryForList("SELECT * FROM law_source_version_vector_receipts WHERE candidate_id=?",id);
        if(rows.isEmpty())return null;
        if(rows.size()!=1)throw new IllegalStateException("Source vector receipt ambiguous");
        var row=rows.get(0);
        return new VectorReceipt((String)row.get("candidate_id"),(String)row.get("validation_manifest"),(String)row.get("vector_collection"),
            (String)row.get("vector_fingerprint"),(String)row.get("baseline_vector_fingerprint"),(String)row.get("embedding_model"),((Number)row.get("chunk_count")).intValue());
    }
    private void requireIdentity(SourceVersionPreparationService.AssetSnapshot source,VectorReceipt receipt,String preserved) {
        if(!source.candidateId().equals(receipt.candidateId()) || !source.validation().manifest().equals(receipt.manifest())
                || !qdrant.lawSourceVersionCollection(source.candidateId()).equals(receipt.vectorCollection())
                || !model.equals(receipt.model()) || receipt.chunkCount()!=source.chunks().size()
                || receipt.vectorFingerprint()==null || !receipt.vectorFingerprint().matches("[0-9a-f]{64}")
                || preserved==null || !preserved.matches("[0-9a-f]{64}") || !preserved.equals(receipt.baselineFingerprint()))
            throw new IllegalStateException("Source vector receipt identity mismatch");
    }
}
