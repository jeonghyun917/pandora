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
            List<SourceReferenceDeclarations.Declaration> declarations,String status,boolean fidelityReady) {
        public Report {references=List.copyOf(references);declarations=List.copyOf(declarations);}
    }
    private final JdbcTemplate db;
    private final SourceVersionPreparationService preparation;
    private final SourceReferenceInspector inspector;
    private final TransactionTemplate tx;
    private final ObjectMapper json=new ObjectMapper();
    private static final int PART_BYTES=1024*1024;
    private static final java.util.concurrent.Semaphore ACQUISITIONS=new java.util.concurrent.Semaphore(1);
    public SourceVersionReferenceReceipts(JdbcTemplate db,SourceVersionPreparationService preparation,
            PlatformTransactionManager manager,SourceReferenceInspector inspector) {
        this.db=db;this.preparation=preparation;this.inspector=inspector;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public Report inspect(String id) {
        if(!ACQUISITIONS.tryAcquire())throw new IllegalStateException("Reference acquisition capacity unavailable");
        try {return inspectOwned(id);}finally {ACQUISITIONS.release();}
    }
    private Report inspectOwned(String id) {
        var snapshot=preparation.snapshotForAssets(id);
        if(snapshot.sourceAssets().isEmpty() || snapshot.sourceAssets().size()>32)throw new IllegalStateException("Bounded declared references required");
        // Validate the complete address set before making any request; aliases cannot silently merge assets.
        var canonical=new HashSet<String>();
        for(var asset:snapshot.sourceAssets())if(!canonical.add(SourceReferenceInspector.officialUri(asset.sourceUrl()).toString()))
            throw new IllegalStateException("Ambiguous duplicate official reference identity");
        var declarations=declarations(snapshot);
        var existing=db.queryForList("SELECT report_json FROM law_source_version_reference_inspections WHERE candidate_id=?",id);
        if(!existing.isEmpty())return verified(snapshot,(String)existing.get(0).get("report_json"));
        var proofs=new ArrayList<SourceReferenceInspector.Inspection>();long total=0;
        var acquisitions=new ArrayList<SourceReferenceInspector.Acquisition>();
        for(var asset:snapshot.sourceAssets()) {
            var acquisition=inspector.acquire(asset.sourceUrl());var proof=acquisition.inspection();total+=proof.byteCount();
            if(total>32L*1024*1024)throw new IllegalStateException("Total reference byte bound exceeded");
            proofs.add(proof);
            acquisitions.add(acquisition);
        }
        String hash=hash(snapshot,proofs,declarations);
        var report=new Report(id,snapshot.validation().manifest(),hash,proofs,declarations,"INTEGRITY_ONLY",false);
        return tx.execute(status->{
            var current=preparation.recheckForEmbeddings(id);
            var candidate=db.queryForMap("SELECT input_hash,raw_hash FROM law_source_version_candidates WHERE candidate_id=? FOR UPDATE",id);
            if(!current.equals(snapshot.validation()) || !snapshot.inputHash().equals(candidate.get("input_hash")) || !snapshot.rawHash().equals(candidate.get("raw_hash")))
                throw new IllegalStateException("Source changed during reference acquisition");
            var stored=db.queryForList("SELECT report_json FROM law_source_version_reference_inspections WHERE candidate_id=? FOR UPDATE",id);
            if(!stored.isEmpty()) {
                var prior=verified(snapshot,(String)stored.get(0).get("report_json"));
                if(!prior.equals(report))throw new IllegalStateException("Reference inspection evidence is immutable");
                return prior;
            }
            db.update("INSERT INTO law_source_version_reference_inspections(candidate_id,input_hash,validation_manifest,receipt_hash,report_json) VALUES(?,?,?,?,?)",
                id,snapshot.inputHash(),current.manifest(),hash,json.writeValueAsString(report));
            for(int asset=0;asset<acquisitions.size();asset++) {
                byte[] bytes=acquisitions.get(asset).bytes();
                for(int start=0,part=0;start<bytes.length;start+=PART_BYTES,part++) {
                    byte[] payload=Arrays.copyOfRange(bytes,start,Math.min(bytes.length,start+PART_BYTES));
                    db.update("INSERT INTO law_source_version_reference_parts(candidate_id,asset_index,part_index,part_hash,part_bytes) VALUES(?,?,?,?,?)",
                        id,asset,part,SourceReferenceInspector.artifactHash(payload),payload);
                }
            }
            verifyParts(report);
            return report;
        });
    }
    private Report verified(SourceVersionPreparationService.AssetSnapshot source,String encoded) {
        var report=read(source,encoded);verifyParts(report);return report;
    }
    private void verifyParts(Report report) {
        int expected=0;long total=0;
        for(var proof:report.references()) {
            if(proof.byteCount()<1 || proof.byteCount()>SourceReferenceInspector.MAX_BYTES || !proof.artifactHash().matches("[0-9a-f]{64}"))
                throw new IllegalStateException("Reference artifact bounds changed");
            expected+=(int)((proof.byteCount()+PART_BYTES-1)/PART_BYTES);total+=proof.byteCount();
        }
        if(total>32L*1024*1024 || db.queryForObject("SELECT COUNT(*) FROM law_source_version_reference_parts WHERE candidate_id=?",Long.class,report.candidateId())!=expected)
            throw new IllegalStateException("Reference artifact part membership changed");
        for(int asset=0;asset<report.references().size();asset++) {
            var proof=report.references().get(asset);var rows=db.queryForList("SELECT part_index,part_hash,part_bytes FROM law_source_version_reference_parts WHERE candidate_id=? AND asset_index=? ORDER BY part_index LIMIT 9",report.candidateId(),asset);
            if(rows.size()!=(proof.byteCount()+PART_BYTES-1)/PART_BYTES)throw new IllegalStateException("Reference artifact parts missing");
            try {
                var digest=java.security.MessageDigest.getInstance("SHA-256");long count=0;
                for(int part=0;part<rows.size();part++) {
                    var row=rows.get(part);byte[] bytes=(byte[])row.get("part_bytes");
                    long required=Math.min(PART_BYTES,proof.byteCount()-(long)part*PART_BYTES);
                    if(((Number)row.get("part_index")).intValue()!=part || bytes.length!=required || !SourceReferenceInspector.artifactHash(bytes).equals(row.get("part_hash")))
                        throw new IllegalStateException("Reference artifact part changed");
                    digest.update(bytes);count+=bytes.length;
                }
                if(count!=proof.byteCount() || !HexFormat.of().formatHex(digest.digest()).equals(proof.artifactHash()))throw new IllegalStateException("Reference artifact bytes changed");
            } catch(java.security.NoSuchAlgorithmException unavailable) {throw new IllegalStateException("SHA-256 unavailable",unavailable);}
        }
    }
    private Report read(SourceVersionPreparationService.AssetSnapshot source,String encoded) {
        var report=json.readValue(encoded,Report.class);
        if(!report.candidateId().equals(source.candidateId()) || !report.manifest().equals(source.validation().manifest())
            || !report.status().equals("INTEGRITY_ONLY") || report.fidelityReady()
            || report.references().size()!=source.sourceAssets().size() || !report.declarations().equals(declarations(source))
            || !hash(source,report.references(),report.declarations()).equals(report.receiptHash()))
            throw new IllegalStateException("Reference inspection evidence changed");
        for(int i=0;i<report.references().size();i++) {
            var proof=report.references().get(i);
            if(!proof.sourceUrl().equals(source.sourceAssets().get(i).sourceUrl()) || !proof.canonicalUrl().equals(SourceReferenceInspector.officialUri(proof.sourceUrl()).toString())
                || !proof.status().equals("INTEGRITY_ONLY") || proof.fidelityReady())throw new IllegalStateException("Reference declaration changed");
        }
        return report;
    }
    private List<SourceReferenceDeclarations.Declaration> declarations(SourceVersionPreparationService.AssetSnapshot source) {
        String raw=db.queryForObject("SELECT raw_detail_json FROM law_source_version_candidates WHERE candidate_id=?",String.class,source.candidateId());
        if(raw==null || raw.length()>4_000_000 || !LawHashUtils.sha256(raw).equals(source.rawHash()))throw new IllegalStateException("Reference source identity changed");
        var result=new SourceReferenceDeclarations().capture(raw);
        var declared=new HashSet<String>();for(var item:result)declared.add(item.canonicalUrl());
        var expected=new HashSet<String>();for(var asset:source.sourceAssets())expected.add(SourceReferenceInspector.officialUri(asset.sourceUrl()).toString());
        if(!declared.equals(expected))throw new IllegalStateException("Reference declaration membership changed");return result;
    }
    private String hash(SourceVersionPreparationService.AssetSnapshot source,List<SourceReferenceInspector.Inspection> proofs,List<SourceReferenceDeclarations.Declaration> declarations) {
        return LawHashUtils.sha256Json(List.of("source-reference-relations-v1",source.candidateId(),source.inputHash(),source.rawHash(),source.validation(),source.sourceAssets(),declarations,proofs));
    }
}
