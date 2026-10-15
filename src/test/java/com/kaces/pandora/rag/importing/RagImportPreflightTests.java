package com.kaces.pandora.rag.importing;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.kaces.pandora.rag.chunk.RagChunker;
import com.kaces.pandora.rag.chunk.RagChunkQualityGate;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.search.RagChunkSearchIndexService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagImportPreflightTests {
    @TempDir Path temp;

    @Test
    void acceptedNewDocumentRebindsDraftChunksToTheAllocatedDocumentId() throws Exception {
        Path file=temp.resolve("accepted.txt");
        Files.writeString(file,"자료 처리 가이드\n\n"+
            "기관은 원본 자료를 검토하고 처리 결과를 기록하여야 한다. 담당자는 변경 이력을 보존하고 필요한 경우 원본과 대조하여 확인하여야 한다. ".repeat(8));
        var mapper=mock(RagDocumentMapper.class);
        var index=mock(RagChunkSearchIndexService.class);
        when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
        when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of());
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            index,null,null,null,null);
        service.importFile(file,"official_doc",false,false);
        var rows=org.mockito.ArgumentCaptor.forClass(com.kaces.pandora.rag.document.RagDocumentChunkRow.class);
        verify(mapper,atLeastOnce()).insertChunk(rows.capture());
        assertThat(rows.getAllValues()).isNotEmpty().allSatisfy(row->{
            assertThat(row.documentId()).isEqualTo(42L);
            assertThat(row.chunkText()).isNotBlank();
            assertThat(row.contentHash()).hasSize(64);
        });
        verify(index).rebuildDocument(42L,4);
    }

    @Test
    void rejectedReplacementCannotMutateDocumentOrActiveChunks() throws Exception {
        Path file=temp.resolve("rejected.txt");
        Files.writeString(file,"문서 제목");
        var mapper=mock(RagDocumentMapper.class);
        var index=mock(RagChunkSearchIndexService.class);
        when(mapper.findDocumentIdByHash(anyString())).thenReturn(42L);
        when(mapper.findChunkIdsByDocumentId(42L)).thenReturn(List.of(17L));
        var service=new RagImportService(mapper,new RagTextExtractor(),new RagChunker(),new RagChunkQualityGate(),
            index,null,null,null,null);
        assertThatThrownBy(()->service.importFile(file,"official_doc",false,true))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("quality gate rejected");
        verify(mapper).findDocumentByHash(anyString());
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(index);
    }
}
