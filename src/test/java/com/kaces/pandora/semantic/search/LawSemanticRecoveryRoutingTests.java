package com.kaces.pandora.semantic.search;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kaces.pandora.infra.openai.OpenAiEmbeddingClient;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.recovery.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class LawSemanticRecoveryRoutingTests {
    @Test void searchUsesOneCapturedRoutingForVectorAndBodyAndFailsClosed() {
        var law=mock(LawChunkMapper.class); var rag=mock(RagDocumentMapper.class);
        var embedding=mock(OpenAiEmbeddingClient.class); var q=mock(QdrantClient.class);
        var router=mock(RagRecoveryRoutingService.class);
        String generation="00000000-0000-0000-0000-000000000001";
        var snapshot=new RecoveryRoutingSnapshot(1,
            Map.of(45L,new RecoverySnapshot(45,1,generation,"a".repeat(64),"b".repeat(64))),
            Map.of(generation,new RecoveryAssets(generation,"c".repeat(64),"rag_recovery_00000000000000000000000000000001",
                "d".repeat(64),"e".repeat(64),1)),Set.of(),"e".repeat(64),"recovery_"+generation);
        when(router.capture()).thenReturn(snapshot);
        when(embedding.embed(List.of("원문 보존"))).thenReturn(List.of(List.of(1.0)));
        when(q.searchBalanced(List.of(1.0),List.of("official_doc"),5,5,snapshot))
            .thenReturn(List.of(new QdrantSearchHit("official_doc",123,.9)));
        when(rag.findSemanticChunksByIdsRouted(List.of(123L),snapshot)).thenReturn(List.of(
            new LawSemanticChunkRow(123,45,"official_doc","45","문서","기관","공식",null,null,
                "1","본문","보존된 원문",1,"$.pages[1]",null,0,"a".repeat(64),null,"body","PASS")));
        var service=new LawSemanticSearchService(law,rag,embedding,q,router);
        var response=service.search("official_doc","원문 보존",5).get("SemanticSearch");
        assertThat(response.totalCnt()).isEqualTo(1);
        assertThat(response.items().get(0).chunkId()).isEqualTo(123);
        verify(router,times(1)).capture();
        when(router.capture()).thenThrow(new IllegalStateException("routing unavailable"));
        assertThatThrownBy(()->service.search("official_doc","원문 보존",5)).isInstanceOf(IllegalStateException.class);
    }
}
