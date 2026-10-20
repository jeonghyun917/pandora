package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.importing.*;
import com.kaces.pandora.rag.chunk.*;

class RagRecoveryValidationTests {
    @TempDir Path temp;
    Path file;String sourceHash;List<RagDocumentChunkRow> chunks;
    final String product="b".repeat(40);
    @BeforeEach void fixture() throws Exception {
        file=temp.resolve("source.hwpx");
        String xml="<section><tbl colCnt='1'><tr><tc><cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>기관은 자료를 보존하여야 한다.</t></tc></tr></tbl><p><t>단, 보존 기간은 협의로 변경할 수 있다.</t></p></section>";
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))){zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        sourceHash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        chunks=new RagChunkQualityGate().evaluate("보존 계획",new RagChunker().chunkV4(42,new RagTextExtractor().extract(file),null,"보존 계획",sourceHash)).retainedChunks();
    }
    @Test void exactOriginalCandidatePassesWithoutGrantingSemanticSupport() {
        var result=validate(chunks);
        assertThat(result.passed()).isTrue();assertThat(result.failures()).isEmpty();
    }
    @Test void deletedEndingConditionFailsEvenIfManifestWasRecomputedForTamperedCandidate() {
        var shortened=chunks.stream().filter(c->!c.chunkText().contains("협의")).toList();
        assertThat(shortened).hasSize(1);
        assertThat(validate(shortened).passed()).isFalse();
    }
    @Test void modifiedInstitutionAndForgedProvenanceFailClosed() {
        var changed=new ArrayList<>(chunks);var c=changed.get(0);
        changed.set(0,copy(c,c.chunkText().replace("기관","다른기관"),c.sourceProvenance()));
        assertThat(validate(changed).passed()).isFalse();
        changed.set(0,copy(c,c.chunkText(),"{}"));
        assertThat(validate(changed).passed()).isFalse();
    }
    @Test void changedSourceOrProductCannotReuseEvidence() throws Exception {
        var generation=generation(chunks);
        assertThat(new RagRecoverySourceValidator().validate(file,generation,"c".repeat(40),"보존 계획",null,chunks).passed()).isFalse();
        Files.writeString(file,"different source");
        assertThat(new RagRecoverySourceValidator().validate(file,generation,product,"보존 계획",null,chunks).passed()).isFalse();
    }
    private RecoveryValidation validate(List<RagDocumentChunkRow> rows) {
        return new RagRecoverySourceValidator().validate(file,generation(rows),product,"보존 계획",null,rows);
    }
    private RecoveryGeneration generation(List<RagDocumentChunkRow> rows) {
        var baseline=List.of(new RagDocumentChunkRow(101,42,4,"page 1",null,"기존","body","원본",null,1,"source",null,0,"a".repeat(64),"PASS",null,null));
        var plan=RecoveryCandidatePlan.of(42,sourceHash,product,baseline,rows);
        return new RecoveryGeneration("c87a26f6-cc34-4303-bdc2-929657369db3",42,sourceHash,product,plan.baselineHash(),plan.manifest(),plan.previewHash(),"PENDING");
    }
    private static RagDocumentChunkRow copy(RagDocumentChunkRow c,String text,String proof) {
        return new RagDocumentChunkRow(0,c.documentId(),c.chunkVersion(),c.chunkNo(),c.parentSectionTitle(),c.chunkTitle(),c.sectionType(),text,c.embeddingText(),c.pageNo(),c.sourcePath(),c.sourceUrl(),c.sortOrder(),c.contentHash(),c.qualityStatus(),c.qualityReason(),proof);
    }
}
