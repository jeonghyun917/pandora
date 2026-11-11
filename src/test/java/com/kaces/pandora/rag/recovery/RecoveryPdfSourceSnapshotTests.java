package com.kaces.pandora.rag.recovery;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RecoveryPdfSourceSnapshotTests {
    private static final String GENERATION="11111111-1111-1111-1111-111111111111";
    private static final String DRAFT="22222222-2222-2222-2222-222222222222";
    private static final String MANIFEST="a".repeat(64), SOURCE="b".repeat(64), BASELINE="c".repeat(64), LEXICAL="d".repeat(64);

    private RecoveryPdfSource source(String title) {
        return new RecoveryPdfSource(GENERATION,42,DRAFT,SOURCE,"e".repeat(64),MANIFEST,title,"https://agency.example/rule.pdf");
    }
    private RecoveryRoutingSnapshot snapshot(Map<String,RecoveryPdfSource> sources) {
        var pointer=new RecoverySnapshot(42,1,GENERATION,SOURCE,BASELINE);
        var assets=new RecoveryAssets(GENERATION,MANIFEST,"rag_recovery_"+GENERATION.replace("-",""),"f".repeat(64),LEXICAL,2);
        return new RecoveryRoutingSnapshot(1,Map.of(42L,pointer),Map.of(GENERATION,assets),Set.of(),LEXICAL,
                "recovery_"+GENERATION,com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot.empty(),sources);
    }

    @Test void capturedMetadataIsImmutableAndChangesCacheIdentity() {
        var mutable=new HashMap<String,RecoveryPdfSource>();mutable.put(GENERATION,source("Reviewed edition"));
        var captured=snapshot(mutable);
        mutable.put(GENERATION,source("Different edition"));
        assertThat(captured.pdfSources().get(GENERATION).title()).isEqualTo("Reviewed edition");
        assertThat(captured.cacheIdentity()).isNotEqualTo(snapshot(mutable).cacheIdentity());
        assertThat(captured.cacheIdentity()).isNotEqualTo(snapshot(Map.of()).cacheIdentity());
    }

    @Test void wrongGenerationSourceDocumentOrManifestCannotBeCaptured() {
        assertThatThrownBy(()->snapshot(Map.of(DRAFT,source("Reviewed edition")))).isInstanceOf(IllegalStateException.class);
        for(var wrong:List.of(
                new RecoveryPdfSource(GENERATION,43,DRAFT,SOURCE,"e".repeat(64),MANIFEST,"Reviewed edition","https://agency.example/rule.pdf"),
                new RecoveryPdfSource(GENERATION,42,DRAFT,"f".repeat(64),"e".repeat(64),MANIFEST,"Reviewed edition","https://agency.example/rule.pdf"),
                new RecoveryPdfSource(GENERATION,42,DRAFT,SOURCE,"e".repeat(64),"f".repeat(64),"Reviewed edition","https://agency.example/rule.pdf")))
            assertThatThrownBy(()->snapshot(Map.of(GENERATION,wrong))).isInstanceOf(IllegalStateException.class);
    }

    @Test void preservedBaselineCannotBeRelabeledAsReplacementOriginal() {
        var pointer=new RecoverySnapshot(42,1,GENERATION,SOURCE,BASELINE);
        assertThatThrownBy(()->new RecoveryRoutingSnapshot(1,Map.of(42L,pointer),Map.of(),Set.of(GENERATION),
                LEXICAL,"recovery_"+GENERATION,com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot.empty(),
                Map.of(GENERATION,source("Replacement title")))).isInstanceOf(IllegalStateException.class);
    }
}
