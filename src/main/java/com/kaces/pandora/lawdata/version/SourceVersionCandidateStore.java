package com.kaces.pandora.lawdata.version;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.lawdata.sync.SearchDocument;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Stages immutable payloads outside the searchable document tables. Never changes CURRENT. */
@Repository
public class SourceVersionCandidateStore {
    public record StoredCandidate(String id,String groupKey,String inputHash,String state) { }
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json=new ObjectMapper();
    public SourceVersionCandidateStore(JdbcTemplate jdbc,PlatformTransactionManager manager) {
        this.jdbc=jdbc;transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        transaction.setTimeout(30);
    }
    public StoredCandidate stage(long baselineId,long expectedRevision,SearchDocument official,String rawDetail,String productSha) {
        if(official==null || rawDetail==null || rawDetail.isBlank() || rawDetail.length()>4_000_000
                || official.externalId()==null || official.externalId().length()>100)
            throw new IllegalArgumentException("Bounded official payload required");
        json.readTree(rawDetail); // Reject malformed JSON before claiming a group.
        return transaction.execute(status->{
            jdbc.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
            var rows=jdbc.queryForList("SELECT * FROM law_api_documents WHERE document_id=? FOR UPDATE",baselineId);
            if(rows.size()!=1)throw new IllegalStateException("Baseline unavailable");
            var row=rows.get(0);String target=(String)row.get("target"),key=(String)row.get("canonical_key");
            if(!"Y".equals(row.get("use_yn")) || !"CURRENT".equals(row.get("effective_status"))
                    || !LawVersionUtils.isVersionedTarget(target) || key==null
                    || !key.equals(LawVersionUtils.canonicalKey(target,(String)row.get("title"))))
                throw new IllegalStateException("Unambiguous current baseline required");
            var current=jdbc.queryForList("SELECT d.document_id FROM law_api_documents d WHERE d.target=? AND (d.canonical_key=? OR "+SourceVersionSchemaMaintenance.canonicalSql("d")+"=?) AND d.use_yn='Y' AND (d.effective_status IN ('CURRENT','UNKNOWN') OR d.effective_status IS NULL) FOR UPDATE",target,key,key);
            if(current.size()!=1 || ((Number)current.get(0).get("document_id")).longValue()!=baselineId)
                throw new IllegalStateException("Ambiguous current source group");
            String groupKey=LawHashUtils.sha256(target+"\n"+key),snapshot=snapshotHash(baselineId);
            var groups=jdbc.queryForList("SELECT * FROM law_source_version_groups WHERE group_key=? FOR UPDATE",groupKey);
            if(groups.isEmpty()) {
                if(expectedRevision!=0)throw new IllegalStateException("Stale baseline revision");
                jdbc.update("INSERT INTO law_source_version_groups(group_key,target,canonical_key,active_document_id,baseline_document_id,revision,baseline_hash) VALUES(?,?,?,?,?,0,?)",groupKey,target,key,baselineId,baselineId,snapshot);
                registerPreserved(groupKey,baselineId,null);
            } else {
                var group=groups.get(0);
                if(!target.equals(group.get("target")) || !key.equals(group.get("canonical_key"))
                        || ((Number)group.get("active_document_id")).longValue()!=baselineId
                        || ((Number)group.get("revision")).longValue()!=expectedRevision
                        || !snapshot.equals(group.get("baseline_hash")))throw new IllegalStateException("Stale baseline identity");
            }
            var baseline=new SourceVersionPolicy.Baseline(baselineId,expectedRevision,target,key,(String)row.get("external_id"),(String)row.get("effective_date"),snapshot);
            var source=new SourceVersionPolicy.Source(official.target(),official.title(),official.externalId(),official.effectiveDate(),LawHashUtils.sha256(rawDetail));
            var candidate=SourceVersionPolicy.stage(baseline,source,productSha,LocalDate.now(ZoneId.of("Asia/Seoul")));
            String inputHash=LawHashUtils.sha256Json(List.of(candidate.inputHash(),official));
            var existing=jdbc.queryForList("SELECT candidate_id,group_key,input_hash,state,raw_hash FROM law_source_version_candidates WHERE group_key=? AND external_id=? LIMIT 257",groupKey,source.externalId());
            if(existing.size()>256)throw new IllegalStateException("Source candidate bound exceeded");
            for(var saved:existing) {
                if(!source.rawHash().equals(saved.get("raw_hash")))throw new IllegalStateException("Official source identity conflict");
                if(inputHash.equals(saved.get("input_hash")))return stored(saved);
            }
            String id=UUID.randomUUID().toString();
            jdbc.update("INSERT INTO law_source_version_candidates(candidate_id,group_key,input_hash,baseline_document_id,baseline_revision,baseline_hash,external_id,effective_date,raw_hash,product_sha,state,metadata_json,raw_detail_json) VALUES(?,?,?,?,?,?,?,?,?,?,'STAGED',?,?)",
                id,groupKey,inputHash,baselineId,expectedRevision,snapshot,source.externalId(),source.effectiveDate(),source.rawHash(),productSha,json.writeValueAsString(official),rawDetail);
            return new StoredCandidate(id,groupKey,inputHash,"STAGED");
        });
    }
    /** Called under the source epoch/document locks; all persisted baseline assets contribute. */
    public String snapshotHash(long documentId) {
        return snapshotHash(documentId,false);
    }
    private String snapshotHash(long documentId,boolean preservedContent) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Locked source transaction required");
        var tables=new LinkedHashMap<String,List<Map<String,String>>>();
        tables.put("document",snapshot("SELECT * FROM law_api_documents WHERE document_id=?",documentId));
        if(preservedContent)for(var row:tables.get("document")) {
            // These three fields are controlled by the atomic visibility transition, not source content.
            row.remove("use_yn");row.remove("effective_status");row.remove("updated_at");
        }
        tables.put("details",snapshot("SELECT * FROM law_api_document_details WHERE document_id=? ORDER BY detail_id LIMIT 10001",documentId));
        tables.put("chunks",snapshot("SELECT * FROM law_api_document_chunks WHERE document_id=? ORDER BY chunk_id LIMIT 10001",documentId));
        tables.put("versions",snapshot("SELECT * FROM law_api_document_chunk_versions WHERE document_id=? ORDER BY chunk_version LIMIT 10001",documentId));
        tables.put("assets",snapshot("SELECT * FROM law_api_assets WHERE document_id=? ORDER BY asset_id LIMIT 10001",documentId));
        tables.put("embeddings",snapshot("SELECT e.* FROM law_api_chunk_embeddings e JOIN law_api_document_chunks c ON c.chunk_id=e.chunk_id WHERE c.document_id=? ORDER BY e.chunk_id,e.embedding_model,e.vector_store LIMIT 10001",documentId));
        String serialized=json.writeValueAsString(tables);
        if(serialized.length()>64_000_000)throw new IllegalStateException("Baseline snapshot bound exceeded");
        return LawHashUtils.sha256(serialized);
    }
    public void registerPreserved(String groupKey,long documentId,String candidateId) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Locked source transaction required");
        var group=jdbc.queryForMap("SELECT * FROM law_source_version_groups WHERE group_key=? FOR UPDATE",groupKey);
        var document=jdbc.queryForMap("SELECT * FROM law_api_documents WHERE document_id=? FOR UPDATE",documentId);
        if(!Objects.equals(group.get("target"),document.get("target")) || !Objects.equals(group.get("canonical_key"),document.get("canonical_key")))
            throw new IllegalStateException("Preserved source group mismatch");
        if(candidateId==null) {
            if(((Number)group.get("baseline_document_id")).longValue()!=documentId || ((Number)group.get("active_document_id")).longValue()!=documentId
                    || ((Number)group.get("revision")).longValue()!=0 || !"Y".equals(document.get("use_yn")) || !"CURRENT".equals(document.get("effective_status")))
                throw new IllegalStateException("Initial preserved baseline required");
        } else {
            var candidate=jdbc.queryForMap("SELECT * FROM law_source_version_candidates WHERE candidate_id=? FOR UPDATE",candidateId);
            if(!groupKey.equals(candidate.get("group_key")) || !(candidate.get("candidate_document_id") instanceof Number owned) || owned.longValue()!=documentId
                    || !"INDEX_READY".equals(candidate.get("state")) || !"N".equals(document.get("use_yn")) || !"PAST".equals(document.get("effective_status")))
                throw new IllegalStateException("Only fully prepared inactive sources can be preserved");
        }
        String hash=snapshotHash(documentId,true);
        var existing=jdbc.queryForList("SELECT * FROM law_source_version_history WHERE group_key=? AND document_id=? FOR UPDATE",groupKey,documentId);
        if(existing.isEmpty())jdbc.update("INSERT INTO law_source_version_history(group_key,document_id,source_candidate_id,content_snapshot_hash) VALUES(?,?,?,?)",groupKey,documentId,candidateId,hash);
        else if(existing.size()!=1 || !Objects.equals(existing.get(0).get("source_candidate_id"),candidateId) || !hash.equals(existing.get(0).get("content_snapshot_hash")))
            throw new IllegalStateException("Preserved source receipt conflict");
    }
    public void recheckPreserved(String groupKey) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Locked source transaction required");
        var rows=jdbc.queryForList("SELECT * FROM law_source_version_history WHERE group_key=? ORDER BY document_id LIMIT 10001 FOR UPDATE",groupKey);
        if(rows.isEmpty() || rows.size()>10000)throw new IllegalStateException("Preserved source membership unavailable");
        for(var row:rows)if(!snapshotHash(((Number)row.get("document_id")).longValue(),true).equals(row.get("content_snapshot_hash")))
            throw new IllegalStateException("Preserved source content changed");
    }
    private List<Map<String,String>> snapshot(String sql,long id) {
        var rows=jdbc.queryForList(sql,id);if(rows.size()>10000)throw new IllegalStateException("Baseline membership bound exceeded");
        return rows.stream().map(row->{var values=new TreeMap<String,String>();row.forEach((k,v)->values.put(k,v==null?null:v instanceof byte[] bytes?Base64.getEncoder().encodeToString(bytes):v.toString()));return (Map<String,String>)values;}).toList();
    }
    private StoredCandidate stored(Map<String,Object> row) {return new StoredCandidate((String)row.get("candidate_id"),(String)row.get("group_key"),(String)row.get("input_hash"),(String)row.get("state"));}
}
