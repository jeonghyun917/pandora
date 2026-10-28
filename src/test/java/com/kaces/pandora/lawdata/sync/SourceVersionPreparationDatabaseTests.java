package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.*;
import com.kaces.pandora.lawdata.version.*;
import com.kaces.pandora.lawdata.persistence.*;
import com.kaces.pandora.common.json.LawJsonWriter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="PANDORA_SOURCE_VERSION_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_source_version_test(?:[?].*)?")
class SourceVersionPreparationDatabaseTests {
    private JdbcTemplate db;
    private SourceVersionCandidateStore store;
    private SourceVersionPreparationService preparation;
    private SourceVersionEmbeddingReceipts receipts;
    private org.springframework.transaction.PlatformTransactionManager manager;
    private LawChunkMapper chunkMapper;
    private com.kaces.pandora.rag.recovery.RagRecoveryMapper recoveryMapper;
    private com.kaces.pandora.semantic.lexical.SemanticLexicalMapper lexicalMapper;
    private SourceVersionVectorPreparation testVectors;
    private long baseline;
    private static final String SHA="c".repeat(40),TITLE="원문 준비 테스트 지침",KEY="admrul:원문준비테스트지침";
    @BeforeEach void setup() throws Exception {
        var ds=new org.mariadb.jdbc.MariaDbDataSource();ds.setUrl(System.getenv("PANDORA_SOURCE_VERSION_TEST_URL"));
        ds.setUser("root");ds.setPassword(System.getenv("PANDORA_SOURCE_VERSION_TEST_PASSWORD"));db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_source_version_test");
        var path=Path.of(db.queryForObject("SELECT @@datadir",String.class)).toRealPath();
        assertThat(path).isEqualTo(Path.of(System.getenv("PANDORA_SOURCE_VERSION_TEST_DATADIR")).toRealPath());
        assertThat(path.getFileName().toString()).startsWith("pandora-import-tx-test-source-");
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(ds);
        db.update("DELETE FROM law_source_version_transitions");db.update("DELETE FROM law_source_version_vector_receipts");db.update("DELETE FROM law_source_version_history");db.update("DELETE FROM law_source_version_embedding_receipts");db.update("DELETE FROM law_source_version_candidates");db.update("DELETE FROM law_source_version_groups");
        db.update("UPDATE law_source_version_visibility_epoch SET revision=0 WHERE singleton_id=1");
        db.update("DELETE FROM law_api_documents WHERE external_id IN ('source-prep-test-old','source-prep-test-new','source-prep-test-next')");
        db.update("INSERT INTO law_api_documents(target,external_id,title,source_date,canonical_key,effective_date,effective_status,raw_json,content_hash) VALUES('admrul','source-prep-test-old',?,'20250101',?,'20250101','CURRENT','{}',?)",TITLE,KEY,"a".repeat(64));
        baseline=db.queryForObject("SELECT document_id FROM law_api_documents WHERE external_id='source-prep-test-old'",Long.class);
        db.update("INSERT INTO law_api_document_chunks(document_id,chunk_type,chunk_text,embedding_text,content_hash) VALUES(?,'article','보존할 기존 본문','보존할 기존 본문',?)",baseline,"e".repeat(64));
        manager=new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds);
        store=new SourceVersionCandidateStore(db,manager);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/law/LawChunkMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/law/LawDetailMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/law/LawAssetMapper.xml"),
            new org.springframework.core.io.ClassPathResource("mapper/law/RagRecoveryMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/law/SemanticLexicalMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());var chunks=session.getMapper(LawChunkMapper.class);
        chunkMapper=chunks;
        recoveryMapper=session.getMapper(com.kaces.pandora.rag.recovery.RagRecoveryMapper.class);
        lexicalMapper=session.getMapper(com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.class);
        var json=new ObjectMapper();
        var writer=new LawDocumentWriter(null,session.getMapper(LawDetailMapper.class),chunks,session.getMapper(LawAssetMapper.class),new LawJsonWriter(json),null,null,null);
        preparation=new SourceVersionPreparationService(db,store,writer,chunks,new LawOpenApiPayloadParser(json,new LawJsonWriter(json)),manager,SHA);
        receipts=new SourceVersionEmbeddingReceipts(db,preparation,manager,"source-test-embedding",3);
        new SourceVersionSchemaMaintenance(db).run(null);
        new com.kaces.pandora.semantic.lexical.SemanticLexicalSchemaMaintenance(db).run(null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_recovery_pointers",Integer.class)).isZero();
        db.update("UPDATE rag_recovery_routing_epoch SET revision=0,lexical_generation=NULL,lexical_fingerprint=NULL,corpus_revision=0,published_corpus_revision=0 WHERE singleton_id=1");
    }
    @AfterEach void cleanupTriggers() {
        if(db==null)return;
        for(String name:db.queryForList("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND (TRIGGER_NAME LIKE 'srcver\\_%' OR TRIGGER_NAME='fail_source_preparation')",String.class)) {
            if(!name.matches("srcver_[a-z_]+|fail_source_preparation"))throw new IllegalStateException("Unexpected isolated trigger");db.execute("DROP TRIGGER "+name);
        }
    }
    private SearchDocument source() {return new SearchDocument("admrul","source-prep-test-new",TITLE,"","","20261019",KEY,"20261019","CURRENT","/DRF/lawService.do?target=admrul&ID=source-prep-test-new&type=JSON","{}");}
    private String raw() {return "{\"AdmRulService\":{\"조문내용\":\"제1조(시정) 협의기관의 장은 사전협의 미이행 시 신청기관에 시정을 요구할 수 있다.\"}}";}
    @Test void validatesOnlyIntoANewInactiveDocumentAndPreservesBaseline() {
        var before=db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline);
        var candidate=store.stage(baseline,0,source(),raw(),SHA);
        var result=preparation.validate(candidate.id());
        assertThat(result.state()).isEqualTo("VALIDATED");assertThat(result.documentId()).isNotEqualTo(baseline);assertThat(result.chunkCount()).isPositive();
        assertThat(result.manifest()).matches("[0-9a-f]{64}");
        assertThat(db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT use_yn FROM law_api_documents WHERE document_id=?",String.class,result.documentId())).isEqualTo("N");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,result.documentId())).isEqualTo("PAST");
        assertThat(db.queryForObject("SELECT chunk_text FROM law_api_document_chunks WHERE document_id=?",String.class,result.documentId())).contains("사전협의 미이행 시","요구할 수 있다");
        assertThat(preparation.validate(candidate.id())).isEqualTo(result);
        var snapshot=preparation.snapshotForAssets(candidate.id());
        assertThat(snapshot.chunks()).extracting(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::documentId).containsOnly(result.documentId());
        assertThat(snapshot.chunks()).extracting(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::target).containsOnly("admrul");
        assertThat(snapshot.chunks()).extracting(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::effectiveStatus).containsOnly("PAST");
        assertThat(snapshot.validation()).isEqualTo(result);
    }
    @Test void lostSourceStructureIsTerminallyRejectedWithoutLeavingPartialDocument() {
        String raw="{\"AdmRulService\":{\"조문내용\":[\"제1장 총칙\",\"제1조(시정) 담당청은 기준을 적용하여야 한다.\"]}}";
        var candidate=store.stage(baseline,0,source(),raw,SHA);
        assertThatThrownBy(()->preparation.validate(candidate.id())).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("REJECTED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_api_documents WHERE external_id='source-prep-test-new'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
    }
    @Test void databaseFailureRollsBackInactiveDocumentAndKeepsCandidateUnvalidated() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);
        db.execute("CREATE TRIGGER fail_source_preparation BEFORE INSERT ON law_api_document_chunks FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected source preparation failure'");
        assertThatThrownBy(()->preparation.validate(candidate.id())).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT candidate_document_id FROM law_source_version_candidates WHERE candidate_id=?",Long.class,candidate.id())).isNull();
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("STAGED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_api_documents WHERE external_id='source-prep-test-new'",Integer.class)).isZero();
    }
    @Test void changedPayloadCannotReuseAValidatedManifest() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);preparation.validate(candidate.id());
        db.update("UPDATE law_source_version_candidates SET raw_detail_json='{}' WHERE candidate_id=?",candidate.id());
        assertThatThrownBy(()->preparation.validate(candidate.id())).isInstanceOf(IllegalStateException.class);
    }
    @Test void candidateIdentityDriftIsRejectedBeforeAnyDocumentIsCreated() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);
        db.update("UPDATE law_source_version_candidates SET effective_date='20261020' WHERE candidate_id=?",candidate.id());
        assertThatThrownBy(()->preparation.validate(candidate.id())).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT candidate_document_id FROM law_source_version_candidates WHERE candidate_id=?",Long.class,candidate.id())).isNull();
    }
    @Test void constructedSourceIsImmutableAndCannotBeAutoSelectedByLegacyRefresh() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var result=preparation.validate(candidate.id());
        assertThatThrownBy(()->db.update("UPDATE law_api_document_chunks SET chunk_text='changed' WHERE document_id=?",result.documentId())).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->db.update("UPDATE law_api_documents SET use_yn='Y',effective_status='CURRENT' WHERE document_id=?",result.documentId())).isInstanceOf(RuntimeException.class);
        new LawVersionStatusService(db,false).refreshAllStatuses();
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
        assertThat(preparation.validate(candidate.id())).isEqualTo(result);
    }
    @Test void embeddingReceiptsAreCompleteDurableAndImmutable() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var result=preparation.validate(candidate.id());
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,result.documentId());
        var vector=Map.of(chunk,List.of(0.25,0.5,0.75));
        receipts.save(candidate.id(),vector);receipts.save(candidate.id(),vector);
        assertThat(receipts.load(candidate.id())).isEqualTo(vector);
        assertThatThrownBy(()->receipts.save(candidate.id(),Map.of(chunk,List.of(0.5,0.5,0.75)))).isInstanceOf(IllegalStateException.class);
        assertThat(receipts.load(candidate.id())).isEqualTo(vector);
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("VALIDATED");
    }
    @Test void malformedEmbeddingCannotCreatePartialReceipts() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var result=preparation.validate(candidate.id());
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,result.documentId());
        for(var vector:List.of(List.of(0.0,0.0,0.0),List.of(1.0,2.0),List.of(Double.NaN,1.0,2.0)))
            assertThatThrownBy(()->receipts.save(candidate.id(),Map.of(chunk,vector))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->receipts.save(candidate.id(),Map.of(chunk+1,List.of(1.0,2.0,3.0)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_embedding_receipts",Integer.class)).isZero();
    }
    @Test void receiptReadbackRejectsChangedVectorOrModel() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var result=preparation.validate(candidate.id());
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,result.documentId());
        receipts.save(candidate.id(),Map.of(chunk,List.of(1.0,2.0,3.0)));
        db.update("UPDATE law_source_version_embedding_receipts SET vector_json='[1.0,2.0,4.0]' WHERE candidate_id=?",candidate.id());
        assertThatThrownBy(()->receipts.load(candidate.id())).isInstanceOf(IllegalStateException.class);
    }
    @Test void servingReadLeaseHoldsOffAtomicSwitchAndReleasesExactlyOnce() throws Exception {
        var leases=new SourceVersionReadLeases(db.getDataSource(),2,java.time.Duration.ofMillis(100));
        var first=leases.acquire();var second=leases.acquire();
        var started=new java.util.concurrent.CountDownLatch(1);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var transition=executor.submit(()->{started.countDown();return db.update("UPDATE law_source_version_visibility_epoch SET revision=revision+1 WHERE singleton_id=1");});
        try {
            assertThat(started.await(2,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(()->transition.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            first.close();first.close();
            assertThatThrownBy(()->transition.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            long revision=second.revision();second.close();
            assertThat(transition.get(3,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
            try(var next=leases.acquire()) {assertThat(next.revision()).isEqualTo(revision+1);}
        } finally {first.close();second.close();executor.shutdownNow();}
    }
    @Test void servingReadLeaseRejectsCapacityExhaustionAndRecoversAfterRelease() {
        var leases=new SourceVersionReadLeases(db.getDataSource(),1,java.time.Duration.ofMillis(50));
        try(var first=leases.acquire()) {assertThatThrownBy(leases::acquire).isInstanceOf(IllegalStateException.class);}
        try(var next=leases.acquire()) {assertThat(next.revision()).isNotNegative();}
    }
    @Test void servingInterceptorReusesRequestLeaseAndReleasesAtSyncOrAsyncCompletion() throws Exception {
        var leases=new SourceVersionReadLeases(db.getDataSource(),1,java.time.Duration.ofMillis(50));
        var interceptor=new SourceVersionServingInterceptor(leases);
        var request=new org.springframework.mock.web.MockHttpServletRequest();var response=new org.springframework.mock.web.MockHttpServletResponse();
        assertThat(interceptor.preHandle(request,response,this)).isTrue();
        assertThat(interceptor.preHandle(request,response,this)).isTrue();
        interceptor.afterCompletion(request,response,this,null);
        try(var next=leases.acquire()) {assertThat(next.revision()).isNotNegative();}
        request=new org.springframework.mock.web.MockHttpServletRequest();request.setAsyncSupported(true);
        assertThat(interceptor.preHandle(request,response,this)).isTrue();
        var async=(org.springframework.mock.web.MockAsyncContext)request.startAsync(request,response);
        interceptor.afterConcurrentHandlingStarted(request,response,this);
        assertThatThrownBy(leases::acquire).isInstanceOf(IllegalStateException.class);
        async.complete();interceptor.afterCompletion(request,response,this,null);
        try(var next=leases.acquire()) {assertThat(next.revision()).isNotNegative();}
    }
    @Test void servingTimeoutTerminatesResponseBeforeLeaseReleaseAndCapacityFailsClosed() throws Exception {
        var leases=new SourceVersionReadLeases(db.getDataSource(),1,java.time.Duration.ofMillis(50));
        var interceptor=new SourceVersionServingInterceptor(leases);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAsyncSupported(true);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        assertThat(interceptor.preHandle(request,response,this)).isTrue();
        var denied=new org.springframework.mock.web.MockHttpServletResponse();
        assertThat(interceptor.preHandle(new org.springframework.mock.web.MockHttpServletRequest(),denied,this)).isFalse();
        assertThat(denied.getStatus()).isEqualTo(503);
        var async=(org.springframework.mock.web.MockAsyncContext)request.startAsync(request,response);
        interceptor.afterConcurrentHandlingStarted(request,response,this);
        for(var listener:List.copyOf(async.getListeners()))listener.onTimeout(new jakarta.servlet.AsyncEvent(async,request,response));
        assertThat(response.getStatus()).isEqualTo(503);
        try(var next=leases.acquire()) {assertThat(next.revision()).isNotNegative();}
    }
    @Test void completedAsyncCycleCannotLeakLeaseDuringListenerReattachment() throws Exception {
        var leases=new SourceVersionReadLeases(db.getDataSource(),1,java.time.Duration.ofMillis(50));
        var interceptor=new SourceVersionServingInterceptor(leases);
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setAsyncSupported(true);
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        assertThat(interceptor.preHandle(request,response,this)).isTrue();
        var initial=(org.springframework.mock.web.MockAsyncContext)request.startAsync(request,response);
        interceptor.afterConcurrentHandlingStarted(request,response,this);
        var completed=org.mockito.Mockito.mock(jakarta.servlet.AsyncContext.class);
        org.mockito.Mockito.doThrow(new IllegalStateException("already completed async cycle")).when(completed).addListener(org.mockito.ArgumentMatchers.any(jakarta.servlet.AsyncListener.class));
        for(var listener:initial.getListeners())listener.onStartAsync(new jakarta.servlet.AsyncEvent(completed));
        try(var next=leases.acquire()) {assertThat(next.revision()).isNotNegative();}
        assertThat(interceptor.preHandle(request,response,this)).isFalse();
        assertThat(response.getStatus()).isEqualTo(503);
    }
    @Test void preservedVersionsPermitOnlyControlledVisibilityChangesWithoutContentMutation() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var result=preparation.validate(candidate.id());
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,result.documentId());
        var vector=Map.of(chunk,List.of(1.0,2.0,3.0));receipts.save(candidate.id(),vector);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_history",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->db.update("UPDATE law_api_documents SET use_yn='Y' WHERE document_id=?",result.documentId())).isInstanceOf(RuntimeException.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        tx.executeWithoutResult(status->{
            db.queryForObject("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
            // Characterize native visibility guards, not the external INDEX_READY qualification service.
            db.update("UPDATE law_source_version_candidates SET state='INDEX_READY' WHERE candidate_id=?",candidate.id());
            store.registerPreserved(candidate.groupKey(),result.documentId(),candidate.id());
            assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_history",Integer.class)).isEqualTo(2);
            store.recheckPreserved(candidate.groupKey());
            db.update("UPDATE law_source_version_candidates SET state='ACTIVE' WHERE candidate_id=?",candidate.id());
            db.update("UPDATE law_source_version_groups SET active_document_id=?,revision=revision+1 WHERE group_key=?",result.documentId(),candidate.groupKey());
            db.update("UPDATE law_api_documents SET effective_status='PAST' WHERE document_id=?",baseline);
            db.update("UPDATE law_api_documents SET use_yn='Y',effective_status='CURRENT' WHERE document_id=?",result.documentId());
            db.update("UPDATE law_source_version_visibility_epoch SET revision=revision+1 WHERE singleton_id=1");
            store.recheckPreserved(candidate.groupKey());
        });
        assertThat(db.queryForObject("SELECT use_yn FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("Y");
        assertThat(db.queryForObject("SELECT chunk_text FROM law_api_document_chunks WHERE document_id=?",String.class,result.documentId())).contains("요구할 수 있다");
        var active=preparation.snapshotForRegisteredAssets(candidate.id());
        assertThat(active.validation().state()).isEqualTo("ACTIVE");
        assertThat(active.validation().manifest()).isEqualTo(result.manifest());
        assertThat(active.chunks()).extracting(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::effectiveStatus).containsOnly("CURRENT");
        assertThat(receipts.load(candidate.id())).isEqualTo(vector);
        assertThatThrownBy(()->new SourceVersionRoutingReader(db,manager).capture()).isInstanceOf(IllegalStateException.class).hasMessageContaining("receipt");
        assertThatThrownBy(()->db.update("UPDATE law_source_version_history SET content_snapshot_hash=? WHERE document_id=?","f".repeat(64),baseline)).isInstanceOf(RuntimeException.class);
    }
    @Test void sourceCorpusRebuildKeepsOnlyTheOwnedCurrentVersionAndPreservesRagRows() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var validated=preparation.validate(candidate.id());
        long oldChunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,baseline);
        long newChunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,validated.documentId());
        int version=db.queryForObject("SELECT validated_chunk_version FROM law_source_version_candidates WHERE candidate_id=?",Integer.class,candidate.id());
        var route=new SourceVersionRoutingSnapshot.Route(candidate.groupKey(),1,"admrul",KEY,baseline,validated.documentId(),
            Set.of(baseline,validated.documentId()),candidate.id(),validated.manifest(),"law_source_"+candidate.id().replace("-",""),"1".repeat(64),"2".repeat(64),"test-model",validated.chunkCount(),version);
        var sources=new SourceVersionRoutingSnapshot(1,Map.of(candidate.groupKey(),route));
        var old=new com.kaces.pandora.semantic.lexical.LexicalChunkDocument("admrul",oldChunk,baseline,null,"e".repeat(64),TITLE,"","","보존할 기존 본문");
        var replacement=new com.kaces.pandora.semantic.lexical.LexicalChunkDocument("admrul",newChunk,validated.documentId(),null,"a".repeat(64),TITLE,"","","physical candidate");
        var rag=new com.kaces.pandora.semantic.lexical.LexicalChunkDocument("official_doc",oldChunk,baseline,null,"b".repeat(64),"unrelated RAG","","","RAG body");
        var physical=com.kaces.pandora.rag.recovery.RecoveryCorpus.overlay(1,List.of(old,replacement,rag),Map.of());
        var service=new SourceVersionCorpusService(db,store);
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(()->tx.execute(status->{service.lockSourceEpoch();return service.overlay(physical,sources);})).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status->{
            service.lockSourceEpoch();
            // Native visibility characterization only, not the readiness/promotion API.
            db.update("UPDATE law_source_version_candidates SET state='INDEX_READY' WHERE candidate_id=?",candidate.id());
            store.registerPreserved(candidate.groupKey(),validated.documentId(),candidate.id());
            db.update("UPDATE law_source_version_candidates SET state='ACTIVE' WHERE candidate_id=?",candidate.id());
            db.update("UPDATE law_source_version_groups SET active_document_id=?,revision=1 WHERE group_key=?",validated.documentId(),candidate.groupKey());
            db.update("UPDATE law_api_documents SET effective_status='PAST' WHERE document_id=?",baseline);
            db.update("UPDATE law_api_documents SET use_yn='Y',effective_status='CURRENT' WHERE document_id=?",validated.documentId());
            db.update("UPDATE law_source_version_visibility_epoch SET revision=1 WHERE singleton_id=1");
        });
        var actualHash=db.queryForObject("SELECT content_snapshot_hash FROM law_source_version_history WHERE document_id=?",String.class,validated.documentId());
        var bound=new SourceVersionRoutingSnapshot.Route(route.groupKey(),1,route.target(),route.canonicalKey(),baseline,validated.documentId(),route.historyDocumentIds(),
            route.candidateId(),route.manifest(),route.vectorCollection(),route.vectorFingerprint(),actualHash,route.model(),route.chunkCount(),route.chunkVersion());
        var captured=new SourceVersionRoutingSnapshot(1,Map.of(candidate.groupKey(),bound));
        var result=tx.execute(status->{service.lockSourceEpoch();return service.overlay(physical,captured);});
        assertThat(result.chunks()).hasSize(2).contains(rag);
        assertThat(result.chunks().stream().filter(row->row.target().equals("admrul")).toList()).singleElement().satisfies(row->{
            assertThat(row.documentId()).isEqualTo(validated.documentId());assertThat(row.body()).contains("사전협의 미이행 시");
        });
        assertThatThrownBy(()->service.overlay(physical,captured)).isInstanceOf(IllegalStateException.class);
        tx.executeWithoutResult(status->{service.lockSourceEpoch();assertThatThrownBy(()->service.overlay(physical,sources)).isInstanceOf(IllegalStateException.class);});
    }
    @Test void vectorPreparationPersistsExactReceiptWithoutGrantingIndexReadiness() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);preparation.validate(candidate.id());
        var embedding=org.mockito.Mockito.mock(com.kaces.pandora.infra.openai.OpenAiEmbeddingClient.class);
        var qdrant=org.mockito.Mockito.mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        org.mockito.Mockito.when(embedding.embed(org.mockito.ArgumentMatchers.anyList())).thenAnswer(invocation->{
            if(calls.incrementAndGet()>1)throw new IllegalStateException("duplicate external embedding");return List.of(List.of(1.0,2.0,3.0));
        });
        String collection="law_source_"+candidate.id().replace("-","");
        org.mockito.Mockito.when(qdrant.lawSourceVersionCollection(candidate.id())).thenReturn(collection);
        org.mockito.Mockito.when(qdrant.baselineLawFingerprint(org.mockito.ArgumentMatchers.anyList())).thenReturn("a".repeat(64));
        long chunk=db.queryForObject("SELECT c.chunk_id FROM law_api_document_chunks c JOIN law_source_version_candidates v ON v.candidate_document_id=c.document_id WHERE v.candidate_id=?",Long.class,candidate.id());
        org.mockito.Mockito.when(qdrant.inspectLawSourceVersionPoints(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new com.kaces.pandora.infra.qdrant.QdrantClient.RecoveryPointInspection(null,Set.of(chunk)));
        org.mockito.Mockito.when(qdrant.verifyLawSourceVersionCollection(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList())).thenReturn("d".repeat(64));
        var service=new SourceVersionVectorPreparation(db,preparation,receipts,chunkMapper,embedding,qdrant,manager,"source-test-embedding");
        var receipt=service.prepare(candidate.id());
        assertThat(receipt.vectorCollection()).isEqualTo(collection);assertThat(receipt.chunkCount()).isEqualTo(1);
        assertThat(service.prepare(candidate.id())).isEqualTo(receipt);
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("VALIDATED");
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(status->{
            db.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
            db.update("UPDATE law_source_version_candidates SET state='INDEX_READY' WHERE candidate_id=?",candidate.id());
            store.registerPreserved(candidate.groupKey(),receiptDocument(candidate.id()),candidate.id());
        });
        assertThat(service.verifyPrepared(candidate.id())).isEqualTo(receipt);
        assertThat(service.verifyPreserved(candidate.groupKey(),receiptDocument(candidate.id()))).isEqualTo(receipt.vectorFingerprint());
        assertThat(service.verifyPreserved(candidate.groupKey(),baseline)).isEqualTo(receipt.baselineFingerprint());
        assertThatThrownBy(()->service.prepare(candidate.id())).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verify(embedding,org.mockito.Mockito.times(1)).embed(org.mockito.ArgumentMatchers.anyList());
        org.mockito.Mockito.when(qdrant.baselineLawFingerprint(org.mockito.ArgumentMatchers.anyList())).thenReturn("a".repeat(64),"b".repeat(64));
        assertThat(service.verifyPrepared(candidate.id())).isEqualTo(receipt);
        assertThatThrownBy(()->service.verifyPrepared(candidate.id())).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.when(qdrant.baselineLawFingerprint(org.mockito.ArgumentMatchers.anyList())).thenReturn("a".repeat(64));
        org.mockito.Mockito.when(qdrant.findExistingLawPointIds(org.mockito.ArgumentMatchers.anyList())).thenReturn(Set.of(chunk));
        assertThatThrownBy(()->service.verifyPrepared(candidate.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("shared");
    }
    private long receiptDocument(String candidate) {return db.queryForObject("SELECT candidate_document_id FROM law_source_version_candidates WHERE candidate_id=?",Long.class,candidate);}
    @Test void preparedSourcePromotionAndRollbackKeepThePreservedBaselineAndPublishOneCorpus() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var validated=preparation.validate(candidate.id());
        var old=db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline);
        var service=transitionService();
        var prepared=service.prepare(candidate.id());
        assertThat(prepared.state()).isEqualTo("READY");assertThat(prepared.kind()).isEqualTo("PROMOTE");
        assertThat(db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline)).isEqualTo(old);
        assertThat(db.queryForObject("SELECT use_yn FROM law_api_documents WHERE document_id=?",String.class,validated.documentId())).isEqualTo("N");
        assertThat(new SourceVersionRoutingReader(db,manager).capture().revision()).isZero();
        assertThat(service.prepare(candidate.id())).isEqualTo(prepared);
        var active=service.promote(prepared.id(),0);
        assertThat(active.documentId()).isEqualTo(validated.documentId());assertThat(active.revision()).isEqualTo(1);
        assertThat(new SourceVersionRoutingReader(db,manager).capture().activeVectors()).containsKey(candidate.id());
        assertThat(db.queryForObject("SELECT use_yn FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("Y");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("PAST");
        assertThat(db.queryForObject("SELECT chunk_text FROM law_api_document_chunks WHERE document_id=?",String.class,baseline)).isEqualTo("보존할 기존 본문");
        assertThatThrownBy(()->service.promote(prepared.id(),0)).isInstanceOf(IllegalStateException.class);
        var rollback=service.prepareRollback(candidate.groupKey(),1,baseline);
        assertThat(rollback.kind()).isEqualTo("ROLLBACK");assertThat(rollback.state()).isEqualTo("READY");
        assertThat(service.rollback(rollback.id(),1).documentId()).isEqualTo(baseline);
        var restored=new SourceVersionRoutingReader(db,manager).capture();
        assertThat(restored.revision()).isEqualTo(2);assertThat(restored.activeVectors()).isEmpty();
        assertThat(restored.routes().get(candidate.groupKey()).activeDocumentId()).isEqualTo(baseline);
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("RETIRED");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_history",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_transitions WHERE state='APPLIED'",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_vector_receipts",Integer.class)).isEqualTo(1);
        assertThat(receipts.load(candidate.id())).hasSize(1);
    }
    @Test void followingVersionUsesPreservedIsolatedVectorsAndCanRestoreThePreviousRegisteredVersion() {
        var first=store.stage(baseline,0,source(),raw(),SHA);var validated=preparation.validate(first.id());var service=transitionService();
        service.promote(service.prepare(first.id()).id(),0);
        var nextSource=new SearchDocument("admrul","source-prep-test-next",TITLE,"","","20261020",KEY,"20261020","CURRENT","/DRF/lawService.do?target=admrul&ID=source-prep-test-next&type=JSON","{}");
        var next=store.stage(validated.documentId(),1,nextSource,raw().replace("미이행 시","재협의 미이행 시"),SHA);
        var nextValidation=preparation.validate(next.id());var prepared=service.prepare(next.id());
        assertThat(db.queryForObject("SELECT baseline_vector_fingerprint FROM law_source_version_vector_receipts WHERE candidate_id=?",String.class,next.id())).isEqualTo("d".repeat(64));
        assertThat(service.promote(prepared.id(),1).documentId()).isEqualTo(nextValidation.documentId());
        var rollback=service.prepareRollback(first.groupKey(),2,validated.documentId());
        assertThat(service.rollback(rollback.id(),2).documentId()).isEqualTo(validated.documentId());
        var route=new SourceVersionRoutingReader(db,manager).capture().routes().get(first.groupKey());
        assertThat(route.candidateId()).isEqualTo(first.id());assertThat(route.historyDocumentIds()).hasSize(3);
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,next.id())).isEqualTo("RETIRED");
        assertThat(receipts.load(first.id())).hasSize(1);assertThat(receipts.load(next.id())).hasSize(1);
        assertThatThrownBy(()->service.prepareRollback(first.groupKey(),3,Long.MAX_VALUE)).isInstanceOf(RuntimeException.class);
    }
    @Test void sourcePointerFailureRollsBackCandidateVisibilityAndAllPublishedEpochs() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var validated=preparation.validate(candidate.id());
        var service=transitionService();var prepared=service.prepare(candidate.id());
        var before=db.queryForMap("SELECT * FROM law_source_version_groups WHERE group_key=?",candidate.groupKey());
        db.execute("CREATE TRIGGER fail_source_preparation BEFORE UPDATE ON law_source_version_groups FOR EACH ROW BEGIN IF NEW.revision>OLD.revision THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected source pointer failure'; END IF; END");
        assertThatThrownBy(()->service.promote(prepared.id(),0)).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForMap("SELECT * FROM law_source_version_groups WHERE group_key=?",candidate.groupKey())).isEqualTo(before);
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("INDEX_READY");
        assertThat(db.queryForObject("SELECT use_yn FROM law_api_documents WHERE document_id=?",String.class,validated.documentId())).isEqualTo("N");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
        assertThat(recoveryMapper.findRoutingEpoch().revision()).isZero();
        assertThat(new SourceVersionRoutingReader(db,manager).capture().revision()).isZero();
        assertThat(db.queryForObject("SELECT state FROM law_source_version_transitions WHERE transition_id=?",String.class,prepared.id())).isEqualTo("READY");
    }
    @Test void changedLexicalAssetOrCorpusCannotActivateAPreparedSource() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);preparation.validate(candidate.id());
        var service=transitionService();var prepared=service.prepare(candidate.id());
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        tx.executeWithoutResult(status->{
            db.update("UPDATE semantic_lexical_terms SET term_frequency=term_frequency+1 WHERE index_version=? LIMIT 1","recovery_"+prepared.id());
            assertThatThrownBy(()->service.promote(prepared.id(),0)).isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
        });
        db.update("UPDATE rag_recovery_routing_epoch SET corpus_revision=corpus_revision+1 WHERE singleton_id=1");
        assertThatThrownBy(()->service.promote(prepared.id(),0)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT active_document_id FROM law_source_version_groups WHERE group_key=?",Long.class,candidate.groupKey())).isEqualTo(baseline);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_transitions WHERE state='APPLIED'",Integer.class)).isZero();
    }
    @Test void servingLeaseBlocksTheActualPromotionUntilResponseCompletion() throws Exception {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var validated=preparation.validate(candidate.id());
        var service=transitionService();var prepared=service.prepare(candidate.id());
        var leases=new SourceVersionReadLeases(db.getDataSource(),1,java.time.Duration.ofMillis(100));
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();var lease=leases.acquire();
        try {
            var switched=executor.submit(()->service.promote(prepared.id(),0));
            assertThatThrownBy(()->switched.get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            assertThat(db.queryForObject("SELECT active_document_id FROM law_source_version_groups WHERE group_key=?",Long.class,candidate.groupKey())).isEqualTo(baseline);
            lease.close();
            assertThat(switched.get(5,java.util.concurrent.TimeUnit.SECONDS).documentId()).isEqualTo(validated.documentId());
        } finally {lease.close();executor.shutdownNow();}
    }
    @Test void sourceIndexAuditRequiresFullIsolatedReadbackRatherThanASharedEmbeddingPlaceholder() {
        var candidate=store.stage(baseline,0,source(),raw(),SHA);var validated=preparation.validate(candidate.id());
        var service=transitionService();var prepared=service.prepare(candidate.id());
        var beans=new org.springframework.beans.factory.support.DefaultListableBeanFactory();
        beans.registerSingleton("sourcePreparation",preparation);beans.registerSingleton("sourceVectors",testVectors);
        var verifier=new SourceVersionIntegrityVerifier(db,beans.getBeanProvider(SourceVersionPreparationService.class),beans.getBeanProvider(SourceVersionVectorPreparation.class));
        var q=org.mockito.Mockito.mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
        var auditProperties=new com.kaces.pandora.semantic.config.LawAiProperties(new com.kaces.pandora.semantic.config.LawAiProperties.OpenAi("","source-test-embedding","gpt-5-mini","low","low",100),new com.kaces.pandora.semantic.config.LawAiProperties.Qdrant("http://127.0.0.1:1","law_shared","rag",3),null,null);
        var audit=new com.kaces.pandora.semantic.integrity.LawIndexIntegrityService(chunkMapper,q,auditProperties);
        audit.configureSourceVersionIntegrity(verifier);
        var ids=chunkMapper.findChunkIdsByDocumentId(validated.documentId());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_api_chunk_embeddings e JOIN law_api_document_chunks c ON c.chunk_id=e.chunk_id WHERE c.document_id=?",Integer.class,validated.documentId())).isZero();
        var inactive=audit.auditByChunkIds("admrul",ids);
        assertThat(inactive.scannedRows()).isEqualTo(validated.chunkCount());assertThat(inactive.issues()).isEmpty();
        assertThat(new SourceVersionRoutingReader(db,manager).capture().revision()).isZero();
        service.promote(prepared.id(),0);
        var report=audit.auditByChunkIds("admrul",ids);
        assertThat(report.scannedRows()).isEqualTo(validated.chunkCount());assertThat(report.issues()).isEmpty();
        db.update("UPDATE law_source_version_vector_receipts SET vector_fingerprint=? WHERE candidate_id=?","f".repeat(64),candidate.id());
        assertThatThrownBy(()->audit.auditByChunkIds("admrul",ids)).isInstanceOf(IllegalStateException.class);
    }
    private SourceVersionTransitionService transitionService() {
        var embedding=org.mockito.Mockito.mock(com.kaces.pandora.infra.openai.OpenAiEmbeddingClient.class);
        var q=org.mockito.Mockito.mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
        org.mockito.Mockito.when(embedding.embed(org.mockito.ArgumentMatchers.anyList())).thenReturn(List.of(List.of(1.0,2.0,3.0)));
        org.mockito.Mockito.when(q.lawSourceVersionCollection(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->"law_source_"+call.<String>getArgument(0).replace("-",""));
        org.mockito.Mockito.when(q.baselineLawFingerprint(org.mockito.ArgumentMatchers.anyList())).thenReturn("a".repeat(64));
        org.mockito.Mockito.when(q.inspectLawSourceVersionPoints(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new com.kaces.pandora.infra.qdrant.QdrantClient.RecoveryPointInspection(null,Set.of()));
        org.mockito.Mockito.when(q.verifyLawSourceVersionCollection(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList())).thenReturn("d".repeat(64));
        var vectors=new SourceVersionVectorPreparation(db,preparation,receipts,chunkMapper,embedding,q,manager,"source-test-embedding");
        testVectors=vectors;
        var routing=new com.kaces.pandora.rag.recovery.RagRecoveryRoutingService(recoveryMapper,lexicalMapper,manager);
        routing.configureSourceVersionRouting(new SourceVersionRoutingReader(db,manager));
        var index=new com.kaces.pandora.semantic.lexical.SemanticLexicalIndexService(lexicalMapper,new com.kaces.pandora.semantic.lexical.KoreanLexicalTokenizer());
        return new SourceVersionTransitionService(db,store,preparation,vectors,chunkMapper,new SourceVersionCorpusService(db,store),recoveryMapper,routing,lexicalMapper,index,manager,SHA,"c".repeat(64));
    }
    @Test void corpusCaptureWaitsForVisibilityBeforeTakingTheRegistrationEpoch() throws Exception {
        var sources=new SourceVersionCorpusService(db,store);
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var future=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<Boolean>>();
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        try {
            tx.executeWithoutResult(status->{
                db.queryForObject("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
                future.set(executor.submit(()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(inner->{sources.lockSourceEpoch();return true;})));
                assertThatThrownBy(()->future.get().get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                var independent=new org.springframework.transaction.support.TransactionTemplate(manager);
                independent.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                independent.executeWithoutResult(inner->db.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class));
            });
            assertThat(future.get().get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        } finally {executor.shutdownNow();}
    }
    @Test void normalIndexQueuesCannotReenqueueManagedSourceAssets() {
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,baseline);
        assertThat(chunkMapper.findSemanticIndexCandidatesByDocumentIds("admrul",List.of(baseline),"source-test-embedding","law_shared",10)).hasSize(1);
        assertThat(chunkMapper.findSemanticChunksByIdsForIndexing(List.of(chunk))).hasSize(1);
        store.stage(baseline,0,source(),raw(),SHA);
        assertThat(chunkMapper.findSemanticIndexCandidates("admrul",TITLE,"source-test-embedding","law_shared",10)).isEmpty();
        assertThat(chunkMapper.findSemanticIndexCandidatesByDocumentIds("admrul",List.of(baseline),"source-test-embedding","law_shared",10)).isEmpty();
        assertThat(chunkMapper.findSemanticChunksByIdsForIndexing(List.of(chunk))).isEmpty();
    }
    @Test void requiredSourceReferenceWithoutFidelityProofCannotCreateReadyAssets() {
        String raw=raw().replace("\"AdmRulService\":{","\"AdmRulService\":{\"원문파일\":\"https://www.law.go.kr/required.pdf\",");
        var candidate=store.stage(baseline,0,source(),raw,SHA);var validated=preparation.validate(candidate.id());
        assertThat(preparation.snapshotForAssets(candidate.id()).sourceAssets()).isNotEmpty();
        assertThatThrownBy(()->transitionService().prepare(candidate.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("reference fidelity");
        assertThat(db.queryForObject("SELECT state FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("VALIDATED");
        assertThat(db.queryForObject("SELECT use_yn FROM law_api_documents WHERE document_id=?",String.class,validated.documentId())).isEqualTo("N");
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_embedding_receipts",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_transitions",Integer.class)).isZero();
    }
    @Test void preQWriteGuardRejectsManagedSourceBeforeCallingExternalMutation() {
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,baseline);
        var guard=new SourceVersionWriteGuard(db,manager);var writes=new java.util.concurrent.atomic.AtomicInteger();
        guard.mutatePoints(List.of(chunk),writes::incrementAndGet);
        assertThat(writes).hasValue(1);
        store.stage(baseline,0,source(),raw(),SHA);
        assertThatThrownBy(()->guard.mutatePoints(List.of(chunk,Long.MAX_VALUE),writes::incrementAndGet)).isInstanceOf(IllegalStateException.class);
        assertThat(writes).hasValue(1);
    }
    @Test void inactiveSourcePreparationCannotChangeCapturedRoutingOrCacheIdentity() {
        var reader=new SourceVersionRoutingReader(db,manager);
        var recovery=org.mockito.Mockito.mock(com.kaces.pandora.rag.recovery.RagRecoveryMapper.class);
        var lexical=org.mockito.Mockito.mock(com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.class);
        org.mockito.Mockito.when(recovery.findRoutingEpoch()).thenReturn(new com.kaces.pandora.rag.recovery.RecoveryRoutingEpoch(0,null,null));
        org.mockito.Mockito.when(recovery.listRoutingPointers()).thenReturn(List.of());org.mockito.Mockito.when(recovery.listActiveBaselines()).thenReturn(List.of());
        org.mockito.Mockito.when(lexical.findReadyRevision()).thenReturn("f".repeat(64));
        var router=new com.kaces.pandora.rag.recovery.RagRecoveryRoutingService(recovery,lexical,manager);
        router.configureSourceVersionRouting(reader);
        var answerBefore=router.capture();
        var before=reader.capture();
        var candidate=store.stage(baseline,0,source(),raw(),SHA);preparation.validate(candidate.id());
        assertThat(reader.capture()).isEqualTo(before);
        assertThat(reader.capture().cacheIdentity()).isEqualTo(before.cacheIdentity());
        assertThat(router.capture().cacheIdentity()).isEqualTo(answerBefore.cacheIdentity());
    }
    @Test void preQGuardCannotReadAnOldTransactionSnapshotAfterSourceRegistration() {
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,baseline);
        var guard=new SourceVersionWriteGuard(db,manager);var writes=new java.util.concurrent.atomic.AtomicInteger();
        var transaction=new org.springframework.transaction.support.TransactionTemplate(manager);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.executeWithoutResult(status->{
            assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_groups",Integer.class)).isZero();
            store.stage(baseline,0,source(),raw(),SHA);
            assertThatThrownBy(()->guard.mutatePoints(List.of(chunk),writes::incrementAndGet)).isInstanceOf(IllegalStateException.class);
            status.setRollbackOnly();
        });
        assertThat(writes).hasValue(0);
    }
    @Test void sourceRegistrationWaitsForTheEntireInFlightExternalWrite() throws Exception {
        long chunk=db.queryForObject("SELECT chunk_id FROM law_api_document_chunks WHERE document_id=?",Long.class,baseline);
        var guard=new SourceVersionWriteGuard(db,manager);var writes=new java.util.concurrent.atomic.AtomicInteger();
        var executor=java.util.concurrent.Executors.newSingleThreadExecutor();
        var pending=new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<SourceVersionCandidateStore.StoredCandidate>>();
        try {
            guard.mutatePoints(List.of(chunk),()->{
                pending.set(executor.submit(()->store.stage(baseline,0,source(),raw(),SHA)));
                assertThatThrownBy(()->pending.get().get(150,java.util.concurrent.TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
                writes.incrementAndGet();
            });
            assertThat(pending.get().get(3,java.util.concurrent.TimeUnit.SECONDS).state()).isEqualTo("STAGED");
            assertThatThrownBy(()->guard.mutatePoints(List.of(chunk),writes::incrementAndGet)).isInstanceOf(IllegalStateException.class);
            assertThat(writes).hasValue(1);
        } finally {executor.shutdownNow();}
    }
}
