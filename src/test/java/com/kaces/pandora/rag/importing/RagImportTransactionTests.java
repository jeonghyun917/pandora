package com.kaces.pandora.rag.importing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.kaces.pandora.rag.chunk.RagChunker;
import com.kaces.pandora.rag.chunk.RagChunkQualityGate;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.search.RagChunkSearchIndexService;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mariadb.jdbc.MariaDbDataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",
    matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RagImportTransactionTests {
    @TempDir Path temp;

    @Test
    void publicReimportIsAtomicAcrossFailureStagesAndSuccess() throws Exception {
        var ds=new MariaDbDataSource();
        ds.setUrl(System.getenv("PANDORA_IMPORT_TX_TEST_URL"));
        ds.setUser("root");ds.setPassword(System.getenv("PANDORA_IMPORT_TX_TEST_PASSWORD"));
        var jdbc=new JdbcTemplate(ds);
        // Refuse all fixture DDL/DML unless this is the explicitly isolated test instance.
        assertThat(jdbc.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(jdbc.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_import_tx_test");
        String expectedDirectory=System.getenv("PANDORA_IMPORT_TX_TEST_DATADIR");
        assertThat(expectedDirectory).isNotBlank();
        Path expected=Path.of(expectedDirectory).toRealPath();
        assertThat(expected.getFileName().toString()).startsWith("pandora-import-tx-test-");
        assertThat(Path.of(jdbc.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(expected);
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        var factory=new SqlSessionFactoryBean();factory.setDataSource(ds);
        var configuration=new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(new ClassPathResource("mapper/law/RagDocumentMapper.xml"));
        var mapper=new SqlSessionTemplate(factory.getObject()).getMapper(RagDocumentMapper.class);
        for(String failure : List.of("lexical","embedding","qdrant","finalize","success")) {
            jdbc.execute("DROP TRIGGER IF EXISTS reject_import_finalize");
            jdbc.update("DELETE FROM rag_chunk_embeddings");
            jdbc.update("DELETE FROM rag_document_chunks");
            jdbc.update("DELETE FROM rag_documents");
            jdbc.update("DELETE FROM rag_import_jobs");
            Path file=temp.resolve(failure+".txt");
            Files.writeString(file,"자료 처리 가이드\n\n"+
                "기관은 원본 자료를 검토하고 처리 결과를 기록하여야 한다. 담당자는 변경 이력을 보존하고 원본과 대조하여 확인하여야 한다. ".repeat(8));
            Files.writeString(temp.resolve(failure+".meta.json"),"{\"title\":\"자료 처리 가이드\",\"documentType\":\"official_doc\"}");
            String hash=com.kaces.pandora.common.text.LawHashUtils.sha256(Files.readString(file));
            jdbc.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash,import_status) VALUES('official_doc','old title',?,?,?,'INDEXED')",
                file.getFileName().toString(),file.toString(),hash);
            long documentId=jdbc.queryForObject("SELECT document_id FROM rag_documents WHERE file_hash=?",Long.class,hash);
            jdbc.update("INSERT INTO rag_document_chunks(document_id,chunk_version,chunk_text,embedding_text,content_hash,index_status) VALUES(?,4,'OLD BASIS','OLD BASIS',?,'INDEXED')",documentId,"a".repeat(64));
            long oldId=jdbc.queryForObject("SELECT chunk_id FROM rag_document_chunks WHERE document_id=?",Long.class,documentId);
            mapper.upsertEmbeddingStatus(oldId,"fixture","fixture",String.valueOf(oldId),"a".repeat(64),"INDEXED",null);
            if(failure.equals("finalize"))jdbc.execute("CREATE TRIGGER reject_import_finalize BEFORE UPDATE ON rag_documents FOR EACH ROW BEGIN IF NEW.import_status='INDEXED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected-finalize'; END IF; END");
            var points=new HashSet<Long>();points.add(oldId);
            var index=mock(RagChunkSearchIndexService.class);
            var embedding=mock(OpenAiEmbeddingClient.class);
            var qdrant=mock(QdrantClient.class);
            var properties=mock(LawAiProperties.class,RETURNS_DEEP_STUBS);
            when(properties.openai().embeddingModel()).thenReturn("fixture");
            when(properties.qdrant().ragCollection()).thenReturn("fixture");
            when(properties.rag().importBatchSize()).thenReturn(1);
            if(failure.equals("lexical"))doThrow(new IllegalStateException("injected-lexical")).when(index).rebuildDocument(anyLong(),anyInt());
            if(failure.equals("embedding"))when(embedding.embed(anyList())).thenThrow(new IllegalStateException("injected-embedding"));
            else when(embedding.embed(anyList())).thenReturn(List.of(List.of(0.0)));
            doAnswer(call->{
                List<LawSemanticChunkRow> batch=call.getArgument(0);
                batch.forEach(row->points.add(row.chunkId()));
                if(failure.equals("qdrant"))throw new IllegalStateException("injected-qdrant");
                return null;
            }).when(qdrant).upsertRag(anyList(),anyList());
            doAnswer(call->{points.removeAll((List<Long>)call.getArgument(0));return null;})
                .when(qdrant).deleteRagPointsBestEffort(anyList());
            var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
                index,embedding,qdrant,properties,new tools.jackson.databind.ObjectMapper(),new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
            var result=service.reimportExistingDocuments("official_doc",true,true);
            if(failure.equals("success")) {
                assertThat(result.failedCount()).isZero();
                assertThat(result.indexedCount()).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT use_yn FROM rag_document_chunks WHERE chunk_id=?",String.class,oldId)).isEqualTo("N");
                assertThat(jdbc.queryForObject("SELECT status FROM rag_chunk_embeddings WHERE chunk_id=?",String.class,oldId)).isEqualTo("SUPERSEDED");
                long activeId=jdbc.queryForObject("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='Y'",Long.class,documentId);
                assertThat(jdbc.queryForObject("SELECT status FROM rag_chunk_embeddings WHERE chunk_id=?",String.class,activeId)).isEqualTo("INDEXED");
                assertThat(points).containsExactly(activeId);
                assertThat(jdbc.queryForObject("SELECT import_status FROM rag_documents WHERE document_id=?",String.class,documentId)).isEqualTo("INDEXED");
                continue;
            }
            assertThat(result.failedCount()).as(failure).isEqualTo(1);
            assertThat(result.message()).as(failure).contains("injected-"+failure);
            assertThat(jdbc.queryForObject("SELECT use_yn FROM rag_document_chunks WHERE chunk_id=?",String.class,oldId)).as(failure).isEqualTo("Y");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=?",Integer.class,documentId)).as(failure).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM rag_chunk_embeddings WHERE chunk_id=?",String.class,oldId)).as(failure).isEqualTo("INDEXED");
            assertThat(jdbc.queryForObject("SELECT title FROM rag_documents WHERE document_id=?",String.class,documentId)).as(failure).isEqualTo("old title");
            assertThat(jdbc.queryForObject("SELECT import_status FROM rag_documents WHERE document_id=?",String.class,documentId)).as(failure).isEqualTo("INDEXED");
            assertThat(points).as(failure).containsExactly(oldId);
        }
    }
}
