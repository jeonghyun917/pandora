package com.kaces.pandora.rag.importing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RagTextExtractorTests {
    @TempDir Path temp;

    @Test
    void extractsBodySectionsWithoutTurningHeaderMetadataIntoAPage() throws Exception {
        Path file = temp.resolve("document.hwpx");
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "Contents/header.xml", "<header><paraHead>^1.</paraHead><metaTag>{\"name\":\"\"}</metaTag></header>");
            entry(zip, "Contents/section0.xml", "<section><p><t>본문 조건</t></p><table><t>표의 금액 50원</t></table><footNote><t>예외 조건</t></footNote></section>");
            entry(zip, "Contents/section1.xml", "<section><p><t>다음 본문</t></p></section>");
        }
        var result = new RagTextExtractor().extract(file);
        assertThat(result.pages()).containsExactly(
            new ExtractedPage(1, "본문 조건\n표의 금액 50원\n예외 조건"),
            new ExtractedPage(2, "다음 본문"));
    }

    private static void entry(ZipOutputStream zip, String name, String xml) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(xml.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    @Test
    void keepsTableRowsTogetherAndCarriesCellsOnlyWithinTheirDeclaredSpan() throws Exception {
        Path file = temp.resolve("rows.hwpx");
        String xml = "<section><tbl colCnt='2'>"
            + "<tr>" + cell(0,0,1,1,"기관") + cell(0,1,1,1,"일정") + "</tr>"
            + "<tr>" + cell(1,0,1,1,"가람기관") + cell(1,1,2,1,"연중") + "</tr>"
            + "<tr>" + cell(2,0,1,1,"누리기관") + "</tr>"
            + "<tr>" + cell(3,0,1,1,"다음기관") + cell(3,1,1,1,"3~4월") + "</tr>"
            + "</tbl><p><t>여건에 따라 변경될 수 있음</t></p></section>";
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip, "Contents/section0.xml", xml);
        }
        assertThat(new RagTextExtractor().extract(file).text()).isEqualTo(
            "기관 | 일정\n가람기관 | 연중\n누리기관 | 연중\n다음기관 | 3~4월\n여건에 따라 변경될 수 있음");
    }

    private static String cell(int row, int column, int rowSpan, int colSpan, String text) {
        return "<tc><cellAddr rowAddr='"+row+"' colAddr='"+column+"'/>"
            + "<cellSpan rowSpan='"+rowSpan+"' colSpan='"+colSpan+"'/>"
            + "<subList><p><t>"+text+"</t></p></subList></tc>";
    }

    @Test
    void retainsFullWidthSectionRowsAndRepeatedHeadersWithoutCrossingTables() throws Exception {
        Path file = temp.resolve("sections.hwpx");
        String xml = "<section><tbl colCnt='2'><tr>"+cell(0,0,1,2,"종합감사")+"</tr>"
            + "<tr>"+cell(1,0,1,1,"기관")+cell(1,1,1,1,"일정")+"</tr>"
            + "<tr>"+cell(2,0,1,1,"가람")+cell(2,1,1,1,"3월")+"</tr>"
            + "<tr>"+cell(3,0,1,2,"특정감사")+"</tr>"
            + "<tr>"+cell(4,0,1,1,"기관")+cell(4,1,1,1,"일정")+"</tr></tbl>"
            + "<tbl colCnt='2'><tr>"+cell(0,0,1,1,"누리")+cell(0,1,1,1,"5월")+"</tr></tbl></section>";
        try (var zip = new ZipOutputStream(Files.newOutputStream(file))) { entry(zip,"Contents/section0.xml",xml); }
        assertThat(new RagTextExtractor().extract(file).text()).isEqualTo(
            "종합감사\n기관 | 일정\n가람 | 3월\n특정감사\n기관 | 일정\n누리 | 5월");
    }

    @Test
    void preservesNestedTableBoundariesRegardlessOfNamespacePrefix() throws Exception {
        for (String prefix : new String[]{"hp", "alt"}) {
            Path file = temp.resolve("nested-"+prefix+".hwpx");
            String nested="<"+prefix+":tbl colCnt='1'><tr>"+cell(0,0,1,1,"INNER")+"</tr></"+prefix+":tbl>";
            String xml="<section xmlns:hp='urn:hp' xmlns:alt='urn:hp'><tbl colCnt='1'><tr><tc>"
                +"<cellAddr rowAddr='0' colAddr='0'/><cellSpan rowSpan='1' colSpan='1'/>"
                +"<t>BEFORE</t>"+nested+"<t>AFTER</t></tc></tr></tbl></section>";
            try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
            assertThat(new RagTextExtractor().extract(file).text()).isEqualTo("BEFORE\nINNER\nAFTER");
        }
    }

    @Test
    void preservesRawTextWhenCellAddressesOverlapOrSpanIsInvalid() throws Exception {
        for (int span : new int[]{0, 3}) {
            Path file = temp.resolve("invalid"+span+".hwpx");
            String xml="<section><tbl colCnt='2'><tr>"+cell(0,0,1,span,"원문조건")
                +cell(0,0,1,1,"금액50원")+"</tr></tbl></section>";
            try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
            assertThat(new RagTextExtractor().extract(file).text()).isEqualTo("원문조건\n금액50원");
        }
    }
}
