package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

class RagSourceProofBackfillServiceTests {
    @TempDir Path temp;
    final RagDocumentMapper mapper = mock(RagDocumentMapper.class);
    final RagOriginalDocumentStore originals = mock(RagOriginalDocumentStore.class);
    final PlatformTransactionManager tx = mock(PlatformTransactionManager.class);
    final RagTextExtractor extractor = new RagTextExtractor();
    final RagChunker chunker = new RagChunker();
    final RagChunkQualityGate quality = new RagChunkQualityGate();
    RagDocumentRow document;
    List<RagDocumentChunkRow> stored;

    @Test void previewProvesRealOriginalWithoutWriting() throws Exception {
        fixture();
        var result = service().preview(42L, null);
        assertThat(result.status()).isEqualTo("ELIGIBLE");
        assertThat(result.eligibleChunks()).isEqualTo(2);
        assertThat(result.token()).matches("[0-9a-f]{64}");
        verify(mapper, never()).backfillSourceProofIfUnchanged(any(), anyString(), anyString());
        verifyNoInteractions(tx);
    }

    @Test void applyUpdatesOnlyNullProofOnExistingIds() throws Exception {
        fixture();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.backfillSourceProofIfUnchanged(any(), eq(document.fileHash()), anyString())).thenReturn(1);
        var service=service();
        var preview=service.preview(42L,null);
        assertThat(service.apply(42,preview.token()).updatedChunks()).isEqualTo(2);
        var captured=org.mockito.ArgumentCaptor.forClass(RagDocumentChunkRow.class);
        verify(mapper,times(2)).backfillSourceProofIfUnchanged(captured.capture(),eq(document.fileHash()),anyString());
        assertThat(captured.getAllValues()).extracting(RagDocumentChunkRow::chunkId).containsExactly(101L,102L);
        verify(tx).commit(any());
    }

    @Test void changedTextFailsClosedEvenWhenHashFieldWasNotChanged() throws Exception {
        fixture();
        var c=stored.get(0);
        stored=new ArrayList<>(stored);
        stored.set(0,copy(c,c.chunkId(),c.chunkText()+" changed",null));
        when(mapper.findActiveChunksForSourceProof(42,false)).thenReturn(stored);
        assertThat(service().preview(42L,null).token()).isNull();
    }

    @Test void sourceHashMismatchCannotProduceProofToken() throws Exception {
        fixture();
        Files.writeString(Path.of(document.filePath()),"different original");
        assertThat(service().preview(42L,null).token()).isNull();
    }

    @Test void staleTokenRefusesAllWrites() throws Exception {
        fixture();
        assertThatThrownBy(()->service().apply(42,"0".repeat(64))).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).backfillSourceProofIfUnchanged(any(),anyString(),anyString());
    }

    @Test void hashLookupIsAvailableOnlyWhenDocumentIdAbsent() throws Exception {
        fixture();
        when(mapper.findDocumentByHash(document.fileHash())).thenReturn(document);
        assertThat(service().preview(null,document.fileHash()).documentId()).isEqualTo(42);
        verify(mapper).findDocumentByHash(document.fileHash());
    }

    @Test void compareAndSetFailureRollsBackDocument() throws Exception {
        fixture();
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.backfillSourceProofIfUnchanged(any(),anyString(),anyString())).thenReturn(1,0);
        var service=service();var preview=service.preview(42L,null);
        assertThatThrownBy(()->service.apply(42,preview.token())).isInstanceOf(IllegalStateException.class);
        verify(tx).rollback(any());verify(tx,never()).commit(any());
    }

    @Test void newerActiveVersionBlocksWithoutIgnoringIt() throws Exception {
        fixture();var c=stored.get(0);
        var newer=new RagDocumentChunkRow(c.chunkId(),c.documentId(),5,c.chunkNo(),c.parentSectionTitle(),c.chunkTitle(),c.sectionType(),c.chunkText(),c.embeddingText(),c.pageNo(),c.sourcePath(),c.sourceUrl(),c.sortOrder(),c.contentHash(),c.qualityStatus(),c.qualityReason(),null);
        when(mapper.findActiveChunksForSourceProof(42,false)).thenReturn(List.of(newer));
        assertThat(service().preview(42L,null).mismatchReason()).isEqualTo("documentId/chunkVersion");
    }

    @Test void oversizedActiveSnapshotFailsClosed() throws Exception {
        fixture();when(mapper.findActiveChunksForSourceProof(42,false)).thenReturn(Collections.nCopies(10001,stored.get(0)));
        var preview=service().preview(42L,null);
        assertThat(preview.token()).isNull();assertThat(preview.storedChunkCount()).isEqualTo(10001);
    }

    @Test void changedSnapshotAfterPreviewInvalidatesToken() throws Exception {
        fixture();var service=service();var preview=service.preview(42L,null);
        when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(mapper.findActiveChunksForSourceProof(42,true)).thenReturn(List.of(stored.get(0)));
        assertThatThrownBy(()->service.apply(42,preview.token())).isInstanceOf(IllegalStateException.class);
        verify(mapper,never()).backfillSourceProofIfUnchanged(any(),anyString(),anyString());
    }

    @Test void moreThan64ProofChunksCannotProduceApplyToken() throws Exception {
        fixture(65);
        var preview=service().preview(42L,null);
        assertThat(preview.token()).isNull();
        assertThat(preview.mismatchReason()).isEqualTo("sourceProofBound");
    }

    private RagSourceProofBackfillService service() {
        return new RagSourceProofBackfillService(mapper,originals,extractor,chunker,quality,tx);
    }
    private void fixture() throws Exception {
        fixture(1);
    }
    private void fixture(int dataRows) throws Exception {
        Path file=temp.resolve("source.hwpx");
        StringBuilder body=new StringBuilder();
        for(int row=1;row<=dataRows;row++) body.append("<tr>").append(cell(row,0,"가람"+row,false)).append(cell(row,1,"7월 단 협의 필요",false)).append("</tr>");
        String xml="<section><tbl colCnt='2'><tr>"+cell(0,0,"기관",true)+cell(0,1,"일정",true)+"</tr>"+body+"</tbl></section>";
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));
            zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        document=new RagDocumentRow(42,"official_doc","계획",null,null,null,null,null,1,"source.hwpx",file.toString(),null,hash,null,null,"INDEXED");
        var generated=quality.evaluate("계획",chunker.chunkV4(42,extractor.extract(file),null,"계획",hash)).retainedChunks();
        stored=new ArrayList<>();
        for(int n=0;n<generated.size();n++) stored.add(copy(generated.get(n),101+n,generated.get(n).chunkText(),null));
        when(mapper.findSourceProofDocument(eq(42L),anyBoolean())).thenReturn(document);
        when(mapper.findActiveChunksForSourceProof(eq(42L),anyBoolean())).thenReturn(stored);
        when(originals.materialize(document)).thenReturn(file);
    }
    private static RagDocumentChunkRow copy(RagDocumentChunkRow c,long id,String text,String proof) {
        return new RagDocumentChunkRow(id,c.documentId(),c.chunkVersion(),c.chunkNo(),c.parentSectionTitle(),c.chunkTitle(),c.sectionType(),text,c.embeddingText(),c.pageNo(),c.sourcePath(),c.sourceUrl(),c.sortOrder(),c.contentHash(),c.qualityStatus(),c.qualityReason(),proof);
    }
    private static String cell(int row,int col,String text,boolean header) {
        return "<tc header='"+(header?1:0)+"'><cellAddr rowAddr='"+row+"' colAddr='"+col+"'/><cellSpan rowSpan='1' colSpan='1'/><t>"+text+"</t></tc>";
    }
}
