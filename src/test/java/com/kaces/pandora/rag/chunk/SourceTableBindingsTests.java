package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.kaces.pandora.rag.importing.*;

class SourceTableBindingsTests {
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="PANDORA_TABLE_PROOF_SOURCE",matches=".+")
    void diagnosesReadOnlyOriginalSourceConsumer() throws Exception {
        var file=java.nio.file.Path.of(System.getenv("PANDORA_TABLE_PROOF_SOURCE"));
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
        var document=new RagTextExtractor().extract(file);
        var chunks=new RagChunker().chunkV4(42,document,null,"원문 구조 진단",hash);
        var inputs=chunks.stream().map(c->new SourceTableBindings.Input(42,4,c.pageNo(),c.chunkText(),hash,c.sourceProvenance())).toList();
        var bindings=new SourceTableBindings().validate(file,inputs);
        assertThat(bindings).isNotEmpty();
        System.out.println("SOURCE_TABLE_PROOF_DIAGNOSTIC fileHash="+hash+" rows="+document.pages().stream().mapToInt(p->p.tableRows().size()).sum()+" chunks="+chunks.size()+" literalBindings="+bindings.size());
    }
    @Test void exposesOnlySourceVerifiedLiteralHeaderValueBindings(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var file=writeTable(temp,"가람","7월");
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
        String data="기관: 가람 | 일정: 7월";
        var chunks=new RagChunker().chunkV4(42,new RagTextExtractor().extract(file),null,"계획",hash);
        var validator=new SourceTableBindings();
        var inputs=chunks.stream().map(c->new SourceTableBindings.Input(c.documentId(),c.chunkVersion(),c.pageNo(),c.chunkText(),hash,c.sourceProvenance())).toList();
        var bindings=validator.validate(file,inputs);
        assertThat(bindings).hasSize(2);
        assertThat(bindings).anySatisfy(b->{assertThat(b.headerText()).isEqualTo("일정");assertThat(b.valueText()).isEqualTo("7월");});
        assertThat(validator.validate(file,List.of(inputs.get(1)))).isEmpty(); // Missing header proof.
        var datum=inputs.get(1);
        assertThat(validator.validate(file,List.of(inputs.get(0),new SourceTableBindings.Input(42,4,1,data.replace("7월","8월"),hash,datum.provenance())))).isEmpty();
        assertThat(validator.validate(file,List.of(inputs.get(0),new SourceTableBindings.Input(42,4,1,data,"b".repeat(64),datum.provenance())))).isEmpty();
        assertThat(validator.validate(file,List.of(inputs.get(0),new SourceTableBindings.Input(42,3,1,data,hash,datum.provenance())))).isEmpty();
        assertThat(validator.validate(file,List.of(inputs.get(0),new SourceTableBindings.Input(43,4,1,data,hash,datum.provenance())))).isEmpty();
        assertThat(validator.validate(file,List.of(inputs.get(0),new SourceTableBindings.Input(42,4,1,data,hash,datum.provenance().replace("tbl[1]","tbl[2]"))))).isEmpty();
        assertThat(validator.validate(file,List.of(new SourceTableBindings.Input(42,4,1,data,hash,"malformed")))).isEmpty();
        for(String changed:List.of(datum.provenance().replace("\"schemaVersion\":1","\"schemaVersion\":2"),
            datum.provenance().replace("\"completeRow\":true","\"completeRow\":false"),
            datum.provenance().replace("/tr[1]/tc[2]","/tr[2]/tc[2]"),
            datum.provenance().replace("\"text\":\"7월\"","\"text\":\"8월\""))) {
            var result=validator.validate(file,List.of(inputs.get(0),new SourceTableBindings.Input(42,4,1,data,hash,changed)));
            assertThat(result).noneSatisfy(b->assertThat(b.headerText()).isEqualTo("일정"));
        }
    }

    @Test void validatesActualExtractorOutputWithoutParsingValueDelimiters(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        var file=temp.resolve("table.hwpx");
        String xml="<section><tbl colCnt='2'><tr>"+cell(0,0,"기관",true)+cell(0,1,"일정",true)+"</tr><tr>"
            +cell(1,0,"가람 | 일정: 임의",false)+cell(1,1,"7월 (변경 가능)",false)+"</tr></tbl></section>";
        try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));
            zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
        var chunks=new RagChunker().chunkV4(42,new RagTextExtractor().extract(file),null,"계획",hash);
        var inputs=chunks.stream().map(c->new SourceTableBindings.Input(42,4,c.pageNo(),c.chunkText(),hash,c.sourceProvenance())).toList();
        var bindings=new SourceTableBindings().validate(file,inputs);
        assertThat(bindings).hasSize(2);
        assertThat(bindings).anySatisfy(b->assertThat(b.valueText()).isEqualTo("가람 | 일정: 임의"));
        assertThat(bindings).anySatisfy(b->assertThat(b.valueText()).isEqualTo("7월 (변경 가능)"));
        var json=new tools.jackson.databind.ObjectMapper();
        var forged=json.readTree(inputs.get(1).provenance());
        ((tools.jackson.databind.node.ObjectNode)forged.get("row").get("cells").get(0)).put("text","가람");
        ((tools.jackson.databind.node.ObjectNode)forged.get("row").get("cells").get(1)).put("text","임의 | 일정: 7월 (변경 가능)");
        var originalInput=inputs.get(1);
        var attack=new SourceTableBindings.Input(42,4,originalInput.pageNo(),originalInput.text(),hash,json.writeValueAsString(forged));
        assertThat(new SourceTableBindings().validate(file,List.of(inputs.get(0),attack))).isEmpty();
    }

    private String cell(int row,int column,String text,boolean header) {
        return "<tc header='"+(header?1:0)+"'><cellAddr rowAddr='"+row+"' colAddr='"+column+"'/><cellSpan rowSpan='1' colSpan='1'/><t>"+text+"</t></tc>";
    }

    @Test void joinsOnlyFullyCoveredOriginalRowFragments(@org.junit.jupiter.api.io.TempDir java.nio.file.Path temp) throws Exception {
        String value="7월 "+"원문 일정 설명 ".repeat(230)+"단, 기관과 협의한 경우에만 변경 가능";
        var file=writeTable(temp,"가람",value);
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(file)));
        var chunks=new RagChunker().chunkV4(42,new RagTextExtractor().extract(file),null,"계획",hash);
        var inputs=chunks.stream().map(c->new SourceTableBindings.Input(42,4,c.pageNo(),c.chunkText(),hash,c.sourceProvenance())).toList();
        assertThat(inputs).hasSizeGreaterThan(2);
        assertThat(new SourceTableBindings().validate(file,inputs)).anySatisfy(b->assertThat(b.valueText()).isEqualTo(value));
        assertThat(new SourceTableBindings().validate(file,inputs.subList(0,inputs.size()-1))).isEmpty();
        var missingHead=new java.util.ArrayList<>(inputs);missingHead.remove(1);
        assertThat(new SourceTableBindings().validate(file,missingHead)).isEmpty();
    }

    private java.nio.file.Path writeTable(java.nio.file.Path temp,String institution,String date) throws Exception {
        var file=temp.resolve("original.hwpx");
        String xml="<section><tbl colCnt='2'><tr>"+cell(0,0,"기관",true)+cell(0,1,"일정",true)+"</tr><tr>"+cell(1,0,institution,false)+cell(1,1,date,false)+"</tr></tbl></section>";
        try(var zip=new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(file))) {
            zip.putNextEntry(new java.util.zip.ZipEntry("Contents/section0.xml"));
            zip.write(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }
        return file;
    }
}
