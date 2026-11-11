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
        var manager=new DataSourceTransactionManager(ds);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/law/RagDocumentMapper.xml"),
                new ClassPathResource("mapper/law/RagRecoveryMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var candidates=new RagRecoveryCandidateService(session.getMapper(com.kaces.pandora.rag.persistence.RagDocumentMapper.class),
                session.getMapper(RagRecoveryMapper.class),null,manager);
        String tag=UUID.randomUUID().toString(), oldHash=hash(tag.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        db.update("INSERT INTO rag_documents(document_type,title,file_name,file_path,file_hash) VALUES('official_doc',?,'old.pdf','preserved',?)",tag,oldHash);
        long id=db.queryForObject("SELECT document_id FROM rag_documents WHERE file_hash=?",Long.class,oldHash);
        db.update("INSERT INTO rag_document_chunks(document_id,chunk_version,chunk_text,embedding_text,content_hash) VALUES(?,4,'Preserved baseline.','Preserved baseline.',?)",id,oldHash);
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
            db.update("UPDATE rag_document_chunks SET chunk_text='Preserved baseline.' WHERE document_id=?",id);
            // updated_at is part of the exact stored snapshot; use a new draft after this mutation.
            Files.write(file,new byte[]{1,2,3});
            assertThatThrownBy(()->store.stage(draft,file)).isInstanceOf(IllegalStateException.class);
            assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_pdf_source_drafts WHERE document_id=?",Integer.class,id)).isOne();
        } finally {
            db.update("DELETE FROM rag_pdf_source_drafts WHERE document_id=?",id);
            db.update("DELETE FROM rag_documents WHERE document_id=?",id);
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
