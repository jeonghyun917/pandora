package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;

class RagSourceTableEvidenceServiceTests {
    @Test void loadsOnlySelectedActiveProofAgainstOriginal(@TempDir Path temp) throws Exception {
        Path file=temp.resolve("source.hwpx");
        String xml="<section><tbl colCnt='2'><tr>"+cell(0,0,"기관",true)+cell(0,1,"일정",true)+"</tr><tr>"+cell(1,0,"가람",false)+cell(1,1,"7월 단 협의 필요",false)+"</tr></tbl></section>";
        try(var zip=new java.util.zip.ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        var chunks=new RagChunker().chunkV4(42,new RagTextExtractor().extract(file),null,"계획",hash);
        var proofRows=new java.util.ArrayList<RagSourceProofRow>();
        for(int n=0;n<chunks.size();n++) {
            var c=chunks.get(n);proofRows.add(new RagSourceProofRow(n+1,42,4,c.pageNo(),c.chunkText(),hash,c.sourceProvenance()));
        }
        var mapper=mock(RagDocumentMapper.class);var originals=mock(RagOriginalDocumentStore.class);
        var document=new RagDocumentRow(42,"official_doc","계획",null,null,null,null,null,1,"source.hwpx",file.toString(),null,hash,null,null,"INDEXED");
        when(mapper.findSourceProofChunksByIds(List.of(1L,2L))).thenReturn(proofRows);
        when(mapper.findDocumentById(42)).thenReturn(document);
        doReturn(file).when(originals).materialize(document);
        var service=new RagSourceTableEvidenceService(mapper,originals);
        assertThat(service.fromSelectedChunks(List.of(1L,2L))).hasSize(2);
        var routing=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,java.util.Map.of(),java.util.Map.of(),java.util.Set.of(),null,null);
        when(mapper.findSourceProofChunksByIdsRouted(List.of(1L,2L),routing)).thenReturn(proofRows);
        assertThat(service.fromSelectedChunks(List.of(1L,2L),routing)).hasSize(2);
        verify(mapper).findSourceProofChunksByIdsRouted(List.of(1L,2L),routing);
        assertThat(service.fromSelectedChunks(List.of())).isEmpty();
        assertThat(service.fromSelectedChunks(null)).isEmpty();
        assertThat(service.fromSelectedChunks(java.util.Collections.nCopies(65,1L))).isEmpty();
        verify(mapper,never()).findSourceProofChunksByIds(List.of());
        when(originals.materialize(document)).thenThrow(new java.io.IOException("unavailable"));
        assertThat(service.fromSelectedChunks(List.of(1L,2L))).isEmpty();
        doReturn(file).when(originals).materialize(document);
        when(mapper.findSourceProofChunksByIds(List.of(1L,2L))).thenReturn(List.of(new RagSourceProofRow(999,42,4,1,"text",hash,null)));
        assertThat(service.fromSelectedChunks(List.of(1L,2L))).isEmpty();
        when(mapper.findSourceProofChunksByIds(List.of(1L,2L))).thenThrow(new IllegalStateException("offline"));
        assertThat(service.fromSelectedChunks(List.of(1L,2L))).isEmpty();
    }
    private String cell(int row,int col,String text,boolean header) {
        return "<tc header='"+(header?1:0)+"'><cellAddr rowAddr='"+row+"' colAddr='"+col+"'/><cellSpan rowSpan='1' colSpan='1'/><t>"+text+"</t></tc>";
    }
}
