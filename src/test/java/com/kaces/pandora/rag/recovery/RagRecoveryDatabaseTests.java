package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;

@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RagRecoveryDatabaseTests {
    @TempDir Path temp;
    @Test void candidateCreationPreservesBaselineAndMidInsertFailureRollsBack() throws Exception {
        var ds=new org.mariadb.jdbc.MariaDbDataSource();ds.setUrl(System.getenv("PANDORA_IMPORT_TX_TEST_URL"));
        ds.setUser("root");ds.setPassword(System.getenv("PANDORA_IMPORT_TX_TEST_PASSWORD"));
        var jdbc=new org.springframework.jdbc.core.JdbcTemplate(ds);
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_import_tx_test");
        var expected=Path.of(System.getenv("PANDORA_IMPORT_TX_TEST_DATADIR")).toRealPath();
        assertThat(expected.getFileName().toString()).startsWith("pandora-import-tx-test-");
        assertThat(Path.of(jdbc.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(expected);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(ds);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/law/RagDocumentMapper.xml"),new org.springframework.core.io.ClassPathResource("mapper/law/RagRecoveryMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var documents=session.getMapper(RagDocumentMapper.class);var recovery=session.getMapper(RagRecoveryMapper.class);
        for(boolean fail:List.of(false,true)) {
            Path file=temp.resolve("source-"+fail+".hwpx");
            String xml="<section><tbl colCnt='1'><tr><tc><cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>기관은 원본 자료를 보존하여야 한다.</t></tc></tr><tr><tc><cellAddr rowAddr='1' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>담당자는 검토 결과를 기록하여야 한다.</t></tc></tr></tbl></section>";
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));zip.write((xml+(fail?" ":"")).getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            jdbc.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash,import_status) VALUES('official_doc','원본 보존','source.hwpx',?,?,'INDEXED')",file.toString(),hash);
            long id=documents.findDocumentIdByHash(hash);String trigger="reject_recovery_"+id;
            try {
                for(int n=0;n<4;n++)documents.insertChunk(new RagDocumentChunkRow(0,id,4,"page "+n,null,"기존","body","기존 원문 "+n,null,1,"$.pages[1]",null,n,"a".repeat(64),"PASS",null,null));
                jdbc.update("UPDATE rag_document_chunks SET index_status='INDEXED',updated_at='2000-01-01' WHERE document_id=?",id);
                var oldIds=documents.findActiveChunkIdsByDocumentIdAndVersion(id,4);
                long oldId=oldIds.get(0);
                jdbc.update("INSERT INTO rag_chunk_embeddings(chunk_id,embedding_model,vector_store,vector_point_id,status) VALUES(?,'test-model','baseline',?,'INDEXED')",oldId,String.valueOf(oldId));
                var original=mock(RagOriginalDocumentStore.class);when(original.materialize(documents.findDocumentById(id))).thenReturn(file);
                var service=new RagRecoveryCandidateService(documents,recovery,original,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                var before=jdbc.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? ORDER BY chunk_id",id);
                var embeds=jdbc.queryForList("SELECT * FROM rag_chunk_embeddings WHERE chunk_id=?",oldId);
                var preview=service.preview(id,"b".repeat(40));
                assertThat(preview.baselineCount()).isEqualTo(4);assertThat(preview.candidateCount()).isEqualTo(2);
                if(fail) {
                    jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON rag_recovery_members FOR EACH ROW BEGIN IF NEW.chunk_id > "+oldIds.get(oldIds.size()-1)+" AND (SELECT COUNT(*) FROM rag_document_chunks WHERE document_id="+id+" AND use_yn='N')=2 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected-recovery-failure'; END IF; END");
                    assertThatThrownBy(()->service.create(id,preview.previewHash(),"b".repeat(40))).isInstanceOf(RuntimeException.class);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Integer.class,id)).isZero();
                    assertThat(recovery.findPointer(id,false)).isNull();
                } else {
                    var created=service.create(id,preview.previewHash(),"b".repeat(40));
                    assertThat(created.state()).isEqualTo("PENDING");
                    assertThat(service.create(id,preview.previewHash(),"b".repeat(40))).isEqualTo(created);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Integer.class,id)).isEqualTo(2);
                    assertThat(documents.findSemanticChunksByIds(jdbc.queryForList("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Long.class,id))).isEmpty();
                    assertThat(recovery.findPointer(id,false).revision()).isZero();
                }
                assertThat(jdbc.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? AND use_yn='Y' ORDER BY chunk_id",id)).isEqualTo(before);
                assertThat(jdbc.queryForList("SELECT * FROM rag_chunk_embeddings WHERE chunk_id=?",oldId)).isEqualTo(embeds);
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
                jdbc.update("DELETE FROM rag_recovery_pointers WHERE document_id=?",id);
                jdbc.update("DELETE m FROM rag_recovery_members m JOIN rag_recovery_generations g ON g.generation_id=m.generation_id WHERE g.document_id=?",id);
                jdbc.update("DELETE FROM rag_recovery_generations WHERE document_id=?",id);
                jdbc.update("DELETE FROM rag_documents WHERE document_id=?",id);
            }
        }
    }
}
