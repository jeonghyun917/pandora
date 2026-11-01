package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Durable inspection evidence, not a fidelity or activation receipt. Acquisition is outside the DB transaction. */
public final class SourceVersionReferenceReceipts {
    public record Report(String candidateId,String manifest,String receiptHash,List<SourceReferenceInspector.Inspection> references,
            String status,boolean fidelityReady) {public Report {references=List.copyOf(references);}}
    private final JdbcTemplate db;
    private final SourceVersionPreparationService preparation;
    private final SourceReferenceInspector inspector;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();
    public SourceVersionReferenceReceipts(JdbcTemplate db,SourceVersionPreparationService preparation,
            PlatformTransactionManager manager,SourceReferenceInspector inspector) {
        this.db=db;this.preparation=preparation;this.inspector=inspector;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public Report inspect(String id) {
        var snapshot=preparation.snapshotForAssets(id);
        if(snapshot.sourceAssets().isEmpty() || snapshot.sourceAssets().size()>32)throw new IllegalStateException("Bounded declared references required");
        // Validate the complete address set before making any request; aliases cannot silently merge assets.
        var canonical=new HashSet<String>();
        for(var asset:snapshot.sourceAssets())if(!canonical.add(SourceReferenceInspector.officialUri(asset.sourceUrl()).toString()))
            throw new IllegalStateException("Ambiguous duplicate official reference identity");
        var existing=db.queryForList("SELECT report_json FROM law_source_version_reference_inspections WHERE candidate_id=?",id);
        if(!existing.isEmpty())return read(snapshot,(String)existing.get(0).get("report_json"));
        var proofs=new ArrayList<SourceReferenceInspector.Inspection>();long total=0;
        for(var asset:snapshot.sourceAssets()) {
            var proof=inspector.inspect(asset.sourceUrl());total+=proof.byteCount();
            if(total>32L*1024*1024)throw new IllegalStateException("Total reference byte bound exceeded");
            proofs.add(proof);
        }
        String hash=hash(snapshot,proofs);
        var report=new Report(id,snapshot.validation().manifest(),hash,proofs,"INTEGRITY_ONLY",false);
        return tx.execute(status->{
            var current=preparation.recheckForEmbeddings(id);
            var candidate=db.queryForMap("SELECT input_hash,raw_hash FROM law_source_version_candidates WHERE candidate_id=? FOR UPDATE",id);
            if(!current.equals(snapshot.validation()) || !snapshot.inputHash().equals(candidate.get("input_hash")) || !snapshot.rawHash().equals(candidate.get("raw_hash")))
                throw new IllegalStateException("Source changed during reference acquisition");
            var stored=db.queryForList("SELECT report_json FROM law_source_version_reference_inspections WHERE candidate_id=? FOR UPDATE",id);
            if(!stored.isEmpty()) {
                var prior=read(snapshot,(String)stored.get(0).get("report_json"));
                if(!prior.equals(report))throw new IllegalStateException("Reference inspection evidence is immutable");
                return prior;
            }
            db.update("INSERT INTO law_source_version_reference_inspections(candidate_id,input_hash,validation_manifest,receipt_hash,report_json) VALUES(?,?,?,?,?)",
                id,snapshot.inputHash(),current.manifest(),hash,json.writeValueAsString(report));
            return report;
        });
    }
    private Report read(SourceVersionPreparationService.AssetSnapshot source,String encoded) {
        var report=json.readValue(encoded,Report.class);
        if(!report.candidateId().equals(source.candidateId()) || !report.manifest().equals(source.validation().manifest())
            || !report.status().equals("INTEGRITY_ONLY") || report.fidelityReady()
            || report.references().size()!=source.sourceAssets().size() || !hash(source,report.references()).equals(report.receiptHash()))
            throw new IllegalStateException("Reference inspection evidence changed");
        for(int i=0;i<report.references().size();i++) {
            var proof=report.references().get(i);
            if(!proof.sourceUrl().equals(source.sourceAssets().get(i).sourceUrl()) || !proof.canonicalUrl().equals(SourceReferenceInspector.officialUri(proof.sourceUrl()).toString())
                || !proof.status().equals("INTEGRITY_ONLY") || proof.fidelityReady())throw new IllegalStateException("Reference declaration changed");
        }
        return report;
    }
    private String hash(SourceVersionPreparationService.AssetSnapshot source,List<SourceReferenceInspector.Inspection> proofs) {
        return LawHashUtils.sha256Json(List.of("source-reference-inspection-v1",source.candidateId(),source.inputHash(),source.rawHash(),source.validation(),source.sourceAssets(),proofs));
    }
}
