package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import com.kaces.pandora.semantic.lexical.LexicalChunkDocument;
import java.util.*;
import org.junit.jupiter.api.Test;

class RecoveryCorpusTests {
    private LexicalChunkDocument row(String target,long id,long document,String body) {
        return new LexicalChunkDocument(target,id,document,"parent","a".repeat(64),"title","parent","title",body);
    }
    @Test void replacesEveryManagedDocumentAndPreservesUnrelatedLawWithSameId() {
        var baseline=List.of(row("official_doc",1,10,"old ten"),row("official_doc",2,20,"old twenty"),row("law",3,10,"law"));
        var result=RecoveryCorpus.overlay(7,baseline,Map.of(10L,List.of(row("official_doc",11,10,"new ten")),
            20L,List.of(row("official_doc",22,20,"new twenty"))));
        assertThat(result.chunks()).extracting(LexicalChunkDocument::chunkId).containsExactly(3L,11L,22L);
        assertThat(result.baseRevision()).isEqualTo(7);
        assertThat(result.fingerprint()).matches("[0-9a-f]{64}");
        assertThatThrownBy(()->result.chunks().clear()).isInstanceOf(UnsupportedOperationException.class);
    }
    @Test void fingerprintChangesForTextOrBaseRevisionButNotInputOrder() {
        var a=row("law",1,2,"a");var b=row("law",2,3,"b");
        var original=RecoveryCorpus.overlay(1,List.of(a,b),Map.of());
        assertThat(RecoveryCorpus.overlay(1,List.of(b,a),Map.of()).fingerprint()).isEqualTo(original.fingerprint());
        assertThat(RecoveryCorpus.overlay(2,List.of(a,b),Map.of()).fingerprint()).isNotEqualTo(original.fingerprint());
        assertThat(RecoveryCorpus.overlay(1,List.of(a,row("law",2,3,"changed")),Map.of()).fingerprint()).isNotEqualTo(original.fingerprint());
    }
    @Test void refusesWrongDocumentLawReplacementEmptyReplacementAndDuplicateIdentity() {
        assertThatThrownBy(()->RecoveryCorpus.overlay(1,List.of(),Map.of(10L,List.of(row("official_doc",1,20,"wrong")))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryCorpus.overlay(1,List.of(),Map.of(10L,List.of(row("law",1,10,"wrong target")))))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryCorpus.overlay(1,List.of(),Map.of(10L,List.of())))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->RecoveryCorpus.overlay(1,List.of(row("law",1,2,"a"),row("law",1,2,"a")),Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
