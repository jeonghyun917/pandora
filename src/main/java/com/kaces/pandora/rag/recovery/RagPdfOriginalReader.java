package com.kaces.pandora.rag.recovery;

import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

/** Reads the retained bytes for a server-captured source, including an in-flight retired generation. */
@Service
public final class RagPdfOriginalReader {
    public record Original(RecoveryPdfSource source,byte[] bytes) {
        public Original {Objects.requireNonNull(source);bytes=bytes.clone();}
        @Override public byte[] bytes(){return bytes.clone();}
    }
    private final JdbcTemplate db;
    private final ObjectMapper json=new ObjectMapper();
    public RagPdfOriginalReader(JdbcTemplate db){this.db=Objects.requireNonNull(db);}

    public boolean isPreservedOriginal(long documentId,String generationId,String sourceHash) {
        if(documentId<=0 || generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || sourceHash==null || !sourceHash.matches("[0-9a-f]{64}"))return false;
        Integer count=db.queryForObject("""
                SELECT COUNT(*) FROM rag_recovery_baselines b
                JOIN rag_recovery_generations baseline ON baseline.generation_id=b.generation_id AND baseline.document_id=b.document_id
                JOIN rag_recovery_generations g ON g.document_id=b.document_id
                  AND g.source_hash=baseline.source_hash AND g.baseline_hash=baseline.baseline_hash
                JOIN rag_documents d ON d.document_id=b.document_id AND d.file_hash=g.source_hash AND d.use_yn='Y'
                LEFT JOIN rag_pdf_generation_sources replacement ON replacement.generation_id=g.generation_id
                WHERE b.document_id=? AND g.generation_id=? AND g.source_hash=? AND g.state IN ('ACTIVE','RETIRED')
                  AND baseline.state IN ('ACTIVE','RETIRED') AND replacement.generation_id IS NULL
                """,Integer.class,documentId,generationId,sourceHash);
        return count!=null && count==1;
    }

    public byte[] read(RecoveryPdfSource source) {
        Objects.requireNonNull(source);
        var original=open(source.documentId(),source.generationId());
        if(!source.equals(original.source()))throw new IllegalStateException("Captured source original identity changed");
        return original.bytes();
    }

    public Original open(long documentId,String generationId) {
        if(documentId<=0 || generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Exact published source identity required");
        // One statement captures the binding, source and generation together; never re-resolve the active pointer.
        var rows=db.queryForList("""
                SELECT q.draft_json,q.source_bytes,g.product_sha,g.source_hash,g.baseline_hash,g.manifest,
                  b.draft_id,b.review_hash,b.prepared_manifest,b.metadata_hash
                FROM rag_recovery_generations g
                JOIN rag_documents d ON d.document_id=g.document_id AND d.use_yn='Y' AND d.document_type='official_doc'
                JOIN rag_pdf_generation_sources b ON b.generation_id=g.generation_id
                  AND b.source_hash=g.source_hash AND b.manifest=g.manifest AND b.product_sha=g.product_sha
                JOIN rag_pdf_source_drafts q ON q.draft_id=b.draft_id AND q.document_id=g.document_id
                  AND q.source_hash=g.source_hash AND q.baseline_hash=g.baseline_hash
                  AND q.product_sha=g.product_sha AND q.review_hash=b.review_hash AND q.state='STAGED'
                WHERE g.generation_id=? AND g.document_id=? AND g.state IN ('ACTIVE','RETIRED')
                """,generationId,documentId);
        if(rows.size()!=1)throw new IllegalStateException("Captured source original unavailable");
        var row=rows.get(0);
        if(!(row.get("draft_json") instanceof String encoded) || encoded.length()>4_000_000
                || !(row.get("source_bytes") instanceof byte[] bytes) || bytes.length<5 || bytes.length>8*1024*1024)
            throw new IllegalStateException("Captured source original bounds rejected");
        final ReviewedPdfDraft draft;
        try {draft=json.readValue(encoded,ReviewedPdfDraft.class);}
        catch(RuntimeException invalid){throw new IllegalStateException("Captured source review changed",invalid);}
        if(draft==null || draft.documentId()!=documentId || !draft.sourceHash().equals(row.get("source_hash"))
                || !draft.baselineHash().equals(row.get("baseline_hash")) || !draft.reviewHash().equals(row.get("review_hash"))
                || !draft.productSha().equals(row.get("product_sha")))
            throw new IllegalStateException("Captured source original identity changed");
        var source=new RecoveryPdfSource(generationId,documentId,(String)row.get("draft_id"),draft.sourceHash(),
                draft.reviewHash(),(String)row.get("manifest"),draft.title(),draft.sourceUrl());
        if(!source.receiptHash((String)row.get("prepared_manifest"),draft.productSha()).equals(row.get("metadata_hash")))
            throw new IllegalStateException("Captured source metadata receipt changed");
        try {
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(source.sourceHash()))
                throw new IllegalStateException("Captured source original bytes changed");
        } catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
        return new Original(source,bytes);
    }
}
