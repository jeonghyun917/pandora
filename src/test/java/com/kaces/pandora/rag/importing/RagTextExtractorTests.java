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
    void ordersHwpxSectionsByTheirNumericSourcePosition() throws Exception {
        Path file=temp.resolve("section-order.hwpx");
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))) {
            entry(zip,"Contents/section10.xml","<section><t>열한번째 원문 표</t></section>");
            entry(zip,"Contents/section2.xml","<section><t>세번째 원문 표</t></section>");
            entry(zip,"Contents/section0.xml","<section><t>첫번째 원문 표</t></section>");
        }
        assertThat(new RagTextExtractor().extract(file).pages()).containsExactly(
            new ExtractedPage(1,"첫번째 원문 표"),
            new ExtractedPage(2,"세번째 원문 표"),
            new ExtractedPage(3,"열한번째 원문 표"));
    }

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
    void bindsUnmarkedHeadersOnlyWithAnExplicitNumberColumnAndFollowingNumberedRow() throws Exception {
        Path file=temp.resolve("numbered-headers.hwpx");
        String xml="<section><tbl colCnt='3'><tr>"+cell(0,0,1,3,"종합 점검")
            +"</tr><tr>"+cell(1,0,1,1,"연번")+cell(1,1,1,1,"기관")+cell(1,2,1,1,"일정")
            +"</tr><tr>"+cell(2,0,1,1,"1")+cell(2,1,1,1,"가람")+cell(2,2,1,1,"3월")
            +"</tr><tr>"+cell(3,0,1,3,"별도 점검")
            +"</tr><tr>"+cell(4,0,1,1,"2")+cell(4,1,1,1,"누리")+cell(4,2,1,1,"5월")+"</tr></tbl></section>";
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
        assertThat(new RagTextExtractor().extract(file).text()).isEqualTo(
            "종합 점검\n연번 | 기관 | 일정\n연번: 1 | 기관: 가람 | 일정: 3월\n별도 점검\n2 | 누리 | 5월");
    }

    @Test
    void doesNotInferNumberedHeadersWithoutACompleteNumberedSuccessor() throws Exception {
        for(String successor : new String[]{
            cell(1,0,1,1,"예시")+cell(1,1,1,1,"가람"),
            cell(1,0,1,2,"1"),
            cell(1,0,1,1,"0")+cell(1,1,1,1,"가람")}) {
            Path file=temp.resolve("unproven-numbered-header.hwpx");
            String xml="<section><tbl colCnt='2'><tr>"+cell(0,0,1,1,"번호")+cell(0,1,1,1,"기관")
                +"</tr><tr>"+successor+"</tr></tbl></section>";
            try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
            assertThat(new RagTextExtractor().extract(file).text()).startsWith("번호 | 기관\n").doesNotContain("번호:","기관:");
        }
    }

    @Test
    void anAmbiguousNumberHeaderCannotKeepThePreviousHeaderOwner() throws Exception {
        Path file=temp.resolve("changed-header.hwpx");
        String xml="<section><tbl colCnt='3'><tr>"
            +(cell(0,0,1,1,"기관")+cell(0,1,1,1,"일정")+cell(0,2,1,1,"구분")).replace("<tc>","<tc header='1'>")
            +"</tr><tr>"+cell(1,0,1,1,"번호")+cell(1,1,1,1,"이름")+cell(1,2,1,1,"이 름")
            +"</tr><tr>"+cell(2,0,1,1,"1")+cell(2,1,1,1,"가람")+cell(2,2,1,1,"3월")+"</tr></tbl></section>";
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
        assertThat(new RagTextExtractor().extract(file).text()).endsWith("번호 | 이름 | 이 름\n1 | 가람 | 3월");
    }

    @Test
    void carriesOnlyExplicitColumnHeadersIntoTheirOwnDataRows() throws Exception {
        Path file=temp.resolve("owned-headers.hwpx");
        String xml="<section><tbl colCnt='2'><tr>"
            +cell(0,0,1,1,"기관").replace("<tc>","<tc header='1'>")
            +cell(0,1,1,1,"일정").replace("<tc>","<tc header='1'>")
            +"</tr><tr>"+cell(1,0,1,1,"가람")+cell(1,1,1,1,"3월")
            +"</tr><tr>"+cell(2,0,1,2,"다른 구역")
            +"</tr><tr>"+cell(3,0,1,1,"누리")+cell(3,1,1,1,"5월")+"</tr></tbl>"
            +"<tbl colCnt='2'><tr>"+cell(0,0,1,1,"기관")+cell(0,1,1,1,"일정")
            +"</tr><tr>"+cell(1,0,1,1,"새봄")+cell(1,1,1,1,"6월")+"</tr></tbl></section>";
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
        var document=new RagTextExtractor().extract(file);
        assertThat(document.text()).isEqualTo("기관 | 일정\n기관: 가람 | 일정: 3월\n다른 구역\n누리 | 5월\n기관 | 일정\n새봄 | 6월");
        var chunks=new com.kaces.pandora.rag.chunk.RagChunker().chunkV4(1L,document,null,"계획");
        assertThat(chunks).anySatisfy(c->assertThat(c.chunkText()).contains("기관: 가람 | 일정: 3월"));
        var source=chunks.stream().filter(c->c.chunkText().contains("기관: 가람 | 일정: 3월")).findFirst().orElseThrow();
        var ground=new com.kaces.pandora.ai.answer.LawAiAnswerGround(1,1L,1L,"official_doc","계획","","","20260101","CURRENT",
            source.chunkNo(),source.chunkTitle(),source.pageNo(),source.chunkText(),source.sourcePath(),null,1.0);
        var matcher=new com.kaces.pandora.ai.answer.ClaimEvidenceMatcher();
        assertThat(matcher.match("기관: 가람 | 일정: 3월",java.util.List.of(ground)).status())
            .isEqualTo(com.kaces.pandora.ai.answer.ClaimEvidenceMatcher.Status.SUPPORTED);
        assertThat(matcher.match("기관: 누리 | 일정: 3월",java.util.List.of(ground)).status())
            .isNotEqualTo(com.kaces.pandora.ai.answer.ClaimEvidenceMatcher.Status.SUPPORTED);
    }

    @Test
    void clearsHeaderOwnershipForPartialDuplicateAndMergedHeaders() throws Exception {
        for(String headerRow : new String[]{
            cell(1,0,1,1,"이름").replace("<tc>","<tc header='1'>")+cell(1,1,1,1,"값"),
            (cell(1,0,1,1,"이름")+cell(1,1,1,1,"이름")).replace("<tc>","<tc header='1'>"),
            (cell(1,0,1,1,"이름")+cell(1,1,1,1,"이 름")).replace("<tc>","<tc header='1'>"),
            (cell(1,0,1,1,"")+cell(1,1,1,1,"값")).replace("<tc>","<tc header='1'>"),
            cell(1,0,1,2,"구역").replace("<tc>","<tc header='1'>")}) {
            Path file=temp.resolve("ambiguous-headers.hwpx");
            String xml="<section><tbl colCnt='2'><tr>"
                +(cell(0,0,1,1,"기관")+cell(0,1,1,1,"일정")).replace("<tc>","<tc header='1'>")
                +"</tr><tr>"+headerRow+"</tr><tr>"+cell(2,0,1,1,"가람")+cell(2,1,1,1,"3월")+"</tr></tbl></section>";
            try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
            assertThat(new RagTextExtractor().extract(file).text()).endsWith("가람 | 3월").doesNotContain("기관: 가람","일정: 3월","이름: 가람");
        }
    }

    @Test
    void preservesColumnLabelsForDeclaredVerticalSpansAndResetsOnNewHeaders() throws Exception {
        Path file=temp.resolve("header-spans.hwpx");
        String xml="<section><tbl colCnt='2'><tr>"
            +(cell(0,0,1,1,"기관")+cell(0,1,1,1,"일정")).replace("<tc>","<tc header='1'>")
            +"</tr><tr>"+cell(1,0,1,1,"가람")+cell(1,1,2,1,"연중")
            +"</tr><tr>"+cell(2,0,1,1,"누리")
            +"</tr><tr>"+(cell(3,0,1,1,"시설")+cell(3,1,1,1,"수량")).replace("<tc>","<tc header='1'>")
            +"</tr><tr>"+cell(4,0,1,1,"본관")+cell(4,1,1,1,"2개")+"</tr></tbl><t>다만 변경될 수 있음</t></section>";
        try(var zip=new ZipOutputStream(Files.newOutputStream(file))){entry(zip,"Contents/section0.xml",xml);}
        assertThat(new RagTextExtractor().extract(file).text()).isEqualTo(
            "기관 | 일정\n기관: 가람 | 일정: 연중\n기관: 누리 | 일정: 연중\n시설 | 수량\n시설: 본관 | 수량: 2개\n다만 변경될 수 있음");
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
