package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Persists inactive reviewed-source generations only. No bean, endpoint or activation authority. */
final class RagPdfGenerationStore {
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
        if(expectedPreparedManifest==null || !expectedPreparedManifest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Exact reviewed preparation required");
        var input=drafts.load(draftId);
        var prepared=new RagPdfDraftPreparation(drafts).prepare(draftId);
        var draft=input.draft();
        if(!expectedPreparedManifest.equals(prepared.manifest()))throw new IllegalStateException("Reviewed preparation changed");
        String preparedJson=json.writeValueAsString(prepared);
        if(preparedJson.length()>16_000_000)throw new IllegalStateException("Prepared journal bound exceeded");
        String manifest=RecoveryCandidatePlan.manifestFor(draft.documentId(),draft.sourceHash(),draft.productSha(),prepared.chunks());
        return tx.execute(status->{
            if(recovery.lockRoutingEpoch()==null)throw new IllegalStateException("Routing epoch unavailable");
            if(!draft.baselineHash().equals(candidates.lockedStoredBaselineHash(draft.documentId())))
                throw new IllegalStateException("Preserved source baseline changed");
            var pointer=recovery.findPointer(draft.documentId(),true);
            if(pointer==null || !draft.baselineHash().equals(pointer.baselineHash()) || recovery.findBaseline(draft.documentId())==null)
                throw new IllegalStateException("Preserved baseline routing required");
            var stored=db.queryForList("SELECT draft_json,source_bytes,source_hash,baseline_hash,product_sha,review_hash,document_id FROM rag_pdf_source_drafts WHERE draft_id=? AND state='STAGED' FOR UPDATE",draftId);
            if(stored.size()!=1 || !json.writeValueAsString(draft).equals(stored.get(0).get("draft_json"))
                    || !draft.sourceHash().equals(stored.get(0).get("source_hash"))
                    || !draft.baselineHash().equals(stored.get(0).get("baseline_hash"))
                    || !draft.productSha().equals(stored.get(0).get("product_sha"))
                    || !draft.reviewHash().equals(stored.get(0).get("review_hash"))
                    || !(stored.get(0).get("document_id") instanceof Number document) || document.longValue()!=draft.documentId()
                    || !(stored.get(0).get("source_bytes") instanceof byte[] bytes) || !Arrays.equals(input.sourceBytes(),bytes))
                throw new IllegalStateException("Reviewed source changed during preparation");
            String preview=LawHashUtils.sha256Json(List.of("reviewed-pdf-generation-v1",draftId,draft.reviewHash(),
                    prepared.manifest(),manifest,pointer));
            var existing=recovery.findByPreviewHash(draft.documentId(),preview);
            RecoveryGeneration generation;
            if(existing!=null) {
                generation=existing;
            } else {
                generation=new RecoveryGeneration(UUID.randomUUID().toString(),draft.documentId(),draft.sourceHash(),
                        draft.productSha(),draft.baselineHash(),manifest,preview,"PENDING");
                recovery.insertGeneration(generation);
                for(var row:prepared.chunks()) {
                    if(!Objects.equals(row.sourceUrl(),draft.sourceUrl()))throw new IllegalStateException("Prepared chunk source changed");
                    recovery.insertInactiveChunk(row);
                    long chunk=recovery.lastChunkId();
                    if(chunk<=0 || recovery.insertMembership(generation.id(),chunk)!=1)
                        throw new IllegalStateException("Prepared membership conflict");
                }
                var source=new RecoveryPdfSource(generation.id(),draft.documentId(),draftId,draft.sourceHash(),
                        draft.reviewHash(),manifest,draft.title(),draft.sourceUrl());
                if(db.update("INSERT INTO rag_pdf_generation_sources(generation_id,draft_id,source_hash,review_hash,manifest,prepared_manifest,prepared_json,product_sha,metadata_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                        generation.id(),draftId,draft.sourceHash(),draft.reviewHash(),manifest,prepared.manifest(),preparedJson,
                        draft.productSha(),source.receiptHash(prepared.manifest(),draft.productSha()))!=1)
                    throw new IllegalStateException("Prepared source binding conflict");
            }
            if(!"PENDING".equals(generation.state()) || !draft.sourceHash().equals(generation.sourceHash())
                    || !draft.productSha().equals(generation.productSha()) || !draft.baselineHash().equals(generation.baselineHash())
                    || !manifest.equals(generation.manifest()))throw new IllegalStateException("Prepared generation is not reusable");
            var actual=recovery.findCandidateChunks(generation.id()).stream().map(RagRecoverySourceValidator::unassigned).toList();
            Long membership=db.queryForObject("SELECT COUNT(*) FROM rag_recovery_members WHERE generation_id=?",Long.class,generation.id());
            if(membership==null || membership!=actual.size() || !actual.equals(prepared.chunks()))
                throw new IllegalStateException("Persisted prepared chunks or membership changed");
            var bindings=db.queryForList("SELECT * FROM rag_pdf_generation_sources WHERE generation_id=? FOR UPDATE",generation.id());
            var source=new RecoveryPdfSource(generation.id(),draft.documentId(),draftId,draft.sourceHash(),
                    draft.reviewHash(),manifest,draft.title(),draft.sourceUrl());
            if(bindings.size()!=1)throw new IllegalStateException("Prepared source binding missing");
            var binding=bindings.get(0);
            if(!draftId.equals(binding.get("draft_id")) || !draft.sourceHash().equals(binding.get("source_hash"))
                    || !draft.reviewHash().equals(binding.get("review_hash")) || !manifest.equals(binding.get("manifest"))
                    || !prepared.manifest().equals(binding.get("prepared_manifest")) || !preparedJson.equals(binding.get("prepared_json"))
                    || !draft.productSha().equals(binding.get("product_sha"))
                    || !source.receiptHash(prepared.manifest(),draft.productSha()).equals(binding.get("metadata_hash")))
                throw new IllegalStateException("Persisted prepared source binding changed");
            return generation;
        });
    }
}
