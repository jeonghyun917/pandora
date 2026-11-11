package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Insert-only quarantine. Deliberately not a bean or endpoint until the replacement workflow is ready. */
final class RagPdfDraftStore {
    record Staged(String id,String reviewHash,String state) { }
    record StoredInput(String id,ReviewedPdfDraft draft,byte[] sourceBytes) {
        StoredInput {sourceBytes=sourceBytes.clone();}
        @Override public byte[] sourceBytes(){return sourceBytes.clone();}
    }
    private final JdbcTemplate db;
    private final RagRecoveryCandidateService candidates;
    private final String productSha;
    private final Set<String> trustedReviews;
    private final TransactionTemplate tx;
    private final TransactionTemplate read;
    private final ObjectMapper json=new ObjectMapper();

    RagPdfDraftStore(JdbcTemplate db,PlatformTransactionManager manager,RagRecoveryCandidateService candidates,
            String productSha,Set<String> trustedReviews) {
        if(productSha==null || !productSha.matches("[0-9a-f]{40}") || trustedReviews==null
                || trustedReviews.size()>256 || trustedReviews.stream().anyMatch(h->h==null || !h.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Server-owned product and reviewed hashes required");
        this.db=Objects.requireNonNull(db);this.candidates=Objects.requireNonNull(candidates);
        this.productSha=productSha;this.trustedReviews=Set.copyOf(trustedReviews);
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
        read=new TransactionTemplate(manager);read.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        read.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);read.setReadOnly(true);read.setTimeout(10);
    }

    Staged stage(ReviewedPdfDraft draft,Path original) {
        if(draft==null || !productSha.equals(draft.productSha()) || !trustedReviews.contains(draft.reviewHash()))
            throw new IllegalStateException("Draft has no server-owned review authority");
        byte[] bytes;
        try {
            if(!Files.isRegularFile(original,LinkOption.NOFOLLOW_LINKS) || Files.size(original)>8*1024*1024)
                throw new IllegalStateException("Bounded original required");
            try(var input=Files.newInputStream(original)){bytes=input.readNBytes(8*1024*1024+1);}
        } catch(java.io.IOException unavailable){throw new IllegalStateException("Reviewed original unavailable",unavailable);}
        if(bytes.length<5 || bytes.length>8*1024*1024 || !draft.sourceHash().equals(hash(bytes)))
            throw new IllegalStateException("Reviewed original changed");
        var replay=draft.replay(original,draft.reviewHash());
        String draftJson=json.writeValueAsString(draft),replayJson=json.writeValueAsString(replay);
        if(draftJson.length()>4_000_000 || replayJson.length()>4_000_000)
            throw new IllegalStateException("Reviewed source manifest bound exceeded");
        String replayHash=LawHashUtils.sha256(replayJson);
        return tx.execute(status->{
            var document=db.queryForList("SELECT document_type FROM rag_documents WHERE document_id=? AND use_yn='Y' FOR UPDATE",draft.documentId());
            if(document.size()!=1 || !"official_doc".equals(document.get(0).get("document_type"))
                    || !draft.baselineHash().equals(candidates.lockedStoredBaselineHash(draft.documentId())))
                throw new IllegalStateException("Preserved source baseline changed");
            var existing=db.queryForList("SELECT * FROM rag_pdf_source_drafts WHERE review_hash=? FOR UPDATE",draft.reviewHash());
            if(!existing.isEmpty()) {
                var row=existing.get(0);
                if(existing.size()!=1 || !"STAGED".equals(row.get("state"))
                        || ((Number)row.get("document_id")).longValue()!=draft.documentId()
                        || !draft.sourceHash().equals(row.get("source_hash")) || !draft.baselineHash().equals(row.get("baseline_hash"))
                        || !productSha.equals(row.get("product_sha")) || !draftJson.equals(row.get("draft_json"))
                        || !replayJson.equals(row.get("replay_json")) || !replayHash.equals(row.get("replay_hash"))
                        || !(row.get("source_bytes") instanceof byte[] stored) || !Arrays.equals(bytes,stored))
                    throw new IllegalStateException("Stored reviewed draft changed");
                return new Staged((String)row.get("draft_id"),draft.reviewHash(),"STAGED");
            }
            Integer count=db.queryForObject("SELECT COUNT(*) FROM rag_pdf_source_drafts WHERE document_id=?",Integer.class,draft.documentId());
            if(count==null || count>=64)throw new IllegalStateException("Source quarantine capacity exceeded");
            String id=UUID.randomUUID().toString();
            if(db.update("INSERT INTO rag_pdf_source_drafts(draft_id,document_id,review_hash,source_hash,baseline_hash,product_sha,draft_json,replay_json,replay_hash,source_bytes,state) VALUES(?,?,?,?,?,?,?,?,?,?,'STAGED')",
                    id,draft.documentId(),draft.reviewHash(),draft.sourceHash(),draft.baselineHash(),productSha,
                    draftJson,replayJson,replayHash,bytes)!=1)throw new IllegalStateException("Source quarantine insert conflict");
            return new Staged(id,draft.reviewHash(),"STAGED");
        });
    }

    StoredInput load(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))
            throw new IllegalArgumentException("Exact quarantine identity required");
        var input=read.execute(status->{
            var first=db.queryForList("SELECT * FROM rag_pdf_source_drafts WHERE draft_id=?",id);
            if(first.size()!=1)throw new IllegalStateException("Reviewed source draft unavailable");
            return readStored(first.get(0));
        });
        return tx.execute(status->{
            // Same lock order as stage: document/baseline first, journal row second.
            if(!input.draft().baselineHash().equals(candidates.lockedStoredBaselineHash(input.draft().documentId())))
                throw new IllegalStateException("Preserved source baseline changed");
            var locked=db.queryForList("SELECT * FROM rag_pdf_source_drafts WHERE draft_id=? FOR UPDATE",id);
            if(locked.size()!=1)throw new IllegalStateException("Reviewed source draft changed");
            var result=readStored(locked.get(0));
            if(!input.draft().equals(result.draft()) || !Arrays.equals(input.sourceBytes(),result.sourceBytes()))
                throw new IllegalStateException("Reviewed source draft changed");
            return result;
        });
    }

    private StoredInput readStored(Map<String,Object> row) {
        if(!(row.get("draft_json") instanceof String draftJson) || draftJson.length()>4_000_000
                || !(row.get("source_bytes") instanceof byte[] bytes) || bytes.length<5 || bytes.length>8*1024*1024)
            throw new IllegalStateException("Stored source bounds rejected");
        final ReviewedPdfDraft draft;
        try {draft=json.readValue(draftJson,ReviewedPdfDraft.class);}
        catch(RuntimeException invalid){throw new IllegalStateException("Stored source manifest rejected",invalid);}
        if(draft==null || !productSha.equals(draft.productSha()) || !trustedReviews.contains(draft.reviewHash())
                || !draft.reviewHash().equals(row.get("review_hash")) || !"STAGED".equals(row.get("state"))
                || !(row.get("document_id") instanceof Number document) || document.longValue()!=draft.documentId()
                || !draft.sourceHash().equals(row.get("source_hash")) || !draft.sourceHash().equals(hash(bytes))
                || !draft.baselineHash().equals(row.get("baseline_hash")) || !productSha.equals(row.get("product_sha")))
            throw new IllegalStateException("Stored source identity rejected");
        var expected=draft.units().stream().map(unit->new ReviewedPdfDraft.ReplayedUnit(unit.key(),unit.heading(),
                unit.review().lines(),unit.review().lines().stream().map(line->line.literal().strip())
                    .collect(java.util.stream.Collectors.joining(" ")))).toList();
        String replay=json.writeValueAsString(expected);
        if(!replay.equals(row.get("replay_json")) || !LawHashUtils.sha256(replay).equals(row.get("replay_hash")))
            throw new IllegalStateException("Stored replay differs from reviewed source");
        return new StoredInput((String)row.get("draft_id"),draft,bytes);
    }

    private static String hash(byte[] bytes) {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
