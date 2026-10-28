package com.kaces.pandora.lawdata.version;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceVersionRoutingSnapshotTests {
    private static final String ID="c87a26f6-cc34-4303-bdc2-929657369db3",GROUP="a".repeat(64);
    private SourceVersionRoutingSnapshot.Route route(long revision,boolean candidate) {
        return new SourceVersionRoutingSnapshot.Route(GROUP,revision,"admrul","admrul:성과지침",10,candidate?20:10,Set.of(10L,20L),
            candidate?ID:null,candidate?"b".repeat(64):null,candidate?"law_source_"+ID.replace("-",""):null,
            "d".repeat(64),"c".repeat(64),"text-embedding-3-small",1,candidate?2:0);
    }
    @Test void activatedSourceExcludesAllRawVersionsAndRollbackRestoresOnlyBaseline() {
        var active=new SourceVersionRoutingSnapshot(1,Map.of(GROUP,route(1,true)));
        assertThat(active.excludedDocumentIds()).containsExactlyInAnyOrder(10L,20L);
        assertThat(active.activeVectors()).containsOnlyKeys(ID);
        var restored=new SourceVersionRoutingSnapshot(2,Map.of(GROUP,route(2,false)));
        assertThat(restored.excludedDocumentIds()).containsExactly(20L);
        assertThat(restored.activeVectors()).isEmpty();
        assertThat(restored.cacheIdentity()).isNotEqualTo(active.cacheIdentity());
    }
    @Test void initialRoutingIsEmptyAndDoesNotAcceptUnactivatedOrUnboundRoutes() {
        assertThat(SourceVersionRoutingSnapshot.empty().excludedDocumentIds()).isEmpty();
        assertThat(SourceVersionRoutingSnapshot.empty().activeVectors()).isEmpty();
        assertThatThrownBy(()->new SourceVersionRoutingSnapshot(0,Map.of(GROUP,route(1,true)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new SourceVersionRoutingSnapshot(1,Map.of("f".repeat(64),route(1,true)))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->new SourceVersionRoutingSnapshot(1,Map.of())).isInstanceOf(IllegalStateException.class);
    }
    @Test void capturedAnswerRoutingBindsActivatedSourcesButEmptySourceStatePreservesCacheIdentity() {
        var old=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,Map.of(),Map.of(),Set.of(),null,null);
        var empty=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,Map.of(),Map.of(),Set.of(),null,null,SourceVersionRoutingSnapshot.empty());
        assertThat(empty.cacheIdentity()).isEqualTo(old.cacheIdentity());
        var source=new SourceVersionRoutingSnapshot(1,Map.of(GROUP,route(1,true)));
        var captured=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(1,Map.of(),Map.of(),Set.of(),"f".repeat(64),"recovery_"+ID,source);
        assertThat(captured.sources()).isEqualTo(source);
        assertThat(captured.cacheIdentity()).isNotEqualTo(old.cacheIdentity());
        assertThatThrownBy(()->new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(0,Map.of(),Map.of(),Set.of(),null,null,source)).isInstanceOf(IllegalStateException.class);
    }
}
