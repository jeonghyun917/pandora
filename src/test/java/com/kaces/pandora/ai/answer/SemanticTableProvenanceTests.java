package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.kaces.pandora.rag.chunk.RagSourceTableEvidenceService;
import com.kaces.pandora.rag.chunk.SourceTableBindings;

class SemanticTableProvenanceTests {
    @Test void carriesVerifiedBindingsWithoutManufacturingSemanticAtoms() {
        var source=mock(RagSourceTableEvidenceService.class);
        var binding=new SourceTableBindings.Binding(42,"section0.xml","tbl[1]",1,0,"header","대상기관","기관A","a".repeat(64),4,1,"대상기관: 기관A | 일정: 10월");
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(binding));
        var ground=ground("official_doc",101,42);
        var legacy=new SemanticEvidenceMatcher().index(List.of(ground));
        var matcher=new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source);
        var actual=matcher.index(List.of(ground));
        assertThat(actual.tableBindings()).containsExactly(binding);
        assertThat(actual.atoms()).isEqualTo(legacy.atoms());
        assertThat(matcher.match(new KoreanEvidenceAtomParser().parse("기관A는 10월에 감사를 받아야 한다."),actual).status())
            .isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        verify(source).fromSelectedChunks(List.of(101L));
    }
    @Test void excludesLawIdsAndFailsClosedForUnavailableProof() {
        var source=mock(RagSourceTableEvidenceService.class);
        var matcher=new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source);
        assertThat(matcher.index(List.of(ground("law",101,42))).tableBindings()).isEmpty();
        verifyNoInteractions(source);
        when(source.fromSelectedChunks(List.of(102L))).thenThrow(new IllegalStateException("unavailable"));
        assertThat(matcher.index(List.of(ground("internal_doc",102,43))).tableBindings()).isEmpty();
    }
    @Test void overBoundSelectionDoesNotStartSourceReads() {
        var source=mock(RagSourceTableEvidenceService.class);
        var grounds=java.util.stream.LongStream.rangeClosed(1,65).mapToObj(id->ground("official_doc",id,42)).toList();
        assertThat(new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source).index(grounds).tableBindings()).isEmpty();
        verifyNoInteractions(source);
    }
    @Test void unexpectedDocumentCannotEnterTheSemanticIndex() {
        var source=mock(RagSourceTableEvidenceService.class);
        var foreign=new SourceTableBindings.Binding(99,"section0.xml","tbl[1]",1,0,"header","대상기관","기관B","b".repeat(64),4,1,"대상기관: 기관B");
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(foreign));
        assertThat(new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source)
            .index(List.of(ground("official_doc",101,42))).tableBindings()).isEmpty();
    }
    private LawAiAnswerGround ground(String target,long chunk,long document) {
        return new LawAiAnswerGround(1,chunk,document,target,"title",null,null,null,null,"1",null,1,
            "대상기관: 기관A | 일정: 10월",null,null,1d);
    }
}
