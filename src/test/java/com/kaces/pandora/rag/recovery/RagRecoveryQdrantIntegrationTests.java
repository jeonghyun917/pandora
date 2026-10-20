package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named="PANDORA_RECOVERY_Q_TEST_URL",matches="http://127[.]0[.]0[.]1:26333")
class RagRecoveryQdrantIntegrationTests {
    @Test void realNamespaceReconciliationAndParityNeverChangesBaseline() throws Exception {
        Path root=Path.of(System.getenv("PANDORA_RECOVERY_Q_TEST_ROOT")).toRealPath();
        assertThat(root.getFileName().toString()).startsWith("qdrant-recovery-test-");
        assertThat(root.getParent().getFileName().toString()).isEqualTo("runtime");
        var process=ProcessHandle.of(Long.parseLong(System.getenv("PANDORA_RECOVERY_Q_TEST_PID"))).orElseThrow();
        assertThat(process.isAlive()).isTrue();
        // Windows ProcessHandle commandLine is optional; use the host's actual CIM/listener ownership fence.
        var fence=new ProcessBuilder("powershell.exe","-NoProfile","-File",Path.of("scripts/assert-recovery-test-qdrant.ps1").toAbsolutePath().toString(),
            "-ProcessId",Long.toString(process.pid()),"-TestRoot",root.toString()).redirectErrorStream(true).start();
        if(!fence.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)) {
            fence.destroy(); throw new IllegalStateException("Qdrant process ownership check timed out");
        }
        assertThat(fence.exitValue()).withFailMessage(new String(fence.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)).isZero();
        var properties=new LawAiProperties(null,new LawAiProperties.Qdrant(System.getenv("PANDORA_RECOVERY_Q_TEST_URL"),"test_baseline_law","test_baseline_rag",3),null,null);
        var q=new QdrantClient(properties,new ObjectMapper());
        var http=RestClient.builder().baseUrl(System.getenv("PANDORA_RECOVERY_Q_TEST_URL")).build();
        String generation=UUID.randomUUID().toString();String collection=q.ragRecoveryCollection(generation);
        String baseline="test_baseline_"+UUID.randomUUID().toString().replace("-", "");
        var a=chunk(200);var b=chunk(201);
        List<List<Double>> vectors=List.of(List.of(1.0,0.0,0.0),List.of(0.0,1.0,0.0));
        try {
            http.put().uri("/collections/{name}",baseline).body(Map.of("vectors",Map.of("size",3,"distance","Cosine"))).retrieve().toBodilessEntity();
            http.put().uri("/collections/{name}/points?wait=true",baseline).body(Map.of("points",List.of(Map.of("id",1,"vector",List.of(0,0,1),"payload",Map.of("control","baseline"))))).retrieve().toBodilessEntity();
            String before=http.post().uri("/collections/{name}/points",baseline).body(Map.of("ids",List.of(1),"with_payload",true,"with_vector",true)).retrieve().body(String.class);
            assertThat(q.inspectRagRecoveryPoints(generation,"text-embedding-3-small","b".repeat(64),List.of(a,b),vectors).missingChunkIds()).containsExactlyInAnyOrder(200L,201L);
            q.upsertRagRecovery(generation,"text-embedding-3-small","b".repeat(64),List.of(a),List.of(vectors.get(0)));
            assertThat(q.inspectRagRecoveryPoints(generation,"text-embedding-3-small","b".repeat(64),List.of(a,b),vectors).missingChunkIds()).containsExactly(201L);
            q.upsertRagRecovery(generation,"text-embedding-3-small","b".repeat(64),List.of(b),List.of(vectors.get(1)));
            assertThat(q.verifyRagRecoveryCollection(generation,"text-embedding-3-small","b".repeat(64),List.of(a,b),vectors)).matches("[0-9a-f]{64}");
            q.upsertRagRecovery(generation,"text-embedding-3-small","b".repeat(64),List.of(chunk(202)),List.of(List.of(0.0,0.0,1.0)));
            assertThatThrownBy(()->q.verifyRagRecoveryCollection(generation,"text-embedding-3-small","b".repeat(64),List.of(a,b),vectors)).isInstanceOf(IllegalStateException.class);
            String after=http.post().uri("/collections/{name}/points",baseline).body(Map.of("ids",List.of(1),"with_payload",true,"with_vector",true)).retrieve().body(String.class);
            var json=new ObjectMapper();
            assertThat(json.readTree(after).get("result")).isEqualTo(json.readTree(before).get("result"));
            http.delete().uri("/collections/{name}",collection).retrieve().toBodilessEntity();
            assertThatThrownBy(()->q.verifyRagRecoveryCollection(generation,"text-embedding-3-small","b".repeat(64),List.of(a,b),vectors)).isInstanceOf(IllegalStateException.class);
        } finally {
            for(String name:List.of(collection,baseline)) {
                try { http.delete().uri("/collections/{name}",name).retrieve().toBodilessEntity(); }
                catch(org.springframework.web.client.HttpClientErrorException.NotFound alreadyRemoved) { }
            }
        }
    }
    private static RagDocumentChunkRow chunk(long id) {
        return new RagDocumentChunkRow(id,42,4,"row",null,"test","body","Synthetic recovery fixture",null,1,"test",null,(int)id,"a".repeat(64),"PASS",null,null);
    }
}
