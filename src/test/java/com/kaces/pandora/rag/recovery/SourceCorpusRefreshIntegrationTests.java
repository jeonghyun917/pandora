package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.kaces.pandora.lawdata.version.*;
import com.kaces.pandora.semantic.lexical.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceCorpusRefreshIntegrationTests {
    @Test void normalRebuildLocksSourceFirstAndPublishesOnlyTheBoundOverlay() {
        var mapper=mock(RagRecoveryMapper.class);var lexical=mock(SemanticLexicalMapper.class);
        var routing=mock(RagRecoveryRoutingService.class);var index=mock(SemanticLexicalIndexService.class);
        var sources=mock(SourceVersionCorpusService.class);var reader=mock(SourceVersionRoutingReader.class);
        var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        String generation=UUID.randomUUID().toString();String hash="a".repeat(64);
        when(mapper.lockRoutingEpoch()).thenReturn(new RecoveryRoutingEpoch(1,generation,hash));
        when(mapper.findCorpusRevision()).thenReturn(3L);
        var captured=new RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),hash,"recovery_"+generation);
        when(routing.captureForRebuildInTransaction()).thenReturn(captured);
        var row=new LexicalChunkDocument("admrul",1,2,null,hash,"원문","","","직접 근거");
        var bound=RecoveryCorpus.overlay(1,List.of(row),Map.of());
        when(lexical.findActiveSearchableChunks()).thenReturn(List.of());
        when(sources.overlay(any(),eq(captured.sources()))).thenReturn(bound);
        when(index.rebuildRecovery(anyString(),eq(bound.chunks()))).thenAnswer(call->new SemanticLexicalIndexService.BuildResult("recovery_"+call.getArgument(0),hash,1,1,1));
        when(mapper.casCorpusEpoch(eq(1L),eq(3L),anyString(),eq(hash))).thenReturn(1);
        var service=new RagRecoveryCorpusRefreshService(mapper,lexical,routing,index,manager);
        service.configureSourceVersions(sources,reader);
        assertThat(service.rebuild().activeChunkCount()).isEqualTo(1);
        var ordered=inOrder(sources,mapper);
        ordered.verify(sources).lockSourceEpoch();ordered.verify(mapper).lockRoutingEpoch();
        verify(sources,times(2)).overlay(any(),eq(captured.sources()));
        verify(index).verifyRecovery(anyString(),eq(bound.chunks()));
        verify(routing).configureSourceVersionRouting(reader);
    }
    @Test void sourceOverlayFailureCannotPublishANormalRebuild() {
        var mapper=mock(RagRecoveryMapper.class);var lexical=mock(SemanticLexicalMapper.class);
        var routing=mock(RagRecoveryRoutingService.class);var index=mock(SemanticLexicalIndexService.class);
        var sources=mock(SourceVersionCorpusService.class);var manager=mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        String generation=UUID.randomUUID().toString(),hash="a".repeat(64);
        when(mapper.lockRoutingEpoch()).thenReturn(new RecoveryRoutingEpoch(1,generation,hash));when(mapper.findCorpusRevision()).thenReturn(1L);
        when(routing.captureForRebuildInTransaction()).thenReturn(new RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),hash,"recovery_"+generation));
        when(lexical.findActiveSearchableChunks()).thenReturn(List.of());when(sources.overlay(any(),any())).thenThrow(new IllegalStateException("source receipt drift"));
        var service=new RagRecoveryCorpusRefreshService(mapper,lexical,routing,index,manager);
        service.configureSourceVersions(sources,mock(SourceVersionRoutingReader.class));
        assertThatThrownBy(service::rebuild).isInstanceOf(IllegalStateException.class).hasMessageContaining("source receipt drift");
        verifyNoInteractions(index);verify(mapper,never()).casCorpusEpoch(anyLong(),anyLong(),anyString(),anyString());
    }
}
