package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.semantic.integrity.LawIndexIntegrityRow;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Source rows stay in the audit. A complete isolated readback replaces the shared-collection assumption. */
@Service
public class SourceVersionIntegrityVerifier {
    private final JdbcTemplate db;
    private final ObjectProvider<SourceVersionPreparationService> preparation;
    private final ObjectProvider<SourceVersionVectorPreparation> vectors;
    public SourceVersionIntegrityVerifier(JdbcTemplate db,ObjectProvider<SourceVersionPreparationService> preparation,ObjectProvider<SourceVersionVectorPreparation> vectors) {
        this.db=db;this.preparation=preparation;this.vectors=vectors;
    }
    public Map<Long,String> verify(List<LawIndexIntegrityRow> rows,String model) {
        if(rows==null || rows.size()>10000 || model==null || model.isBlank())throw new IllegalArgumentException("Bounded source audit required");
        var documents=rows.stream().map(LawIndexIntegrityRow::documentId).filter(id->id>0).distinct().sorted().toList();
        if(documents.isEmpty())return Map.of();
        var owned=db.queryForList("SELECT h.document_id,h.group_key,v.candidate_id,v.state,v.validation_manifest,g.active_document_id,d.use_yn,d.effective_status,t.state AS proof_state,t.kind AS proof_kind,t.source_candidate_id AS proof_candidate,t.target_document_id AS proof_document,t.group_key AS proof_group,t.manifest AS proof_manifest,t.vector_fingerprint AS proof_vector FROM law_source_version_history h JOIN law_source_version_candidates v ON v.candidate_id=h.source_candidate_id AND v.candidate_document_id=h.document_id AND v.group_key=h.group_key JOIN law_source_version_groups g ON g.group_key=h.group_key JOIN law_api_documents d ON d.document_id=h.document_id LEFT JOIN law_source_version_transitions t ON t.transition_id=v.prepared_id WHERE h.document_id IN ("+String.join(",",Collections.nCopies(documents.size(),"?"))+") ORDER BY h.document_id LIMIT 65",documents.toArray());
        if(owned.isEmpty())return Map.of();
        if(owned.size()>64 || preparation.getIfAvailable()==null || vectors.getIfAvailable()==null)throw new IllegalStateException("Complete source audit verifier unavailable");
        var result=new HashMap<Long,String>();var verifiedDocuments=new HashMap<Long,Boolean>();
        for(var source:owned) {
            long document=number(source,"document_id"),active=number(source,"active_document_id");String candidate=(String)source.get("candidate_id");
            boolean inactive="INDEX_READY".equals(source.get("state")) && "N".equals(source.get("use_yn")) && document!=active && "PAST".equals(source.get("effective_status"));
            boolean visible="Y".equals(source.get("use_yn")) && (("ACTIVE".equals(source.get("state")) && document==active && "CURRENT".equals(source.get("effective_status")))
                || ("RETIRED".equals(source.get("state")) && document!=active && "PAST".equals(source.get("effective_status"))));
            if((!inactive && !visible) || !(inactive?"READY":"APPLIED").equals(source.get("proof_state")) || !"PROMOTE".equals(source.get("proof_kind"))
                    || !candidate.equals(source.get("proof_candidate")) || number(source,"proof_document")!=document
                    || !Objects.equals(source.get("group_key"),source.get("proof_group")) || !Objects.equals(source.get("validation_manifest"),source.get("proof_manifest")))
                throw new IllegalStateException("Registered source audit provenance missing");
            var receipt=vectors.getIfAvailable().verifyPrepared(candidate);
            var content=preparation.getIfAvailable().snapshotForRegisteredAssets(candidate);
            if(!model.equals(receipt.model()) || !receipt.vectorFingerprint().equals(source.get("proof_vector")) || !receipt.manifest().equals(source.get("validation_manifest"))
                    || content.validation().documentId()!=document || verifiedDocuments.put(document,!inactive)!=null)throw new IllegalStateException("Source audit receipt changed");
            for(var row:content.chunks())if(result.put(row.chunkId(),row.contentHash())!=null)throw new IllegalStateException("Duplicate source audit membership");
        }
        var selected=new HashMap<Long,String>();
        for(var row:rows)if(verifiedDocuments.containsKey(row.documentId())) {
            if(row.active()!=verifiedDocuments.get(row.documentId()) || !Objects.equals(row.chunkContentHash(),result.get(row.chunkId())))throw new IllegalStateException("Source audit row/body mismatch");
            selected.put(row.chunkId(),row.chunkContentHash());
        }
        return Map.copyOf(selected);
    }
    private long number(Map<String,Object> row,String key) {return row.get(key) instanceof Number value?value.longValue():0;}
}
