package com.kaces.pandora.rag.recovery;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Reads immutable published source bindings in the same transaction as generation routing. */
@Service
public final class RagPdfSourceRoutingReader {
    private final JdbcTemplate db;
    public RagPdfSourceRoutingReader(JdbcTemplate db){this.db=Objects.requireNonNull(db);}

    Map<String,RecoveryPdfSource> capture(Map<Long,RecoverySnapshot> pointers,Set<String> baselines) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("PDF source routing requires captured transaction");
        if(pointers==null || pointers.size()>10000 || baselines==null)
            throw new IllegalArgumentException("Bounded generation routing required");
        if(pointers.isEmpty())return Map.of();
        var expected=new TreeMap<String,RecoverySnapshot>();
        for(var entry:pointers.entrySet()) {
            var pointer=entry.getValue();
            if(pointer==null || entry.getKey()!=pointer.documentId() || pointer.activeGeneration()==null
                    || !pointer.activeGeneration().matches("[0-9a-f-]{36}")
                    || expected.put(pointer.activeGeneration(),pointer)!=null)
                throw new IllegalStateException("Ambiguous captured source generation");
        }
        String marks=String.join(",",Collections.nCopies(expected.size(),"?"));
        var rows=db.queryForList("""
                SELECT g.generation_id,g.document_id,g.source_hash,g.baseline_hash,g.product_sha,g.manifest,g.state,
                  d.file_hash AS original_hash,d.document_type,
                  b.generation_id AS bound_generation,b.draft_id,b.source_hash AS bound_source,
                  b.review_hash AS bound_review,b.manifest AS bound_manifest,b.product_sha AS bound_product,
                  b.prepared_manifest,b.metadata_hash,
                  q.document_id AS draft_document,q.source_hash AS draft_source,q.review_hash,
                  q.baseline_hash AS draft_baseline,q.product_sha AS draft_product,q.state AS draft_state,
                  JSON_UNQUOTE(JSON_EXTRACT(q.draft_json,'$.title')) AS source_title,
                  JSON_UNQUOTE(JSON_EXTRACT(q.draft_json,'$.sourceUrl')) AS source_url
                FROM rag_recovery_generations g JOIN rag_documents d ON d.document_id=g.document_id AND d.use_yn='Y'
                LEFT JOIN rag_pdf_generation_sources b ON b.generation_id=g.generation_id
                LEFT JOIN rag_pdf_source_drafts q ON q.draft_id=b.draft_id
                WHERE g.generation_id IN (
                """+marks+")",expected.keySet().toArray());
        if(rows.size()!=expected.size())throw new IllegalStateException("Captured source generation unavailable");
        var result=new TreeMap<String,RecoveryPdfSource>();
        for(var row:rows) {
            String generation=(String)row.get("generation_id");
            var pointer=expected.get(generation);
            if(pointer==null || !(row.get("document_id") instanceof Number document) || document.longValue()!=pointer.documentId()
                    || !"ACTIVE".equals(row.get("state")) || !Objects.equals(pointer.sourceHash(),row.get("source_hash"))
                    || !Objects.equals(pointer.baselineHash(),row.get("baseline_hash")))
                throw new IllegalStateException("Captured source pointer mismatch");
            if(row.get("bound_generation")==null) {
                if(!Objects.equals(row.get("source_hash"),row.get("original_hash")))
                    throw new IllegalStateException("Replacement source binding missing");
                continue;
            }
            if(baselines.contains(generation) || !"official_doc".equals(row.get("document_type"))
                    || !(row.get("draft_document") instanceof Number draftDocument) || draftDocument.longValue()!=pointer.documentId()
                    || !"STAGED".equals(row.get("draft_state"))
                    || !Objects.equals(row.get("source_hash"),row.get("bound_source"))
                    || !Objects.equals(row.get("source_hash"),row.get("draft_source"))
                    || !Objects.equals(row.get("baseline_hash"),row.get("draft_baseline"))
                    || !Objects.equals(row.get("manifest"),row.get("bound_manifest"))
                    || !Objects.equals(row.get("review_hash"),row.get("bound_review"))
                    || !Objects.equals(row.get("product_sha"),row.get("bound_product"))
                    || !Objects.equals(row.get("product_sha"),row.get("draft_product")))
                throw new IllegalStateException("Published source identity mismatch");
            var source=new RecoveryPdfSource(generation,pointer.documentId(),(String)row.get("draft_id"),
                    (String)row.get("source_hash"),(String)row.get("review_hash"),(String)row.get("manifest"),
                    (String)row.get("source_title"),(String)row.get("source_url"));
            if(!source.receiptHash((String)row.get("prepared_manifest"),(String)row.get("product_sha")).equals(row.get("metadata_hash"))
                    || result.put(generation,source)!=null)
                throw new IllegalStateException("Published source metadata changed");
        }
        return Collections.unmodifiableMap(result);
    }
}
