package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Exact durable embeddings; neither storage nor successful readback grants INDEX_READY. */
public final class SourceVersionEmbeddingReceipts {
    private final JdbcTemplate db;
    private final SourceVersionPreparationService preparation;
    private final TransactionTemplate tx;
    private final String model;
    private final int dimensions;
    private final ObjectMapper json=new ObjectMapper();
    public SourceVersionEmbeddingReceipts(JdbcTemplate db,SourceVersionPreparationService preparation,
            PlatformTransactionManager manager,String model,int dimensions) {
        if(model==null || model.isBlank() || model.length()>200 || dimensions<1 || dimensions>65536)
            throw new IllegalArgumentException("Bounded server embedding configuration required");
        this.db=db;this.preparation=preparation;this.model=model;this.dimensions=dimensions;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public void save(String id,Map<Long,List<Double>> vectors) {
        if(vectors==null || vectors.isEmpty() || vectors.size()>10000)throw new IllegalArgumentException("Complete bounded vectors required");
        var copied=new TreeMap<Long,List<Double>>();
        vectors.forEach((key,value)->{if(key==null || key<=0)throw new IllegalArgumentException("Chunk identity required");copied.put(key,checked(value));});
        locked(id,true,context->{
            if(!copied.keySet().equals(context.chunks.keySet()))throw new IllegalArgumentException("Source embedding membership mismatch");
            var stored=rows(id);
            if(!stored.isEmpty()) {
                if(!read(id,context,stored).equals(copied))throw new IllegalStateException("Source embeddings are immutable");
            } else {
                for(var entry:copied.entrySet()) {
                    String vector=json.writeValueAsString(entry.getValue());
                    if(db.update("INSERT INTO law_source_version_embedding_receipts(candidate_id,chunk_id,validation_manifest,embedding_model,vector_dimensions,vector_json,receipt_hash) VALUES(?,?,?,?,?,?,?)",
                        id,entry.getKey(),context.validation.manifest(),model,dimensions,vector,hash(id,context,entry.getKey(),entry.getValue()))!=1)
                        throw new IllegalStateException("Source embedding receipt conflict");
                }
            }
            return null;
        });
    }
    public Map<Long,List<Double>> load(String id) {return locked(id,false,context->read(id,context,rows(id)));}
    private Map<Long,List<Double>> read(String id,Context context,List<Map<String,Object>> rows) {
        if(rows.size()!=context.chunks.size())throw new IllegalStateException("Source embedding receipts incomplete");
        var result=new TreeMap<Long,List<Double>>();
        for(var row:rows) {
            long chunk=((Number)row.get("chunk_id")).longValue();
            try {
                var node=json.readTree((String)row.get("vector_json"));
                if(!node.isArray())throw new IllegalStateException("Source embedding vector malformed");
                var components=new ArrayList<Double>();
                for(var component:node) {if(!component.isNumber())throw new IllegalStateException("Source embedding component malformed");components.add(component.doubleValue());}
                var vector=checked(components);
                if(!context.chunks.containsKey(chunk) || result.put(chunk,vector)!=null
                        || !context.validation.manifest().equals(row.get("validation_manifest")) || !model.equals(row.get("embedding_model"))
                        || dimensions!=((Number)row.get("vector_dimensions")).intValue()
                        || !hash(id,context,chunk,vector).equals(row.get("receipt_hash")))throw new IllegalStateException("Source embedding receipt changed");
            } catch(RuntimeException invalid) {throw new IllegalStateException("Source embedding integrity failure",invalid);}
        }
        return Collections.unmodifiableMap(result);
    }
    private <T> T locked(String id,boolean writing,Function<Context,T> action) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Candidate UUID required");
        return tx.execute(status->{
            var validation=writing?preparation.recheckForEmbeddings(id):preparation.recheckReadableContent(id);
            var chunks=new TreeMap<Long,List<String>>();
            for(var row:db.queryForList("SELECT chunk_id,content_hash,embedding_text FROM law_api_document_chunks WHERE document_id=? ORDER BY chunk_id LIMIT 10001",validation.documentId()))
                chunks.put(((Number)row.get("chunk_id")).longValue(),List.of(Objects.toString(row.get("content_hash"),""),Objects.toString(row.get("embedding_text"),"")));
            if(chunks.isEmpty() || chunks.size()>10000 || chunks.size()!=validation.chunkCount())throw new IllegalStateException("Source chunk membership changed");
            return action.apply(new Context(validation,chunks));
        });
    }
    private List<Map<String,Object>> rows(String id) {return db.queryForList("SELECT * FROM law_source_version_embedding_receipts WHERE candidate_id=? ORDER BY chunk_id LIMIT 10001",id);}
    private List<Double> checked(List<Double> vector) {
        if(vector==null || vector.size()!=dimensions || vector.stream().anyMatch(v->v==null || !Double.isFinite(v) || !Float.isFinite(v.floatValue())))
            throw new IllegalArgumentException("Embedding dimension or component mismatch");
        double norm=vector.stream().mapToDouble(v->v*v).sum();
        if(norm<=0 || !Float.isFinite((float)norm))throw new IllegalArgumentException("Finite nonzero embedding required");
        return List.copyOf(vector);
    }
    private String hash(String id,Context context,long chunk,List<Double> vector) {
        return LawHashUtils.sha256Json(List.of(id,context.validation.manifest(),context.validation.documentId(),chunk,context.chunks.get(chunk),model,dimensions,vector));
    }
    private record Context(SourceVersionPreparationService.Validation validation,Map<Long,List<String>> chunks) { }
}
