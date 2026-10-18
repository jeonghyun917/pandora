package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.kaces.pandora.rag.chunk.RagSourceTableEvidenceService;
import com.kaces.pandora.rag.chunk.SourceTableBindings;

class SemanticTableProvenanceTests {
    @Test void completeCellKeepsItsConditionAndCannotBeBorrowedByAnotherGround() {
        String sentence="담당기관은 검토가 완료될 때까지 절차를 보류할 수 있다.";
        var source=mock(RagSourceTableEvidenceService.class);
        var cell=new com.kaces.pandora.rag.importing.SourceTableRow.Cell(1,0,1,1,sentence,null,null);
        var binding=new SourceTableBindings.Binding(42,"section0.xml","tbl[1]",1,0,null,null,sentence,"a".repeat(64),4,1,sentence,List.of(cell));
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(binding));
        when(source.fromSelectedChunks(List.of(102L))).thenReturn(List.of());
        var first=new LawAiAnswerGround(1,101,42,"official_doc","title",null,null,null,null,"1",null,1,"담당기관은",null,null,1d);
        var second=new LawAiAnswerGround(2,102,42,"official_doc","title",null,null,null,null,"1",null,1,"다른 항목",null,null,1d);
        var matcher=new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source);
        var index=matcher.index(List.of(first,second));
        assertThat(index.atoms()).filteredOn(a->a.sentence().equals(sentence)).singleElement()
            .satisfies(a->assertThat(a.groundNumber()).isEqualTo(1));
        assertThat(matcher.match(new KoreanEvidenceAtomParser().parse(sentence),index).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.match(new KoreanEvidenceAtomParser().parse("담당기관은 절차를 보류할 수 있다."),index).status()).isEqualTo(ClaimEvidenceMatcher.Status.INSUFFICIENT);
        assertThat(matcher.match(new KoreanEvidenceAtomParser().parse(sentence.replace("할 수 있다","하여야 한다")),index).status()).isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
    @Test void unownedContextAnnotationCannotBorrowACompleteCellSentence() {
        String sentence="담당기관은 신청인에게 검토결과를 통보하여야 한다.";
        var source=mock(RagSourceTableEvidenceService.class);
        var cell=new com.kaces.pandora.rag.importing.SourceTableRow.Cell(1,0,1,1,sentence,"header","내용");
        var binding=new SourceTableBindings.Binding(42,"section0.xml","tbl[1]",1,0,"header","내용",sentence,"a".repeat(64),4,1,"내용: "+sentence,List.of(cell));
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(binding));
        var marked=new LawAiAnswerGround(1,101,42,"official_doc","title",null,null,null,null,"1",null,1,"담당기관은 신청인에게*",null,null,1d);
        assertThat(new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source).index(List.of(marked)).atoms())
            .noneMatch(a->a.sentence().equals(sentence));
    }
    @Test void actualOriginalSplitCellNeedsCompleteSelectedCoverage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        String sentence="담당기관은 "+"신청자료 ".repeat(380)+"자료를 검토하여야 한다.";
        var file=temp.resolve("single-cell.hwpx");
        String xml="<section><tbl colCnt='1'><tr><tc header='1'><cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>내용</t></tc></tr><tr><tc><cellAddr rowAddr='1' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/><t>"+sentence+"</t></tc></tr></tbl></section>";
        try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
        var chunks=new com.kaces.pandora.rag.chunk.RagChunker().chunkV4(42,new com.kaces.pandora.rag.importing.RagTextExtractor().extract(file),null,"계획",hash);
        var inputs=chunks.stream().map(c->new SourceTableBindings.Input(42,4,c.pageNo(),c.chunkText(),hash,c.sourceProvenance())).toList();
        var bindings=new SourceTableBindings().validate(file,inputs);
        assertThat(bindings).filteredOn(b->b.valueText().equals(sentence)).singleElement()
            .satisfies(b->assertThat(b.rowCells()).hasSize(1));
        var source=mock(RagSourceTableEvidenceService.class);
        var ids=java.util.stream.LongStream.range(101,101+chunks.size()).boxed().toList();
        when(source.fromSelectedChunks(ids)).thenReturn(bindings);
        var fragment=chunks.get(1).chunkText();
        var ground=new LawAiAnswerGround(1,101,42,"official_doc","title",null,null,null,null,"1",null,1,fragment,null,null,1d,fragment,null,ids,"explicit");
        var matcher=new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source);
        assertThat(matcher.index(List.of(ground)).atoms()).anyMatch(a->a.sentence().equals(sentence));
        var incomplete=new SourceTableBindings().validate(file,inputs.subList(0,inputs.size()-1));
        assertThat(incomplete).noneMatch(b->b.valueText().equals(sentence));
        when(source.fromSelectedChunks(ids)).thenReturn(incomplete);
        assertThat(matcher.index(List.of(ground)).atoms()).noneMatch(a->a.sentence().equals(sentence));
    }
    @Test void consumesAnOriginalCompleteSentenceCellRatherThanInferringFromItsHeader() {
        var source=mock(RagSourceTableEvidenceService.class);
        String sentence="담당기관은 신청인에게 검토결과를 통보하여야 한다.";
        var cell=new com.kaces.pandora.rag.importing.SourceTableRow.Cell(1,0,1,1,sentence,"header","내용");
        var binding=new SourceTableBindings.Binding(42,"section0.xml","tbl[1]",1,0,"header","내용",sentence,"a".repeat(64),4,1,"내용: "+sentence,List.of(cell));
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(binding));
        var fragment=new LawAiAnswerGround(1,101,42,"official_doc","title",null,null,null,null,"1",null,1,
            "담당기관은 신청인에게",null,null,1d);
        var matcher=new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source);
        var index=matcher.index(List.of(fragment));
        assertThat(index.atoms()).anySatisfy(atom->{
            assertThat(atom.sentence()).isEqualTo(sentence);
            assertThat(atom.groundNumber()).isEqualTo(1);
            assertThat(atom.membershipContextUnrestricted()).isFalse();
        });
        assertThat(matcher.match(new KoreanEvidenceAtomParser().parse(sentence),index).status()).isEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.match(new KoreanEvidenceAtomParser().parse(sentence.replace("담당기관","다른기관")),index).status()).isNotEqualTo(ClaimEvidenceMatcher.Status.SUPPORTED);
    }
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
    @Test void separateCellOrParentRestrictionCannotBeLost() {
        String sentence="담당기관은 신청인에게 검토결과를 통보하여야 한다.";
        var source=mock(RagSourceTableEvidenceService.class);
        var first=new com.kaces.pandora.rag.importing.SourceTableRow.Cell(1,0,1,1,sentence,"header","내용");
        var condition=new com.kaces.pandora.rag.importing.SourceTableRow.Cell(1,1,1,1,"심사가 완료된 경우","h2","조건");
        var binding=new SourceTableBindings.Binding(42,"section0.xml","tbl[1]",1,0,"header","내용",sentence,"a".repeat(64),4,1,"내용: "+sentence,List.of(first,condition));
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(binding));
        var matcher=new SemanticEvidenceMatcher(new KoreanEvidenceAtomParser(),source);
        assertThat(matcher.index(List.of(ground("official_doc",101,42))).atoms()).noneMatch(a->a.sentence().equals(sentence));
        binding=new SourceTableBindings.Binding(42,"section0.xml","tbl[1]",1,0,"header","내용",sentence,"a".repeat(64),4,1,"내용: "+sentence,List.of(first));
        when(source.fromSelectedChunks(List.of(101L))).thenReturn(List.of(binding));
        var limited=new LawAiAnswerGround(1,101,42,"official_doc","title",null,null,null,null,"1",null,1,
            "담당기관은 신청인에게",null,null,1d,"담당기관은 신청인에게","심사가 완료된 경우에만 처리한다.",List.of(101L),"explicit");
        assertThat(matcher.index(List.of(limited)).atoms()).noneMatch(a->a.sentence().equals(sentence));
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
