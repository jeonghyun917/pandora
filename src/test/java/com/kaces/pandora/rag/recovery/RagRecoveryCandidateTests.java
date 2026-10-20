package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RagRecoveryCandidateTests {
    @TempDir Path temp;
    final RagDocumentMapper documents=mock(RagDocumentMapper.class);
    final RagRecoveryMapper recovery=mock(RagRecoveryMapper.class);
    final RagOriginalDocumentStore originals=mock(RagOriginalDocumentStore.class);
    final PlatformTransactionManager transactions=mock(PlatformTransactionManager.class);
    final String product="b".repeat(40);
    Path source;
    RagDocumentRow document;
    @BeforeEach void fixture() throws Exception {
        source=temp.resolve("source.txt");Files.writeString(source,"기관은 원본 자료를 보존하여야 한다. ".repeat(20));
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source)));
        document=new RagDocumentRow(42,"official_doc","원본 보존",null,null,null,null,null,1,"source.txt",source.toString(),null,hash,"text/plain",null,"INDEXED");
        when(documents.findSourceProofDocument(eq(42L),anyBoolean())).thenReturn(document);
        when(documents.findActiveChunksForSourceProof(eq(42L),anyBoolean())).thenReturn(List.of(new RagDocumentChunkRow(101,42,4,"page 1",null,"기존","body","기존 청크",null,1,"$.pages[1]",null,0,"a".repeat(64),"PASS",null,null)));
        when(originals.materialize(document)).thenReturn(source);
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(recovery.baselineStorageSnapshot(42)).thenReturn(List.of(Map.of("chunk_id",101L,"index_status","INDEXED")));
        when(recovery.embeddingSnapshot(42)).thenReturn(List.of());
        when(recovery.keywordSnapshot(42)).thenReturn(List.of());
    }
    @Test void previewProducesInactiveManifestWithoutDependingOnMatchingChunkCount() {
        var preview=service().preview(42,product);
        assertThat(preview.documentId()).isEqualTo(42);
        assertThat(preview.baselineCount()).isEqualTo(1);
        assertThat(preview.candidateCount()).isPositive();
        assertThat(preview.previewHash()).matches("[0-9a-f]{64}");
    }
    @Test void sourceMutationCannotCreateCandidate() throws Exception {
        var service=service();var preview=service.preview(42,product);
        Files.writeString(source,"different file");
        assertThatThrownBy(()->service.create(42,preview.previewHash(),product)).isInstanceOf(IllegalStateException.class);
    }
    @Test void staleBaselineMetadataAndIndexStatusInvalidatePreview() {
        var service=service();var preview=service.preview(42,product);
        when(recovery.baselineStorageSnapshot(42)).thenReturn(List.of(Map.of("chunk_id",101L,"index_status","FAILED")));
        assertThatThrownBy(()->service.create(42,preview.previewHash(),product)).isInstanceOf(IllegalStateException.class);
    }
    @Test void unchangedManifestCreateReturnsExistingGeneration() {
        var service=service();var preview=service.preview(42,product);
        var existing=new RecoveryGeneration("c87a26f6-cc34-4303-bdc2-929657369db3",42,document.fileHash(),product,preview.baselineHash(),preview.manifest(),preview.previewHash(),"PENDING");
        when(recovery.findByPreviewHash(42,preview.previewHash())).thenReturn(existing);
        assertThat(service.create(42,preview.previewHash(),product)).isEqualTo(existing);
    }
    @Test void inactiveOrWrongDocumentAndMalformedTokenAreRejected() {
        assertThatThrownBy(()->service().preview(43,product)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->service().create(42,"bad",product)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void truncatedAssetSnapshotCannotProducePreview() {
        when(recovery.embeddingSnapshot(42)).thenReturn(Collections.nCopies(100001,Map.of("chunk_id",101L)));
        assertThatThrownBy(()->service().preview(42,product)).isInstanceOf(IllegalStateException.class);
    }
    @Test void keywordTermMutationInvalidatesPreview() {
        var service=service();var preview=service.preview(42,product);
        when(recovery.keywordTermSnapshot(42)).thenReturn(List.of(Map.of("term","변경","chunk_id",101L)));
        assertThatThrownBy(()->service.create(42,preview.previewHash(),product)).isInstanceOf(IllegalStateException.class).hasMessage("Recovery snapshot changed");
    }
    @Test void canonicalMetadataKeyOrderingDoesNotChangePreview() {
        var a=new LinkedHashMap<String,Object>();a.put("chunk_id",101L);a.put("index_status","INDEXED");
        var b=new LinkedHashMap<String,Object>();b.put("index_status","INDEXED");b.put("chunk_id",101L);
        when(recovery.baselineStorageSnapshot(42)).thenReturn(List.of(a));var first=service().preview(42,product);
        when(recovery.baselineStorageSnapshot(42)).thenReturn(List.of(b));
        assertThat(service().preview(42,product).previewHash()).isEqualTo(first.previewHash());
    }
    private RagRecoveryCandidateService service() {
        return new RagRecoveryCandidateService(documents,recovery,originals,transactions);
    }
}
