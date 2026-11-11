package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.lexical.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.*;

/** Exact local source snapshot; no production writes, OpenAI requests or answer-quality claim. */
@EnabledIfEnvironmentVariable(named="PANDORA_IMPORT_TX_TEST_URL",matches="jdbc:mariadb://127[.]0[.]0[.]1:23306/pandora_import_tx_test(?:[?].*)?")
class RagReviewedSourceSnapshotDatabaseTests {
    @TempDir Path temporary;
    @Test
    @EnabledIfEnvironmentVariable(named="PANDORA_REVIEWED_SOURCE_SNAPSHOT",matches=".+")
    void originalSnapshotAndReviewedPdfSwitchAndRestoreInIsolation() throws Exception {
        RagRecoveryQdrantIntegrationTests.assertDedicatedServer();
        Path owned=Path.of(".superpowers/sdd/2026-11-11-reviewed-pdf-source-staging").toRealPath();
        Path root=Path.of(System.getenv("PANDORA_REVIEWED_SOURCE_SNAPSHOT")).toRealPath();
        assertThat(root.startsWith(owned)).isTrue();
        var json=new ObjectMapper();
        byte[] sourceBytes=Files.readAllBytes(root.resolve("snapshot.json")),qBytes=Files.readAllBytes(root.resolve("q-baseline.json"));
        String sourceDigest=hash(sourceBytes),qDigest=hash(qBytes);
        assertThat(sourceDigest).isEqualTo(System.getenv("PANDORA_REVIEWED_SOURCE_SNAPSHOT_SHA256"));
        assertThat(qDigest).isEqualTo(System.getenv("PANDORA_REVIEWED_Q_SNAPSHOT_SHA256"));
        var source=json.readTree(sourceBytes);var qSnapshot=json.readTree(qBytes);
        assertThat(source.path("kind").asText()).isEqualTo("pandora-original-readonly-v1");
        assertThat(qSnapshot.path("kind").asText()).isEqualTo("pandora-original-q-readonly-v1");
        assertThat(qSnapshot.path("databaseSnapshot").asText()).isEqualTo(sourceDigest);
        long document=source.path("documentId").asLong();
        assertThat(qSnapshot.path("documentId").asLong()).isEqualTo(document);
        String originalHash=source.path("sourceHash").asText(),product="a".repeat(40);
        Path original=root.resolve(source.path("originalFile").asText()).normalize();
        assertThat(original.getParent()).isEqualTo(root);
        assertThat(hash(Files.readAllBytes(original))).isEqualTo(originalHash);
        Path pdf=Path.of(System.getenv("PANDORA_REVIEWED_SOURCE_PDF")).toRealPath();
        String newHash=hash(Files.readAllBytes(pdf));
        assertThat(newHash).isEqualTo(System.getenv("PANDORA_REVIEWED_SOURCE_PDF_SHA256"));
        var ds=new org.mariadb.jdbc.MariaDbDataSource();
        ds.setUrl(System.getenv("PANDORA_IMPORT_TX_TEST_URL"));ds.setUser("root");ds.setPassword(System.getenv("PANDORA_IMPORT_TX_TEST_PASSWORD"));
        var db=new JdbcTemplate(ds);
        assertThat(db.queryForObject("SELECT @@port",Integer.class)).isEqualTo(23306);
        assertThat(db.queryForObject("SELECT DATABASE()",String.class)).isEqualTo("pandora_import_tx_test");
        Path directory=Path.of(System.getenv("PANDORA_IMPORT_TX_TEST_DATADIR")).toRealPath();
        assertThat(directory.getFileName().toString()).startsWith("pandora-import-tx-test-");
        assertThat(Path.of(db.queryForObject("SELECT @@datadir",String.class)).toRealPath()).isEqualTo(directory);
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        new SemanticLexicalSchemaMaintenance(db).run(null);
        assertThat(db.queryForObject("SELECT COUNT(*) FROM rag_documents",Integer.class)).isZero();
        importRows(db,source.path("tables"));
        var manager=new DataSourceTransactionManager(ds);
        var factory=new org.mybatis.spring.SqlSessionFactoryBean();factory.setDataSource(ds);
        factory.setMapperLocations(new ClassPathResource("mapper/law/RagDocumentMapper.xml"),new ClassPathResource("mapper/law/RagRecoveryMapper.xml"),
                new ClassPathResource("mapper/law/SemanticLexicalMapper.xml"));
        var session=new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        var documents=session.getMapper(RagDocumentMapper.class);var recovery=session.getMapper(RagRecoveryMapper.class);
        var lexical=session.getMapper(SemanticLexicalMapper.class);
        var originals=mock(RagOriginalDocumentStore.class);when(originals.materialize(any())).thenReturn(original);
        var candidates=new RagRecoveryCandidateService(documents,recovery,originals,manager);
        String collection=qSnapshot.path("collection").asText();
        assertThat(collection).isEqualTo("rag_chunks_v4");
        int dimensions=qSnapshot.path("vectorSize").asInt();assertThat(dimensions).isEqualTo(1536);
        var properties=new LawAiProperties(null,new LawAiProperties.Qdrant(System.getenv("PANDORA_RECOVERY_Q_TEST_URL"),"law_chunks",collection,dimensions),null,null);
        var q=new QdrantClient(properties,json);
        var http=RestClient.builder().baseUrl(System.getenv("PANDORA_RECOVERY_Q_TEST_URL")).build();
        boolean baselineCreated=false;String generationId=null;
        try {
            assertThatThrownBy(()->http.get().uri("/collections/{name}",collection).retrieve().toBodilessEntity())
                    .isInstanceOf(org.springframework.web.client.HttpClientErrorException.NotFound.class);
            http.put().uri("/collections/{name}",collection).body(Map.of("vectors",Map.of("size",dimensions,"distance","Cosine"))).retrieve().toBodilessEntity();
            baselineCreated=true;
            http.put().uri("/collections/{name}/points?wait=true",collection).body(Map.of("points",qSnapshot.path("points"))).retrieve().toBodilessEntity();
            var baselineService=new RagRecoveryBaselineService(documents,recovery,candidates,q,manager,properties);
            var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
            String baseline=tx.execute(status->candidates.lockedStoredBaselineHash(document));
            var draft=draft(document,newHash,baseline,product,pdf);
            Path manifest=temporary.resolve("review.json");
            String input=json.writeValueAsString(new RagPdfTrustedInput.Input(draft,pdf.toString()));Files.writeString(manifest,input);
            var routing=new RagRecoveryRoutingService(recovery,lexical,manager);routing.configurePdfSources(new RagPdfSourceRoutingReader(db));
            var embeddings=mock(OpenAiEmbeddingClient.class);
            var vector=new ArrayList<Double>(Collections.nCopies(dimensions,0.0));vector.set(0,1.0);
            when(embeddings.embed(anyList())).thenAnswer(call->Collections.nCopies(((List<?>)call.getArgument(0)).size(),List.copyOf(vector)));
            var lexicalIndex=new SemanticLexicalIndexService(lexical,new KoreanLexicalTokenizer());
            var readback=new RagPdfValidatedSourceReader(db);
            var receipts=new RagRecoveryReceiptService(documents,recovery,manager,properties,readback);
            var index=new RagRecoveryIndexService(documents,recovery,candidates,receipts,embeddings,q,lexical,lexicalIndex,manager,properties,product,routing,readback);
            var promotion=new RagRecoveryPromotionService(index,recovery,baselineService,manager);
            var rollback=new RagRecoveryRollbackService(recovery,documents,routing,baselineService,lexical,lexicalIndex,manager);
            var workflow=new RagPdfWorkflowService(db,manager,documents,recovery,candidates,baselineService,index,promotion,rollback,
                    product,true,true,manifest.toString(),LawHashUtils.sha256(input));
            var originalRows=db.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? AND use_yn='Y' ORDER BY chunk_id",document);
            var receipt=workflow.register();
            assertThat(receipt.chunkCount()).isEqualTo(qSnapshot.path("points").size());
            var before=routing.capture();
            String baselineFingerprint=q.baselineRagFingerprint(documents.findSemanticChunksByDocumentId(document).stream()
                    .filter(c->Set.of("PASS","REVIEW").contains(c.qualityStatus())).toList());
            var staged=workflow.stage();var preparation=workflow.inspect(staged.id());
            var candidate=workflow.create(staged.id(),preparation.manifest());generationId=candidate.id();
            assertThat(workflow.validate(candidate.id(),preparation.manifest()).state()).isEqualTo("VALIDATED");
            var assets=workflow.prepare(candidate.id());
            assertThat(routing.capture()).isEqualTo(before);
            var pointer=workflow.promote(candidate.id(),0,assets.manifest());
            var after=routing.capture();
            assertThat(pointer.sourceHash()).isEqualTo(newHash);
            var selected=documents.findSemanticChunksByDocumentIdRouted(document,after);
            assertThat(selected).hasSize(preparation.chunks());
            assertThat(selected).allSatisfy(row->assertThat(row.title()).isEqualTo(draft.title()));
            assertThat(documents.findSemanticChunksByDocumentIdRouted(document,before)).hasSize(originalRows.size());
            assertThat(new RagPdfOriginalReader(db).open(document,candidate.id()).source().sourceHash()).isEqualTo(newHash);
            var restored=workflow.rollback(document,candidate.id(),pointer.revision(),receipt.generationId());
            assertThat(restored.sourceHash()).isEqualTo(originalHash);
            assertThat(routing.capture().pdfSources()).isEmpty();
            assertThat(db.queryForList("SELECT * FROM rag_document_chunks WHERE document_id=? AND use_yn='Y' ORDER BY chunk_id",document)).isEqualTo(originalRows);
            assertThat(q.baselineRagFingerprint(documents.findSemanticChunksByDocumentId(document).stream()
                    .filter(c->Set.of("PASS","REVIEW").contains(c.qualityStatus())).toList())).isEqualTo(baselineFingerprint);
            assertThat(hash(Files.readAllBytes(original))).isEqualTo(originalHash);
            System.out.println("ISOLATED_SOURCE original="+originalRows.size()+" replacement="+preparation.chunks()+" units="+preparation.units()
                    +" retainedOriginal=true syntheticEmbeddings=true baseline="+baseline+" review="+draft.reviewHash());
        } finally {
            if(generationId!=null)delete(http,q.ragRecoveryCollection(generationId));
            if(baselineCreated)delete(http,collection);
        }
    }
    private static void importRows(JdbcTemplate db,JsonNode tables) {
        for(String table:List.of("rag_documents","rag_document_chunks","rag_chunk_embeddings","rag_chunk_search_index_state","rag_chunk_search_terms")) {
            for(var row:tables.path(table)) {
                var columns=new ArrayList<String>();var values=new ArrayList<Object>();
                row.properties().forEach(entry->{
                    if(!entry.getKey().matches("[a-z][a-z0-9_]*"))throw new IllegalArgumentException("Invalid captured column");
                    columns.add("`"+entry.getKey()+"`");values.add(entry.getValue().isNull()?null:entry.getValue().asText());
                });
                db.update("INSERT INTO "+table+"("+String.join(",",columns)+") VALUES("+String.join(",",Collections.nCopies(columns.size(),"?"))+")",values.toArray());
            }
        }
    }
    private static ReviewedPdfDraft draft(long document,String source,String baseline,String product,Path pdf) {
        var pages=com.kaces.pandora.lawdata.sync.SourcePdfInspection.normalizedDocument(pdf,source).pages();
        var ranges=List.of(new int[][]{{25,20,23}},new int[][]{{34,18,20}},new int[][]{{34,21,21},{35,2,5}},
                new int[][]{{35,8,24},{36,2,24},{37,2,23},{38,2,14}},new int[][]{{38,15,25},{39,2,13}},new int[][]{{39,21,22}},new int[][]{{149,2,5}});
        var keys=List.of("scope","timing","request","review-authority","omission","result","effective-date");
        var units=new ArrayList<ReviewedPdfDraft.Unit>();
        for(int i=0;i<ranges.size();i++) {
            var lines=new ArrayList<ReviewedPdfUnit.Line>();
            for(int[] range:ranges.get(i))for(int line=range[1];line<=range[2];line++)
                lines.add(new ReviewedPdfUnit.Line(range[0],line,pages.get(range[0]-1).text().lines().toList().get(line-1)));
            units.add(new ReviewedPdfDraft.Unit(keys.get(i),lines.get(0).literal().strip(),new ReviewedPdfUnit(lines)));
        }
        return new ReviewedPdfDraft(document,source,baseline,product,"국가 사이버보안 기본지침 (2026. 5. 1.)",
                "https://www.ncsc.go.kr/template/resources/file/nis_guide_lines_2023_1_31.pdf",units);
    }
    private static void delete(RestClient http,String collection) {
        try {http.delete().uri("/collections/{name}",collection).retrieve().toBodilessEntity();}
        catch(org.springframework.web.client.HttpClientErrorException.NotFound alreadyRemoved) { }
    }
    private static String hash(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
