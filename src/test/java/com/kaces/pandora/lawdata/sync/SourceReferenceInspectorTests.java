package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import java.net.URI;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SourceReferenceInspectorTests {
    @Test void expandedSerializedColumnEvidenceCannotAccumulateAcrossArtifacts() throws Exception {
        String[] values={"구간","\"".repeat(150000),"점수","2"};var xml=new StringBuilder("<hs:sec xmlns:hs=\"urn:hs\" xmlns:hp=\"urn:hp\"><hp:tbl rowCnt=\"2\" colCnt=\"2\">");
        for(int r=0;r<2;r++){xml.append("<hp:tr>");for(int c=0;c<2;c++)xml.append("<hp:tc><hp:cellAddr rowAddr=\"").append(r).append("\" colAddr=\"").append(c).append("\"/><hp:cellSpan rowSpan=\"1\" colSpan=\"1\"/><hp:p><hp:t>").append(values[r*2+c]).append("</hp:t></hp:p></hp:tc>");xml.append("</hp:tr>");}xml.append("</hp:tbl></hs:sec>");
        byte[] bytes=zip(Map.of("mimetype","application/hwp+zip","Contents/section0.xml",xml.toString()));
        var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/hwp+zip",bytes.length,new ByteArrayInputStream(bytes)));
        assertThatThrownBy(()->inspector.inspect("/flDownload.do?flSeq=123")).isInstanceOf(IllegalStateException.class);
    }
    @Test void hierarchyMetadataIsBoundIntoInspectionWithoutGrantingFidelity() throws Exception {
        byte[] bytes=zip(Map.of("mimetype","application/hwp+zip","Contents/section0.xml",SourceHwpxTableGraphTests.nestedSection()));
        var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/hwp+zip",bytes.length,new ByteArrayInputStream(bytes)));
        var proof=inspector.inspect("/flDownload.do?flSeq=123");
        var file=java.nio.file.Files.createTempFile("source-hierarchy-test-",".hwpx");
        try {
            java.nio.file.Files.write(file,bytes);
            var pages=new com.kaces.pandora.rag.importing.RagTextExtractor().extract(file).pages();
            var hierarchy=new SourceHwpxTableGraph().capture(file);
            assertThat(proof.columnEvidence()).hasSize(2).allSatisfy(table->assertThat(table.status()).isEqualTo("UNSUPPORTED"));
            assertThat(proof.structureHash()).isEqualTo(com.kaces.pandora.common.text.LawHashUtils.sha256Json(List.of("source-hwpx-relations-v1",pages,hierarchy,proof.columnEvidence())));
            assertThat(proof.fidelityReady()).isFalse();assertThat(proof.status()).isEqualTo("INTEGRITY_ONLY");
        } finally {java.nio.file.Files.deleteIfExists(file);}
    }
    @Test void acquiredArtifactBytesAreImmutableAndHashBoundWithoutEnteringPublicInspection() throws Exception {
        byte[] bytes=hwpx();
        var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/hwp+zip",bytes.length,new ByteArrayInputStream(bytes)));
        var acquisition=inspector.acquire("/flDownload.do?flSeq=123");
        byte[] first=acquisition.bytes();first[0]=0;
        assertThat(acquisition.bytes()[0]).isEqualTo((byte)'P');
        assertThat(acquisition.inspection().byteCount()).isEqualTo(bytes.length);
        String response=new tools.jackson.databind.ObjectMapper().writeValueAsString(acquisition.inspection());
        assertThat(response).doesNotContain("\"bytes\"","artifactBytes");
        assertThatThrownBy(()->new SourceReferenceInspector.Acquisition(acquisition.inspection(),new byte[]{1,2,3})).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void responseBodyHasAnIndependentDeadlineAfterHeadersArrive() {
        var closed=new java.util.concurrent.CountDownLatch(1);
        InputStream stalled=new InputStream() {
            public int read() throws IOException {try {closed.await();throw new IOException("closed");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException(e);}}
            public void close() {closed.countDown();}
        };
        var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/pdf",-1,stalled),20);
        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(java.time.Duration.ofSeconds(2),()->
            assertThatThrownBy(()->inspector.inspect("/flDownload.do?flSeq=123")).isInstanceOf(IllegalStateException.class));
        assertThat(closed.getCount()).isZero();
    }
    @Test void pdfNativeProcessingUsesIsolationAndCannotAuthorizeFidelity() throws Exception {
        byte[] bytes=pdf();
        var proof=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/pdf",bytes.length,new ByteArrayInputStream(bytes))).inspect("/flDownload.do?flSeq=123");
        assertThat(proof.nativeTextHash()).matches("[0-9a-f]{64}");
        assertThat(proof.fidelityReady()).isFalse();
        assertThat(proof.status()).isEqualTo("INTEGRITY_ONLY");
    }
    @Test void onlyExactOfficialDownloadAddressesAreAcquired() {
        assertThat(SourceReferenceInspector.officialUri("http://law.go.kr/flDownload.do?flSeq=123"))
            .isEqualTo(URI.create("https://www.law.go.kr/LSW/flDownload.do?flSeq=123"));
        for(String invalid:List.of("https://evil.test/flDownload.do?flSeq=1","https://law.go.kr.evil.test/flDownload.do?flSeq=1",
            "https://user@law.go.kr/flDownload.do?flSeq=1","https://law.go.kr:8443/flDownload.do?flSeq=1",
            "//evil.test/flDownload.do?flSeq=1","/LSW/../flDownload.do?flSeq=1","/flDownload.do?flSeq=1&url=http://evil.test",
            "/flDownload.do?flSeq=1#fragment","/file.pdf"))
            assertThatThrownBy(()->SourceReferenceInspector.officialUri(invalid)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void downloadedBytesAreBoundToSourceAndNeverGrantFidelity() throws Exception {
        byte[] pdf=hwpx();
        var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/hwp+zip",pdf.length,new ByteArrayInputStream(pdf)));
        var proof=inspector.inspect("/LSW/flDownload.do?flSeq=123");
        assertThat(proof.byteCount()).isEqualTo(pdf.length);
        assertThat(proof.artifactHash()).matches("[0-9a-f]{64}");
        assertThat(proof.nativeTextHash()).matches("[0-9a-f]{64}");
        assertThat(proof.fidelityReady()).isFalse();
        assertThat(proof.status()).isEqualTo("INTEGRITY_ONLY");
    }
    @Test void rejectsRedirectLengthMismatchHtmlAndUnsupportedBinary() {
        for(var download:List.of(
            new SourceReferenceInspector.Download(302,"application/pdf",4,new ByteArrayInputStream(new byte[4])),
            new SourceReferenceInspector.Download(200,"application/pdf",100,new ByteArrayInputStream(new byte[4])),
            new SourceReferenceInspector.Download(200,"text/html",4,new ByteArrayInputStream(new byte[4])),
            new SourceReferenceInspector.Download(200,"application/octet-stream",4,new ByteArrayInputStream(new byte[4])),
            new SourceReferenceInspector.Download(200,"application/pdf",SourceReferenceInspector.MAX_BYTES+1,new ByteArrayInputStream(new byte[0])))) {
            var inspector=new SourceReferenceInspector(uri->download);
            assertThatThrownBy(()->inspector.inspect("/flDownload.do?flSeq=123")).isInstanceOf(IllegalStateException.class);
        }
    }
    @Test void inspectsNativeHwpxButRejectsArchiveEscapeAndNonHwpxZip() throws Exception {
        byte[] nativeZip=zip(Map.of("mimetype","application/hwp+zip","Contents/section0.xml","<section><p><t>원문 적용 조건을 보존한다.</t></p></section>"));
        var inspector=new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/octet-stream",nativeZip.length,new ByteArrayInputStream(nativeZip)));
        assertThat(inspector.inspect("/flDownload.do?flSeq=123").format()).isEqualTo("hwpx");
        for(var entries:List.of(Map.of("Contents/section0.xml","<section><t>text</t></section>"),
            Map.of("mimetype","application/hwp+zip","../escape","x","Contents/section0.xml","<section><t>text</t></section>"))) {
            byte[] invalid=zip(entries);
            assertThatThrownBy(()->new SourceReferenceInspector(uri->new SourceReferenceInspector.Download(200,"application/zip",invalid.length,new ByteArrayInputStream(invalid))).inspect("/flDownload.do?flSeq=123"))
                .isInstanceOf(IllegalStateException.class);
        }
    }
    private static byte[] zip(Map<String,String> entries) throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(out)) {for(var entry:entries.entrySet()) {
            zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
        }}
        return out.toByteArray();
    }
    static byte[] hwpx() throws Exception {
        return zip(Map.of("mimetype","application/hwp+zip","Contents/section0.xml","<section><p><t>원문 적용 조건을 보존한다.</t></p></section>"));
    }
    static byte[] multipartHwpx() throws Exception {
        var out=new ByteArrayOutputStream();
        try(var zip=new java.util.zip.ZipOutputStream(out)) {
            for(var entry:Map.of("mimetype","application/hwp+zip","Contents/section0.xml","<section><p><t>원문 분할 순서를 보존한다.</t></p></section>").entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));zip.closeEntry();
            }
            byte[] padding=new byte[2*1024*1024+17];new Random(7).nextBytes(padding);
            var entry=new java.util.zip.ZipEntry("BinData/bounded-padding.bin");entry.setMethod(java.util.zip.ZipEntry.STORED);entry.setSize(padding.length);
            var crc=new java.util.zip.CRC32();crc.update(padding);entry.setCrc(crc.getValue());
            zip.putNextEntry(entry);zip.write(padding);zip.closeEntry();
        }
        return out.toByteArray();
    }
    static byte[] pdf() throws Exception {
        try(var doc=new org.apache.pdfbox.pdmodel.PDDocument();var out=new ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();doc.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(doc,page)) {
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(20,700);content.showText("Official reference native text");content.endText();
            }
            doc.save(out);return out.toByteArray();
        }
    }
}
