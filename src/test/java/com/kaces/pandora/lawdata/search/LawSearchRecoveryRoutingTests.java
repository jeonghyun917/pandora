package com.kaces.pandora.lawdata.search;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kaces.pandora.lawdata.persistence.*;
import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.recovery.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class LawSearchRecoveryRoutingTests {
    @Test void countAndPageShareCapturedGenerationAndUnavailabilityPropagates() {
        var rag=mock(RagDocumentMapper.class);var router=mock(RagRecoveryRoutingService.class);
        var routing=new RecoveryRoutingSnapshot(0,Map.of(),Map.of(),Set.of(),null,null);
        when(router.capture()).thenReturn(routing);
        when(rag.countChunkSearchRouted("official_doc","보존",routing)).thenReturn(1);
        when(rag.searchChunksRouted("official_doc","보존",10,0,routing)).thenReturn(List.of(
            new LawChunkSearchRow(123,7,"official_doc","7","문서","기관","공식",null,null,"document","1","보존","원본 보존",null,null,0)));
        var service=new LawSearchQueryService(mock(LawDocumentMapper.class),mock(LawChunkMapper.class),rag);
        service.configureRecoveryRouting(router);
        var query=LawSearchQuery.normalize("official_doc","보존",1,10,false,true);
        var result=service.searchChunks(query);
        assertThat(result.total()).isEqualTo(1);assertThat(result.rows()).extracting(LawChunkSearchRow::chunkId).containsExactly(123L);
        verify(router,times(1)).capture();
        when(router.capture()).thenThrow(new IllegalStateException("routing unavailable"));
        assertThatThrownBy(()->service.searchChunks(query)).isInstanceOf(IllegalStateException.class);
    }
}
