package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Persists and validates inactive reviewed-source generations. No endpoint, indexing or activation authority. */
final class RagPdfGenerationStore {
    private record PreparedInput(RagPdfDraftStore.StoredInput stored,RagPdfDraftPreparation.Prepared prepared,
            String preparedJson,String manifest) {
        ReviewedPdfDraft draft(){return stored.draft();}
    }
    private final JdbcTemplate db;
    private final RagRecoveryCandidateService candidates;
    private final RagRecoveryMapper recovery;
    private final RagPdfDraftStore drafts;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();

    RagPdfGenerationStore(JdbcTemplate db,PlatformTransactionManager manager,RagRecoveryCandidateService candidates,
            RagRecoveryMapper recovery,RagPdfDraftStore drafts) {
        this.db=Objects.requireNonNull(db);this.candidates=Objects.requireNonNull(candidates);
        this.recovery=Objects.requireNonNull(recovery);this.drafts=Objects.requireNonNull(drafts);
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }

    RecoveryGeneration create(String draftId,String expectedPreparedManifest) {
        var input=prepare(draftId,expectedPreparedManifest);
        var draft=input.draft();
        return tx.execute(status->{
            String preview=preview(input,lockCurrent(input));
            var generation=recovery.findByPreviewHash(draft.documentId(),preview);
            if(generation==null) {
                generation=new RecoveryGeneration(UUID.randomUUID().toString(),draft.documentId(),draft.sourceHash(),
                        draft.productSha(),draft.baselineHash(),input.manifest(),preview,"PENDING");
                recovery.insertGeneration(generation);
                for(var row:input.prepared().chunks()) {
                    if(!Objects.equals(row.sourceUrl(),draft.sourceUrl()))throw new IllegalStateException("Prepared chunk source changed");
                    recovery.insertInactiveChunk(row);
                    long chunk=recovery.lastChunkId();
                    if(chunk<=0 || recovery.insertMembership(generation.id(),chunk)!=1)
                        throw new IllegalStateException("Prepared membership conflict");
                }
                var source=source(input,generation);
                if(db.update("INSERT INTO rag_pdf_generation_sources(generation_id,draft_id,source_hash,review_hash,manifest,prepared_manifest,prepared_json,product_sha,metadata_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                        generation.id(),draftId,draft.sourceHash(),draft.reviewHash(),input.manifest(),input.prepared().manifest(),input.preparedJson(),
                        draft.productSha(),source.receiptHash(input.prepared().manifest(),draft.productSha()))!=1)
                    throw new IllegalStateException("Prepared source binding conflict");
            }
            verifyPending(input,generation,preview);
            return generation;
        });
    }

    RecoveryGeneration validate(String generationId,String expectedPreparedManifest) {
        if(generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Exact generation required");
        var binding=db.queryForList("SELECT draft_id,prepared_manifest FROM rag_pdf_generation_sources WHERE generation_id=?",generationId);
        if(binding.size()!=1 || expectedPreparedManifest==null || !expectedPreparedManifest.equals(binding.get(0).get("prepared_manifest")))
            throw new IllegalStateException("Reviewed preparation binding unavailable");
        var input=prepare((String)binding.get(0).get("draft_id"),expectedPreparedManifest);
        return tx.execute(status->{
            String preview=preview(input,lockCurrent(input));
            var generation=recovery.findGeneration(generationId,true);
            var members=verifyPending(input,generation,preview);
            String evidence=validationEvidence(generation,input.stored().id(),input.prepared(),input.preparedJson(),members);
            if(recovery.saveValidation(generation,"VALIDATED",evidence)!=1)
                throw new IllegalStateException("Reviewed validation CAS failed");
            return recovery.findGeneration(generationId,true);
        });
    }

    private PreparedInput prepare(String draftId,String expectedPreparedManifest) {
        if(expectedPreparedManifest==null || !expectedPreparedManifest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact reviewed preparation required");
        var input=drafts.load(draftId);
        var prepared=new RagPdfDraftPreparation(drafts).prepare(draftId);
        var draft=input.draft();
        if(!expectedPreparedManifest.equals(prepared.manifest()) || !draft.reviewHash().equals(prepared.reviewHash())
                || !draft.sourceHash().equals(prepared.sourceHash()) || !draft.baselineHash().equals(prepared.baselineHash())
                || !draft.productSha().equals(prepared.productSha()))throw new IllegalStateException("Reviewed preparation changed");
        String preparedJson=json.writeValueAsString(prepared);
        if(preparedJson.length()>16_000_000)throw new IllegalStateException("Prepared journal bound exceeded");
        String manifest=RecoveryCandidatePlan.manifestFor(draft.documentId(),draft.sourceHash(),draft.productSha(),prepared.chunks());
        return new PreparedInput(input,prepared,preparedJson,manifest);
    }

    private RecoverySnapshot lockCurrent(PreparedInput input) {
        var draft=input.draft();
        if(recovery.lockRoutingEpoch()==null)throw new IllegalStateException("Routing epoch unavailable");
        if(!draft.baselineHash().equals(candidates.lockedStoredBaselineHash(draft.documentId())))
            throw new IllegalStateException("Preserved source baseline changed");
        var pointer=recovery.findPointer(draft.documentId(),true);
        if(pointer==null || !draft.baselineHash().equals(pointer.baselineHash()) || recovery.findBaseline(draft.documentId())==null)
            throw new IllegalStateException("Preserved baseline routing required");
        var stored=db.queryForList("SELECT draft_json,source_bytes,source_hash,baseline_hash,product_sha,review_hash,document_id FROM rag_pdf_source_drafts WHERE draft_id=? AND state='STAGED' FOR UPDATE",input.stored().id());
        if(stored.size()!=1)throw new IllegalStateException("Reviewed source unavailable");
        var row=stored.get(0);
        if(!json.writeValueAsString(draft).equals(row.get("draft_json")) || !draft.sourceHash().equals(row.get("source_hash"))
                || !draft.baselineHash().equals(row.get("baseline_hash")) || !draft.productSha().equals(row.get("product_sha"))
                || !draft.reviewHash().equals(row.get("review_hash"))
                || !(row.get("document_id") instanceof Number document) || document.longValue()!=draft.documentId()
                || !(row.get("source_bytes") instanceof byte[] bytes) || !Arrays.equals(input.stored().sourceBytes(),bytes))
            throw new IllegalStateException("Reviewed source changed during preparation");
        return pointer;
    }

    private String preview(PreparedInput input,RecoverySnapshot pointer) {
        return previewHash(input.stored().id(),input.prepared(),input.manifest(),pointer);
    }

    static String previewHash(String draftId,RagPdfDraftPreparation.Prepared prepared,String manifest,RecoverySnapshot pointer) {
        return LawHashUtils.sha256Json(List.of("reviewed-pdf-generation-v1",draftId,prepared.reviewHash(),
                prepared.manifest(),manifest,pointer));
    }

    static String validationEvidence(RecoveryGeneration generation,String draftId,RagPdfDraftPreparation.Prepared prepared,String encoded,List<Long> members) {
        return new ObjectMapper().writeValueAsString(List.of(VALIDATION_KIND,generation.id(),generation.documentId(),
                draftId,prepared.reviewHash(),generation.productSha(),generation.sourceHash(),generation.baselineHash(),generation.previewHash(),
                generation.manifest(),prepared.manifest(),LawHashUtils.sha256(encoded),prepared.extractionHash(),
                LawHashUtils.sha256Json(members),prepared.chunks().size(),prepared.units().size()));
    }

    private static final String VALIDATION_KIND="reviewed-pdf-validation-v1";
    static boolean isReviewedEvidence(Object evidence) {
        if(!(evidence instanceof String encoded))return false;
        try {
            var root=new ObjectMapper().readTree(encoded);
            return root.isArray() && VALIDATION_KIND.equals(root.path(0).asText());
        } catch(RuntimeException malformed){return false;}
    }

    static List<Long> verifiedMemberIds(JdbcTemplate db,String generationId,List<RagDocumentChunkRow> chunks) {
        var ids=db.queryForList("SELECT chunk_id FROM rag_recovery_members WHERE generation_id=? ORDER BY chunk_id LIMIT 10001",Long.class,generationId);
        if(!ids.equals(chunks.stream().map(RagDocumentChunkRow::chunkId).sorted().toList()))
            throw new IllegalStateException("Reviewed PDF membership changed");
        return List.copyOf(ids);
    }

    private RecoveryPdfSource source(PreparedInput input,RecoveryGeneration generation) {
        var draft=input.draft();
        return new RecoveryPdfSource(generation.id(),draft.documentId(),input.stored().id(),draft.sourceHash(),
                draft.reviewHash(),input.manifest(),draft.title(),draft.sourceUrl());
    }

    private List<Long> verifyPending(PreparedInput input,RecoveryGeneration generation,String preview) {
        var draft=input.draft();
        if(generation==null || generation.documentId()!=draft.documentId() || !"PENDING".equals(generation.state())
                || !preview.equals(generation.previewHash()) || !draft.sourceHash().equals(generation.sourceHash())
                || !draft.productSha().equals(generation.productSha()) || !draft.baselineHash().equals(generation.baselineHash())
                || !input.manifest().equals(generation.manifest()))throw new IllegalStateException("Prepared generation is not current");
        var chunks=recovery.findCandidateChunks(generation.id());
        var members=verifiedMemberIds(db,generation.id(),chunks);
        if(!chunks.stream().map(RagRecoverySourceValidator::unassigned).toList().equals(input.prepared().chunks()))
            throw new IllegalStateException("Persisted prepared chunks changed");
        var bindings=db.queryForList("SELECT * FROM rag_pdf_generation_sources WHERE generation_id=? FOR UPDATE",generation.id());
        if(bindings.size()!=1)throw new IllegalStateException("Prepared source binding missing");
        var binding=bindings.get(0);
        if(!input.stored().id().equals(binding.get("draft_id")) || !draft.sourceHash().equals(binding.get("source_hash"))
                || !draft.reviewHash().equals(binding.get("review_hash")) || !input.manifest().equals(binding.get("manifest"))
                || !input.prepared().manifest().equals(binding.get("prepared_manifest")) || !input.preparedJson().equals(binding.get("prepared_json"))
                || !draft.productSha().equals(binding.get("product_sha"))
                || !source(input,generation).receiptHash(input.prepared().manifest(),draft.productSha()).equals(binding.get("metadata_hash")))
            throw new IllegalStateException("Persisted prepared source binding changed");
        return members;
    }
}
