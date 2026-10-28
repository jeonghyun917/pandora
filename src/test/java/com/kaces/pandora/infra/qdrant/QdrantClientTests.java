package com.kaces.pandora.infra.qdrant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.semantic.config.LawAiProperties;
import com.kaces.pandora.semantic.search.QdrantSearchHit;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.HttpMessageNotWritableException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.DatabindException;
import tools.jackson.databind.ObjectMapper;

class QdrantClientTests {
    @Test void preservedLawBaselineBindsOriginalPointIdsPayloadAndVectorWithoutRewriting() throws Exception {
        var mode=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            String path=exchange.getRequestURI().getPath();
            assertThat(exchange.getRequestMethod()).isIn("GET","POST");
            assertThat(path).startsWith("/collections/law");
            if(path.endsWith("/points/count")){respond(exchange,200,"{\"result\":{\"count\":1}}");return;}
            if(path.endsWith("/points")) {
                var payload=new java.util.HashMap<String,Object>(java.util.Map.of("chunkId",200,"documentId",42,"target","admrul","chunkVersion",2,
                    "activationStatus","ACTIVE","sourcePath","$.AdmRulService.조문내용"));
                if(mode.get()==1)payload.put("documentId",43);
                if(mode.get()==2)payload.put("generationId","foreign");
                var vector=java.util.Collections.nCopies(1536,mode.get()==3?.2:.1);
                respond(exchange,200,new ObjectMapper().writeValueAsString(java.util.Map.of("result",java.util.List.of(java.util.Map.of("id",200,"payload",payload,"vector",vector)))));return;
            }
            respond(exchange,200,collectionWithQueue("green",1536,1,0,1,1));
        });server.start();client=client();
        String first=client.baselineLawFingerprint(java.util.List.of(sourceVersionRow()));
        assertThat(first).matches("[0-9a-f]{64}");
        for(int bad:new int[]{1,2}) {mode.set(bad);assertThatThrownBy(()->client.baselineLawFingerprint(java.util.List.of(sourceVersionRow()))).isInstanceOf(IllegalStateException.class);}
        mode.set(3);assertThat(client.baselineLawFingerprint(java.util.List.of(sourceVersionRow()))).isNotEqualTo(first);
    }
    private static LawSemanticChunkRow sourceVersionRow() {
        return new LawSemanticChunkRow(200,42,"admrul","source-42","지침",null,null,"20261019","PAST","제1조","시정","본문",null,"$.AdmRulService.조문내용",null,0,"a".repeat(64),null,"article","PASS","본문",null,2);
    }
    @Test void lawSourceVersionWritesUseOriginalLawIdentityInSeparateNamespace() throws Exception {
        var paths=new java.util.ArrayList<String>();var point=new AtomicReference<String>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            paths.add(exchange.getRequestURI().getPath());
            String body=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(exchange.getRequestURI().getPath().endsWith("/points"))point.set(body);
            respond(exchange,200,"{\"result\":{\"status\":\"completed\"}}");
        });server.start();client=client();
        client.upsertLawSourceVersion("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),java.util.List.of(sourceVersionRow()),java.util.List.of(java.util.Collections.nCopies(1536,.1)));
        assertThat(paths).allMatch(path->path.startsWith("/collections/law_source_c87a26f6cc344303bdc2929657369db3"));
        assertThat(point.get()).contains("\"id\":200","\"target\":\"admrul\"","\"chunkVersion\":2","\"activationStatus\":\"CANDIDATE\"");
    }
    @Test void lawSourceReadbackChecksCompleteNamespaceAndExactPayload() throws Exception {
        var mode=new AtomicInteger();var vector=java.util.Collections.nCopies(1536,.1);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            String path=exchange.getRequestURI().getPath();
            if(path.endsWith("/points/count")){respond(exchange,200,"{\"result\":{\"count\":"+(mode.get()==1?2:1)+"}}");return;}
            if(path.endsWith("/points")) {
                var payload=java.util.Map.of("chunkId",200,"documentId",42,"chunkVersion",2,"target",mode.get()==2?"law":"admrul","activationStatus","CANDIDATE",
                    "generationId","c87a26f6-cc34-4303-bdc2-929657369db3","contentHash","a".repeat(64),"embeddingModel","text-embedding-3-small","manifest","b".repeat(64),"qualityStatus","PASS");
                respond(exchange,200,new ObjectMapper().writeValueAsString(java.util.Map.of("result",java.util.List.of(java.util.Map.of("id",200,"payload",payload,"vector",mode.get()==3?java.util.Collections.nCopies(1536,0.0):vector)))));return;
            }
            respond(exchange,200,collectionWithQueue("green",1536,1,0,1,1));
        });server.start();client=client();
        assertThat(client.verifyLawSourceVersionCollection("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),java.util.List.of(sourceVersionRow()),java.util.List.of(vector))).matches("[0-9a-f]{64}");
        for(int bad:new int[]{1,2,3}) {mode.set(bad);assertThatThrownBy(()->client.verifyLawSourceVersionCollection("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),java.util.List.of(sourceVersionRow()),java.util.List.of(vector))).isInstanceOf(IllegalStateException.class);}
    }
    @Test void baselineReadbackBindsExistingVectorAndRejectsMissingOrForeignPoint() throws Exception {
        var mode=new AtomicInteger();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            String path=exchange.getRequestURI().getPath();
            if(path.endsWith("/points/count")){respond(exchange,200,"{\"result\":{\"count\":1}}");return;}
            if(path.endsWith("/points")) {
                if(mode.get()==1){respond(exchange,200,"{\"result\":[]}");return;}
                var payload=new java.util.HashMap<String,Object>(java.util.Map.of("chunkId",7,"documentId",mode.get()==2?99:42,"target","official_doc",
                    "chunkVersion",4,"activationStatus","ACTIVE","sourcePath","$.v4.chunks[0]"));
                if(mode.get()>=4)payload.remove("activationStatus");
                if(mode.get()==5)payload.put("activationStatus",null);
                if(mode.get()==6)payload.put("activationStatus","CANDIDATE");
                if(mode.get()==7)payload.put("activationStatus","RETIRED");
                if(mode.get()==8)payload.put("generationId","foreign-generation");
                if(mode.get()==9)payload.put("activationStatus","UNKNOWN");
                if(mode.get()==10)payload.put("manifest","b".repeat(64));
                var point=java.util.Map.of("id",QdrantClient.ragPointId(7),"payload",payload,
                    "vector",java.util.Collections.nCopies(1536,mode.get()==3?.2:.1));
                respond(exchange,200,new ObjectMapper().writeValueAsString(java.util.Map.of("result",java.util.List.of(point))));return;
            }
            respond(exchange,200,readyCollection(1536,1));
        });server.start();client=client();
        var row=new LawSemanticChunkRow(7L,42L,"official_doc","42","title",null,null,null,null,null,"title","body",1,
            "$.v4.chunks[0]",null,1,"a".repeat(64),"parent","body","PASS","body",null,4);
        assertThatThrownBy(()->client.baselineRagFingerprint(java.util.List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->client.baselineRagFingerprint(java.util.Arrays.asList((LawSemanticChunkRow)null))).isInstanceOf(IllegalArgumentException.class);
        String first=client.baselineRagFingerprint(java.util.List.of(row));
        assertThat(first).matches("[0-9a-f]{64}");
        assertThat(client.baselineRagFingerprint(java.util.List.of(row))).isEqualTo(first);
        for(int failure:new int[]{1,2}) {mode.set(failure);
            assertThatThrownBy(()->client.baselineRagFingerprint(java.util.List.of(row))).isInstanceOf(IllegalStateException.class);}
        mode.set(3);
        assertThat(client.baselineRagFingerprint(java.util.List.of(row))).isNotEqualTo(first);
        mode.set(4);
        String legacy=client.baselineRagFingerprint(java.util.List.of(row));
        assertThat(legacy).isNotEqualTo(first);
        assertThat(client.baselineRagFingerprint(java.util.List.of(row))).isEqualTo(legacy);
        for(int invalid:new int[]{5,6,7,8,9,10}) { mode.set(invalid);
            assertThatThrownBy(()->client.baselineRagFingerprint(java.util.List.of(row))).isInstanceOf(IllegalStateException.class); }
    }

    @Test void generationSnapshotFiltersBaselineBeforeTopKAndFailsForMissingActiveAssets() throws Exception {
        String generation="c87a26f6-cc34-4303-bdc2-929657369db3";
        String collection="rag_recovery_"+generation.replace("-", "");
        var bodies=new java.util.concurrent.ConcurrentHashMap<String,String>();
        var missing=new java.util.concurrent.atomic.AtomicBoolean();
        var pointCount=new AtomicInteger(2);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            String path=exchange.getRequestURI().getPath();
            bodies.put(path,new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            if(path.contains(collection)) {
                if(missing.get()){respond(exchange,404,"{}");return;}
                if(path.endsWith("/points/count")){respond(exchange,200,"{\"result\":{\"count\":"+pointCount.get()+"}}");return;}
                if(path.equals("/collections/"+collection)){respond(exchange,200,collectionWithQueue("green",1536,2,0,2,1));return;}
                respond(exchange,200,"{\"result\":[{\"id\":9000000000000300,\"score\":0.7,\"payload\":{\"target\":\"official_doc\",\"chunkId\":300,\"documentId\":42,\"generationId\":\""+generation+"\",\"manifest\":\""+"b".repeat(64)+"\",\"contentHash\":\""+"a".repeat(64)+"\",\"embeddingModel\":\"text-embedding-3-small\",\"qualityStatus\":\"PASS\"}}]}");
            } else {
                assertThat(bodies.get(path)).contains("\"documentId\"","42","must_not");
                respond(exchange,200,"{\"result\":[{\"id\":11,\"score\":0.6,\"payload\":{\"target\":\"official_doc\",\"chunkId\":11,\"documentId\":77}}]}");
            }
        });server.start();client=client();
        var routing=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(1,
            java.util.Map.of(42L,new com.kaces.pandora.rag.recovery.RecoverySnapshot(42,1,generation,"a".repeat(64),"b".repeat(64))),
            java.util.Map.of(generation,new com.kaces.pandora.rag.recovery.RecoveryAssets(generation,"b".repeat(64),collection,"c".repeat(64),"d".repeat(64),2)),java.util.Set.of(),"d".repeat(64),"recovery_"+generation);
        assertThat(client.searchBalanced(java.util.Collections.nCopies(1536,.1),java.util.List.of("official_doc"),1,2,routing))
            .extracting(com.kaces.pandora.semantic.search.QdrantSearchHit::chunkId).containsExactly(300L,11L);
        assertThat(bodies.get("/collections/"+collection+"/points/search")).contains("generationId","manifest","qualityStatus");
        client.assertRecoveryReady(routing);
        pointCount.set(1);
        assertThatThrownBy(()->client.assertRecoveryReady(routing)).isInstanceOf(IllegalStateException.class);
        pointCount.set(2);
        missing.set(true);
        assertThatThrownBy(()->client.searchBalanced(java.util.Collections.nCopies(1536,.1),java.util.List.of("official_doc"),1,2,routing))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test void recoveryInspectionReportsOnlyConfirmedMissingPointsWithoutWriting() throws Exception {
        var requests = new java.util.ArrayList<String>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections", exchange -> {
            requests.add(exchange.getRequestMethod());
            respond(exchange, 200, "{\"result\":[]}");
        }); server.start(); client = client();
        var result = client.inspectRagRecoveryPoints("c87a26f6-cc34-4303-bdc2-929657369db3", "text-embedding-3-small", "b".repeat(64),
            java.util.List.of(recoveryRow()), java.util.List.of(java.util.Collections.nCopies(1536, 0.1)));
        assertThat(result.missingChunkIds()).containsExactly(200L);
        assertThat(requests).containsExactly("POST");
    }

    @Test void wholeRecoveryCollectionRejectsExtraPointsAndMutationDuringReadback() throws Exception {
        var mode = new AtomicInteger(); var counts = new AtomicInteger();
        var vector = new java.util.ArrayList<Double>(java.util.Collections.nCopies(1536, 0.0)); vector.set(0, 1.0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/points/count")) {
                int observed = counts.incrementAndGet();
                respond(exchange, 200, "{\"result\":{\"count\":" + (mode.get() == 1 || mode.get() == 2 && observed > 1 ? 2 : 1) + "}}");
            } else if (path.endsWith("/points")) {
                var payload = java.util.Map.of("chunkId",200,"documentId",42,"chunkVersion",4,"target","official_doc","activationStatus","CANDIDATE",
                    "generationId","c87a26f6-cc34-4303-bdc2-929657369db3","contentHash","a".repeat(64),"embeddingModel","text-embedding-3-small","manifest","b".repeat(64),"qualityStatus","PASS");
                respond(exchange,200,new ObjectMapper().writeValueAsString(java.util.Map.of("result",java.util.List.of(java.util.Map.of("id",9000000000000200L,"payload",payload,"vector",vector)))));
            } else respond(exchange, 200, collectionWithQueue("green", 1536, 1, 0, 1, 1));
        }); server.start(); client = client();
        assertThat(client.verifyRagRecoveryCollection("c87a26f6-cc34-4303-bdc2-929657369db3", "text-embedding-3-small", "b".repeat(64), java.util.List.of(recoveryRow()), java.util.List.of(vector))).matches("[0-9a-f]{64}");
        for (int bad : new int[]{1, 2}) {
            mode.set(bad); counts.set(0);
            assertThatThrownBy(() -> client.verifyRagRecoveryCollection("c87a26f6-cc34-4303-bdc2-929657369db3", "text-embedding-3-small", "b".repeat(64), java.util.List.of(recoveryRow()), java.util.List.of(vector)))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test void recoveryVectorsAreWrittenOnlyToAnIsolatedGenerationCollection() throws Exception {
        var paths=new java.util.concurrent.CopyOnWriteArrayList<String>();var body=new AtomicReference<String>();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            paths.add(exchange.getRequestURI().getPath());
            String text=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            if(exchange.getRequestURI().getPath().endsWith("/points"))body.set(text);
            respond(exchange,200,"{\"result\":{\"status\":\"completed\"}}");
        });server.start();client=client();
        String generation="c87a26f6-cc34-4303-bdc2-929657369db3";
        var row=new com.kaces.pandora.rag.document.RagDocumentChunkRow(200,42,4,"page 1",null,"계획","body","기관은 자료를 보존한다.",null,1,"$.pages[1]",null,0,"a".repeat(64),"PASS",null,null);
        client.upsertRagRecovery(generation,"text-embedding-3-small","b".repeat(64),java.util.List.of(row),java.util.List.of(java.util.Collections.nCopies(1536,0.1)));
        assertThat(paths).allMatch(p->p.startsWith("/collections/rag_recovery_c87a26f6cc344303bdc2929657369db3"));
        assertThat(body.get()).contains("\"chunkId\":200","\"documentId\":42","\"activationStatus\":\"CANDIDATE\"","\"qualityStatus\":\"PASS\"","\"contentHash\":\""+"a".repeat(64),"\"manifest\":\""+"b".repeat(64));
    }
    @Test void malformedRecoveryNamespaceAndVectorsAreRejectedBeforeAnyHttpRequest() throws Exception {
        var requests=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{requests.incrementAndGet();respond(exchange,200,"{}");});server.start();client=client();
        var row=new com.kaces.pandora.rag.document.RagDocumentChunkRow(200,42,4,"page 1",null,"계획","body","원본",null,1,"source",null,0,"a".repeat(64),"PASS",null,null);
        assertThatThrownBy(()->client.upsertRagRecovery("../rag","text-embedding-3-small","b".repeat(64),java.util.List.of(row),java.util.List.of(java.util.List.of(0.1)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->client.upsertRagRecovery("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),java.util.List.of(row),java.util.List.of(java.util.List.of(0.1)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }
    @Test void zeroRecoveryVectorCannotBeWritten() throws Exception {
        var requests=new AtomicInteger();server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{requests.incrementAndGet();respond(exchange,200,"{}");});server.start();client=client();
        assertThatThrownBy(()->client.upsertRagRecovery("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),
                java.util.List.of(recoveryRow()),java.util.List.of(java.util.Collections.nCopies(1536,0.0)))).isInstanceOf(IllegalArgumentException.class);
        assertThat(requests).hasValue(0);
    }
    @Test void recoveryPointReadbackRejectsWrongManifestMissingPointAndChangedVector() throws Exception {
        var mode=new AtomicInteger();var vector=new java.util.ArrayList<Double>(java.util.Collections.nCopies(1536,0.0));vector.set(0,1.0);
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/collections",exchange->{
            assertThat(exchange.getRequestURI().getPath()).isEqualTo("/collections/rag_recovery_c87a26f6cc344303bdc2929657369db3/points");
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            if(mode.get()==2){respond(exchange,200,"{\"result\":[]}");return;}
            var observed=new java.util.ArrayList<>(vector);if(mode.get()==3){observed.set(0,0.0);observed.set(1,1.0);}
            var payload=java.util.Map.of("chunkId",200,"documentId",42,"chunkVersion",4,"target","official_doc","activationStatus","CANDIDATE",
                    "generationId","c87a26f6-cc34-4303-bdc2-929657369db3","contentHash","a".repeat(64),"embeddingModel","text-embedding-3-small","manifest",(mode.get()==1?"c":"b").repeat(64),"qualityStatus","PASS");
            respond(exchange,200,new ObjectMapper().writeValueAsString(java.util.Map.of("result",java.util.List.of(java.util.Map.of("id",9000000000000200L,"payload",payload,"vector",observed)))));
        });server.start();client=client();
        assertThat(client.verifyRagRecoveryPoints("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),java.util.List.of(recoveryRow()),java.util.List.of(vector))).matches("[0-9a-f]{64}");
        for(int bad=1;bad<=3;bad++){mode.set(bad);assertThatThrownBy(()->client.verifyRagRecoveryPoints("c87a26f6-cc34-4303-bdc2-929657369db3","text-embedding-3-small","b".repeat(64),java.util.List.of(recoveryRow()),java.util.List.of(vector))).isInstanceOf(IllegalStateException.class);}
    }
    private static com.kaces.pandora.rag.document.RagDocumentChunkRow recoveryRow() {
        return new com.kaces.pandora.rag.document.RagDocumentChunkRow(200,42,4,"page 1",null,"계획","body","원본",null,1,"source",null,0,"a".repeat(64),"PASS",null,null);
    }

	private HttpServer server;
	private QdrantClient client;
	private AtomicInteger searchBodyAttempts;

	@Test
	void activeMetadataRepairReadsNoVectorsAndPreservesCandidateOwnership() throws Exception {
		AtomicReference<String> repaired = new AtomicReference<>();
		AtomicInteger writes = new AtomicInteger();
		String source = "{\"chunkId\":10,\"documentId\":42,\"chunkVersion\":2,\"target\":\"admrul\",\"title\":\"rule\",\"activationStatus\":\"CANDIDATE\"}";
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections", exchange -> {
			String body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			String path = exchange.getRequestURI().getPath();
			if (path.endsWith("/payload")) {
				assertThat(exchange.getRequestMethod()).isEqualTo("POST");
				repaired.set(body);
				writes.incrementAndGet();
				respond(exchange, 200, "{\"result\":{\"status\":\"completed\"}}");
			} else {
				assertThat(body).contains("\"with_vector\":false");
				String payload = path.contains("candidate") || repaired.get() != null
					? source.replace("CANDIDATE", path.contains("candidate") ? "CANDIDATE" : "ACTIVE")
					: "{\"activationStatus\":\"ACTIVE\"}";
				respond(exchange, 200, "{\"result\":[{\"id\":10,\"payload\":" + payload + "}]}");
			}
		});
		server.start();
		client = client("law_chunks", "rag");
		client.restoreActiveLawPayloadsFromCandidate(java.util.List.of(10L), 42L, 2);
		assertThat(writes).hasValue(1);
		assertThat(repaired.get()).contains("\"documentId\":42", "\"chunkVersion\":2", "\"target\":\"admrul\"", "\"activationStatus\":\"ACTIVE\"");
		client.restoreActiveLawPayloadsFromCandidate(java.util.List.of(10L), 42L, 2);
		assertThat(writes).as("verified metadata must not be rewritten").hasValue(1);
	}

	@Test
	void activationStatusUpdatesUseMergePayloadInsteadOfDestructiveOverwrite() throws IOException {
		AtomicReference<String> method = new AtomicReference<>();
		AtomicReference<String> body = new AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points/payload", exchange -> {
			method.set(exchange.getRequestMethod());
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"result\":{\"status\":\"completed\"}}");
		});
		server.start();
		client = client("law_chunks", "rag");
		client.markLawPointsActive(java.util.List.of(10L));
		assertThat(method).hasValue("POST");
		assertThat(body.get()).contains("\"activationStatus\":\"ACTIVE\"", "\"points\":[10]");
		client.markLawPointsRetired(java.util.List.of(10L));
		assertThat(method).hasValue("POST");
		assertThat(body.get()).contains("\"activationStatus\":\"RETIRED\"");
		client.markLawPointsCandidate(java.util.List.of(10L));
		assertThat(method).hasValue("POST");
		assertThat(body.get()).contains("\"activationStatus\":\"CANDIDATE\"");
	}

	@Test
	void activeMetadataRepairRejectsForeignOrIncompleteCandidateBeforeAnyWrite() throws IOException {
		for (String source : java.util.List.of(
			"{\"chunkId\":10,\"documentId\":99,\"chunkVersion\":2,\"target\":\"admrul\",\"activationStatus\":\"CANDIDATE\"}",
			"{\"chunkId\":10,\"documentId\":42,\"chunkVersion\":3,\"target\":\"admrul\",\"activationStatus\":\"CANDIDATE\"}",
			"{\"chunkId\":11,\"documentId\":42,\"chunkVersion\":2,\"target\":\"admrul\",\"activationStatus\":\"CANDIDATE\"}",
			"{\"chunkId\":10,\"documentId\":42,\"chunkVersion\":2,\"target\":\"official_doc\",\"activationStatus\":\"CANDIDATE\"}",
			"{\"activationStatus\":\"CANDIDATE\"}")) {
			AtomicInteger writes = new AtomicInteger();
			server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/collections", exchange -> {
				String path = exchange.getRequestURI().getPath();
				if (path.endsWith("/payload")) writes.incrementAndGet();
				String payload = path.contains("candidate") ? source : "{\"activationStatus\":\"ACTIVE\"}";
				respond(exchange, 200, "{\"result\":[{\"id\":10,\"payload\":" + payload + "}]}");
			});
			server.start();
			client = client("law_chunks", "rag");
			assertThatThrownBy(() -> client.restoreActiveLawPayloadsFromCandidate(java.util.List.of(10L), 42L, 2)).isInstanceOf(IllegalStateException.class);
			assertThat(writes).hasValue(0);
			tearDown();
		}
	}

	@AfterEach
	void tearDown() {
		if (client != null) {
			client.shutdownExecutor();
		}
		if (server != null) {
			server.stop(0);
		}
	}

	@Test
	void findExistingLawPointIdsRejectsNonIntegralNonPositiveAndOutOfRangeResponseIds() throws IOException {
		for (String invalidId : java.util.List.of("10.5", "0", "9223372036854775808")) {
			startPointLookupServer("{\"result\":[{\"id\":" + invalidId + "}]}");
			client = client("law_chunks", "rag");

			assertThatThrownBy(() -> client.findExistingLawPointIds(java.util.List.of(10L)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("malformed point");
			tearDown();
		}
	}

	@Test
	void findExistingLawPointIdsUsesBoundedPointsLookupWithoutPayloadOrVectors() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> method = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> path = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points", exchange -> {
			method.set(exchange.getRequestMethod());
			path.set(exchange.getRequestURI().getPath());
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"result\":[{\"id\":10},{\"id\":30}]}");
		});
		server.start();
		client = client("law_chunks", "rag");

		Set<Long> existing = client.findExistingLawPointIds(java.util.List.of(10L, 20L, 30L));

		assertThat(existing).containsExactlyInAnyOrder(10L, 30L);
		assertThat(method).hasValue("POST");
		assertThat(path).hasValue("/collections/law_chunks/points");
		assertThat(body.get()).contains("\"with_payload\":false", "\"with_vector\":false", "10", "20", "30");
	}

	@Test
	void lawCandidateAndProductionStatusLookupsBatchMoreThanTwoHundredFiftySixPointIds() throws IOException {
		AtomicInteger candidateRequests = new AtomicInteger();
		AtomicInteger productionRequests = new AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections", exchange -> {
			String request = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
			String result = requestPointIds(request).stream()
				.map(id -> "{\"id\":" + id + ",\"payload\":{\"activationStatus\":\"CANDIDATE\"}}")
				.collect(java.util.stream.Collectors.joining(",", "{\"result\":[", "]}"));
			if (exchange.getRequestURI().getPath().contains("law_chunks_candidate")) candidateRequests.incrementAndGet();
			else productionRequests.incrementAndGet();
			respond(exchange, 200, result);
		});
		server.start();
		client = client("law_chunks", "rag");
		java.util.List<Long> ids = java.util.stream.LongStream.rangeClosed(1, 257).boxed().toList();

		assertThat(client.findExistingLawCandidatePointIds(ids)).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(client.findLawPointIdsWithActivationStatus(ids, "CANDIDATE")).containsExactlyInAnyOrderElementsOf(ids);
		assertThat(client.findLawPointIdsWithActivationStatus(ids, "ACTIVE")).isEmpty();
		assertThat(candidateRequests).hasValue(2);
		assertThat(productionRequests).hasValue(4);
	}

	@Test
	void searchReadinessRequiresBothConfiguredCollections() throws IOException {
		startServer(200, readyCollection(1536, 10));
		client = client();

		assertThat(client.isSearchReady()).isTrue();
	}

	@Test
	void searchReadinessFailsClosedWhenACollectionIsMissing() throws IOException {
		startServer(404, "{}");
		client = client();

		assertThat(client.isSearchReady()).isFalse();
	}

	@Test
	void searchReadinessFailsClosedWhenCollectionConfigurationIsMissing() throws IOException {
		startServer(200, readyCollection(1536, 10));
		client = client(null, "rag");

		assertThat(client.isSearchReady()).isFalse();
	}

	@Test
	void searchReadinessRejectsRecoveringEmptyAndWrongVectorCollections() throws IOException {
		startServer(200, collection("red", 1536, 10));
		client = client();
		assertThat(client.isSearchReady()).isFalse();
		tearDown();

		startServer(200, readyCollection(1536, 0));
		client = client();
		assertThat(client.isSearchReady()).isFalse();
		tearDown();

		startServer(200, readyCollection(3072, 10));
		client = client();
		assertThat(client.isSearchReady()).isFalse();
	}

	@Test
	void indexSnapshotUsesExactCountAndStableCollectionFields() throws IOException {
		startIndexSnapshotServer(
			200,
			collectionWithQueue("green", 1536, 999, 0, 998, 7),
			200,
			"{\"result\":{\"count\":10}}"
		);
		client = client();

		Optional<QdrantIndexSnapshot> snapshot = client.indexSnapshot("law");

		assertThat(snapshot).contains(new QdrantIndexSnapshot(
			"law", "green", 0, 10, 1536, "Cosine", 998, 7
		));
	}

	@Test
	void indexSnapshotFailsClosedForBusyQueueAndMalformedExactCount() throws IOException {
		startIndexSnapshotServer(
			200,
			collectionWithQueue("green", 1536, 10, 1, 10, 2),
			200,
			"{\"result\":{\"count\":10}}"
		);
		client = client();
		assertThat(client.indexSnapshot("law")).isEmpty();
		tearDown();

		startIndexSnapshotServer(
			200,
			collectionWithQueue("green", 1536, 10, 0, 10, 2),
			200,
			"{\"result\":{}}"
		);
		client = client();
		assertThat(client.indexSnapshot("law")).isEmpty();
	}

	@Test
	void indexSnapshotFailsClosedWhenUpdateQueueIsMissing() throws IOException {
		startIndexSnapshotServer(
			200,
			collection("green", 1536, 10),
			200,
			"{\"result\":{\"count\":10}}"
		);
		client = client();

		assertThat(client.indexSnapshot("law")).isEmpty();
	}

	@Test
	void exhaustedSearchFailureIsCountedOnceAfterRetries() throws IOException {
		startSearchServer(503, "{}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void validEmptySearchResultIsNotCountedAsInfrastructureFailure() throws IOException {
		startSearchServer(200, "{\"result\":[]}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isZero();
	}

	@Test
	void malformedSuccessfulSearchResponseIsCountedAsInfrastructureFailure() throws IOException {
		startSearchServer(200, "{}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void malformedSearchResultItemIsRetriedAndCountedAsInfrastructureFailure() throws IOException {
		startSearchServer(200, "{\"result\":[{}]}");
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void retrySuccessDoesNotIncrementSearchFailureCount() throws IOException {
		java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> {
			if (attempts.incrementAndGet() == 1) {
				respond(exchange, 503, "{}");
				return;
			}
			respond(exchange, 200, "{\"result\":[]}");
		});
		server.start();
		client = client();

		assertThat(client.search(java.util.List.of(0.25d), "law", 1)).isEmpty();
		assertThat(attempts).hasValue(2);
		assertThat(client.searchFailureCount()).isZero();
	}

	@Test
	void searchRetriesExactSpringRequestBodyTransportFailureAndReturnsSecondResponse() throws Exception {
		client = clientWithSearchBodyFailures(1);
		AtomicReference<java.util.List<QdrantSearchHit>> result = new AtomicReference<>();

		assertThat(catchThrowable(() -> result.set(client.search(java.util.List.of(0.25d), "law", 1)))).isNull();
		assertThat(result.get())
			.containsExactly(new QdrantSearchHit("law", 42L, 0.9d));
		assertThat(searchBodyAttempts).hasValue(2);
		assertThat(client.searchFailureCount()).isZero();
	}

	@Test
	void searchCountsExactSpringRequestBodyTransportFailureOnceAfterBothAttempts() throws Exception {
		client = clientWithSearchBodyFailures(2);
		AtomicReference<java.util.List<QdrantSearchHit>> result = new AtomicReference<>();

		assertThat(catchThrowable(() -> result.set(client.search(java.util.List.of(0.25d), "law", 1)))).isNull();
		assertThat(result.get()).isEmpty();
		assertThat(searchBodyAttempts).hasValue(2);
		assertThat(client.searchFailureCount()).isEqualTo(1L);
	}

	@Test
	void searchDoesNotRetryNonTransportSpringJsonMappingFailure() throws Exception {
		AtomicInteger attempts = new AtomicInteger();
		HttpMessageNotWritableException mappingFailure = new HttpMessageNotWritableException("Could not write JSON: invalid mapping");
		client = clientWithSearchBodyFailure(attempts, mappingFailure);

		assertThatThrownBy(() -> client.search(java.util.List.of(0.25d), "law", 1)).isSameAs(mappingFailure);
		assertThat(attempts).hasValue(1);
		assertThat(client.searchFailureCount()).isZero();
	}

	private void startServer(int ragStatus, String ragBody) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law", exchange -> respond(exchange, 200, readyCollection(1536, 10)));
		server.createContext("/collections/rag", exchange -> respond(exchange, ragStatus, ragBody));
		server.start();
	}

	@Test
	void productionSearchExcludesCandidateAndRetiredPointsWhenCleanupIsDelayed() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{\"result\":[]}");
		});
		server.start();
		client = client();

		client.search(java.util.List.of(0.25d), "law", 1);

		assertThat(body.get()).contains("\"activationStatus\"", "\"CANDIDATE\"", "\"RETIRED\"", "\"must_not\"");
	}

	@Test
	void candidateUpsertUsesTheIsolatedCandidateCollection() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> pointPath = new java.util.concurrent.atomic.AtomicReference<>();
		java.util.concurrent.atomic.AtomicReference<String> pointBody = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks_candidate", exchange -> {
			if (exchange.getRequestURI().getPath().endsWith("/points")) {
				pointPath.set(exchange.getRequestURI().getPath());
				pointBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			}
			respond(exchange, 200, readyCollection(1536, 1));
		});
		server.start();
		client = client("law_chunks", "rag");

		client.upsertLawCandidates(java.util.List.of(new LawSemanticChunkRow(
			7L, 41L, "law", "x", "Title", null, null, null, null,
			"Article 1", "Article 1", "candidate", null, "$.law.articles[0].body", null,
			0, "hash", "Article 1", "provision", "PASS", "embedding", "parent", 2
		)), java.util.List.of(java.util.List.of(0.25d)));

		assertThat(pointPath).hasValue("/collections/law_chunks_candidate/points");
		assertThat(pointBody.get()).contains("\"activationStatus\":\"CANDIDATE\"");
	}

	@Test
	void upsertUsesExplicitStoredChunkVersionForVersionTwoLawPayload() throws IOException {
		java.util.concurrent.atomic.AtomicReference<String> body = new java.util.concurrent.atomic.AtomicReference<>();
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points", exchange -> {
			body.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
			respond(exchange, 200, "{}");
		});
		server.start();
		client = client("law_chunks", "rag");

		client.upsert(java.util.List.of(new LawSemanticChunkRow(
			7L, 41L, "law", "x", "Title", null, null, null, null,
			"Article 1", "Article 1", "child", null, "$.law.articles[0].body", null,
			0, "hash", "Article 1", "provision", "PASS", "embedding", "parent", 2
		)), java.util.List.of(java.util.List.of(0.25d)));

		assertThat(body.get()).contains("\"chunkVersion\":2");
	}

	private void startPointLookupServer(String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law_chunks/points", exchange -> respond(exchange, 200, body));
		server.start();
	}

	private java.util.List<Long> requestPointIds(String request) {
		java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\d+").matcher(request);
		java.util.List<Long> ids = new java.util.ArrayList<>();
		while (matcher.find()) ids.add(Long.parseLong(matcher.group()));
		return ids;
	}

	private void startSearchServer(int status, String body) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law/points/search", exchange -> respond(exchange, status, body));
		server.start();
	}

	private QdrantClient clientWithSearchBodyFailures(int failuresBeforeSuccess) throws Exception {
		searchBodyAttempts = new AtomicInteger();
		return clientWithSearchBodyFailure(searchBodyAttempts, exactSpringRequestBodyTransportFailure(), failuresBeforeSuccess);
	}

	private QdrantClient clientWithSearchBodyFailure(AtomicInteger attempts, RuntimeException failure) throws Exception {
		return clientWithSearchBodyFailure(attempts, failure, Integer.MAX_VALUE);
	}

	private QdrantClient clientWithSearchBodyFailure(
		AtomicInteger attempts,
		RuntimeException failure,
		int failuresBeforeSuccess
	) throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.start();
		QdrantClient qdrant = client();
		RestClient restClient = mock(RestClient.class);
		RestClient.RequestBodyUriSpec uriSpec = mock(RestClient.RequestBodyUriSpec.class);
		RestClient.RequestBodySpec bodySpec = mock(RestClient.RequestBodySpec.class);
		RestClient.ResponseSpec responseSpec = mock(RestClient.ResponseSpec.class);
		when(restClient.post()).thenReturn(uriSpec);
		when(uriSpec.uri(anyString(), any(Object[].class))).thenReturn(bodySpec);
		when(bodySpec.body(org.mockito.ArgumentMatchers.<Object>any())).thenAnswer(invocation -> {
			if (attempts.incrementAndGet() <= failuresBeforeSuccess) {
				throw failure;
			}
			return bodySpec;
		});
		when(bodySpec.retrieve()).thenReturn(responseSpec);
		when(responseSpec.body(byte[].class)).thenReturn(
			"{\"result\":[{\"id\":7,\"score\":0.9,\"payload\":{\"target\":\"law\",\"chunkId\":42}}]}"
				.getBytes(java.nio.charset.StandardCharsets.UTF_8)
		);
		replaceRestClient(qdrant, restClient);
		return qdrant;
	}

	private HttpMessageNotWritableException exactSpringRequestBodyTransportFailure() {
		return new HttpMessageNotWritableException(
			"Could not write JSON: Error writing request body to server",
			DatabindException.from(
				(JsonGenerator) null,
				"Error writing request body to server",
				new IOException("Error writing request body to server")
			)
		);
	}

	private void replaceRestClient(QdrantClient qdrant, RestClient restClient) throws Exception {
		Field field = QdrantClient.class.getDeclaredField("restClient");
		field.setAccessible(true);
		field.set(qdrant, restClient);
	}

	private void startIndexSnapshotServer(
		int infoStatus,
		String infoBody,
		int countStatus,
		String countBody
	) throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/collections/law", exchange -> {
			if ("/collections/law/points/count".equals(exchange.getRequestURI().getPath())) {
				respond(exchange, countStatus, countBody);
				return;
			}
			respond(exchange, infoStatus, infoBody);
		});
		server.start();
	}

	private QdrantClient client() {
		return client("law", "rag");
	}

	private QdrantClient client(String lawCollection, String ragCollection) {
		String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
		LawAiProperties properties = new LawAiProperties(
			null,
			new LawAiProperties.Qdrant(baseUrl, lawCollection, ragCollection, 1536),
			null,
			null
		);
		return new QdrantClient(properties, new ObjectMapper());
	}

	private String readyCollection(int vectorSize, long pointsCount) {
		return collectionWithQueue("green", vectorSize, pointsCount, 0, pointsCount, 1);
	}

	private String collection(String status, int vectorSize, long pointsCount) {
		return """
			{"result":{"status":"%s","points_count":%d,"config":{"params":{"vectors":{"size":%d,"distance":"Cosine"}}}}}
			""".formatted(status, pointsCount, vectorSize);
	}

	private String collectionWithQueue(
		String status,
		int vectorSize,
		long pointsCount,
		long updateQueueLength,
		long indexedVectorsCount,
		int segmentsCount
	) {
		return """
			{"result":{"status":"%s","points_count":%d,"indexed_vectors_count":%d,"segments_count":%d,
			"update_queue":{"length":%d},"config":{"params":{"vectors":{"size":%d,"distance":"Cosine"}}}}}
			""".formatted(status, pointsCount, indexedVectorsCount, segmentsCount, updateQueueLength, vectorSize);
	}

	private void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
		exchange.close();
	}
}
