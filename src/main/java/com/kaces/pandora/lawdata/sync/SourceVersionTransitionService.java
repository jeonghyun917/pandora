package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.version.*;
import com.kaces.pandora.rag.recovery.*;
import com.kaces.pandora.semantic.lexical.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Server-owned asset reservations; external I/O precedes the exclusive, DB-only CAS. */
public final class SourceVersionTransitionService {
    public record Transition(String id,String kind,String state,String groupKey,long targetDocumentId,long expectedRevision,
            String manifest,String corpusFingerprint,String lexicalFingerprint) { }
    public record Active(String groupKey,long documentId,long revision) { }
    private record Proof(String group,String kind,String candidate,long target,String manifest,long active,long revision,
            long sourceRevision,long ragRevision,long corpusRevision,String routing,String corpus,String config,String reference,
            String vector,String baselineVector) { }
    private record Snapshot(Proof proof,RecoveryCorpus corpus) { }
    private final JdbcTemplate db;
    private final SourceVersionCandidateStore store;
    private final SourceVersionPreparationService preparation;
    private final SourceVersionVectorPreparation vectors;
    private final LawChunkMapper chunks;
    private final SourceVersionCorpusService sources;
    private final RagRecoveryMapper recovery;
    private final RagRecoveryRoutingService routing;
    private final SemanticLexicalMapper lexical;
    private final SemanticLexicalIndexService index;
    private final TransactionTemplate tx;
    private final String product,config;
    public SourceVersionTransitionService(JdbcTemplate db,SourceVersionCandidateStore store,SourceVersionPreparationService preparation,
            SourceVersionVectorPreparation vectors,LawChunkMapper chunks,SourceVersionCorpusService sources,RagRecoveryMapper recovery,
            RagRecoveryRoutingService routing,SemanticLexicalMapper lexical,SemanticLexicalIndexService index,
            PlatformTransactionManager manager,String product,String config) {
        if(product==null || !product.matches("[0-9a-f]{40}") || !hash(config))throw new IllegalArgumentException("Server product/config identity required");
        this.db=db;this.store=store;this.preparation=preparation;this.vectors=vectors;this.chunks=chunks;this.sources=sources;
        this.recovery=recovery;this.routing=routing;this.lexical=lexical;this.index=index;this.product=product;this.config=config;
        tx=new TransactionTemplate(manager);tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);tx.setTimeout(30);
    }
    public Transition prepare(String candidate) {
        uuid(candidate);
        var c=db.queryForMap("SELECT * FROM law_source_version_candidates WHERE candidate_id=?",candidate);
        if(!product.equals(c.get("product_sha")) || !Set.of("VALIDATED","INDEX_READY").contains(c.get("state")))throw new IllegalStateException("Current-product validated candidate required");
        // New references need a genuine fidelity validator; absence of one is not permission to omit them.
        var source="VALIDATED".equals(c.get("state"))?preparation.snapshotForAssets(candidate):preparation.snapshotForRegisteredAssets(candidate);
        requireReferences(source);
        var verified="VALIDATED".equals(c.get("state"))?vectors.prepare(candidate):vectors.verifyPrepared(candidate);
        var first=tx.execute(status->capture("PROMOTE",(String)c.get("group_key"),number(c,"baseline_revision"),number(c,"candidate_document_id"),candidate,verified.vectorFingerprint(),verified.baselineFingerprint()));
        return prepareAssets(first);
    }
    public Transition prepareRollback(String group,long revision,long document) {
        group(group);if(revision<1 || document<=0)throw new IllegalArgumentException("Exact registered rollback required");
        var g=db.queryForMap("SELECT * FROM law_source_version_groups WHERE group_key=?",group);
        if(number(g,"revision")!=revision || number(g,"active_document_id")==document)throw new IllegalStateException("Stale rollback request");
        var owned=db.queryForList("SELECT source_candidate_id FROM law_source_version_history WHERE group_key=? AND document_id=?",group,document);
        if(owned.size()!=1)throw new IllegalStateException("Only registered history can be restored");
        String target=vectors.verifyPreserved(group,document),active=vectors.verifyPreserved(group,number(g,"active_document_id"));
        return prepareAssets(tx.execute(status->capture("ROLLBACK",group,revision,document,(String)owned.get(0).get("source_candidate_id"),target,active)));
    }
    private Transition prepareAssets(Snapshot first) {
        var intent=tx.execute(status->{
            var current=recapture(first.proof());requireSame(first,current);
            String input=input(current.proof());var found=db.queryForList("SELECT * FROM law_source_version_transitions WHERE input_hash=? FOR UPDATE",input);
            if(!found.isEmpty())return checkedTransition(found.get(0),current.proof());
            String id=UUID.randomUUID().toString();var p=current.proof();
            db.update("INSERT INTO law_source_version_transitions(transition_id,input_hash,group_key,kind,source_candidate_id,target_document_id,manifest,expected_active_document_id,expected_group_revision,base_source_revision,base_rag_revision,base_corpus_revision,routing_identity,corpus_fingerprint,config_fingerprint,reference_fingerprint,vector_fingerprint,baseline_vector_fingerprint,state) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'PREPARING')",
                id,input,p.group(),p.kind(),p.candidate(),p.target(),p.manifest(),p.active(),p.revision(),p.sourceRevision(),p.ragRevision(),p.corpusRevision(),p.routing(),p.corpus(),p.config(),p.reference(),p.vector(),p.baselineVector());
            return read(id);
        });
        if(!Set.of("PREPARING","READY").contains(intent.state()))throw new IllegalStateException("Transition is not preparable");
        if(lexical.findRecoveryState("recovery_"+intent.id())==null)index.rebuildRecovery(intent.id(),first.corpus().chunks());
        String fingerprint=index.verifyRecovery(intent.id(),first.corpus().chunks());
        verifyExternal(first.proof());
        return tx.execute(status->{
            var current=recapture(first.proof());requireSame(first,current);
            var row=db.queryForMap("SELECT * FROM law_source_version_transitions WHERE transition_id=? FOR UPDATE",intent.id());
            var saved=checkedTransition(row,current.proof());
            if("READY".equals(saved.state())) {
                if(!fingerprint.equals(saved.lexicalFingerprint()))throw new IllegalStateException("Transition lexical receipt changed");
                return saved;
            }
            if(!"PREPARING".equals(saved.state()) || db.update("UPDATE law_source_version_transitions SET lexical_fingerprint=?,state='READY' WHERE transition_id=? AND state='PREPARING'",fingerprint,intent.id())!=1)
                throw new IllegalStateException("Transition readiness conflict");
            if("PROMOTE".equals(current.proof().kind())) {
                String candidate=current.proof().candidate();
                int changed=db.update("UPDATE law_source_version_candidates SET state='INDEX_READY',prepared_id=? WHERE candidate_id=? AND state IN ('VALIDATED','INDEX_READY') AND product_sha=?",intent.id(),candidate,product);
                if(changed!=1)throw new IllegalStateException("Source readiness state conflict");
                store.registerPreserved(current.proof().group(),current.proof().target(),candidate);
            }
            return read(intent.id());
        });
    }
    public Active promote(String id,long revision) {return apply(id,revision,"PROMOTE");}
    public Active rollback(String id,long revision) {return apply(id,revision,"ROLLBACK");}
    private Active apply(String id,long revision,String kind) {
        uuid(id);if(revision<0 || revision==Long.MAX_VALUE)throw new IllegalArgumentException("Exact transition revision required");
        var row=db.queryForMap("SELECT * FROM law_source_version_transitions WHERE transition_id=?",id);
        if(!kind.equals(row.get("kind")) || !"READY".equals(row.get("state")) || number(row,"expected_group_revision")!=revision)
            throw new IllegalStateException("Ready transition/revision required");
        var expected=proof(row);
        var before=tx.execute(status->recapture(expected));
        if(!expected.equals(before.proof()) || !input(expected).equals(row.get("input_hash")))throw new IllegalStateException("Transition identity changed");
        verifyExternal(expected);
        String fingerprint=index.verifyRecovery(id,before.corpus().chunks());
        if(!fingerprint.equals(row.get("lexical_fingerprint")))throw new IllegalStateException("Transition lexical receipt changed");
        verifyExternal(expected);
        return tx.execute(status->{
            // Shared request leases must drain before any source/routing lock or visible write.
            db.queryForObject("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
            var current=recapture(expected);requireSame(before,current);
            var saved=db.queryForMap("SELECT * FROM law_source_version_transitions WHERE transition_id=? FOR UPDATE",id);
            checkedTransition(saved,current.proof());
            if(!"READY".equals(saved.get("state")) || !fingerprint.equals(saved.get("lexical_fingerprint")))throw new IllegalStateException("Transition CAS receipt changed");
            var lexicalState=lexical.findRecoveryState("recovery_"+id);
            if(lexicalState==null || !fingerprint.equals(lexicalState.contentFingerprint()))throw new IllegalStateException("Transition lexical namespace missing");
            var active=db.queryForList("SELECT candidate_id FROM law_source_version_candidates WHERE group_key=? AND candidate_document_id=? AND state='ACTIVE' FOR UPDATE",expected.group(),expected.active());
            if(active.size()>1)throw new IllegalStateException("Ambiguous active source");
            if(!active.isEmpty() && db.update("UPDATE law_source_version_candidates SET state='RETIRED' WHERE candidate_id=? AND state='ACTIVE'",active.get(0).get("candidate_id"))!=1)
                throw new IllegalStateException("Source retirement conflict");
            if(expected.candidate()!=null && db.update("UPDATE law_source_version_candidates SET state='ACTIVE' WHERE candidate_id=? AND state=?",expected.candidate(),kind.equals("PROMOTE")?"INDEX_READY":"RETIRED")!=1)
                throw new IllegalStateException("Source activation conflict");
            if(db.update("UPDATE law_source_version_groups SET active_document_id=?,revision=revision+1 WHERE group_key=? AND active_document_id=? AND revision=?",expected.target(),expected.group(),expected.active(),expected.revision())!=1)
                throw new IllegalStateException("Source pointer CAS failed");
            if(db.update("UPDATE law_api_documents SET effective_status='PAST' WHERE document_id=? AND use_yn='Y' AND effective_status='CURRENT'",expected.active())!=1
                    || db.update("UPDATE law_api_documents SET use_yn='Y',effective_status='CURRENT' WHERE document_id=? AND effective_status='PAST'",expected.target())!=1)
                throw new IllegalStateException("Source visibility CAS failed");
            db.update("UPDATE law_source_version_groups SET baseline_hash=? WHERE group_key=?",store.snapshotHash(expected.target()),expected.group());
            if(recovery.markCorpusChanged()!=1 || recovery.casCorpusEpoch(expected.ragRevision(),expected.corpusRevision()+1,id,fingerprint)!=1
                    || db.update("UPDATE law_source_version_visibility_epoch SET revision=revision+1 WHERE singleton_id=1 AND revision=?",expected.sourceRevision())!=1
                    || db.update("UPDATE law_source_version_transitions SET state='APPLIED' WHERE transition_id=? AND state='READY'",id)!=1)
                throw new IllegalStateException("Source/lexical epoch CAS failed");
            store.recheckPreserved(expected.group());routing.captureInTransaction();
            return new Active(expected.group(),expected.target(),expected.revision()+1);
        });
    }
    private Snapshot recapture(Proof p) {return capture(p.kind(),p.group(),p.revision(),p.target(),p.candidate(),p.vector(),p.baselineVector());}
    private Snapshot capture(String kind,String group,long revision,long target,String candidate,String vector,String baselineVector) {
        sources.lockSourceEpoch();var epoch=recovery.lockRoutingEpoch();
        if(epoch==null)throw new IllegalStateException("Routing epoch missing");
        var captured=routing.captureInTransaction();
        var g=db.queryForMap("SELECT * FROM law_source_version_groups WHERE group_key=? FOR UPDATE",group);
        long active=number(g,"active_document_id");
        if(number(g,"revision")!=revision || active==target || !store.snapshotHash(active).equals(g.get("baseline_hash")))throw new IllegalStateException("Stale source pointer/content");
        store.recheckPreserved(group);
        List<LawSemanticChunkRow> rows;String manifest,reference;
        if(kind.equals("PROMOTE")) {
            var c=db.queryForMap("SELECT * FROM law_source_version_candidates WHERE candidate_id=? FOR UPDATE",candidate);
            if(!group.equals(c.get("group_key")) || !product.equals(c.get("product_sha")) || !Set.of("VALIDATED","INDEX_READY").contains(c.get("state"))
                    || number(c,"candidate_document_id")!=target || number(c,"baseline_document_id")!=active || number(c,"baseline_revision")!=revision
                    || !Objects.equals(c.get("baseline_hash"),g.get("baseline_hash")))throw new IllegalStateException("Forward source identity changed");
            var source=preparation.snapshotForTransition(candidate);requireReferences(source);rows=source.chunks();manifest=source.validation().manifest();
            reference=LawHashUtils.sha256Json(source.sourceAssets());
            var receipt=db.queryForMap("SELECT * FROM law_source_version_vector_receipts WHERE candidate_id=?",candidate);
            if(!manifest.equals(receipt.get("validation_manifest")) || !vector.equals(receipt.get("vector_fingerprint")) || !baselineVector.equals(receipt.get("baseline_vector_fingerprint"))
                    || source.chunks().size()!=number(receipt,"chunk_count"))throw new IllegalStateException("Source vector receipt changed");
        } else {
            var owned=db.queryForMap("SELECT * FROM law_source_version_history WHERE group_key=? AND document_id=? FOR UPDATE",group,target);
            if(!Objects.equals(candidate,owned.get("source_candidate_id")))throw new IllegalStateException("Rollback source ownership changed");
            if(candidate==null) {
                if(target!=number(g,"baseline_document_id"))throw new IllegalStateException("Only the preserved baseline may use legacy assets");
                rows=List.copyOf(chunks.findPreservedSourceVersionChunks(group,target));manifest=(String)owned.get("content_snapshot_hash");
                var known=db.queryForList("SELECT DISTINCT baseline_vector_fingerprint FROM law_source_version_transitions WHERE group_key=? AND expected_active_document_id=? AND kind='PROMOTE' AND state='APPLIED'",group,target);
                if(known.size()!=1 || !vector.equals(known.get(0).get("baseline_vector_fingerprint")))throw new IllegalStateException("Preserved baseline vectors lack applied provenance");
                // Preserved-content hash already binds every original asset row and its immutable metadata.
                reference=LawHashUtils.sha256Json(List.of("preserved-source-assets-v1",manifest));
            } else {
                var source=preparation.snapshotForTransition(candidate);
                if(!"RETIRED".equals(source.validation().state()))throw new IllegalStateException("Registered retired source required");
                requireReferences(source);rows=source.chunks();manifest=source.validation().manifest();reference=LawHashUtils.sha256Json(source.sourceAssets());
                if(!vector.equals(db.queryForObject("SELECT vector_fingerprint FROM law_source_version_vector_receipts WHERE candidate_id=?",String.class,candidate)))throw new IllegalStateException("Restored source vector receipt changed");
            }
        }
        if(rows.isEmpty() || rows.size()>10000 || !hash(vector) || !hash(baselineVector))throw new IllegalStateException("Complete bounded source assets required");
        var current=captured.sources().routes().get(group);
        if(revision>0 && (current==null || current.activeDocumentId()!=active || !current.vectorFingerprint().equals(baselineVector)))throw new IllegalStateException("Active source vector provenance changed");
        var replacements=new TreeMap<Long,List<LexicalChunkDocument>>();
        for(long id:captured.replacedDocumentIds())replacements.put(id,recovery.findGenerationLexicalChunks(captured.documents().get(id).activeGeneration()));
        var corpus=RecoveryCorpus.overlay(epoch.revision(),lexical.findActiveSearchableChunks(),replacements);
        corpus=sources.overlay(corpus,captured.sources());
        var old=new TreeSet<>(db.queryForList("SELECT document_id FROM law_source_version_history WHERE group_key=? ORDER BY document_id LIMIT 10001",Long.class,group));old.add(active);old.remove(target);
        var proposed=rows.stream().filter(row->"PASS".equals(row.qualityStatus())).map(row->new LexicalChunkDocument(row.target(),row.chunkId(),row.documentId(),row.parentKey(),row.contentHash(),row.title(),row.parentSectionTitle(),row.chunkTitle(),row.chunkText())).toList();
        corpus=SourceVersionCorpus.overlayCurrent(epoch.revision(),corpus.chunks(),List.of(new SourceVersionCorpus.Replacement((String)g.get("target"),(String)g.get("canonical_key"),old,target,proposed)));
        Long corpusRevision=recovery.findCorpusRevision();if(corpusRevision==null || corpusRevision<0 || corpusRevision==Long.MAX_VALUE || epoch.revision()==Long.MAX_VALUE || captured.sources().revision()==Long.MAX_VALUE)
            throw new IllegalStateException("Corpus revision unavailable");
        return new Snapshot(new Proof(group,kind,candidate,target,manifest,active,revision,captured.sources().revision(),epoch.revision(),corpusRevision,captured.cacheIdentity(),corpus.fingerprint(),config,reference,vector,baselineVector),corpus);
    }
    private void verifyExternal(Proof p) {
        // VALIDATED new source is not registered until readiness, but its exact vector receipt already exists.
        String target=p.kind().equals("PROMOTE")?vectors.verifyPrepared(p.candidate()).vectorFingerprint():vectors.verifyPreserved(p.group(),p.target());
        String active=vectors.verifyPreserved(p.group(),p.active());
        if(!p.vector().equals(target) || !p.baselineVector().equals(active))throw new IllegalStateException("External source assets changed");
    }
    private void requireReferences(SourceVersionPreparationService.AssetSnapshot source) {
        if(!source.sourceAssets().isEmpty())throw new IllegalStateException("Required source reference fidelity proof is unavailable; source remains inactive");
    }
    private String input(Proof proof) {return LawHashUtils.sha256Json(List.of(product,proof));}
    private void requireSame(Snapshot expected,Snapshot actual) {if(!expected.equals(actual))throw new IllegalStateException("Source/corpus changed during preparation");}
    private Transition checkedTransition(Map<String,Object> row,Proof expected) {
        if(!expected.equals(proof(row)) || !input(expected).equals(row.get("input_hash")))throw new IllegalStateException("Transition provenance mismatch");
        return transition(row);
    }
    private Transition read(String id) {return transition(db.queryForMap("SELECT * FROM law_source_version_transitions WHERE transition_id=?",id));}
    private Transition transition(Map<String,Object> r) {return new Transition((String)r.get("transition_id"),(String)r.get("kind"),(String)r.get("state"),(String)r.get("group_key"),number(r,"target_document_id"),number(r,"expected_group_revision"),(String)r.get("manifest"),(String)r.get("corpus_fingerprint"),(String)r.get("lexical_fingerprint"));}
    private Proof proof(Map<String,Object> r) {return new Proof((String)r.get("group_key"),(String)r.get("kind"),(String)r.get("source_candidate_id"),number(r,"target_document_id"),(String)r.get("manifest"),number(r,"expected_active_document_id"),number(r,"expected_group_revision"),number(r,"base_source_revision"),number(r,"base_rag_revision"),number(r,"base_corpus_revision"),(String)r.get("routing_identity"),(String)r.get("corpus_fingerprint"),(String)r.get("config_fingerprint"),(String)r.get("reference_fingerprint"),(String)r.get("vector_fingerprint"),(String)r.get("baseline_vector_fingerprint"));}
    private static long number(Map<String,Object> row,String key) {return ((Number)row.get(key)).longValue();}
    private static boolean hash(String value) {return value!=null && value.matches("[0-9a-f]{64}");}
    private static void group(String value) {if(!hash(value))throw new IllegalArgumentException("Source group required");}
    private static void uuid(String value) {if(value==null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Source transition UUID required");}
}
