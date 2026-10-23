package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import com.kaces.pandora.semantic.lexical.*;

@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RecoveryCardinalityDatabaseTests {
    @Test void exactCountersFollowDmlRollbackCascadesAndFailClosedOnMissingInfrastructure() throws Exception {
        var ds=new org.mariadb.jdbc.MariaDbDataSource();ds.setUrl(System.getenv("PANDORA_IMPORT_TX_TEST_URL"));
        ds.setUser("root");ds.setPassword(System.getenv("PANDORA_IMPORT_TX_TEST_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_import_tx_test");
        var root=Path.of(System.getenv("PANDORA_IMPORT_TX_TEST_DATADIR")).toRealPath();
        assertThat(root.getFileName().toString()).startsWith("pandora-import-tx-test-");
        assertThat(Path.of(jdbc.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(root);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(ds);
        var maintenance=new SemanticLexicalSchemaMaintenance(jdbc);maintenance.run(null);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/law/SemanticLexicalMapper.xml"));
        var mapper=new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(SemanticLexicalMapper.class);
        String version="recovery_"+UUID.randomUUID(), other="recovery_"+UUID.randomUUID(), hash="a".repeat(64);
        try {
            jdbc.update("INSERT INTO semantic_lexical_index_state(index_version,tokenizer_version,active_chunk_count,average_weighted_length,content_fingerprint,status) VALUES(?,'test',2,1,?,'BUILDING')",version,hash);
            for(long id:new long[]{1,2}) jdbc.update("INSERT INTO semantic_lexical_chunks(index_version,target,chunk_id,document_id,weighted_length,build_status) VALUES(?,'law',?,?,1,'READY')",version,id,id);
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',1,'alpha','body',1,1),(?,'law',1,'alpha','document_title',1,1),(?,'law',2,'beta','body',1,1)",version,version,version);
            jdbc.update("INSERT INTO semantic_lexical_term_stats VALUES(?,'alpha',1),(?,'beta',1)",version,version);
            mapper.recordRecoveryIntegrity(version,2,3);
            jdbc.update("UPDATE semantic_lexical_index_state SET status='RECOVERY_READY' WHERE index_version=?",version);
            jdbc.update("INSERT INTO semantic_lexical_index_state(index_version,tokenizer_version,active_chunk_count,average_weighted_length,content_fingerprint,status) VALUES(?,'test',1,1,?,'BUILDING')",other,hash);
            jdbc.update("INSERT INTO semantic_lexical_chunks(index_version,target,chunk_id,document_id,weighted_length,build_status) VALUES(?,'law',2,2,1,'READY')",other);
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',2,'gamma','body',1,1)",other);
            jdbc.update("INSERT INTO semantic_lexical_term_stats VALUES(?,'gamma',1)",other);mapper.recordRecoveryIntegrity(other,1,1);
            jdbc.update("UPDATE semantic_lexical_index_state SET status='RECOVERY_READY' WHERE index_version=?",other);
            assertThat(jdbc.queryForObject("SELECT SUM(term_count) FROM semantic_lexical_term_cardinality WHERE index_version=?",Long.class,version)).isEqualTo(3);
            assertThat(mapper.recoveryAvailable(version,hash)).isTrue();
            new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).executeWithoutResult(status->{
                jdbc.update("DELETE FROM semantic_lexical_term_cardinality WHERE index_version=? AND chunk_id=1",version);
                jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',1,'extra1','body',1,1),(?,'law',1,'extra2','body',1,1)",version,version);
                assertThat(mapper.recoveryAvailable(version,hash)).as("Missing counters cannot be synthesized from a partial posting history").isFalse();
                status.setRollbackOnly();
            });
            new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).executeWithoutResult(status->{
                jdbc.update("DELETE FROM semantic_lexical_terms WHERE index_version=? AND term='beta'",version);
                assertThat(mapper.recoveryAvailable(version,hash)).isFalse();status.setRollbackOnly();
            });
            assertThat(mapper.recoveryAvailable(version,hash)).isTrue();
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',1,'moving','body',1,1)",version);
            jdbc.update("UPDATE semantic_lexical_terms SET chunk_id=2 WHERE index_version=? AND term='moving'",version);
            assertThat(jdbc.queryForObject("SELECT term_count FROM semantic_lexical_term_cardinality WHERE index_version=? AND chunk_id=2",Long.class,version)).isEqualTo(2);
            jdbc.update("UPDATE semantic_lexical_terms SET index_version=? WHERE index_version=? AND term='moving'",other,version);
            assertThat(mapper.recoveryAvailable(other,hash)).isFalse();
            jdbc.update("DELETE FROM semantic_lexical_terms WHERE index_version=? AND term='moving'",other);
            assertThat(mapper.recoveryAvailable(other,hash)).isTrue();
            assertThat(mapper.recoveryAvailable(version,hash)).isTrue();
            maintenance.run(null); // A normal restart must preserve valid accounting.
            assertThat(mapper.recoveryAvailable(version,hash)).isTrue();
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',2,'alpha','body',1,1)",other);
            assertThatThrownBy(()->jdbc.update("UPDATE semantic_lexical_terms SET index_version=?,chunk_id=2 WHERE index_version=? AND term='alpha' AND field_kind='body'",other,version))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
            jdbc.update("DELETE FROM semantic_lexical_terms WHERE index_version=? AND term='alpha'",other);
            assertThat(mapper.recoveryAvailable(version,hash)).as("Failed identity move rolls back BEFORE trigger decrement").isTrue();
            assertThat(mapper.recoveryAvailable(other,hash)).isTrue();
            new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).executeWithoutResult(status->{
            jdbc.update("DELETE FROM semantic_lexical_chunks WHERE index_version=? AND chunk_id=1",version);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_lexical_term_cardinality WHERE index_version=? AND chunk_id=1",Long.class,version)).isZero();
            jdbc.update("INSERT INTO semantic_lexical_chunks(index_version,target,chunk_id,document_id,weighted_length,build_status) VALUES(?,'law',1,1,1,'READY')",version);
            assertThat(mapper.recoveryAvailable(version,hash)).isFalse();
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',1,'alpha','body',1,1),(?,'law',1,'alpha','document_title',1,1)",version,version);
            assertThat(mapper.recoveryAvailable(version,hash)).as("READY chunk reinsertion cannot repair accounting").isFalse();
            status.setRollbackOnly();
            });
            assertThat(mapper.recoveryAvailable(version,hash)).isTrue();
            new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).executeWithoutResult(status->{
            jdbc.update("DELETE FROM semantic_lexical_term_cardinality WHERE index_version=?",version);
            assertThat(mapper.recoveryAvailable(version,hash)).isFalse();
            jdbc.update("DELETE FROM semantic_lexical_terms WHERE index_version=?",version);
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',1,'alpha','body',1,1),(?,'law',1,'alpha','document_title',1,1),(?,'law',2,'beta','body',1,1)",version,version,version);
            assertThat(mapper.recoveryAvailable(version,hash)).isFalse();
            // Counterless legacy DML must not synthesize partial counts or fail a negative CHECK.
            jdbc.update("DELETE FROM semantic_lexical_terms WHERE index_version=?",version);
            status.setRollbackOnly();
            });
            jdbc.execute("DROP TRIGGER recovery_terms_delete_count");
            jdbc.update("DELETE FROM semantic_lexical_terms WHERE index_version=? AND term='beta'",version);
            assertThat(mapper.recoveryAvailable(version,hash)).isFalse();
            maintenance.run(null);
            assertThat(mapper.recoveryAvailable(version,hash)).as("Trigger reinstall cannot erase a write gap").isFalse();
            jdbc.update("DELETE FROM semantic_lexical_index_state WHERE index_version=?",other);
            jdbc.update("INSERT INTO semantic_lexical_index_state(index_version,tokenizer_version,active_chunk_count,average_weighted_length,content_fingerprint,status) VALUES(?,'test',1,1,?,'BUILDING')",other,hash);
            jdbc.update("INSERT INTO semantic_lexical_chunks(index_version,target,chunk_id,document_id,weighted_length,build_status) VALUES(?,'law',2,2,1,'READY')",other);
            jdbc.update("INSERT INTO semantic_lexical_terms VALUES(?,'law',2,'gamma','body',1,1)",other);
            jdbc.update("INSERT INTO semantic_lexical_term_stats VALUES(?,'gamma',1)",other);
            mapper.recordRecoveryIntegrity(other,1,1);
            jdbc.update("UPDATE semantic_lexical_index_state SET status='RECOVERY_READY' WHERE index_version=?",other);
            assertThat(mapper.recoveryAvailable(other,hash)).as("Only a freshly built namespace can attest the new epoch").isTrue();
            maintenance.run(null);
            assertThat(mapper.recoveryAvailable(other,hash)).isTrue();
            jdbc.execute("TRUNCATE TABLE semantic_lexical_terms");
            assertThat(mapper.recoveryAvailable(version,hash)).isFalse();
            assertThat(mapper.recoveryAvailable(other,hash)).isFalse();
        } finally { maintenance.run(null);jdbc.update("DELETE FROM semantic_lexical_index_state WHERE index_version IN (?,?)",version,other); }
    }
}
