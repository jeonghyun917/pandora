package com.kaces.pandora.lawdata.version;

import static org.assertj.core.api.Assertions.*;
import com.kaces.pandora.semantic.lexical.LexicalChunkDocument;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceVersionCorpusTests {
    private LexicalChunkDocument row(String target,long chunk,long document,String title) {
        return new LexicalChunkDocument(target,chunk,document,"parent","a".repeat(64),title,"","제1조","본문");
    }
    @Test void replacesOnlyRegisteredVersionsAndPreservesUnrelatedAndRagRows() {
        var old=row("admrul",1,10,"성과 지침");var historical=row("admrul",2,11,"성과 지침");
        var unrelated=row("admrul",3,12,"다른 지침");var rag=row("official_doc",4,10,"안내서");var current=row("admrul",5,20,"성과 지침");
        var replacement=new SourceVersionCorpus.Replacement("admrul","admrul:성과지침",Set.of(10L,11L),20,List.of(current));
        var corpus=SourceVersionCorpus.overlay(3,List.of(old,historical,unrelated,rag),List.of(replacement));
        assertThat(corpus.chunks()).containsExactly(unrelated,current,rag);
        assertThat(corpus.fingerprint()).matches("[0-9a-f]{64}");
        assertThat(SourceVersionCorpus.overlay(4,List.of(old,historical,unrelated,rag),List.of(replacement)).fingerprint()).isNotEqualTo(corpus.fingerprint());
    }
    @Test void refusesMissingMembershipWrongScopeAndDuplicateSourceIdentity() {
        var old=row("admrul",1,10,"성과 지침");var current=row("admrul",5,20,"성과 지침");
        assertThatThrownBy(()->SourceVersionCorpus.overlay(0,List.of(old),List.of(new SourceVersionCorpus.Replacement("admrul","admrul:성과지침",Set.of(10L),20,List.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SourceVersionCorpus.overlay(0,List.of(row("admrul",1,10,"다른 지침")),List.of(new SourceVersionCorpus.Replacement("admrul","admrul:성과지침",Set.of(10L),20,List.of(current))))).isInstanceOf(IllegalArgumentException.class);
        var replacement=new SourceVersionCorpus.Replacement("admrul","admrul:성과지침",Set.of(10L),20,List.of(current));
        assertThatThrownBy(()->SourceVersionCorpus.overlay(0,List.of(old),List.of(replacement,replacement))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->SourceVersionCorpus.overlay(0,List.of(old,current),List.of(replacement))).isInstanceOf(IllegalArgumentException.class);
    }
}
