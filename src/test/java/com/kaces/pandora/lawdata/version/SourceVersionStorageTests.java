package com.kaces.pandora.lawdata.version;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kaces.pandora.lawdata.sync.SearchDocument;

@EnabledIfEnvironmentVariable(named="PANDORA_SOURCE_VERSION_TEST_URL",matches=".+")
class SourceVersionStorageTests {
    private JdbcTemplate db;
    private SourceVersionCandidateStore store;
    private long baseline;
    private static final String SHA="c".repeat(40);
    @BeforeEach void setup() throws Exception {
        var ds=new org.mariadb.jdbc.MariaDbDataSource();ds.setUrl(System.getenv("PANDORA_SOURCE_VERSION_TEST_URL"));
        ds.setUser("root");ds.setPassword(System.getenv("PANDORA_SOURCE_VERSION_TEST_PASSWORD"));
        db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_source_version_test");
        assertThat(Path.of(db.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(Path.of(System.getenv("PANDORA_SOURCE_VERSION_TEST_DATADIR")).toRealPath());
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(ds);
        db.update("DELETE FROM law_source_version_vector_receipts");db.update("DELETE FROM law_source_version_history");db.update("DELETE FROM law_source_version_embedding_receipts");db.update("DELETE FROM law_source_version_candidates");db.update("DELETE FROM law_source_version_groups");
        db.update("DELETE FROM law_api_documents WHERE external_id IN ('source-test-old','source-test-new')");
        db.update("INSERT INTO law_api_documents(target,external_id,title,source_date,canonical_key,effective_date,effective_status,raw_json,content_hash) VALUES('admrul','source-test-old','원문 버전 테스트 지침','20250101','admrul:원문버전테스트지침','20250101','CURRENT','{}',?)","a".repeat(64));
        baseline=db.queryForObject("SELECT document_id FROM law_api_documents WHERE external_id='source-test-old'",Long.class);
        store=new SourceVersionCandidateStore(db,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
    }
    private SearchDocument source() {return new SearchDocument("admrul","source-test-new","원문 버전 테스트 지침","","","20261019","admrul:원문버전테스트지침","20261019","CURRENT","/DRF/lawService.do?target=admrul&MST=source-test-new&type=JSON","{}");}
    @AfterEach void removeIsolatedTriggers() {
        if(db==null)return;
        for(String name:db.queryForList("SELECT TRIGGER_NAME FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME LIKE 'srcver\\_%'",String.class)) {
            if(!name.matches("srcver_[a-z_]+"))throw new IllegalStateException("Unexpected isolated trigger");
            db.execute("DROP TRIGGER "+name);
        }
    }
    @Test void stagePersistsOnlyInactivePayloadAndPreservesCurrentDocumentByteForByte() {
        var before=db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline);
        var candidate=store.stage(baseline,0,source(),"{\"조문\":\"공식 신규 원문\"}",SHA);
        assertThat(candidate.state()).isEqualTo("STAGED");
        assertThat(db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_api_documents WHERE external_id='source-test-new'",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT active_document_id FROM law_source_version_groups",Long.class)).isEqualTo(baseline);
        assertThat(db.queryForObject("SELECT raw_detail_json FROM law_source_version_candidates WHERE candidate_id=?",String.class,candidate.id())).isEqualTo("{\"조문\":\"공식 신규 원문\"}");
    }
    @Test void identicalStageIsIdempotentButChangedOfficialPayloadConflicts() {
        var first=store.stage(baseline,0,source(),"{\"v\":1}",SHA);
        assertThat(store.stage(baseline,0,source(),"{\"v\":1}",SHA)).isEqualTo(first);
        assertThatThrownBy(()->store.stage(baseline,0,source(),"{\"v\":2}",SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_candidates",Integer.class)).isEqualTo(1);
    }
    @Test void newProductRequiresNewCandidateIdentityForTheSameImmutableOfficialSource() {
        var first=store.stage(baseline,0,source(),"{}",SHA);
        var second=store.stage(baseline,0,source(),"{}","d".repeat(40));
        assertThat(second.id()).isNotEqualTo(first.id());
        assertThat(second.inputHash()).isNotEqualTo(first.inputHash());
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_candidates",Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
    }
    @Test void staleBaselineRevisionOrContentCannotStageAnotherCandidate() {
        store.stage(baseline,0,source(),"{}",SHA);
        assertThatThrownBy(()->store.stage(baseline,1,source(),"{}",SHA)).isInstanceOf(IllegalStateException.class);
        db.update("UPDATE law_api_documents SET content_hash=? WHERE document_id=?","d".repeat(64),baseline);
        assertThatThrownBy(()->store.stage(baseline,0,source(),"{}",SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_candidates",Integer.class)).isEqualTo(1);
    }
    @Test void failedInsertRollsBackNewGroupAndLeavesCurrentSource() {
        db.execute("CREATE TRIGGER fail_source_candidate BEFORE INSERT ON law_source_version_candidates FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected candidate failure'");
        try {
            assertThatThrownBy(()->store.stage(baseline,0,source(),"{}",SHA)).isInstanceOf(RuntimeException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_groups",Integer.class)).isZero();
            assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
        } finally {db.execute("DROP TRIGGER fail_source_candidate");}
    }
    @Test void legacyGroupRefreshCannotDisplaceManagedActiveSource() {
        store.stage(baseline,0,source(),"{}",SHA);
        db.update("INSERT INTO law_api_documents(target,external_id,title,source_date,canonical_key,effective_date,effective_status,raw_json,use_yn) VALUES('admrul','source-test-new','원문 버전 테스트 지침','20261019','admrul:원문버전테스트지침','20261019','PAST','{}','Y')");
        var refresh=new LawVersionStatusService(db,false);refresh.refreshGroup("admrul","admrul:원문버전테스트지침");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE external_id='source-test-new'",String.class)).isEqualTo("PAST");
    }
    @Test void bulkRefreshCannotOverrideTheRegisteredCurrentVersion() {
        store.stage(baseline,0,source(),"{}",SHA);
        db.update("INSERT INTO law_api_documents(target,external_id,title,source_date,canonical_key,effective_date,effective_status,raw_json,use_yn) VALUES('admrul','source-test-new','원문 버전 테스트 지침','20261019','admrul:원문버전테스트지침','20261019','PAST','{}','Y')");
        new LawVersionStatusService(db,false).refreshAllStatuses();
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE external_id='source-test-new'",String.class)).isEqualTo("PAST");
    }
    @Test void unknownLegacyMetadataCannotJoinManagedGroupAsAnotherCurrentSource() {
        store.stage(baseline,0,source(),"{}",SHA);
        db.update("INSERT INTO law_api_documents(target,external_id,title,source_date,effective_status,raw_json) VALUES('admrul','source-test-new','원문 버전 테스트 지침','20261019','UNKNOWN','{}')");
        new LawVersionStatusService(db,false).refreshAllStatuses();
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE external_id='source-test-new'",String.class)).isEqualTo("UNKNOWN");
        assertThat(db.queryForObject("SELECT effective_status FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("CURRENT");
    }
    @Test void databaseGuardsRejectOldRuntimeWritesWithoutChangingTheActiveSource() {
        db.update("INSERT INTO law_api_document_chunks(document_id,chunk_type,chunk_text,content_hash) VALUES(?,'article','보존할 기존 본문',?)",baseline,"e".repeat(64));
        new SourceVersionSchemaMaintenance(db).run(null);
        var candidate=store.stage(baseline,0,source(),"{}",SHA);
        var before=db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline);
        assertThatThrownBy(()->db.update("UPDATE law_api_documents SET effective_status='PAST' WHERE document_id=?",baseline)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE law_api_documents SET raw_json='{\"changed\":true}' WHERE document_id=?",baseline)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("DELETE FROM law_api_document_chunks WHERE document_id=?",baseline)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("UPDATE law_api_document_chunks SET chunk_text='변조' WHERE document_id=?",baseline)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->db.update("INSERT INTO law_api_documents(target,external_id,title,canonical_key,effective_status,raw_json) VALUES('admrul','source-test-new','원문 버전 테스트 지침','admrul:원문버전테스트지침','CURRENT','{}')")).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForMap("SELECT * FROM law_api_documents WHERE document_id=?",baseline)).isEqualTo(before);
        assertThat(db.queryForObject("SELECT chunk_text FROM law_api_document_chunks WHERE document_id=?",String.class,baseline)).isEqualTo("보존할 기존 본문");
        // Inactive version construction is allowed only for its registered STAGED candidate.
        db.update("INSERT INTO law_api_documents(target,external_id,title,canonical_key,effective_status,use_yn,raw_json) VALUES('admrul','source-test-new','원문 버전 테스트 지침','admrul:원문버전테스트지침','PAST','N','{}')");
        long inactive=db.queryForObject("SELECT document_id FROM law_api_documents WHERE external_id='source-test-new'",Long.class);
        db.update("UPDATE law_source_version_candidates SET candidate_document_id=? WHERE candidate_id=?",inactive,candidate.id());
        db.update("INSERT INTO law_api_document_chunks(document_id,chunk_type,chunk_text) VALUES(?,'article','후보 본문')",inactive);
        db.update("UPDATE law_source_version_candidates SET state='INDEX_READY' WHERE candidate_id=?",candidate.id());
        assertThatThrownBy(()->db.update("UPDATE law_api_document_chunks SET chunk_text='준비 후 변조' WHERE document_id=?",inactive)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_api_documents WHERE canonical_key='admrul:원문버전테스트지침' AND use_yn='Y' AND effective_status='CURRENT'",Integer.class)).isEqualTo(1);
    }
    @Test void permissiveExistingDatabaseGuardIsNotSilentlyTrusted() {
        db.execute("CREATE TRIGGER srcver_doc_bu BEFORE UPDATE ON law_api_documents FOR EACH ROW SET NEW.title=NEW.title");
        assertThatThrownBy(()->new SourceVersionSchemaMaintenance(db).run(null)).isInstanceOf(IllegalStateException.class);
    }
    @Test void missingOrWrongCanonicalMetadataCannotBypassDatabaseSourceProtection() {
        new SourceVersionSchemaMaintenance(db).run(null);store.stage(baseline,0,source(),"{}",SHA);
        for(String key:java.util.Arrays.asList(null,"admrul:incorrect")) {
            assertThatThrownBy(()->db.update("INSERT INTO law_api_documents(target,external_id,title,canonical_key,effective_status,raw_json) VALUES('admrul','source-test-new','원문 버전 테스트 지침',?,'CURRENT','{}')",key))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_api_documents WHERE external_id='source-test-new'",Integer.class)).isZero();
    }
    @Test void oldRepeatableReadTransactionCannotIgnoreNewlyRegisteredSourceProtection() {
        new SourceVersionSchemaMaintenance(db).run(null);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(db.getDataSource()));
        tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        tx.executeWithoutResult(status->{
            assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_groups",Integer.class)).isZero();
            store.stage(baseline,0,source(),"{}",SHA);
            assertThatThrownBy(()->db.update("UPDATE law_api_documents SET raw_json='{\"oldTransaction\":true}' WHERE document_id=?",baseline))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            status.setRollbackOnly();
        });
        assertThat(db.queryForObject("SELECT raw_json FROM law_api_documents WHERE document_id=?",String.class,baseline)).isEqualTo("{}");
    }
    @Test void stageRejectsAnotherAlreadySearchableUnknownVersionInTheSameTitleGroup() {
        db.update("INSERT INTO law_api_documents(target,external_id,title,source_date,effective_status,raw_json) VALUES('admrul','source-test-new','원문 버전 테스트 지침','20261019','UNKNOWN','{}')");
        assertThatThrownBy(()->store.stage(baseline,0,source(),"{}",SHA)).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM law_source_version_groups",Integer.class)).isZero();
    }
}
