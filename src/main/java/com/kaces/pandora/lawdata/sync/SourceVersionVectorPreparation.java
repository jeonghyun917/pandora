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
    private final String model;
    public SourceVersionVectorPreparation(JdbcTemplate db,SourceVersionPreparationService preparation,SourceVersionEmbeddingReceipts embeddings,
            LawChunkMapper chunks,OpenAiEmbeddingClient openai,QdrantClient qdrant,PlatformTransactionManager manager,String model) {
        if(model==null || model.isBlank() || model.length()>200)throw new IllegalArgumentException("Server embedding model required");
        this.db=db;this.preparation=preparation;this.embeddings=embeddings;this.chunks=chunks;this.openai=openai;this.qdrant=qdrant;this.model=model;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public VectorReceipt prepare(String id) {
        var source=preparation.snapshotForAssets(id);
        var baseline=List.copyOf(chunks.findSourceVersionBaselineChunks(id));
        if(baseline.isEmpty() || baseline.size()>10000)throw new IllegalStateException("Complete preserved baseline required");
        String preserved=qdrant.baselineLawFingerprint(baseline);
        var existing=find(id);
        if(existing!=null) {
            requireIdentity(source,existing,preserved);
            var vectors=embeddings.load(id);
            if(!existing.vectorFingerprint().equals(qdrant.verifyLawSourceVersionCollection(id,model,source.validation().manifest(),source.chunks(),ordered(source,vectors))))
                throw new IllegalStateException("Prepared source vectors changed");
            if(!preserved.equals(qdrant.baselineLawFingerprint(baseline)))throw new IllegalStateException("Preserved baseline vectors changed during readback");
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
        if(!preserved.equals(qdrant.baselineLawFingerprint(baseline)))throw new IllegalStateException("Preserved baseline vectors changed");
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
