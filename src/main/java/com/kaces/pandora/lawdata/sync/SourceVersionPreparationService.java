package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.version.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Builds a separately identified inactive document; validation never authorizes serving. */
public final class SourceVersionPreparationService {
    public record Validation(String state,long documentId,int chunkCount,String manifest) { }
    private final JdbcTemplate db;
    private final SourceVersionCandidateStore store;
    private final LawDocumentWriter writer;
    private final LawOpenApiPayloadParser parser;
    private final LawChunkMapper chunks;
    private final TransactionTemplate tx;
    private final String productSha;
    private final ObjectMapper json=new ObjectMapper();

    public SourceVersionPreparationService(JdbcTemplate db,SourceVersionCandidateStore store,LawDocumentWriter writer,
            LawChunkMapper chunks,LawOpenApiPayloadParser parser,PlatformTransactionManager manager,String productSha) {
        this.db=db;this.store=store;this.writer=writer;this.parser=parser;this.chunks=chunks;
        if(productSha==null || !productSha.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("Exact product SHA required");
        this.productSha=productSha;tx=new TransactionTemplate(manager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public Validation validate(String id) {
        if(id==null || !UUID.fromString(id).toString().equals(id))throw new IllegalArgumentException("Candidate UUID required");
        try {return tx.execute(status->validateLocked(id));}
        catch(TextRejection rejected) {
            // The construction transaction rolled back. Persist only a terminal quality decision.
            tx.executeWithoutResult(status->{
                lockEpoch();
                db.update("UPDATE law_source_version_candidates SET state='REJECTED' WHERE candidate_id=? AND input_hash=? AND product_sha=? AND state='STAGED' AND candidate_document_id IS NULL",
                    id,rejected.inputHash,productSha);
            });
            throw new IllegalStateException("Official source fidelity rejected",rejected.getCause());
        }
    }
    public record AssetSnapshot(String candidateId,String inputHash,String rawHash,Validation validation,
            List<com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow> chunks,List<SyncAsset> sourceAssets) {
        public AssetSnapshot {chunks=List.copyOf(chunks);sourceAssets=List.copyOf(sourceAssets);}
    }
    public AssetSnapshot snapshotForAssets(String id) {
        if(id==null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Candidate UUID required");
        return tx.execute(status->{
            var validation=recheckForEmbeddings(id);
            var candidate=db.queryForMap("SELECT * FROM law_source_version_candidates WHERE candidate_id=?",id);
            var source=json.readValue((String)candidate.get("metadata_json"),SearchDocument.class);
            var rows=List.copyOf(chunks.findSourceVersionChunks(id));
            if(rows.size()!=validation.chunkCount() || rows.isEmpty() || rows.size()>10000 || rows.stream().anyMatch(row->row.documentId()!=validation.documentId()))
                throw new IllegalStateException("Inactive source membership unavailable");
            return new AssetSnapshot(id,(String)candidate.get("input_hash"),(String)candidate.get("raw_hash"),validation,rows,
                parser.parseDetailDocument((String)candidate.get("raw_detail_json"),source.title()).assets());
        });
    }
    private Validation validateLocked(String id) {
        lockEpoch();
        var candidates=db.queryForList("SELECT * FROM law_source_version_candidates WHERE candidate_id=? FOR UPDATE",id);
        if(candidates.size()!=1)throw new IllegalStateException("Candidate unavailable");
        var c=candidates.get(0);String state=(String)c.get("state");
        if(!Set.of("STAGED","VALIDATED").contains(state) || !productSha.equals(c.get("product_sha")))
            throw new IllegalStateException("Candidate is not validatable by this product");
        long baseline=number(c,"baseline_document_id");
        var group=db.queryForMap("SELECT * FROM law_source_version_groups WHERE group_key=? FOR UPDATE",c.get("group_key"));
        var original=db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=? FOR UPDATE",baseline);
        if(number(group,"active_document_id")!=baseline || number(group,"revision")!=number(c,"baseline_revision")
                || !Objects.equals(group.get("baseline_hash"),c.get("baseline_hash"))
                || !Objects.equals(store.snapshotHash(baseline),c.get("baseline_hash"))
                || !"Y".equals(original.get("use_yn")) || !"CURRENT".equals(original.get("effective_status")))
            throw new IllegalStateException("Preserved baseline changed");
        String raw=(String)c.get("raw_detail_json");
        if(raw==null || raw.length()>4_000_000 || !LawHashUtils.sha256(raw).equals(c.get("raw_hash")))
            throw new IllegalStateException("Candidate source changed");
        SearchDocument source=json.readValue((String)c.get("metadata_json"),SearchDocument.class);
        var policy=SourceVersionPolicy.stage(new SourceVersionPolicy.Baseline(baseline,number(c,"baseline_revision"),
                (String)original.get("target"),(String)original.get("canonical_key"),(String)original.get("external_id"),
                (String)original.get("effective_date"),(String)c.get("baseline_hash")),
                new SourceVersionPolicy.Source(source.target(),source.title(),source.externalId(),source.effectiveDate(),LawHashUtils.sha256(raw)),
                productSha,LocalDate.now(ZoneId.of("Asia/Seoul")));
        String input=LawHashUtils.sha256Json(List.of(policy.inputHash(),source));
        if(!input.equals(c.get("input_hash")) || !source.externalId().equals(c.get("external_id"))
                || !source.effectiveDate().equals(c.get("effective_date")))throw new IllegalStateException("Candidate identity changed");
        if("VALIDATED".equals(state)) {
            long document=number(c,"candidate_document_id");
            var plan=new SourceVersionTextPlan(parser).plan(source.target(),document,source.title(),raw);
            String manifest=manifest(input,plan,document);
            int count=db.queryForObject("SELECT COUNT(*) FROM law_api_document_chunks WHERE document_id=?",Integer.class,document);
            if(!manifest.equals(c.get("validation_manifest")) || count!=number(c,"validated_chunk_count"))
                throw new IllegalStateException("Validated source assets changed");
            return new Validation(state,document,count,manifest);
        }
        db.update("INSERT INTO law_api_documents(target,external_id,title,agency_name,category_name,source_date,canonical_key,effective_date,effective_status,detail_link,raw_json,content_hash,use_yn) VALUES(?,?,?,?,?,?,?,?,'PAST',?,?,?,'N')",
            source.target(),source.externalId(),source.title(),source.agencyName(),source.categoryName(),source.sourceDate(),
            original.get("canonical_key"),source.effectiveDate(),source.detailLink(),source.rawJson(),LawHashUtils.sha256(source.rawJson()));
        long document=db.queryForObject("SELECT document_id FROM law_api_documents WHERE target=? AND external_id=?",Long.class,source.target(),source.externalId());
        if(db.update("UPDATE law_source_version_candidates SET candidate_document_id=? WHERE candidate_id=? AND state='STAGED' AND candidate_document_id IS NULL",document,id)!=1)
            throw new IllegalStateException("Candidate construction conflict");
        SourceVersionTextPlan.Plan plan;
        try {plan=new SourceVersionTextPlan(parser).plan(source.target(),document,source.title(),raw);}
        catch(IllegalStateException | IllegalArgumentException invalid) {throw new TextRejection(input,invalid);}
        var detail=parser.parseDetailDocument(raw,source.title());
        long detailId=writer.upsertDetail(document,detail,raw);
        var version=writer.createCandidateChunks(document,detailId,source.target(),source.title(),plan.sections(),source.detailLink(),plan.manifest(),0);
        if(version.expectedChunkCount()!=plan.chunks().size())throw new IllegalStateException("Chunk plan drift");
        int count=db.update("UPDATE law_api_document_chunks SET activation_status='ACTIVE' WHERE document_id=? AND chunk_version=? AND activation_status='CANDIDATE'",document,version.chunkVersion());
        if(count!=plan.chunks().size())throw new IllegalStateException("Incomplete inactive chunk membership");
        if(db.update("UPDATE law_api_document_chunk_versions SET activation_status='ACTIVE' WHERE document_id=? AND chunk_version=? AND activation_status='CANDIDATE'",document,version.chunkVersion())!=1)
            throw new IllegalStateException("Inactive chunk version conflict");
        writer.replaceAssets(document,detailId,plan.assets());
        String manifest=manifest(input,plan,document);
        if(db.update("UPDATE law_source_version_candidates SET state='VALIDATED',validation_manifest=?,validated_chunk_version=?,validated_chunk_count=? WHERE candidate_id=? AND state='STAGED' AND input_hash=?",
                manifest,version.chunkVersion(),count,id,input)!=1)throw new IllegalStateException("Candidate validation conflict");
        return new Validation("VALIDATED",document,count,manifest);
    }
    private void lockEpoch() {db.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);}
    Validation recheckForEmbeddings(String id) {
        if(!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Source asset transaction required");
        lockEpoch();
        if(!"VALIDATED".equals(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=? FOR UPDATE",String.class,id)))
            throw new IllegalStateException("Source is not preparable");
        return validateLocked(id);
    }
    private long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private String manifest(String input,SourceVersionTextPlan.Plan plan,long document) {
        var snapshot=new LinkedHashMap<String,Object>();
        for(String table:List.of("law_api_documents","law_api_document_details","law_api_document_chunks","law_api_document_chunk_versions","law_api_assets")) {
            String order=switch(table) {case "law_api_document_details"->"detail_id";case "law_api_document_chunks"->"chunk_id";case "law_api_document_chunk_versions"->"chunk_version";case "law_api_assets"->"asset_id";default->"document_id";};
            var rows=db.queryForList("SELECT * FROM "+table+" WHERE document_id=? ORDER BY "+order+" LIMIT 10001",document);
            if(rows.size()>10000)throw new IllegalStateException("Source membership bound exceeded");
            snapshot.put(table,rows.stream().map(row->{var values=new TreeMap<String,String>();row.forEach((k,v)->values.put(k,v==null?null:v.toString()));return values;}).toList());
        }
        return LawHashUtils.sha256Json(List.of(input,plan.manifest(),snapshot));
    }
    private static final class TextRejection extends RuntimeException {
        final String inputHash;
        TextRejection(String inputHash,RuntimeException cause) {super(cause);this.inputHash=inputHash;}
    }
}
