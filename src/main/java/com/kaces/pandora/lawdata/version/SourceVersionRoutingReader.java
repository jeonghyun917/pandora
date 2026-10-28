package com.kaces.pandora.lawdata.version;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** One DB snapshot of activated source pointers; no inactive candidate enters serving identity. */
@Service
public class SourceVersionRoutingReader {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    public SourceVersionRoutingReader(JdbcTemplate db,PlatformTransactionManager manager) {
        this.db=db;tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);tx.setReadOnly(true);
    }
    public SourceVersionRoutingSnapshot capture() {return tx.execute(status->captureInTransaction());}
    public SourceVersionRoutingSnapshot captureInTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Source routing transaction required");
        long revision=db.queryForObject("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1",Long.class);
        var groups=db.queryForList("SELECT g.*,d.use_yn AS active_use,d.effective_status AS active_status,d.target AS active_target,d.canonical_key AS active_key FROM law_source_version_groups g JOIN law_api_documents d ON d.document_id=g.active_document_id WHERE g.revision>0 ORDER BY g.group_key LIMIT 65");
        if(groups.size()>64)throw new IllegalStateException("Source routing fan-out bound exceeded");
        var routes=new TreeMap<String,SourceVersionRoutingSnapshot.Route>();
        for(var group:groups) {
            String key=(String)group.get("group_key"),target=(String)group.get("target"),canonical=(String)group.get("canonical_key");
            long active=number(group,"active_document_id"),baseline=number(group,"baseline_document_id");
            if(!"Y".equals(group.get("active_use")) || !"CURRENT".equals(group.get("active_status"))
                    || !target.equals(group.get("active_target")) || !canonical.equals(group.get("active_key")))throw new IllegalStateException("Source pointer visibility mismatch");
            var history=db.queryForList("SELECT h.* FROM law_source_version_history h LEFT JOIN law_source_version_candidates v ON v.candidate_id=h.source_candidate_id WHERE h.group_key=? AND (h.source_candidate_id IS NULL OR v.state IN ('ACTIVE','RETIRED')) ORDER BY h.document_id LIMIT 10001",key);
            if(history.size()>10000)throw new IllegalStateException("Source history bound exceeded");
            var ids=new TreeSet<Long>();Map<String,Object> owned=null;
            for(var row:history) {long document=number(row,"document_id");if(!ids.add(document))throw new IllegalStateException("Duplicate source history");if(document==active)owned=row;}
            if(owned==null)throw new IllegalStateException("Active source history receipt missing");
            String candidate=(String)owned.get("source_candidate_id"),manifest=null,collection=null,vector,model;int count,chunkVersion=0;
            if(candidate==null) {
                if(active!=baseline)throw new IllegalStateException("Unregistered baseline restoration");
                var receipts=db.queryForList("SELECT DISTINCT r.baseline_vector_fingerprint,r.embedding_model FROM law_source_version_vector_receipts r JOIN law_source_version_candidates v ON v.candidate_id=r.candidate_id JOIN law_source_version_transitions t ON t.source_candidate_id=v.candidate_id AND t.group_key=v.group_key WHERE v.group_key=? AND v.baseline_document_id=? AND t.expected_active_document_id=? AND t.kind='PROMOTE' AND t.state='APPLIED' AND t.manifest=r.validation_manifest AND t.baseline_vector_fingerprint=r.baseline_vector_fingerprint",key,baseline,baseline);
                if(receipts.size()!=1)throw new IllegalStateException("Preserved baseline vector receipt unavailable or conflicting");
                vector=(String)receipts.get(0).get("baseline_vector_fingerprint");model=(String)receipts.get(0).get("embedding_model");
                count=db.queryForObject("SELECT COUNT(*) FROM law_api_document_chunks WHERE document_id=? AND use_yn='Y' AND activation_status='ACTIVE' AND quality_status IN ('PASS','CONTEXT_ONLY')",Integer.class,active);
            } else {
                var receipts=db.queryForList("SELECT r.*,v.validated_chunk_version FROM law_source_version_candidates v JOIN law_source_version_vector_receipts r ON r.candidate_id=v.candidate_id JOIN law_source_version_transitions t ON t.transition_id=v.prepared_id WHERE v.candidate_id=? AND v.candidate_document_id=? AND v.group_key=? AND v.state='ACTIVE' AND t.source_candidate_id=v.candidate_id AND t.group_key=v.group_key AND t.target_document_id=v.candidate_document_id AND t.kind='PROMOTE' AND t.state='APPLIED' AND t.manifest=v.validation_manifest AND r.validation_manifest=v.validation_manifest AND t.vector_fingerprint=r.vector_fingerprint AND r.chunk_count=v.validated_chunk_count",candidate,active,key);
                if(receipts.size()!=1)throw new IllegalStateException("Active source ready receipt missing");
                var receipt=receipts.get(0);manifest=(String)receipt.get("validation_manifest");collection=(String)receipt.get("vector_collection");
                vector=(String)receipt.get("vector_fingerprint");model=(String)receipt.get("embedding_model");count=((Number)receipt.get("chunk_count")).intValue();
                chunkVersion=((Number)receipt.get("validated_chunk_version")).intValue();
            }
            routes.put(key,new SourceVersionRoutingSnapshot.Route(key,number(group,"revision"),target,canonical,baseline,active,ids,
                candidate,manifest,collection,vector,(String)owned.get("content_snapshot_hash"),model,count,chunkVersion));
        }
        return new SourceVersionRoutingSnapshot(revision,routes);
    }
    private long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
}
