package com.kaces.pandora.lawdata.sync;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourceHwpxTableGraphTests {
    @TempDir Path root;
    private int sequence;
    @Test void nestedTablesRetainOwnedCellsWithoutParentHeaderInheritance() throws Exception {
        Path file=file(nestedSection());var graph=new SourceHwpxTableGraph().capture(file);
        assertThat(graph.tables()).hasSize(2);
        var parent=graph.tables().get(0);var child=graph.tables().get(1);
        assertThat(parent.parentCellPath()).isNull();
        assertThat(parent.sourcePath()).contains("{urn%3Ahp}tbl[1]");
        assertThat(parent.namespaceURI()).isEqualTo("urn:hp");
        assertThat(child.parentCellPath()).isEqualTo(parent.cells().get(0).sourcePath());
        assertThat(parent.cells().get(0).childTablePaths()).containsExactly(child.sourcePath());
        assertThat(parent.cells().get(0).directText()).contains("계약 완료 전 x <= 1").doesNotContain("90% 미만","2점");
        assertThat(child.cells()).extracting(SourceHwpxTableGraph.Cell::column).containsExactly(0,1);
        assertThat(child.cells()).extracting(SourceHwpxTableGraph.Cell::header).containsOnly("0");
        assertThat(child.cells().get(0).directText()).contains("90% 미만");
        assertThatThrownBy(()->graph.tables().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThat(new com.kaces.pandora.rag.importing.RagTextExtractor().extract(file).pages()).allSatisfy(page->assertThat(page.tableRows()).isEmpty());
    }
    static String nestedSection() {
        String inner=table(1,2,row(cell(0,0,1,1,"90% 미만",""),cell(0,1,1,1,"2점","")));
        String outer=table(1,1,row(cell(0,0,1,1,"계약 완료 전 x <= 1",inner).replaceFirst("header=\"0\"","header=\"1\"")));
        return section(outer);
    }
    @Test void mergesRetainOneOriginCellWithoutDuplicatingOwnership() throws Exception {
        Path file=file(section(table(2,2,row(cell(0,0,2,1,"완료 시까지",""),cell(0,1,1,1,"상단",""))+row(cell(1,1,1,1,"하단","")))));
        var table=new SourceHwpxTableGraph().capture(file).tables().get(0);
        assertThat(table.cells()).hasSize(3);assertThat(table.cells().get(0).rowSpan()).isEqualTo(2);
        assertThat(table.cells().stream().map(SourceHwpxTableGraph.Cell::sourcePath).distinct()).hasSize(3);
    }
    @Test void rejectsMissingOverlappingOrOutOfBoundsCellOwnership() throws Exception {
        for(String invalid:List.of(
            table(1,2,row(cell(0,0,1,1,"조건",""))),
            table(1,2,row(cell(0,0,1,1,"조건",""),cell(0,0,1,1,"다른 조건",""))),
            table(1,1,row(cell(0,0,2,1,"범위 초과",""))),
            table(1,1,row(cell(1,0,1,1,"행 불일치",""))),
            table(1,1,row(cell(0,0,1,1,"주소 누락","").replace("<hp:cellAddr rowAddr=\"0\" colAddr=\"0\"/>",""))))) {
            Path file=file(section(invalid));assertThatThrownBy(()->new SourceHwpxTableGraph().capture(file)).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void rejectsDtdExternalEntitiesDepthAndDuplicateSectionIdentity() throws Exception {
        String dtd="<!DOCTYPE hs:sec [<!ENTITY external SYSTEM 'file:///not-read-by-source-capture'>]>"+section("<hp:p><hp:t>&external;</hp:t></hp:p>");
        Path dtdFile=file(dtd);assertThatThrownBy(()->new SourceHwpxTableGraph().capture(dtdFile)).isInstanceOf(IllegalStateException.class);
        Path deep=file(section("<hp:p>".repeat(70)+"text"+"</hp:p>".repeat(70)));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(deep)).isInstanceOf(IllegalStateException.class);
        Path duplicate=archive(Map.of("Contents/section0.xml",section(""),"Contents/section00.xml",section("")));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(duplicate)).isInstanceOf(IllegalStateException.class);
    }
    @Test void rejectsXmlGridAndAggregateTextExpansionBounds() throws Exception {
        Path large=file(section(" ".repeat(2*1024*1024+1)));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(large)).isInstanceOf(IllegalStateException.class);
        Path grid=file(section(table(2000,200,row(cell(0,0,2000,200,"oversized grid",""))+row().repeat(1999))));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(grid)).isInstanceOf(IllegalStateException.class);
        String text=section(table(1,1,row(cell(0,0,1,1,"x".repeat(1_400_000),""))));
        Path aggregate=archive(Map.of("Contents/section0.xml",text,"Contents/section1.xml",text,"Contents/section2.xml",text));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(aggregate)).isInstanceOf(IllegalStateException.class);
    }
    @Test void nodeTableAndCellCountBudgetsCannotReturnPartialGraphs() throws Exception {
        Path nodes=file(section("<hp:x/>".repeat(100001)));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(nodes)).isInstanceOf(IllegalStateException.class);
        Path tables=file(section(table(1,1,row(cell(0,0,1,1,"x",""))).repeat(257)));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(tables)).isInstanceOf(IllegalStateException.class);
        var rows=new StringBuilder();
        for(int r=0;r<51;r++) {rows.append("<hp:tr>");for(int c=0;c<200;c++)rows.append("<hp:tc><hp:cellAddr rowAddr=\"").append(r).append("\" colAddr=\"").append(c).append("\"/><hp:cellSpan rowSpan=\"1\" colSpan=\"1\"/><hp:t>x</hp:t></hp:tc>");rows.append("</hp:tr>");}
        Path cells=file(section(table(51,200,rows.toString())));
        assertThatThrownBy(()->new SourceHwpxTableGraph().capture(cells)).isInstanceOf(IllegalStateException.class);
    }
    private Path file(String xml) throws Exception {return archive(Map.of("Contents/section0.xml",xml));}
    private Path archive(Map<String,String> sections) throws Exception {
        Path file=root.resolve("source-"+(sequence++)+".hwpx");
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry("mimetype"));zip.write("application/hwp+zip".getBytes());zip.closeEntry();
            for(var entry:sections.entrySet()) {zip.putNextEntry(new ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();}
        }return file;
    }
    private static String section(String body) {return "<hs:sec xmlns:hs=\"urn:hs\" xmlns:hp=\"urn:hp\">"+body+"</hs:sec>";}
    private static String table(int rows,int columns,String body) {return "<hp:tbl rowCnt=\""+rows+"\" colCnt=\""+columns+"\">"+body+"</hp:tbl>";}
    private static String row(String... cells) {return "<hp:tr>"+String.join("",cells)+"</hp:tr>";}
    private static String cell(int row,int column,int rowSpan,int columnSpan,String text,String nested) {
        String escaped=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        return "<hp:tc header=\"0\"><hp:cellAddr rowAddr=\""+row+"\" colAddr=\""+column+"\"/><hp:cellSpan rowSpan=\""+rowSpan+"\" colSpan=\""+columnSpan+"\"/><hp:p><hp:run><hp:t>"+escaped+"</hp:t>"+nested+"</hp:run></hp:p></hp:tc>";
    }
}
