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
        jdbc.update("DELETE FROM semantic_lexical_index_state WHERE status='RECOVERY_READY' AND LEFT(index_version,9)='recovery_'");
        jdbc.execute("ALTER TABLE semantic_lexical_index_state DROP CONSTRAINT chk_semantic_lexical_state_status, ADD CONSTRAINT chk_semantic_lexical_state_status CHECK (status IN ('BUILDING','READY','FAILED'))");
        new com.kaces.pandora.semantic.lexical.SemanticLexicalSchemaMaintenance(jdbc).run(null);
        var lexicalFactory = new org.mybatis.spring.SqlSessionFactoryBean();
        lexicalFactory.setDataSource(ds);
        lexicalFactory.setMapperLocations(new org.springframework.core.io.ClassPathResource("mapper/law/SemanticLexicalMapper.xml"));
        var lexical = new org.mybatis.spring.SqlSessionTemplate(lexicalFactory.getObject())
            .getMapper(com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.class);
        String beforeRevision = lexical.findReadyRevision();
        String lexicalGeneration = UUID.randomUUID().toString();
        String lexicalVersion = "recovery_" + lexicalGeneration;
        try {
            var lexicalService = new com.kaces.pandora.semantic.lexical.SemanticLexicalIndexService(lexical,
                new com.kaces.pandora.semantic.lexical.KoreanLexicalTokenizer());
            var result = lexicalService.rebuildRecovery(lexicalGeneration, List.of(
                new com.kaces.pandora.semantic.lexical.LexicalChunkDocument("official_doc", 123, 456,
                    "parent", "a".repeat(64), "제목", "부모", "조문", "원본 보존")));
            assertThat(result.activeChunkCount()).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT status FROM semantic_lexical_index_state WHERE index_version=?", String.class, lexicalVersion)).isEqualTo("RECOVERY_READY");
            assertThat(lexical.findReadyRevision()).isEqualTo(beforeRevision);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM semantic_lexical_terms WHERE index_version=?", Integer.class, lexicalVersion)).isPositive();
            var snapshot = List.of(new com.kaces.pandora.semantic.lexical.LexicalChunkDocument("official_doc", 123, 456,
                "parent", "a".repeat(64), "제목", "부모", "조문", "원본 보존"));
            assertThat(lexicalService.verifyRecovery(lexicalGeneration, snapshot)).isEqualTo(result.contentFingerprint());
            assertThat(lexical.findRecoveryTermStatistics(lexicalVersion,result.contentFingerprint(),List.of("원본")))
                .extracting(row -> row.term()).contains("원본");
            assertThat(lexical.findRecoveryBm25TermMatches(lexicalVersion,result.contentFingerprint(),List.of("원본"),List.of("official_doc")))
                .extracting(row -> row.chunkId()).containsOnly(123L);
            assertThat(lexical.findRecoveryBm25TermMatches(lexicalVersion,"f".repeat(64),List.of("원본"),List.of("official_doc"))).isEmpty();
            jdbc.update("UPDATE semantic_lexical_terms SET term_frequency=term_frequency+1 WHERE index_version=? LIMIT 1", lexicalVersion);
            assertThatThrownBy(() -> lexicalService.verifyRecovery(lexicalGeneration, snapshot)).isInstanceOf(IllegalStateException.class);
            jdbc.update("UPDATE semantic_lexical_terms SET term_frequency=1 WHERE index_version=?", lexicalVersion);
            assertThat(lexicalService.verifyRecovery(lexicalGeneration, snapshot)).isEqualTo(result.contentFingerprint());
            jdbc.update("UPDATE semantic_lexical_term_stats SET document_frequency=2 WHERE index_version=? LIMIT 1", lexicalVersion);
            assertThatThrownBy(() -> lexicalService.verifyRecovery(lexicalGeneration, snapshot)).isInstanceOf(IllegalStateException.class);
            jdbc.update("UPDATE semantic_lexical_term_stats SET document_frequency=1 WHERE index_version=?", lexicalVersion);
            jdbc.update("INSERT INTO semantic_lexical_chunks(index_version,target,chunk_id,document_id,parent_key,content_hash,weighted_length,build_status) VALUES(?,'official_doc',124,456,'parent',?,1,'READY')", lexicalVersion, "a".repeat(64));
            assertThatThrownBy(() -> lexicalService.verifyRecovery(lexicalGeneration, snapshot)).isInstanceOf(IllegalStateException.class);
            jdbc.update("DELETE FROM semantic_lexical_chunks WHERE index_version=? AND chunk_id=124", lexicalVersion);
            assertThat(lexicalService.verifyRecovery(lexicalGeneration, snapshot)).isEqualTo(result.contentFingerprint());
            assertThatThrownBy(() -> jdbc.update("UPDATE semantic_lexical_index_state SET status='UNSAFE' WHERE index_version=?", lexicalVersion)).isInstanceOf(RuntimeException.class);
        } finally {
            jdbc.update("DELETE FROM semantic_lexical_index_state WHERE index_version=?", lexicalVersion);
        }
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
                    var writeGuard=new RagRecoveryWriteGuard(recovery,new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                    var entered=new java.util.concurrent.CountDownLatch(1);
                    var release=new java.util.concurrent.CountDownLatch(1);
                    var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
                    RecoveryGeneration created;
                    try {
                        var writer=pool.submit(()->writeGuard.mutateDocuments(List.of(id),()->{
                            entered.countDown();
                            try { if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("writer test timeout"); }
                            catch(InterruptedException e){Thread.currentThread().interrupt();throw new IllegalStateException(e);}
                            return null;
                        }));
                        assertThat(entered.await(3,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                        var creator=pool.submit(()->service.create(id,preview.previewHash(),"b".repeat(40)));
                        assertThatThrownBy(()->creator.get(250,java.util.concurrent.TimeUnit.MILLISECONDS))
                            .isInstanceOf(java.util.concurrent.TimeoutException.class);
                        release.countDown();writer.get(5,java.util.concurrent.TimeUnit.SECONDS);
                        created=creator.get(10,java.util.concurrent.TimeUnit.SECONDS);
                    } finally { release.countDown();pool.shutdownNow(); }
                    var wrote=new java.util.concurrent.atomic.AtomicBoolean();
                    assertThatThrownBy(()->writeGuard.mutateDocuments(List.of(id),()->{wrote.set(true);return null;}))
                        .isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(()->writeGuard.mutateChunk(oldId,()->wrote.set(true))).isInstanceOf(IllegalStateException.class);
                    var guardTx=new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                    assertThatThrownBy(()->guardTx.executeWithoutResult(s->writeGuard.assertMutableHash(hash))).isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(()->guardTx.executeWithoutResult(s->writeGuard.assertMutablePaths(List.of(file.toString())))).isInstanceOf(IllegalStateException.class);
                    assertThat(wrote).isFalse();
                    assertThatThrownBy(()->writeGuard.assertMutableDocument(id)).isInstanceOf(IllegalStateException.class);
                    assertThat(created.state()).isEqualTo("PENDING");
                    assertThat(service.create(id,preview.previewHash(),"b".repeat(40))).isEqualTo(created);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Integer.class,id)).isEqualTo(2);
                    assertThat(documents.findSemanticChunksByIds(jdbc.queryForList("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Long.class,id))).isEmpty();
                    assertThat(recovery.findPointer(id,false).revision()).isZero();
                    var validation=new RagRecoveryValidationService(documents,recovery,original,
                            new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),"b".repeat(40));
                    assertThat(validation.validate(created.id()).passed()).isTrue();
                    assertThat(recovery.findGeneration(created.id(),false).state()).isEqualTo("VALIDATED");
                    var receiptService = new RagRecoveryReceiptService(documents, recovery,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                        new com.kaces.pandora.semantic.config.LawAiProperties(null,
                            new com.kaces.pandora.semantic.config.LawAiProperties.Qdrant("http://127.0.0.1:1", "unused-law", "unused-rag", 3), null, null));
                    var candidateIds = jdbc.queryForList("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='N' ORDER BY chunk_id", Long.class, id);
                    var vectors = Map.of(candidateIds.get(0), List.of(1.0, 0.0, 0.0), candidateIds.get(1), List.of(0.0, 1.0, 0.0));
                    String receiptTrigger = "reject_receipt_" + id;
                    jdbc.execute("CREATE TRIGGER " + receiptTrigger + " BEFORE INSERT ON rag_recovery_embedding_receipts FOR EACH ROW BEGIN IF NEW.chunk_id=" + candidateIds.get(1) + " THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected-receipt-failure'; END IF; END");
                    try {
                        assertThatThrownBy(() -> receiptService.save(created.id(), vectors)).isInstanceOf(RuntimeException.class);
                        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_recovery_embedding_receipts WHERE generation_id=?", Integer.class, created.id())).isZero();
                    } finally { jdbc.execute("DROP TRIGGER IF EXISTS " + receiptTrigger); }
                    receiptService.save(created.id(), vectors);
                    receiptService.save(created.id(), vectors);
                    assertThat(receiptService.load(created.id())).isEqualTo(vectors);
                    assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM rag_recovery_embedding_receipts WHERE generation_id=?", Integer.class, created.id())).isEqualTo(2);
                    assertThatThrownBy(() -> receiptService.save(created.id(), Map.of(candidateIds.get(0), List.of(0.0, 0.0, 1.0), candidateIds.get(1), List.of(0.0, 1.0, 0.0))))
                        .isInstanceOf(IllegalStateException.class);
                    assertThatThrownBy(() -> receiptService.save(created.id(), Map.of(oldId, List.of(1.0, 0.0, 0.0))))
                        .isInstanceOf(IllegalArgumentException.class);
                    assertThatThrownBy(() -> receiptService.save(created.id(), Map.of(candidateIds.get(0), List.of(0.0, 0.0, 0.0), candidateIds.get(1), List.of(0.0, 1.0, 0.0))))
                        .isInstanceOf(IllegalArgumentException.class);
                    jdbc.update("UPDATE rag_recovery_embedding_receipts SET vector_json='[0.0,0.0,1.0]' WHERE generation_id=? AND chunk_id=?", created.id(), candidateIds.get(0));
                    assertThatThrownBy(() -> receiptService.load(created.id())).isInstanceOf(IllegalStateException.class);
                    var nextPreview = service.preview(id, "c".repeat(40));
                    var next = service.create(id, nextPreview.previewHash(), "c".repeat(40));
                    var nextValidation = new RagRecoveryValidationService(documents, recovery, original,
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds), "c".repeat(40));
                    assertThat(nextValidation.validate(next.id()).passed()).isTrue();
                    var embeddingClient = mock(com.kaces.pandora.infra.openai.OpenAiEmbeddingClient.class);
                    when(embeddingClient.embed(anyList())).thenReturn(List.of(List.of(1.0,0.0,0.0), List.of(0.0,1.0,0.0)));
                    var q = mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
                    var nextIds = recovery.findCandidateChunks(next.id()).stream().map(RagDocumentChunkRow::chunkId).collect(java.util.stream.Collectors.toSet());
                    when(q.ragRecoveryCollection(next.id())).thenReturn("rag_recovery_" + next.id().replace("-", ""));
                    when(q.inspectRagRecoveryPoints(anyString(), anyString(), anyString(), anyList(), anyList()))
                        .thenReturn(new com.kaces.pandora.infra.qdrant.QdrantClient.RecoveryPointInspection(null, nextIds));
                    when(q.verifyRagRecoveryCollection(anyString(), anyString(), anyString(), anyList(), anyList())).thenReturn("d".repeat(64));
                    var indexService = new RagRecoveryIndexService(documents, recovery, service, receiptService, embeddingClient, q,
                        lexical, new com.kaces.pandora.semantic.lexical.SemanticLexicalIndexService(lexical, new com.kaces.pandora.semantic.lexical.KoreanLexicalTokenizer()),
                        new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds),
                        new com.kaces.pandora.semantic.config.LawAiProperties(null,
                            new com.kaces.pandora.semantic.config.LawAiProperties.Qdrant("http://127.0.0.1:1", "unused-law", "unused-rag", 3), null, null), "c".repeat(40));
                    doThrow(new IllegalStateException("lost write response")).doNothing().when(q)
                        .upsertRagRecovery(anyString(), anyString(), anyString(), anyList(), anyList());
                    assertThatThrownBy(() -> indexService.prepare(next.id())).isInstanceOf(IllegalStateException.class);
                    assertThat(recovery.findGeneration(next.id(),false).state()).isEqualTo("VALIDATED");
                    assertThat(recovery.verifiedEmbeddingCount(next.id())).isZero();
                    when(q.inspectRagRecoveryPoints(anyString(), anyString(), anyString(), anyList(), anyList()))
                        .thenReturn(new com.kaces.pandora.infra.qdrant.QdrantClient.RecoveryPointInspection(null, Set.of(nextIds.iterator().next())));
                    var assets = indexService.prepare(next.id());
                    assertThat(assets.chunkCount()).isEqualTo(2);
                    assertThat(recovery.findGeneration(next.id(),false).state()).isEqualTo("INDEX_READY");
                    assertThat(indexService.prepare(next.id())).isEqualTo(assets);
                    verify(embeddingClient, times(1)).embed(anyList());
                    @SuppressWarnings("unchecked")
                    var writtenBatches = org.mockito.ArgumentCaptor.forClass((Class<List<RagDocumentChunkRow>>)(Class<?>)List.class);
                    verify(q,times(2)).upsertRagRecovery(anyString(),anyString(),anyString(),writtenBatches.capture(),anyList());
                    assertThat(writtenBatches.getAllValues().get(0)).hasSize(2);
                    assertThat(writtenBatches.getAllValues().get(1)).hasSize(1);
                    doThrow(new IllegalStateException("missing candidate collection")).when(q).verifyRagRecoveryCollection(anyString(), anyString(), anyString(), anyList(), anyList());
                    assertThatThrownBy(() -> indexService.verify(assets)).isInstanceOf(IllegalStateException.class);
                    var router = new RagRecoveryRoutingService(recovery, lexical, new org.springframework.jdbc.datasource.DataSourceTransactionManager(ds));
                    var beforeRouting = router.capture();
                    assertThat(beforeRouting.replacedDocumentIds()).isEmpty();
                    assertThat(beforeRouting.baselineGenerations()).contains(recovery.findPointer(id,false).activeGeneration());
                    jdbc.update("UPDATE rag_recovery_generations SET state='RETIRED' WHERE generation_id=?", recovery.findPointer(id,false).activeGeneration());
                    jdbc.update("UPDATE rag_recovery_generations SET state='ACTIVE' WHERE generation_id=?", next.id());
                    jdbc.update("UPDATE rag_recovery_pointers SET revision=1,active_generation=? WHERE document_id=?",next.id(),id);
                    jdbc.update("UPDATE rag_recovery_routing_epoch SET revision=1,lexical_generation=?,lexical_fingerprint=? WHERE singleton_id=1",next.id(),assets.lexicalFingerprint());
                    var afterRouting=router.capture();
                    assertThat(afterRouting.replacedDocumentIds()).contains(id);
                    assertThat(afterRouting.cacheIdentity()).isNotEqualTo(beforeRouting.cacheIdentity());
                    assertThat(beforeRouting.documents().get(id).revision()).isZero();
                    var allIds = jdbc.queryForList("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? ORDER BY chunk_id", Long.class, id);
                    assertThat(documents.findSemanticChunksByIdsRouted(allIds, beforeRouting)).extracting(row -> row.chunkId())
                        .containsExactlyInAnyOrderElementsOf(oldIds);
                    var activeIds = recovery.findCandidateChunks(next.id()).stream().map(RagDocumentChunkRow::chunkId).toList();
                    assertThat(documents.findSemanticChunksByIdsRouted(allIds, afterRouting)).extracting(row -> row.chunkId())
                        .containsExactlyInAnyOrderElementsOf(activeIds);
                    assertThat(documents.findSemanticChunksByIdsRouted(activeIds, beforeRouting)).isEmpty();
                    assertThat(documents.findSemanticChunksByIdsRouted(oldIds, afterRouting)).isEmpty();
                    var types=List.of("official_doc"); var titles=List.of("원본 보존"); var terms=List.of("원본","검토");
                    var titleReads=List.of(
                        documents.findSemanticChunksByDocumentTitleScopedRouted(types,titles,100,afterRouting),
                        documents.findSemanticChunksByDocumentTitleAndTextScopedRouted(types,titles,terms,100,afterRouting),
                        documents.findSemanticChunksByDocumentTitleAndTextRouted(types,titles,terms,100,afterRouting),
                        documents.findSemanticChunksByDocumentTitleWithTextHintsRouted(types,titles,terms,100,afterRouting),
                        documents.findSemanticChunksByHeadingTextRouted(types,List.of(),100,afterRouting),
                        documents.findSemanticChunksByLegacyTextRouted(types,terms,100,afterRouting),
                        documents.findSemanticChunksByTextRouted(types,List.of("원본"),100,afterRouting));
                    for(var rows:titleReads) assertThat(rows.stream().filter(row->row.documentId()==id).map(row->row.chunkId()).toList())
                        .containsExactlyInAnyOrderElementsOf(activeIds);
                    assertThat(documents.findDocumentExpansionDocumentsRouted(types,titles,List.of(),100,afterRouting))
                        .extracting(row->row.documentId()).contains(id);
                    assertThat(documents.findDocumentExpansionChunksRouted(List.of(id),List.of(),List.of(),List.of(),100,100,afterRouting))
                        .extracting(row->row.chunkId()).containsExactlyInAnyOrderElementsOf(activeIds);
                    assertThat(documents.findDocumentExpansionChunksRouted(List.of(id),List.of(),List.of(),List.of(),100,100,beforeRouting))
                        .extracting(row->row.chunkId()).containsExactlyInAnyOrderElementsOf(oldIds);
                    assertThat(documents.countChunkSearchRouted("official_doc","원본 보존",afterRouting)).isEqualTo(activeIds.size());
                    assertThat(documents.searchChunksRouted("official_doc","원본 보존",100,0,afterRouting))
                        .extracting(row->row.chunkId()).containsExactlyInAnyOrderElementsOf(activeIds);
                    assertThat(documents.countChunkSearchRouted("official_doc","원본 보존",beforeRouting)).isEqualTo(oldIds.size());
                    assertThat(documents.findSemanticChunksByDocumentTitleScopedRouted(types,titles,100,beforeRouting).stream()
                        .filter(row->row.documentId()==id).map(row->row.chunkId()).toList()).containsExactlyInAnyOrderElementsOf(oldIds);
                    assertThat(documents.findSemanticChunksByDocumentIdRouted(id, beforeRouting)).extracting(row -> row.chunkId())
                        .containsExactlyInAnyOrderElementsOf(oldIds);
                    assertThat(documents.findSemanticChunksByDocumentIdRouted(id, afterRouting)).extracting(row -> row.chunkId())
                        .containsExactlyInAnyOrderElementsOf(activeIds);
                    assertThat(documents.findSemanticContextChunksRouted(id, 0, 100, beforeRouting)).extracting(row -> row.chunkId())
                        .containsExactlyInAnyOrderElementsOf(oldIds);
                    assertThat(documents.findSemanticContextChunksRouted(id, 0, 100, afterRouting)).extracting(row -> row.chunkId())
                        .containsExactlyInAnyOrderElementsOf(activeIds);
                    jdbc.update("DELETE FROM rag_recovery_assets WHERE generation_id=?",next.id());
                    assertThatThrownBy(router::capture).isInstanceOf(IllegalStateException.class);
                    assertThat(validation.validate(created.id()).passed()).isTrue();
                    jdbc.update("UPDATE rag_document_chunks SET chunk_text='변조된 원문' WHERE chunk_id=?",candidateIds.get(0));
                    assertThat(validation.validate(created.id()).passed()).isFalse();
                    assertThat(recovery.findGeneration(created.id(),false).state()).isEqualTo("FAILED");
                }
                assertThat(jdbc.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? AND use_yn='Y' ORDER BY chunk_id",id)).isEqualTo(before);
                assertThat(jdbc.queryForList("SELECT * FROM rag_chunk_embeddings WHERE chunk_id=?",oldId)).isEqualTo(embeds);
            } finally {
                jdbc.execute("DROP TRIGGER IF EXISTS "+trigger);
                jdbc.update("UPDATE rag_recovery_routing_epoch SET revision=0,lexical_generation=NULL,lexical_fingerprint=NULL WHERE singleton_id=1");
                jdbc.update("DELETE FROM rag_recovery_baselines WHERE document_id=?",id);
                jdbc.update("DELETE FROM semantic_lexical_index_state WHERE index_version IN (SELECT CONCAT('recovery_',generation_id) FROM rag_recovery_generations WHERE document_id=?)",id);
                jdbc.update("DELETE FROM rag_recovery_pointers WHERE document_id=?",id);
                jdbc.update("DELETE a FROM rag_recovery_assets a JOIN rag_recovery_generations g ON g.generation_id=a.generation_id WHERE g.document_id=?", id);
                jdbc.update("DELETE r FROM rag_recovery_embedding_receipts r JOIN rag_recovery_generations g ON g.generation_id=r.generation_id WHERE g.document_id=?", id);
                jdbc.update("DELETE m FROM rag_recovery_members m JOIN rag_recovery_generations g ON g.generation_id=m.generation_id WHERE g.document_id=?",id);
                jdbc.update("DELETE FROM rag_recovery_generations WHERE document_id=?",id);
                jdbc.update("DELETE FROM rag_documents WHERE document_id=?",id);
            }
        }
    }
}
