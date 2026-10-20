package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class RagRecoveryRoutingTests {
    private static final String GENERATION="c87a26f6-cc34-4303-bdc2-929657369db3";
    @Test void snapshotCopiesMutableInputsAndSeparatesCacheRevisions() {
        var documents=new HashMap<Long,RecoverySnapshot>();
        documents.put(42L,new RecoverySnapshot(42,1,GENERATION,"a".repeat(64),"b".repeat(64)));
        var assets=Map.of(GENERATION,new RecoveryAssets(GENERATION,"c".repeat(64),"rag_recovery_"+GENERATION.replace("-",""),"d".repeat(64),"e".repeat(64),2));
        var before=new RecoveryRoutingSnapshot(1,documents,assets,Set.of(),"e".repeat(64));
        documents.clear();
        assertThat(before.replacedDocumentIds()).containsExactly(42L);
        assertThatThrownBy(()->before.documents().clear()).isInstanceOf(UnsupportedOperationException.class);
        var after=new RecoveryRoutingSnapshot(2,before.documents(),assets,Set.of(),"e".repeat(64));
        assertThat(before.cacheIdentity()).isNotEqualTo(after.cacheIdentity());
    }
    @Test void missingAssetsCannotBeMistakenForBaseline() {
        var documents=Map.of(42L,new RecoverySnapshot(42,1,GENERATION,"a".repeat(64),"b".repeat(64)));
        assertThatThrownBy(()->new RecoveryRoutingSnapshot(1,documents,Map.of(),Set.of(),"e".repeat(64))).isInstanceOf(IllegalStateException.class);
        var baseline=new RecoveryRoutingSnapshot(2,documents,Map.of(),Set.of(GENERATION),null);
        assertThat(baseline.replacedDocumentIds()).isEmpty();
    }
    @Test void unmanagedLegacySnapshotNeedsNoRecoveryAssets() {
        var snapshot=new RecoveryRoutingSnapshot(0,Map.of(),Map.of(),Set.of(),null);
        assertThat(snapshot.replacedDocumentIds()).isEmpty();
        assertThat(snapshot.cacheIdentity()).matches("[0-9a-f]{64}");
    }
}
