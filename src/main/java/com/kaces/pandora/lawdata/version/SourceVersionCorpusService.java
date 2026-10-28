package com.kaces.pandora.lawdata.version;

import com.kaces.pandora.rag.recovery.RecoveryCorpus;
import com.kaces.pandora.semantic.lexical.LexicalChunkDocument;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Complete source overlay after the RAG overlay, under source-before-routing lock order. */
@Service
public class SourceVersionCorpusService {
    private final JdbcTemplate db;
    private final SourceVersionCandidateStore store;
    public SourceVersionCorpusService(JdbcTemplate db,SourceVersionCandidateStore store) {this.db=db;this.store=store;}
    public void lockSourceEpoch() {
        requireTransaction();
        // SERIALIZABLE routing reads also lock visibility. Take that shared lock first,
        // matching the exclusive visibility -> source -> RAG order used by activation.
        db.queryForObject("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1 LOCK IN SHARE MODE",Long.class);
        db.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
    }
    public RecoveryCorpus overlay(RecoveryCorpus corpus,SourceVersionRoutingSnapshot sources) {
        requireTransaction();
        if(corpus==null || sources==null)throw new IllegalArgumentException("Captured source corpus required");
        if(sources.revision()==0)return corpus;
        long revision=db.queryForObject("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1",Long.class);
        if(revision!=sources.revision())throw new IllegalStateException("Source routing changed during corpus capture");
        var replacements=new ArrayList<SourceVersionCorpus.Replacement>();
        for(var route:new TreeMap<>(sources.routes()).values()) {
            store.recheckPreserved(route.groupKey());
            var owned=db.queryForList("SELECT h.*,g.active_document_id,g.revision AS group_revision,d.target,d.canonical_key,d.use_yn,d.effective_status FROM law_source_version_history h JOIN law_source_version_groups g ON g.group_key=h.group_key JOIN law_api_documents d ON d.document_id=h.document_id WHERE h.group_key=? AND h.document_id=?",route.groupKey(),route.activeDocumentId());
            if(owned.size()!=1)throw new IllegalStateException("Source corpus ownership missing");
            var identity=owned.get(0);
            if(!Objects.equals(identity.get("source_candidate_id"),route.candidateId()) || !route.contentSnapshotHash().equals(identity.get("content_snapshot_hash"))
                    || ((Number)identity.get("active_document_id")).longValue()!=route.activeDocumentId() || ((Number)identity.get("group_revision")).longValue()!=route.revision()
                    || !route.target().equals(identity.get("target")) || !route.canonicalKey().equals(identity.get("canonical_key"))
                    || !"Y".equals(identity.get("use_yn")) || !"CURRENT".equals(identity.get("effective_status")))
                throw new IllegalStateException("Source corpus pointer or content receipt changed");
            var chunks=db.query("SELECT d.target,c.chunk_id,c.document_id,c.parent_key,c.content_hash,d.title,COALESCE(NULLIF(c.parent_title,''),c.chunk_title,''),COALESCE(c.chunk_title,''),c.chunk_text FROM law_api_documents d JOIN law_api_document_chunks c ON c.document_id=d.document_id WHERE d.document_id=? AND c.use_yn='Y' AND c.activation_status='ACTIVE' AND c.quality_status='PASS' AND (?=0 OR c.chunk_version=?) ORDER BY c.chunk_id LIMIT 10001",
                (row,n)->new LexicalChunkDocument(row.getString(1),row.getLong(2),row.getLong(3),row.getString(4),row.getString(5),row.getString(6),row.getString(7),row.getString(8),row.getString(9)),
                route.activeDocumentId(),route.chunkVersion(),route.chunkVersion());
            var old=new TreeSet<>(route.historyDocumentIds());old.remove(route.activeDocumentId());
            replacements.add(new SourceVersionCorpus.Replacement(route.target(),route.canonicalKey(),old,route.activeDocumentId(),chunks));
        }
        return SourceVersionCorpus.overlayCurrent(corpus.baseRevision(),corpus.chunks(),replacements);
    }
    private void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())throw new IllegalStateException("Locked source corpus transaction required");
    }
}
