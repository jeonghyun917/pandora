package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagRecoveryRoutingTests {
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
