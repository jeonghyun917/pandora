package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;

/** Pre-asset readback of a source-specific validation receipt. Does not embed, index or activate. */
@org.springframework.stereotype.Service
final class RagPdfValidatedSourceReader {
    record Verified(RecoveryPdfSource source,String preparedManifest,String evidenceHash) { }
    private final JdbcTemplate db;
    private final ObjectMapper json=new ObjectMapper();
    RagPdfValidatedSourceReader(JdbcTemplate db){this.db=Objects.requireNonNull(db);}

    Optional<Verified> find(RecoveryGeneration generation,RecoverySnapshot pointer,List<RagDocumentChunkRow> chunks) {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Reviewed source readback requires a transaction");
        Objects.requireNonNull(generation);
        var rows=db.queryForList("""
                SELECT b.draft_id,b.source_hash AS bound_source,b.review_hash,b.manifest AS bound_manifest,
                  b.prepared_manifest,b.prepared_json,b.product_sha AS bound_product,b.metadata_hash,
                  g.document_id,g.source_hash,g.product_sha,g.baseline_hash,g.manifest,g.preview_hash,g.state,g.validation_evidence,
                  q.document_id AS draft_document,q.source_hash AS draft_source,q.review_hash AS draft_review,
                  q.baseline_hash AS draft_baseline,q.product_sha AS draft_product,q.state AS draft_state,q.draft_json,q.source_bytes
                FROM rag_pdf_generation_sources b JOIN rag_recovery_generations g ON g.generation_id=b.generation_id
                LEFT JOIN rag_pdf_source_drafts q ON q.draft_id=b.draft_id
                WHERE b.generation_id=?
                """,generation.id());
        if(rows.isEmpty())return Optional.empty();
        if(rows.size()!=1 || pointer==null || pointer.documentId()!=generation.documentId()
                || !generation.baselineHash().equals(pointer.baselineHash()) || !Set.of("VALIDATED","INDEX_READY").contains(generation.state())
                || chunks==null || chunks.isEmpty() || chunks.size()>10000)
            throw new IllegalStateException("Validated PDF generation required");
        var row=rows.get(0);
        var stored=new RecoveryGeneration(generation.id(),((Number)row.get("document_id")).longValue(),(String)row.get("source_hash"),
                (String)row.get("product_sha"),(String)row.get("baseline_hash"),(String)row.get("manifest"),(String)row.get("preview_hash"),(String)row.get("state"));
        if(!generation.equals(stored) || !"STAGED".equals(row.get("draft_state"))
                || !(row.get("draft_document") instanceof Number document) || document.longValue()!=generation.documentId()
                || !generation.sourceHash().equals(row.get("bound_source")) || !generation.sourceHash().equals(row.get("draft_source"))
                || !generation.manifest().equals(row.get("bound_manifest")) || !generation.productSha().equals(row.get("bound_product"))
                || !generation.productSha().equals(row.get("draft_product")) || !generation.baselineHash().equals(row.get("draft_baseline"))
                || !Objects.equals(row.get("review_hash"),row.get("draft_review"))
                || !(row.get("source_bytes") instanceof byte[] bytes) || bytes.length<5 || bytes.length>8*1024*1024
                || !generation.sourceHash().equals(RagPdfDraftStore.hash(bytes))
                || !(row.get("draft_json") instanceof String draftJson) || draftJson.length()>4_000_000
                || !(row.get("prepared_json") instanceof String encoded) || encoded.length()>16_000_000)
            throw new IllegalStateException("Validated PDF source identity changed");
        final ReviewedPdfDraft draft;
        final RagPdfDraftPreparation.Prepared prepared;
        try {
            draft=json.readValue(draftJson,ReviewedPdfDraft.class);
            prepared=json.readValue(encoded,RagPdfDraftPreparation.Prepared.class);
        } catch(RuntimeException invalid){throw new IllegalStateException("Validated PDF receipt malformed",invalid);}
        if(draft==null || prepared==null || draft.documentId()!=generation.documentId()
                || !generation.sourceHash().equals(draft.sourceHash()) || !generation.baselineHash().equals(draft.baselineHash())
                || !generation.productSha().equals(draft.productSha()) || !draft.reviewHash().equals(row.get("review_hash"))
                || !draft.reviewHash().equals(prepared.reviewHash()) || !draft.sourceHash().equals(prepared.sourceHash())
                || !draft.baselineHash().equals(prepared.baselineHash()) || !draft.productSha().equals(prepared.productSha())
                || !Objects.equals(prepared.manifest(),row.get("prepared_manifest")))
            throw new IllegalStateException("Validated PDF review changed");
        var actual=chunks.stream().map(RagRecoverySourceValidator::unassigned).toList();
        var members=RagPdfGenerationStore.verifiedMemberIds(db,generation.id(),chunks);
        if(!actual.equals(prepared.chunks())
                || !generation.manifest().equals(RecoveryCandidatePlan.manifestFor(generation.documentId(),generation.sourceHash(),generation.productSha(),actual)))
            throw new IllegalStateException("Validated PDF membership changed");
        String draftId=(String)row.get("draft_id");
        var source=new RecoveryPdfSource(generation.id(),generation.documentId(),draftId,generation.sourceHash(),draft.reviewHash(),
                generation.manifest(),draft.title(),draft.sourceUrl());
        String evidence=RagPdfGenerationStore.validationEvidence(generation,draftId,prepared,encoded,members);
        if(!generation.previewHash().equals(RagPdfGenerationStore.previewHash(draftId,prepared,generation.manifest(),pointer))
                || !source.receiptHash(prepared.manifest(),generation.productSha()).equals(row.get("metadata_hash"))
                || !evidence.equals(row.get("validation_evidence")))throw new IllegalStateException("Validated PDF receipt changed");
        return Optional.of(new Verified(source,prepared.manifest(),LawHashUtils.sha256(evidence)));
    }
}
