package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;

@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RagSourceProofBackfillDatabaseTests {
    @TempDir Path temp;
    @Test void metadataOnlyCommitAndMidWriteFailurePreserveEveryOtherStoredField() throws Exception {
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
        factory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/law/RagDocumentMapper.xml"));
        var mapper=new org.mybatis.spring.SqlSessionTemplate(factory.getObject()).getMapper(RagDocumentMapper.class);
        for(boolean fail:List.of(false,true)) {
            Path file=temp.resolve("proof-"+fail+".hwpx");
            String xml="<section><tbl colCnt='1'><tr><tc><cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>기관은 원본 자료를 검토하여야 한다.</t></tc></tr><tr><tc><cellAddr rowAddr='1' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>담당자는 검토 결과를 기록하여야 한다.</t></tc></tr></tbl></section>";
            try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
            jdbc.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash,import_status) VALUES('official_doc','계획','proof.hwpx',?,?,'INDEXED')",file.toString(),hash);
            long id=mapper.findDocumentIdByHash(hash);
            String trigger="reject_proof_"+id;
            try {
                var extractor=new RagTextExtractor();var chunker=new RagChunker();var quality=new RagChunkQualityGate();
                for(var c:quality.evaluate("계획",chunker.chunkV4(id,extractor.extract(file),null,"계획",hash)).retainedChunks()) {
                    mapper.insertChunk(new RagDocumentChunkRow(0,id,4,c.chunkNo(),c.parentSectionTitle(),c.chunkTitle(),c.sectionType(),c.chunkText(),c.embeddingText(),c.pageNo(),c.sourcePath(),c.sourceUrl(),c.sortOrder(),c.contentHash(),c.qualityStatus(),c.qualityReason(),null));
                }
                jdbc.update("UPDATE rag_document_chunks SET index_status='INDEXED',updated_at='2000-01-01' WHERE document_id=?",id);
                var ids=mapper.findActiveChunksForSourceProof(id,false).stream().map(RagDocumentChunkRow::chunkId).toList();
                assertThat(ids).hasSize(2);
                var originals=mock(RagOriginalDocumentStore.class);
                when(originals.materialize(mapper.findDocumentById(id))).thenReturn(file);
                var service=new RagSourceProofBackfillService(mapper,originals,extractor,chunker,quality,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                var before=withoutProof(jdbc.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? ORDER BY chunk_id",id));
                var docBefore=jdbc.queryForMap("SELECT * FROM rag_documents WHERE document_id=?",id);
                var preview=service.preview(id,null);
                assertThat(preview.status()).isEqualTo("ELIGIBLE");
                if(fail) {
                    jdbc.execute("CREATE TRIGGER "+trigger+" BEFORE UPDATE ON rag_document_chunks FOR EACH ROW BEGIN IF OLD.chunk_id="+ids.get(1)+" AND NEW.source_provenance IS NOT NULL THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected-proof-failure'; END IF; END");
                    assertThatThrownBy(()->service.apply(id,preview.token())).isInstanceOf(RuntimeException.class);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND source_provenance IS NOT NULL",Integer.class,id)).isZero();
                } else {
                    assertThat(service.apply(id,preview.token()).updatedChunks()).isEqualTo(2);
                    assertThat(new RagSourceTableEvidenceService(mapper,originals).fromSelectedChunks(ids))
                        .filteredOn(b->b.valueText().equals("기관은 원본 자료를 검토하여야 한다.")).hasSize(1);
                    var again=service.preview(id,null);
                    assertThat(again.status()).isEqualTo("NO_CHANGE");
                    assertThat(service.apply(id,again.token()).updatedChunks()).isZero();
                }
                assertThat(withoutProof(jdbc.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? ORDER BY chunk_id",id))).isEqualTo(before);
                assertThat(jdbc.queryForMap("SELECT * FROM rag_documents WHERE document_id=?",id)).isEqualTo(docBefore);
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
                jdbc.update("DELETE FROM rag_documents WHERE document_id=? AND file_hash=?",id,hash);
            }
        }
    }
    private List<Map<String,Object>> withoutProof(List<Map<String,Object>> rows) {
        return rows.stream().map(row->{var copy=new LinkedHashMap<>(row);copy.remove("source_provenance");return (Map<String,Object>)copy;}).toList();
    }
}
