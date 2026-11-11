package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagRecoveryRoutingTests {
    @Test void indexPreparationUsesTheInjectedCapturedRouterBeforeExternalWrites() {
        var recovery=org.mockito.Mockito.mock(RagRecoveryMapper.class);
        var routing=org.mockito.Mockito.mock(RagRecoveryRoutingService.class);
        var manager=org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
        org.mockito.Mockito.when(manager.getTransaction(org.mockito.ArgumentMatchers.any())).thenReturn(new org.springframework.transaction.support.SimpleTransactionStatus());
        org.mockito.Mockito.when(recovery.lockRoutingEpoch()).thenReturn(new RecoveryRoutingEpoch(0,null,null));
        org.mockito.Mockito.when(routing.captureInTransaction()).thenThrow(new IllegalStateException("captured PDF source unavailable"));
        var properties=org.mockito.Mockito.mock(com.kaces.pandora.semantic.config.LawAiProperties.class,org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.when(properties.openai().embeddingModel()).thenReturn("test");
        var embeddings=org.mockito.Mockito.mock(com.kaces.pandora.infra.openai.OpenAiEmbeddingClient.class);
        var qdrant=org.mockito.Mockito.mock(com.kaces.pandora.infra.qdrant.QdrantClient.class);
        var index=new RagRecoveryIndexService(null,recovery,null,null,embeddings,qdrant,null,null,manager,properties,"a".repeat(40),routing,
                org.mockito.Mockito.mock(RagPdfValidatedSourceReader.class));
        assertThatThrownBy(()->index.prepare(GENERATION)).isInstanceOf(IllegalStateException.class).hasMessageContaining("captured PDF source unavailable");
        org.mockito.Mockito.verifyNoInteractions(embeddings,qdrant);
    }
    @Test void pdfSourceReaderRunsInsideTheSameCapturedTransaction() {
        var mapper=org.mockito.Mockito.mock(RagRecoveryMapper.class);
        var lexical=org.mockito.Mockito.mock(com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.class);
        var pdf=org.mockito.Mockito.mock(RagPdfSourceRoutingReader.class);
        String hash="a".repeat(64),manifest="b".repeat(64),base="c".repeat(64);
        var pointer=new RecoverySnapshot(42,1,GENERATION,hash,base);
        var assets=new RecoveryAssets(GENERATION,manifest,"rag_recovery_"+GENERATION.replace("-",""),"d".repeat(64),"e".repeat(64),1);
        var source=new RecoveryPdfSource(GENERATION,42,"11111111-1111-1111-1111-111111111111",hash,"f".repeat(64),
                manifest,"Reviewed edition","https://agency.example/rule.pdf");
        org.mockito.Mockito.when(mapper.findRoutingEpoch()).thenReturn(new RecoveryRoutingEpoch(1,GENERATION,"e".repeat(64)));
        org.mockito.Mockito.when(mapper.isCorpusCurrent()).thenReturn(true);
        org.mockito.Mockito.when(mapper.listRoutingPointers()).thenReturn(List.of(pointer));
        org.mockito.Mockito.when(mapper.listActiveBaselines()).thenReturn(List.of());
        org.mockito.Mockito.when(mapper.findGeneration(GENERATION,false)).thenReturn(new RecoveryGeneration(GENERATION,42,hash,"f".repeat(40),base,manifest,"d".repeat(64),"ACTIVE"));
        org.mockito.Mockito.when(mapper.findAssets(GENERATION)).thenReturn(assets);
        org.mockito.Mockito.when(mapper.verifiedEmbeddingCount(GENERATION)).thenReturn(1);
        org.mockito.Mockito.when(lexical.findRecoveryState("recovery_"+GENERATION)).thenReturn(
                new com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.IndexStateRow("recovery_"+GENERATION,"test",1,1,"e".repeat(64),"RECOVERY_READY",null));
        org.mockito.Mockito.when(pdf.capture(org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.anySet())).thenAnswer(call->{
            assertThat(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return Map.of(GENERATION,source);
        });
        var transactions=new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            protected Object doGetTransaction(){return new Object();}
            protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition){}
            protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status){}
            protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status){}
        };
        var service=new RagRecoveryRoutingService(mapper,lexical,transactions);
        service.configurePdfSources(pdf);
        assertThat(service.capture().pdfSources()).containsExactlyEntriesOf(Map.of(GENERATION,source));
    }
    @Test void preparationCannotReadUnboundRoutingOutsideTransaction() {
        var service=new RagRecoveryRoutingService(org.mockito.Mockito.mock(RagRecoveryMapper.class),
            org.mockito.Mockito.mock(com.kaces.pandora.semantic.lexical.SemanticLexicalMapper.class),
            org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(service::captureInTransaction).isInstanceOf(IllegalStateException.class);
    }
    private static final String GENERATION="c87a26f6-cc34-4303-bdc2-929657369db3";
    @Test void lexicalNamespaceIsCapturedRatherThanResolvedAgainByFingerprint() {
        var first=new RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),"e".repeat(64),"recovery_"+GENERATION);
        var second=new RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),"e".repeat(64),"recovery_00000000-0000-0000-0000-000000000002");
        assertThat(first.lexicalNamespace()).isEqualTo("recovery_"+GENERATION);
        assertThat(first.cacheIdentity()).isNotEqualTo(second.cacheIdentity());
        assertThatThrownBy(()->new RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),"e".repeat(64),"arbitrary"))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void snapshotCopiesMutableInputsAndSeparatesCacheRevisions() {
        var documents=new HashMap<Long,RecoverySnapshot>();
        documents.put(42L,new RecoverySnapshot(42,1,GENERATION,"a".repeat(64),"b".repeat(64)));
        var assets=Map.of(GENERATION,new RecoveryAssets(GENERATION,"c".repeat(64),"rag_recovery_"+GENERATION.replace("-",""),"d".repeat(64),"e".repeat(64),2));
        var before=new RecoveryRoutingSnapshot(1,documents,assets,Set.of(),"e".repeat(64),"recovery_"+GENERATION);
        documents.clear();
        assertThat(before.replacedDocumentIds()).containsExactly(42L);
        assertThatThrownBy(()->before.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        var after=new RecoveryRoutingSnapshot(2,before.documents(),assets,Set.of(),"e".repeat(64),"recovery_"+GENERATION);
        assertThat(before.cacheIdentity()).isNotEqualTo(after.cacheIdentity());
    }
    @Test void missingAssetsCannotBeMistakenForBaseline() {
        var documents=Map.of(42L,new RecoverySnapshot(42,1,GENERATION,"a".repeat(64),"b".repeat(64)));
        assertThatThrownBy(()->new RecoveryRoutingSnapshot(1,documents,Map.of(),Set.of(),"e".repeat(64),"recovery_"+GENERATION)).isInstanceOf(IllegalStateException.class);
        var baseline=new RecoveryRoutingSnapshot(2,documents,Map.of(),Set.of(GENERATION),null,null);
        assertThat(baseline.replacedDocumentIds()).isEmpty();
    }
    @Test void unmanagedLegacySnapshotNeedsNoRecoveryAssets() {
        var snapshot=new RecoveryRoutingSnapshot(0,Map.of(),Map.of(),Set.of(),null,null);
        assertThat(snapshot.replacedDocumentIds()).isEmpty();
        assertThat(snapshot.cacheIdentity()).matches("[0-9a-f]{64}");
    }
}
