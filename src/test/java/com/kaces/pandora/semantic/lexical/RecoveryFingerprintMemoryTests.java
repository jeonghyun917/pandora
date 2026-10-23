package com.kaces.pandora.semantic.lexical;

import static org.assertj.core.api.Assertions.assertThat;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RecoveryFingerprintMemoryTests {
    @Test void preservesExistingCanonicalUtf8Fingerprint() throws Exception {
        var tokenizer = new KoreanLexicalTokenizer();
        var service = new SemanticLexicalIndexService(null, tokenizer, () -> "unused",
            new com.kaces.pandora.semantic.config.LawAiLexicalProperties(1.2,.75,9,6,7,1,24,100));
        var first = new LexicalChunkDocument("admrul",2,1,null,"hash","제목","","","한글\"\\\n😀");
        var second = new LexicalChunkDocument("law",1,1,"parent",null,"","상위","조문","body");
        var canonical = List.of(tokenizer.version(), List.of(List.of("document_title",9),List.of("parent_title",6),
            List.of("chunk_title",7),List.of("body",1)), List.of(first,second));
        String expected = com.kaces.pandora.common.text.LawHashUtils.sha256(new tools.jackson.databind.ObjectMapper().writeValueAsString(canonical));
        var method = SemanticLexicalIndexService.class.getDeclaredMethod("recoveryFingerprint",List.class);
        method.setAccessible(true);
        assertThat(method.invoke(service,List.of(second,first))).isEqualTo(expected);
        String corpusExpected = com.kaces.pandora.common.text.LawHashUtils.sha256(new tools.jackson.databind.ObjectMapper().writeValueAsString(List.of(0L,List.of(first,second))));
        assertThat(com.kaces.pandora.rag.recovery.RecoveryCorpus.overlay(0,List.of(second,first),java.util.Map.of()).fingerprint()).isEqualTo(corpusExpected);
    }
    @Test void fingerprintsLargeCorpusWithoutAllocatingItsSerializedBody() throws Exception {
        var child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
            "-Xmx64m", "-cp", System.getProperty("java.class.path"), Probe.class.getName())
            .redirectErrorStream(true).start();
        assertThat(child.waitFor(45, TimeUnit.SECONDS)).isTrue();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(child.exitValue()).withFailMessage(output).isZero();
        assertThat(output.trim()).matches("[0-9a-f]{64}");
    }
    public static class Probe {
        public static void main(String[] args) throws Exception {
            var service = new SemanticLexicalIndexService(null, new KoreanLexicalTokenizer());
            String body = "한글\"\\\n😀".repeat(12000);
            var documents = new ArrayList<LexicalChunkDocument>();
            for (int i=1;i<=500;i++) documents.add(new LexicalChunkDocument("law",i,1,null,"hash","title","","",body));
            var method = SemanticLexicalIndexService.class.getDeclaredMethod("recoveryFingerprint",List.class);
            method.setAccessible(true);
            var hash = method.invoke(service,documents);
            com.kaces.pandora.rag.recovery.RecoveryCorpus.overlay(0,documents,java.util.Map.of());
            System.out.println(hash);
        }
    }
}
