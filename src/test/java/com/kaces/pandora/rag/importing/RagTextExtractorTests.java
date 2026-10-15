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
}
