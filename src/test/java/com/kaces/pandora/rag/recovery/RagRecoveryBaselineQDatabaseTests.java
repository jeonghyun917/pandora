package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.LocalRagOriginalDocumentStore;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClient;

@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RagRecoveryBaselineQDatabaseTests {
    @TempDir Path root;
    @Test
    @EnabledIfEnvironmentVariable(named="PANDORA_RECOVERY_Q_TEST_URL",matches="http://127[.]0[.]0[.]1:26333")
    void realRegistrationPreservesQAndRejectsMissingPoints() throws Exception {
        RagRecoveryQdrantIntegrationTests.assertDedicatedServer();
        var ds=new org.mariadb.jdbc.MariaDbDataSource();
        ds.setUrl(System.getenv("PANDORA_IMPORT_TX_TEST_URL"));ds.setUser("root");
        ds.setPassword(System.getenv("PANDORA_IMPORT_TX_TEST_PASSWORD"));
        var db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_import_tx_test");
        var owned=Path.of(System.getenv("PANDORA_IMPORT_TX_TEST_DATADIR")).toRealPath();
        assertThat(owned.getFileName().toString()).startsWith("pandora-import-tx-test-");
        assertThat(Path.of(db.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(owned);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/law/RagDocumentMapper.xml"),new ClassPathResource("mapper/law/RagRecoveryMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var documents=session.getMapper(RagDocumentMapper.class);var recovery=session.getMapper(RagRecoveryMapper.class);
        String collection="test_register_"+UUID.randomUUID().toString().replace("-","");
        var http=RestClient.builder().baseUrl(System.getenv("PANDORA_RECOVERY_Q_TEST_URL")).build();
        var properties=new LawAiProperties(null,new LawAiProperties.Qdrant(System.getenv("PANDORA_RECOVERY_Q_TEST_URL"),"unused-law",collection,3),null,null);
        var q=new QdrantClient(properties,new tools.jackson.databind.ObjectMapper());
        var manager=new DataSourceTransactionManager(ds);
        var candidates=new RagRecoveryCandidateService(documents,recovery,new LocalRagOriginalDocumentStore(),manager);
        var service=new RagRecoveryBaselineService(documents,recovery,candidates,q,manager,properties);
        var file=root.resolve("original.txt");Files.writeString(file,"Preserve the entire original record before replacing its source.");
        String source=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        db.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash,import_status) VALUES('official_doc','Preserved original','original.txt',?,?,'INDEXED')",file.toString(),source);
        long id=documents.findDocumentIdByHash(source);
        try {
            for(int n=0;n<2;n++) {
                String text="Complete original clause "+n+".";
                db.update("INSERT INTO rag_document_chunks(document_id,chunk_version,chunk_no,chunk_text,embedding_text,content_hash,source_path,sort_order,index_status) VALUES(?,4,?,?,?,?,?,?,'INDEXED')",
                        id,"clause "+n,text,text,LawHashUtils.sha256(text),"$.pages[1]",n);
            }
            var ids=documents.findActiveChunkIdsByDocumentIdAndVersion(id,4);
            for(long chunk:ids)db.update("INSERT INTO rag_chunk_embeddings(chunk_id,embedding_model,vector_store,vector_point_id,content_hash,status) SELECT chunk_id,?,?,?,content_hash,'INDEXED' FROM rag_document_chunks WHERE chunk_id=?",
                    properties.openai().embeddingModel(),collection,String.valueOf(QdrantClient.ragPointId(chunk)),chunk);
            var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            String baseline=tx.execute(status->candidates.lockedStoredBaselineHash(id));
            var epoch=recovery.findRoutingEpoch();
            var stored=db.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? ORDER BY chunk_id",id);
            http.put().uri("/collections/{name}",collection).body(Map.of("vectors",Map.of("size",3,"distance","Cosine"))).retrieve().toBodilessEntity();
            var points=ids.stream().map(chunk->Map.of("id",QdrantClient.ragPointId(chunk),"vector",List.of(1,0,0),
                    "payload",Map.of("chunkId",chunk,"documentId",id,"target","official_doc","chunkVersion",4,"sourcePath","$.pages[1]"))).toList();
            http.put().uri("/collections/{name}/points?wait=true",collection).body(Map.of("points",points.subList(0,1))).retrieve().toBodilessEntity();
            assertThatThrownBy(()->service.registerPreserved(id,source,baseline,"b".repeat(40))).isInstanceOf(IllegalStateException.class);
            assertThat(recovery.findPointer(id,false)).isNull();
            http.put().uri("/collections/{name}/points?wait=true",collection).body(Map.of("points",points)).retrieve().toBodilessEntity();
            String before=q.baselineRagFingerprint(documents.findSemanticChunksByDocumentId(id));
            var receipt=service.registerPreserved(id,source,baseline,"b".repeat(40));
            assertThat(receipt.vectorFingerprint()).isEqualTo(before);
            assertThat(service.verify(id)).isEqualTo(receipt);
            assertThat(q.baselineRagFingerprint(documents.findSemanticChunksByDocumentId(id))).isEqualTo(before);
            assertThat(recovery.findRoutingEpoch()).isEqualTo(epoch);
            assertThat(db.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? ORDER BY chunk_id",id)).isEqualTo(stored);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Integer.class,id)).isZero();
        } finally {
            try {http.delete().uri("/collections/{name}",collection).retrieve().toBodilessEntity();}
            catch(org.springframework.web.client.HttpClientErrorException.NotFound alreadyRemoved) { }
            db.update("DELETE FROM rag_recovery_baseline_assets WHERE document_id=?",id);
            db.update("DELETE FROM rag_recovery_pointers WHERE document_id=?",id);
            db.update("DELETE FROM rag_recovery_baselines WHERE document_id=?",id);
            db.update("DELETE FROM rag_recovery_members WHERE generation_id IN (SELECT generation_id FROM rag_recovery_generations WHERE document_id=?)",id);
            db.update("DELETE FROM rag_recovery_generations WHERE document_id=?",id);
            db.update("DELETE FROM rag_documents WHERE document_id=?",id);
        }
    }
}
