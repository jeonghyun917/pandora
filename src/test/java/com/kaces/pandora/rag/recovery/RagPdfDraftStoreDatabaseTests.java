package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.core.io.ClassPathResource;

@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RagPdfDraftStoreDatabaseTests {
    @TempDir Path root;

    @Test void stageIsImmutableIdempotentAndCannotChangeSearchableBaseline() throws Exception {
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
        new com.kaces.pandora.semantic.lexical.SemanticLexicalSchemaMaintenance(db).run(null);
        var manager=new DataSourceTransactionManager(ds);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/law/RagDocumentMapper.xml"),
                new ClassPathResource("mapper/law/RagRecoveryMapper.xml"),new ClassPathResource("mapper/law/SemanticLexicalMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var candidates=new RagRecoveryCandidateService(session.getMapper(com.kaces.pandora.rag.persistence.RagDocumentMapper.class),
                session.getMapper(RagRecoveryMapper.class),new com.kaces.pandora.rag.storage.LocalRagOriginalDocumentStore(),manager);
        String oldBody="The agency shall preserve the original approved operational decision and provide the complete record for inspection before replacing the service.";
        var original=root.resolve("preserved.txt");Files.writeString(original,oldBody);
        String tag=UUID.randomUUID().toString(),oldHash=hash(Files.readAllBytes(original));
        db.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash,source_org,published_date,source_url) VALUES('official_doc',?,'old.txt',?,?,'Legacy agency','19990101','https://legacy.example/old.pdf')",tag,original.toString(),oldHash);
        long id=db.queryForObject("SELECT document_id FROM rag_documents WHERE file_hash=?",Long.class,oldHash);
        db.update("INSERT INTO rag_document_chunks(document_id,chunk_version,chunk_text,embedding_text,content_hash) VALUES(?,4,?,?,?)",id,oldBody,oldBody,oldHash);
        var tx=new TransactionTemplate(manager);
        String baseline=tx.execute(status->candidates.lockedStoredBaselineHash(id));
        var before=baseline;
        var file=root.resolve("source.pdf");writePdf(file);
        byte[] bytes=Files.readAllBytes(file);
        String product="a".repeat(40);
        var draft=new ReviewedPdfDraft(id,hash(bytes),baseline,product,"Official rule","https://example.gov/rule.pdf",
                List.of(new ReviewedPdfDraft.Unit("procedure","Article 1",
                        new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"Notify before launch."),
                                new ReviewedPdfUnit.Line(2,1,"Keep the written decision.")))),
                        new ReviewedPdfDraft.Unit("owned-heading","Scope-specific procedure",
                                new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"Notify before launch."))))));
        try {
            var denied=new RagPdfDraftStore(db,manager,candidates,product,Set.of());
            assertThatThrownBy(()->denied.stage(draft,file)).isInstanceOf(IllegalStateException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_pdf_source_drafts WHERE document_id=?",Integer.class,id)).isZero();
            var store=new RagPdfDraftStore(db,manager,candidates,product,Set.of(draft.reviewHash()));
            var first=store.stage(draft,file);
            assertThat(first.state()).isEqualTo("STAGED");
            assertThat(store.stage(draft,file)).isEqualTo(first);
            assertThat(store.load(first.id()).draft()).isEqualTo(draft);
            assertThat(store.load(first.id()).sourceBytes()).isEqualTo(bytes);
            byte[] detached=store.load(first.id()).sourceBytes();detached[0]^=1;
            assertThat(store.load(first.id()).sourceBytes()).isEqualTo(bytes);
            assertThatThrownBy(()->denied.load(first.id())).isInstanceOf(IllegalStateException.class);
            var preparation=new RagPdfDraftPreparation(store);
            var prepared=preparation.prepare(first.id());
            assertThat(preparation.prepare(first.id()).manifest()).isEqualTo(prepared.manifest());
            Files.write(file,new byte[]{1,2,3});
            assertThat(preparation.prepare(first.id()).manifest()).isEqualTo(prepared.manifest());
            Files.write(file,bytes);
            assertThat(prepared.chunks()).hasSize(4);
            assertThat(prepared.chunks().stream().map(com.kaces.pandora.rag.document.RagDocumentChunkRow::chunkText).toList())
                .contains("Notify before launch.","Keep the written decision.","Notify before launch. Keep the written decision.");
            assertThat(prepared.units().stream().filter(u->u.key().equals("procedure")).toList()).singleElement().satisfies(binding->{
                assertThat(binding.positions()).extracting(ReviewedPdfUnit.Line::page).containsExactly(1,2);
                assertThat(prepared.chunks().stream().filter(c->c.sortOrder()==binding.sortOrder()).findFirst().orElseThrow().pageNo()).isNull();
            });
            var ownedUnit=prepared.units().stream().filter(u->u.key().equals("owned-heading")).findFirst().orElseThrow();
            var ownedRow=prepared.chunks().stream().filter(c->c.sortOrder()==ownedUnit.sortOrder()).findFirst().orElseThrow();
            assertThat(ownedRow.chunkTitle()).isEqualTo("Scope-specific procedure");
            assertThat(ownedRow.sourcePath()).isEqualTo("$.reviewedPdf.units[1]");
            capturedSourceMetadataIsVersionSpecific(db,session,draft,first,prepared,tag,oldHash);
            pendingGenerationPreservesOriginal(db,session,manager,candidates,store,draft,first,prepared,oldHash);
            assertThat(db.queryForObject("SELECT source_bytes FROM rag_pdf_source_drafts WHERE draft_id=?",byte[].class,first.id())).isEqualTo(bytes);
            String after=tx.execute(status->candidates.lockedStoredBaselineHash(id));
            assertThat(after).isEqualTo(before);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_recovery_pointers WHERE document_id=?",Integer.class,id)).isZero();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=?",Integer.class,id)).isOne();
            concurrentReadAndRestage(ds,manager,candidates,draft,file,first);
            db.update("UPDATE rag_pdf_source_drafts SET source_bytes=? WHERE draft_id=?",new byte[]{1,2,3},first.id());
            assertThatThrownBy(()->store.stage(draft,file)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->store.load(first.id())).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_pdf_source_drafts SET source_bytes=? WHERE draft_id=?",bytes,first.id());
            var nextDraft=new ReviewedPdfDraft(id,draft.sourceHash(),baseline,product,"Next reviewed rule",
                    draft.sourceUrl(),draft.units());
            var nextStore=new RagPdfDraftStore(db,manager,candidates,product,Set.of(nextDraft.reviewHash()));
            String trigger="fail_pdf_"+tag.replace("-","");
            db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON rag_pdf_source_drafts FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='isolated rollback control'");
            try {
                assertThatThrownBy(()->nextStore.stage(nextDraft,file)).isInstanceOf(RuntimeException.class);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_pdf_source_drafts WHERE document_id=?",Integer.class,id)).isOne();
                String afterFailure=tx.execute(status->candidates.lockedStoredBaselineHash(id));
                assertThat(afterFailure).isEqualTo(before);
                assertThat(Files.readAllBytes(file)).isEqualTo(bytes);
            } finally {db.execute("DROP TRIGGER "+trigger);}
            var wrongProduct=new RagPdfDraftStore(db,manager,candidates,"b".repeat(40),Set.of(draft.reviewHash()));
            assertThatThrownBy(()->wrongProduct.stage(draft,file)).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_document_chunks SET chunk_text='Changed baseline.' WHERE document_id=?",id);
            assertThatThrownBy(()->store.stage(draft,file)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->store.load(first.id())).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_document_chunks SET chunk_text=? WHERE document_id=?",oldBody,id);
            // updated_at is part of the exact stored snapshot; use a new draft after this mutation.
            Files.write(file,new byte[]{1,2,3});
            assertThatThrownBy(()->store.stage(draft,file)).isInstanceOf(IllegalStateException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_pdf_source_drafts WHERE document_id=?",Integer.class,id)).isOne();
        } finally {
            db.update("DELETE FROM rag_pdf_source_drafts WHERE document_id=?",id);
            db.update("DELETE FROM rag_documents WHERE document_id=?",id);
        }
    }

    private static void pendingGenerationPreservesOriginal(JdbcTemplate db,org.mybatis.spring.SqlSessionTemplate session,
            DataSourceTransactionManager manager,RagRecoveryCandidateService candidates,RagPdfDraftStore store,
            ReviewedPdfDraft draft,RagPdfDraftStore.Staged stored,RagPdfDraftPreparation.Prepared prepared,String originalHash) throws Exception {
        long document=draft.documentId();
        String baselineId=UUID.randomUUID().toString();
        var recovery=session.getMapper(RagRecoveryMapper.class);
        long oldChunk=db.queryForObject("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='Y'",Long.class,document);
        recovery.insertGeneration(new RecoveryGeneration(baselineId,document,originalHash,draft.productSha(),
                draft.baselineHash(),draft.baselineHash(),"8".repeat(64),"ACTIVE"));
        recovery.insertMembership(baselineId,oldChunk);
        recovery.insertBaseline(document,baselineId);
        var pointer=new RecoverySnapshot(document,0,baselineId,originalHash,draft.baselineHash());
        recovery.insertPointer(pointer);
        var writer=new RagPdfGenerationStore(db,manager,candidates,recovery,store);
        try {
            var originalReader=new RagPdfOriginalReader(db);
            assertThat(originalReader.isPreservedOriginal(document,baselineId,originalHash)).isTrue();
            assertThat(originalReader.isPreservedOriginal(document,baselineId,"0".repeat(64))).isFalse();
            String sameOriginal=UUID.randomUUID().toString();
            recovery.insertGeneration(new RecoveryGeneration(sameOriginal,document,originalHash,draft.productSha(),
                    draft.baselineHash(),"7".repeat(64),"6".repeat(64),"RETIRED"));
            try {
                assertThat(originalReader.isPreservedOriginal(document,sameOriginal,originalHash)).isTrue();
                db.update("UPDATE rag_recovery_generations SET validation_evidence=? WHERE generation_id=?",
                        "[\"reviewed-pdf-validation-v1\"]",sameOriginal);
                assertThat(originalReader.isPreservedOriginal(document,sameOriginal,originalHash)).isFalse();
            }
            finally {db.update("DELETE FROM rag_recovery_generations WHERE generation_id=?",sameOriginal);}
            var firstInput=store.load(stored.id());
            var alternateDraft=new ReviewedPdfDraft(document,draft.sourceHash(),draft.baselineHash(),draft.productSha(),draft.title(),draft.sourceUrl(),
                    List.of(new ReviewedPdfDraft.Unit("alternate","Alternate reviewed heading",draft.units().get(0).review())));
            var alternateInput=new RagPdfDraftStore.StoredInput(stored.id(),alternateDraft,firstInput.sourceBytes());
            // Model two individually accepted reads; the actual locked DB row still holds the first input.
            var alternateStore=org.mockito.Mockito.mock(RagPdfDraftStore.class);
            org.mockito.Mockito.when(alternateStore.load(stored.id())).thenReturn(alternateInput);
            var alternatePrepared=new RagPdfDraftPreparation(alternateStore).prepare(stored.id());
            var driftingStore=org.mockito.Mockito.mock(RagPdfDraftStore.class);
            org.mockito.Mockito.when(driftingStore.load(stored.id())).thenReturn(firstInput,alternateInput,alternateInput);
            var driftingWriter=new RagPdfGenerationStore(db,manager,candidates,recovery,driftingStore);
            assertThatThrownBy(()->driftingWriter.create(stored.id(),alternatePrepared.manifest())).isInstanceOf(IllegalStateException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_recovery_generations WHERE document_id=?",Integer.class,document)).isOne();
            assertThatThrownBy(()->writer.create(stored.id(),"0".repeat(64))).isInstanceOf(IllegalStateException.class);
            var generation=writer.create(stored.id(),prepared.manifest());
            assertThat(generation.state()).isEqualTo("PENDING");
            assertThat(generation.sourceHash()).isEqualTo(draft.sourceHash());
            assertThat(writer.create(stored.id(),prepared.manifest())).isEqualTo(generation);
            assertThat(recovery.findPointer(document,false)).isEqualTo(pointer);
            var rows=recovery.findCandidateChunks(generation.id());
            assertThat(rows).hasSize(prepared.chunks().size());
            assertThat(rows).extracting(com.kaces.pandora.rag.document.RagDocumentChunkRow::chunkText)
                .containsExactlyElementsOf(prepared.chunks().stream().map(com.kaces.pandora.rag.document.RagDocumentChunkRow::chunkText).toList());
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND use_yn='Y'",Integer.class,document)).isOne();
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_pdf_generation_sources WHERE generation_id=?",Integer.class,generation.id())).isOne();
            assertThat(db.queryForObject("SELECT JSON_EXTRACT(prepared_json,'$.units[0].positions[1].page') FROM rag_pdf_generation_sources WHERE generation_id=?",String.class,generation.id())).isEqualTo("2");
            assertThat(recovery.findAssets(generation.id())).isNull();
            assertThat(recovery.findPreparation(generation.id())).isNull();
            assertThat(recovery.findEmbeddingReceipts(generation.id())).isEmpty();
            String preserved=new TransactionTemplate(manager).execute(status->candidates.lockedStoredBaselineHash(document));
            assertThat(preserved).isEqualTo(draft.baselineHash());
            String preparedJson=db.queryForObject("SELECT prepared_json FROM rag_pdf_generation_sources WHERE generation_id=?",String.class,generation.id());
            db.update("UPDATE rag_pdf_generation_sources SET prepared_json='{}' WHERE generation_id=?",generation.id());
            assertThatThrownBy(()->writer.create(stored.id(),prepared.manifest())).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_pdf_generation_sources SET prepared_json=? WHERE generation_id=?",preparedJson,generation.id());
            String foreignHash="9".repeat(64);
            db.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash) VALUES('official_doc','Unrelated source','foreign.pdf','foreign',?)",foreignHash);
            long foreignDocument=db.queryForObject("SELECT document_id FROM rag_documents WHERE file_hash=?",Long.class,foreignHash);
            db.update("INSERT INTO rag_document_chunks(document_id,chunk_version,chunk_text,embedding_text,content_hash) VALUES(?,4,'Unrelated text.','Unrelated text.',?)",foreignDocument,foreignHash);
            long foreignChunk=db.queryForObject("SELECT chunk_id FROM rag_document_chunks WHERE document_id=?",Long.class,foreignDocument);
            db.update("INSERT INTO rag_recovery_members(generation_id,chunk_id) VALUES(?,?)",generation.id(),foreignChunk);
            try {
                assertThatThrownBy(()->writer.create(stored.id(),prepared.manifest())).isInstanceOf(IllegalStateException.class);
            } finally {
                db.update("DELETE FROM rag_recovery_members WHERE generation_id=? AND chunk_id=?",generation.id(),foreignChunk);
                db.update("DELETE FROM rag_documents WHERE document_id=?",foreignDocument);
            }
            db.update("UPDATE rag_document_chunks SET chunk_text='tampered' WHERE chunk_id=?",rows.get(0).chunkId());
            assertThatThrownBy(()->writer.create(stored.id(),prepared.manifest())).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->writer.validate(generation.id(),prepared.manifest())).isInstanceOf(IllegalStateException.class);
            assertThat(recovery.findGeneration(generation.id(),false).state()).isEqualTo("PENDING");
            db.update("UPDATE rag_document_chunks SET chunk_text=? WHERE chunk_id=?",rows.get(0).chunkText(),rows.get(0).chunkId());
            assertThatThrownBy(()->writer.validate(generation.id(),"0".repeat(64))).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_recovery_pointers SET revision=1 WHERE document_id=?",document);
            try {assertThatThrownBy(()->writer.validate(generation.id(),prepared.manifest())).isInstanceOf(IllegalStateException.class);}
            finally {db.update("UPDATE rag_recovery_pointers SET revision=0 WHERE document_id=?",document);}
            assertThat(recovery.findGeneration(generation.id(),false).state()).isEqualTo("PENDING");
            var validated=writer.validate(generation.id(),prepared.manifest());
            assertThat(validated.state()).isEqualTo("VALIDATED");
            assertThat(validated.manifest()).isEqualTo(generation.manifest());
            assertThat(recovery.findPointer(document,false)).isEqualTo(pointer);
            assertThat(recovery.findAssets(generation.id())).isNull();
            assertThat(recovery.findPreparation(generation.id())).isNull();
            assertThat(recovery.findEmbeddingReceipts(generation.id())).isEmpty();
            assertThat(db.queryForObject("SELECT validation_evidence FROM rag_recovery_generations WHERE generation_id=?",String.class,generation.id()))
                .contains("reviewed-pdf-validation-v1",stored.reviewHash(),prepared.manifest(),prepared.extractionHash(),generation.id(),
                    hash(preparedJson.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var readback=new RagPdfValidatedSourceReader(db);
            var transaction=new TransactionTemplate(manager);
            assertThatThrownBy(()->readback.find(validated,pointer,rows)).isInstanceOf(IllegalStateException.class);
            var proof=transaction.execute(status->readback.find(validated,pointer,rows)).orElseThrow();
            assertThat(proof.source().sourceHash()).isEqualTo(draft.sourceHash());
            assertThat(proof.source().title()).isEqualTo(draft.title());
            assertThat(proof.preparedManifest()).isEqualTo(prepared.manifest());
            assertThatThrownBy(()->transaction.execute(status->readback.find(generation,pointer,rows))).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->transaction.execute(status->readback.find(validated,new RecoverySnapshot(document,1,baselineId,originalHash,draft.baselineHash()),rows)))
                .isInstanceOf(IllegalStateException.class);
            String changedLocator=preparedJson.replace("\"key\":\"procedure\"","\"key\":\"substituted\"");
            assertThat(changedLocator).isNotEqualTo(preparedJson);
            db.update("UPDATE rag_pdf_generation_sources SET prepared_json=? WHERE generation_id=?",changedLocator,generation.id());
            try {assertThatThrownBy(()->transaction.execute(status->readback.find(validated,pointer,rows))).isInstanceOf(IllegalStateException.class);}
            finally {db.update("UPDATE rag_pdf_generation_sources SET prepared_json=? WHERE generation_id=?",preparedJson,generation.id());}
            byte[] changedBytes=firstInput.sourceBytes();changedBytes[10]^=1;
            db.update("UPDATE rag_pdf_source_drafts SET source_bytes=? WHERE draft_id=?",changedBytes,stored.id());
            try {assertThatThrownBy(()->transaction.execute(status->readback.find(validated,pointer,rows))).isInstanceOf(IllegalStateException.class);}
            finally {db.update("UPDATE rag_pdf_source_drafts SET source_bytes=? WHERE draft_id=?",firstInput.sourceBytes(),stored.id());}
            long duplicate=transaction.execute(status->{recovery.insertInactiveChunk(rows.get(0));return recovery.lastChunkId();});
            db.update("UPDATE rag_recovery_members SET chunk_id=? WHERE generation_id=? AND chunk_id=?",duplicate,generation.id(),rows.get(0).chunkId());
            try {
                var remapped=recovery.findCandidateChunks(generation.id());
                assertThat(remapped.stream().map(RagRecoverySourceValidator::unassigned).toList())
                    .isEqualTo(rows.stream().map(RagRecoverySourceValidator::unassigned).toList());
                assertThatThrownBy(()->transaction.execute(status->readback.find(validated,pointer,remapped))).isInstanceOf(IllegalStateException.class);
            } finally {
                db.update("UPDATE rag_recovery_members SET chunk_id=? WHERE generation_id=? AND chunk_id=?",rows.get(0).chunkId(),generation.id(),duplicate);
                db.update("DELETE FROM rag_document_chunks WHERE chunk_id=?",duplicate);
            }
            prepareReviewedAssets(db,session,manager,candidates,validated,readback,pointer,rows);
            db.update("DELETE FROM rag_pdf_generation_sources WHERE generation_id=?",generation.id());
            db.update("DELETE FROM rag_recovery_members WHERE generation_id=?",generation.id());
            db.update("DELETE FROM rag_recovery_generations WHERE generation_id=?",generation.id());
            db.update("DELETE FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",document);
            String trigger="fail_prepared_"+UUID.randomUUID().toString().replace("-","");
            db.execute("CREATE TRIGGER "+trigger+" BEFORE INSERT ON rag_pdf_generation_sources FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='prepared rollback control'");
            try {
                assertThatThrownBy(()->writer.create(stored.id(),prepared.manifest())).isInstanceOf(RuntimeException.class);
                assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_recovery_generations WHERE document_id=?",Integer.class,document)).isOne();
                assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Integer.class,document)).isZero();
                assertThat(recovery.findPointer(document,false)).isEqualTo(pointer);
            } finally {db.execute("DROP TRIGGER "+trigger);}
        } finally {
            db.update("DELETE FROM rag_pdf_generation_sources WHERE generation_id IN (SELECT generation_id FROM rag_recovery_generations WHERE document_id=?)",document);
            db.update("DELETE FROM rag_recovery_pointers WHERE document_id=?",document);
            db.update("DELETE FROM rag_recovery_baselines WHERE document_id=?",document);
            db.update("DELETE FROM rag_recovery_members WHERE generation_id IN (SELECT generation_id FROM rag_recovery_generations WHERE document_id=?)",document);
            db.update("DELETE FROM rag_recovery_generations WHERE document_id=?",document);
            db.update("DELETE FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",document);
        }
    }

    private static void prepareReviewedAssets(JdbcTemplate db,org.mybatis.spring.SqlSessionTemplate session,DataSourceTransactionManager manager,
            RagRecoveryCandidateService candidates,RecoveryGeneration generation,RagPdfValidatedSourceReader readback,
            RecoverySnapshot preserved,List<com.kaces.pandora.rag.document.RagDocumentChunkRow> rows) {
        var recovery=session.getMapper(RagRecoveryMapper.class);
        var documents=session.getMapper(com.kaces.pandora.rag.persistence.RagDocumentMapper.class);
        var lexical=session.getMapper(com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.class);
        var index=new com.kaces.pandora.semantic.lexical.SemanticLexicalIndexService(lexical,new com.kaces.pandora.semantic.lexical.KoreanLexicalTokenizer());
        var routing=new RagRecoveryRoutingService(recovery,lexical,manager);routing.configurePdfSources(new RagPdfSourceRoutingReader(db));
        var embeddings=org.mockito.Mockito.mock(com.kaces.pandora.infra.openai.OpenAiEmbeddingClient.class);
        var q=org.mockito.Mockito.mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
        var properties=new com.kaces.pandora.semantic.config.LawAiProperties(null,
            new com.kaces.pandora.semantic.config.LawAiProperties.Qdrant("http://127.0.0.1:1","unused-law","unused-rag",3),null,null);
        var receipts=new RagRecoveryReceiptService(documents,recovery,manager,properties,readback);
        var preparer=new RagRecoveryIndexService(documents,recovery,candidates,receipts,embeddings,q,lexical,index,manager,
                properties,generation.productSha(),routing,readback);
        String id=generation.id(),collection="rag_recovery_"+id.replace("-",""),version="recovery_"+id;
        assertThatThrownBy(()->preparer.prepare(id)).isInstanceOf(IllegalStateException.class);
        var binding=db.queryForMap("SELECT * FROM rag_pdf_generation_sources WHERE generation_id=?",id);
        // Same-byte classification must not fall back to legacy when the typed binding disappears.
        db.update("UPDATE rag_documents SET file_hash=? WHERE document_id=?",generation.sourceHash(),generation.documentId());
        db.update("DELETE FROM rag_pdf_generation_sources WHERE generation_id=?",id);
        try {
            var vectors=new TreeMap<Long,List<Double>>();rows.forEach(row->vectors.put(row.chunkId(),List.of(1.0,2.0,3.0)));
            assertThatThrownBy(()->receipts.save(id,vectors)).isInstanceOf(IllegalStateException.class);
            var legacy=new RagRecoveryValidationService(documents,recovery,new com.kaces.pandora.rag.storage.LocalRagOriginalDocumentStore(),manager,generation.productSha());
            assertThatThrownBy(()->legacy.validate(id)).isInstanceOf(IllegalStateException.class);
            assertThat(recovery.findGeneration(id,false).state()).isEqualTo("VALIDATED");
            assertThat(recovery.findGenerationLexicalChunksForSource(id,null)).isEmpty();
            db.update("UPDATE rag_recovery_generations SET state='ACTIVE' WHERE generation_id=?",id);
            var pointer=new RecoverySnapshot(generation.documentId(),preserved.revision()+1,id,generation.sourceHash(),generation.baselineHash());
            assertThatThrownBy(()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(
                    status->new RagPdfSourceRoutingReader(db).capture(Map.of(generation.documentId(),pointer),Set.of())))
                    .isInstanceOf(IllegalStateException.class).hasMessage("Replacement source binding missing");
        } finally {
            db.update("UPDATE rag_recovery_generations SET state='VALIDATED' WHERE generation_id=?",id);
            db.update("DELETE FROM rag_recovery_embedding_receipts WHERE generation_id=?",id);
            db.update("UPDATE rag_documents SET file_hash=? WHERE document_id=?",preserved.sourceHash(),generation.documentId());
            db.update("INSERT INTO rag_pdf_generation_sources(generation_id,draft_id,source_hash,review_hash,manifest,prepared_manifest,prepared_json,product_sha,metadata_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                    id,binding.get("draft_id"),binding.get("source_hash"),binding.get("review_hash"),binding.get("manifest"),binding.get("prepared_manifest"),
                    binding.get("prepared_json"),binding.get("product_sha"),binding.get("metadata_hash"));
        }
        String evidence=db.queryForObject("SELECT validation_evidence FROM rag_recovery_generations WHERE generation_id=?",String.class,id);
        db.update("UPDATE rag_recovery_generations SET validation_evidence='[]' WHERE generation_id=?",id);
        try {assertThatThrownBy(()->preparer.prepareReviewedPdf(id)).isInstanceOf(IllegalStateException.class);}
        finally {db.update("UPDATE rag_recovery_generations SET validation_evidence=? WHERE generation_id=?",evidence,id);}
        org.mockito.Mockito.verifyNoInteractions(embeddings,q);
        assertThat(recovery.findPreparation(id)).isNull();
        org.mockito.Mockito.when(embeddings.embed(org.mockito.ArgumentMatchers.anyList())).thenReturn(Collections.nCopies(rows.size(),List.of(1.0,2.0,3.0)));
        org.mockito.Mockito.when(q.ragRecoveryCollection(id)).thenReturn(collection);
        org.mockito.Mockito.when(q.inspectRagRecoveryPoints(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList()))
            .thenReturn(new com.kaces.pandora.infra.qdrant.QdrantClient.RecoveryPointInspection(null,
                    rows.stream().map(com.kaces.pandora.rag.document.RagDocumentChunkRow::chunkId).collect(java.util.stream.Collectors.toSet())));
        org.mockito.Mockito.when(q.verifyRagRecoveryCollection(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyList(),org.mockito.ArgumentMatchers.anyList())).thenReturn("e".repeat(64));
        try {
            var assets=preparer.prepareReviewedPdf(id);
            assertThat(assets.chunkCount()).isEqualTo(rows.size());
            assertThat(recovery.findGeneration(id,false).state()).isEqualTo("INDEX_READY");
            assertThat(recovery.findPointer(generation.documentId(),false)).isEqualTo(preserved);
            assertThat(recovery.verifiedEmbeddingCount(id)).isEqualTo(rows.size());
            assertThat(db.queryForList("SELECT DISTINCT term FROM semantic_lexical_terms WHERE index_version=? AND target='official_doc' AND field_kind='document_title'",String.class,version))
                .contains("official","rule");
            preparer.verifyReviewedPdf(assets);
            assertThatThrownBy(()->preparer.verify(assets)).isInstanceOf(IllegalStateException.class);
        } finally {
            db.update("DELETE FROM rag_recovery_embedding_receipts WHERE generation_id=?",id);
            db.update("DELETE FROM rag_recovery_assets WHERE generation_id=?",id);
            db.update("DELETE FROM rag_recovery_preparations WHERE generation_id=?",id);
            db.update("DELETE FROM semantic_lexical_index_state WHERE index_version=?",version);
        }
    }

    private static void capturedSourceMetadataIsVersionSpecific(JdbcTemplate db,org.mybatis.spring.SqlSessionTemplate session,
            ReviewedPdfDraft draft,RagPdfDraftStore.Staged stored,RagPdfDraftPreparation.Prepared prepared,
            String oldTitle,String oldHash) throws Exception {
        long document=draft.documentId();
        long oldChunk=db.queryForObject("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='Y'",Long.class,document);
        String old=UUID.randomUUID().toString(),fresh=UUID.randomUUID().toString();
        String title=draft.title(),url=draft.sourceUrl(),lexical="f".repeat(64);
        db.update("INSERT INTO rag_recovery_generations(generation_id,document_id,source_hash,product_sha,baseline_hash,manifest,preview_hash,state) VALUES(?,?,?,?,?,?,?,'RETIRED')",
                old,document,oldHash,draft.productSha(),draft.baselineHash(),"a".repeat(64),"a".repeat(64));
        db.update("INSERT INTO rag_recovery_generations(generation_id,document_id,source_hash,product_sha,baseline_hash,manifest,preview_hash,state) VALUES(?,?,?,?,?,?,?,'ACTIVE')",
                fresh,document,draft.sourceHash(),draft.productSha(),draft.baselineHash(),prepared.manifest(),"b".repeat(64));
        var member=prepared.chunks().stream().filter(c->c.chunkNo().equals("reviewed:procedure")).findFirst().orElseThrow();
        db.update("INSERT INTO rag_document_chunks(document_id,chunk_version,chunk_no,chunk_title,chunk_text,embedding_text,content_hash,source_path,sort_order,use_yn) VALUES(?,4,?,?,?,?,?,?,9,'N')",
                document,member.chunkNo(),member.chunkTitle(),member.chunkText(),member.embeddingText(),member.contentHash(),member.sourcePath());
        long newChunk=db.queryForObject("SELECT chunk_id FROM rag_document_chunks WHERE document_id=? AND use_yn='N'",Long.class,document);
        try {
            db.update("INSERT INTO rag_recovery_members(generation_id,chunk_id) VALUES(?,?)",old,oldChunk);
            db.update("INSERT INTO rag_recovery_members(generation_id,chunk_id) VALUES(?,?)",fresh,newChunk);
            var prior=new RecoveryRoutingSnapshot(0,Map.of(document,new RecoverySnapshot(document,0,old,oldHash,draft.baselineHash())),
                    Map.of(),Set.of(old),null,null);
            var assets=new RecoveryAssets(fresh,prepared.manifest(),"rag_recovery_"+fresh.replace("-",""),"e".repeat(64),lexical,1);
            var source=new RecoveryPdfSource(fresh,document,stored.id(),draft.sourceHash(),stored.reviewHash(),prepared.manifest(),title,url);
            var pointer=new RecoverySnapshot(document,1,fresh,draft.sourceHash(),draft.baselineHash());
            var reader=new RagPdfSourceRoutingReader(db);
            var tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
            tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
            assertThatThrownBy(()->tx.execute(status->reader.capture(Map.of(document,pointer),Set.of())))
                .isInstanceOf(IllegalStateException.class);
            String receipt=source.receiptHash(prepared.manifest(),draft.productSha());
            db.update("INSERT INTO rag_pdf_generation_sources(generation_id,draft_id,source_hash,review_hash,manifest,prepared_manifest,prepared_json,product_sha,metadata_hash) VALUES(?,?,?,?,?,?,?,?,?)",
                    fresh,stored.id(),draft.sourceHash(),stored.reviewHash(),prepared.manifest(),prepared.manifest(),new tools.jackson.databind.ObjectMapper().writeValueAsString(prepared),draft.productSha(),receipt);
            Map<String,RecoveryPdfSource> captured=tx.execute(status->reader.capture(Map.of(document,pointer),Set.of()));
            assertThat(captured).containsExactlyEntriesOf(Map.of(fresh,source));
            db.update("UPDATE rag_pdf_generation_sources SET metadata_hash=? WHERE generation_id=?","0".repeat(64),fresh);
            assertThatThrownBy(()->tx.execute(status->reader.capture(Map.of(document,pointer),Set.of())))
                .isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_pdf_generation_sources SET metadata_hash=? WHERE generation_id=?",receipt,fresh);
            var current=new RecoveryRoutingSnapshot(1,Map.of(document,new RecoverySnapshot(document,1,fresh,draft.sourceHash(),draft.baselineHash())),
                    Map.of(fresh,assets),Set.of(),lexical,"recovery_"+fresh,
                    com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot.empty(),Map.of(fresh,source));
            var mapper=session.getMapper(com.kaces.pandora.rag.persistence.RagDocumentMapper.class);
            var ids=List.of(oldChunk,newChunk);
            assertThat(mapper.findSourceProofChunksByIdsRouted(ids,prior)).singleElement().satisfies(row->{
                assertThat(row.chunkId()).isEqualTo(oldChunk);assertThat(row.sourceFileHash()).isEqualTo(oldHash);
            });
            assertThat(mapper.findSourceProofChunksByIdsRouted(ids,current)).singleElement().satisfies(row->{
                assertThat(row.chunkId()).isEqualTo(newChunk);assertThat(row.sourceFileHash()).isEqualTo(draft.sourceHash());
            });
            assertThat(mapper.findSemanticChunksByIdsRouted(ids,prior)).singleElement().satisfies(row->{
                assertThat(row.title()).isEqualTo(oldTitle);assertThat(row.agencyName()).isEqualTo("Legacy agency");
                assertThat(row.sourceDate()).isEqualTo("19990101");
            });
            assertThat(mapper.findSemanticChunksByIdsRouted(ids,current)).singleElement().satisfies(row->{
                assertThat(row.title()).isEqualTo(title);assertThat(row.sourceUrl()).isEqualTo(url);
                assertThat(row.agencyName()).isNull();assertThat(row.sourceDate()).isNull();
            });
            assertThat(mapper.findSemanticChunksByDocumentTitleScopedCaptured(List.of("official_doc"),List.of(title),10,current))
                .extracting(com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow::chunkId).containsExactly(newChunk);
            assertThat(mapper.findSemanticChunksByDocumentTitleScopedCaptured(List.of("official_doc"),List.of(title),10,prior)).isEmpty();
            assertThat(mapper.findSemanticChunksByDocumentTitleScopedCaptured(List.of("official_doc"),List.of(oldTitle),10,current)).isEmpty();
            var recovery=session.getMapper(RagRecoveryMapper.class);
            assertThat(recovery.findGenerationLexicalChunksForSource(fresh,source)).singleElement().satisfies(row->{
                assertThat(row.documentTitle()).isEqualTo(title);assertThat(row.documentId()).isEqualTo(document);
            });
            assertThat(recovery.findGenerationLexicalChunksForSource(old,null)).singleElement().satisfies(row->assertThat(row.documentTitle()).isEqualTo(oldTitle));
            assertThat(recovery.findGenerationLexicalChunksForSource(fresh,null)).isEmpty();
            assertThat(recovery.findGenerationLexicalChunksForSource(old,source)).isEmpty();
            var originals=new RagPdfOriginalReader(db);
            byte[] sourceBytes=originals.read(source);
            assertThat(hash(sourceBytes)).isEqualTo(draft.sourceHash());
            assertThat(originals.open(document,fresh).source()).isEqualTo(source);
            assertThat(originals.open(document,fresh).bytes()).isEqualTo(sourceBytes);
            assertThatThrownBy(()->originals.open(document+1,fresh)).isInstanceOf(IllegalStateException.class);
            var wrongTitle=new RecoveryPdfSource(fresh,document,stored.id(),draft.sourceHash(),stored.reviewHash(),prepared.manifest(),"Wrong edition",url);
            assertThatThrownBy(()->originals.read(wrongTitle)).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_recovery_generations SET state='RETIRED' WHERE generation_id=?",fresh);
            assertThat(originals.read(source)).isEqualTo(sourceBytes);
            db.update("UPDATE rag_recovery_generations SET state='PENDING' WHERE generation_id=?",fresh);
            assertThatThrownBy(()->originals.read(source)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->originals.open(document,fresh)).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_recovery_generations SET state='RETIRED' WHERE generation_id=?",fresh);
            db.update("UPDATE rag_pdf_source_drafts SET source_bytes=? WHERE draft_id=?",new byte[]{1,2,3},stored.id());
            assertThatThrownBy(()->originals.read(source)).isInstanceOf(IllegalStateException.class);
            db.update("UPDATE rag_pdf_source_drafts SET source_bytes=? WHERE draft_id=?",sourceBytes,stored.id());
        } finally {
            db.update("DELETE FROM rag_pdf_generation_sources WHERE generation_id IN (?,?)",old,fresh);
            db.update("DELETE FROM rag_recovery_members WHERE generation_id IN (?,?)",old,fresh);
            db.update("DELETE FROM rag_recovery_generations WHERE generation_id IN (?,?)",old,fresh);
            db.update("DELETE FROM rag_document_chunks WHERE chunk_id=?",newChunk);
        }
    }

    private static void concurrentReadAndRestage(javax.sql.DataSource ds,DataSourceTransactionManager manager,
            RagRecoveryCandidateService candidates,ReviewedPdfDraft draft,Path file,RagPdfDraftStore.Staged first)throws Exception {
        var readTaken=new java.util.concurrent.CountDownLatch(1);
        var releaseRead=new java.util.concurrent.CountDownLatch(1);
        var documentLocked=new java.util.concurrent.CountDownLatch(1);
        var observed=new JdbcTemplate(ds) {
            @Override public List<Map<String,Object>> queryForList(String sql,Object... args) {
                var result=super.queryForList(sql,args);
                if(sql.equals("SELECT * FROM rag_pdf_source_drafts WHERE draft_id=?")) {
                    readTaken.countDown();
                    try {if(!releaseRead.await(12,java.util.concurrent.TimeUnit.SECONDS))throw new IllegalStateException("read release deadline");}
                    catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IllegalStateException(interrupted);}
                }
                if(sql.startsWith("SELECT document_type FROM rag_documents"))documentLocked.countDown();
                return result;
            }
        };
        var store=new RagPdfDraftStore(observed,manager,candidates,draft.productSha(),Set.of(draft.reviewHash()));
        var workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var read=workers.submit(()->store.load(first.id()));
            assertThat(readTaken.await(12,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            var stage=workers.submit(()->store.stage(draft,file));
            assertThat(documentLocked.await(12,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            releaseRead.countDown();
            assertThat(read.get(12,java.util.concurrent.TimeUnit.SECONDS).draft()).isEqualTo(draft);
            assertThat(stage.get(12,java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(first);
        } finally {releaseRead.countDown();workers.shutdownNow();}
    }

    private static String hash(byte[] bytes)throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static void writePdf(Path file)throws Exception {
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
            for(String literal:List.of("Notify before launch.","Keep the written decision.")) {
                var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
                try(var text=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                    text.beginText();text.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                    text.newLineAtOffset(40,700);text.showText(literal);text.endText();
                }
            }
            pdf.save(file.toFile());
        }
    }
}
