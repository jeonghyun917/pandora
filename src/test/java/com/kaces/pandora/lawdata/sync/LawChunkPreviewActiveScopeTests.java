package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LawChunkPreviewActiveScopeTests {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
    void actualDatabaseCountsOnlyTheActiveVersion() throws Exception {
        var ds=new org.mariadb.jdbc.MariaDbDataSource();ds.setUrl(System.getenv("PANDORA_IMPORT_TX_TEST_URL"));
        ds.setUser("root");ds.setPassword(System.getenv("PANDORA_IMPORT_TX_TEST_PASSWORD"));
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_import_tx_test");
        var expected=java.nio.file.Path.of(System.getenv("PANDORA_IMPORT_TX_TEST_DATADIR")).toRealPath();
        assertThat(expected.getFileName().toString()).startsWith("pandora-import-tx-test-");
        assertThat(java.nio.file.Path.of(jdbc.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(expected);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/law/LawDetailMapper.xml"),
            new org.springframework.core.io.ClassPathResource("mapper/law/LawChunkMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var mapper=session.getMapper(com.kaces.pandora.lawdata.persistence.LawDetailMapper.class);
        var chunks=session.getMapper(com.kaces.pandora.lawdata.persistence.LawChunkMapper.class);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds)).execute(status->{
            String key="preview-scope-"+java.util.UUID.randomUUID();
            jdbc.update("INSERT INTO law_api_documents(target,external_id,title,raw_json) VALUES('law',?,'scope fixture','{}')",key);
            long id=jdbc.queryForObject("SELECT document_id FROM law_api_documents WHERE external_id=?",Long.class,key);
            jdbc.update("INSERT INTO law_api_document_details(document_id,raw_json) VALUES(?,'{}')",id);
            for(String state:List.of("ACTIVE","CANDIDATE","RETIRED")) {
                jdbc.update("INSERT INTO law_api_document_chunks(document_id,chunk_type,chunk_text,activation_status) VALUES(?,'article','short',?)",id,state);
            }
            var row=mapper.findChunkRebuildRowsByDocumentIds("law",List.of(id)).get(0);
            assertThat(row.currentChunkCount()).isEqualTo(1);assertThat(row.currentTinyChunkCount()).isEqualTo(1);
            assertThat(mapper.findChunkRebuildRows("law",100,0)).filteredOn(r->r.documentId()==id).singleElement().satisfies(r->{
                assertThat(r.currentChunkCount()).isEqualTo(1);assertThat(r.currentTinyChunkCount()).isEqualTo(1);
            });
            jdbc.update("INSERT INTO law_api_document_chunk_versions(document_id,chunk_version,activation_status,expected_chunk_count,preview_approved,unexplained_loss_span_count,preview_token_hash) VALUES(?,1,'CANDIDATE',1,TRUE,0,?)",id,"a".repeat(64));
            var verification=chunks.findChunkVersionVerification(id,1,"fixture","fixture");
            assertThat(verification.expectedChunkCount()).isEqualTo(1);
            assertThat(verification.candidateChunkCount()).isEqualTo(1);
            assertThat(verification.indexedCurrentCount()).isZero();
            assertThat(verification.databaseGatesPass()).isFalse();
            status.setRollbackOnly();return null;
        });
    }
    @Test void previewCurrentCountsExcludeCandidateAndRetiredVersions() throws Exception {
        var configuration=new org.apache.ibatis.session.Configuration();
        String resource="mapper/law/LawDetailMapper.xml";
        try(var input=getClass().getClassLoader().getResourceAsStream(resource)) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        for(String id:List.of("findChunkRebuildRows","findChunkRebuildRowsByDocumentIds")) {
            String sql=configuration.getMappedStatement("com.kaces.pandora.lawdata.persistence.LawDetailMapper."+id)
                .getBoundSql(Map.of("target","law","limit",1,"offset",0,"documentIds",List.of(42L))).getSql().replaceAll("\\s+"," ");
            assertThat(sql.split("c.activation_status = 'ACTIVE'",-1).length-1).as(id+" total and tiny counts").isEqualTo(2);
        }
    }
}
