package com.kaces.pandora.lawdata.sync;
import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SourcePdfInspectionTests {
    @TempDir Path root;
    @Test void identicalTextAtDifferentCoordinatesCannotHaveEquivalentPositionEvidence() throws Exception {
        Path a=root.resolve("a.pdf"),b=root.resolve("b.pdf");positioned(a,20);positioned(b,40);
        var inspector=new SourcePdfInspection();assertThat(inspector.inspect(a).textHash()).isEqualTo(inspector.inspect(b).textHash());
        var first=inspector.positions(a);var second=inspector.positions(b);
        assertThat(first.glyphs()).isNotEmpty();assertThat(first.pages()).hasSize(1);
        assertThat(second.glyphs().get(0).x()-first.glyphs().get(0).x()).isCloseTo(20f,within(0.01f));
        assertThat(first.geometryHash()).isNotEqualTo(second.geometryHash());
        assertThat(first.artifactHash()).matches("[0-9a-f]{64}");
    }
    private static void positioned(Path file,float x) throws Exception {
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)) {
                content.beginText();content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),10);
                content.newLineAtOffset(x,700);content.showText("condition 90 score 4");content.endText();
            }document.save(file.toFile());
        }
    }
    @Test void blankPagesCannotRenumberSourceGlyphOwnership() throws Exception {
        Path file=root.resolve("blank-first.pdf");positioned(file,20);
        try(var document=org.apache.pdfbox.Loader.loadPDF(file.toFile())) {
            document.getPages().insertBefore(new org.apache.pdfbox.pdmodel.PDPage(),document.getPage(0));document.save(root.resolve("with-blank.pdf").toFile());
        }
        var result=new SourcePdfInspection().positions(root.resolve("with-blank.pdf"));
        assertThat(result.pages()).hasSize(2);assertThat(result.glyphs()).allSatisfy(g->assertThat(g.page()).isEqualTo(2));
    }
    @Test void rotatedAndOverCapacityPositionsCannotReturnPartialEvidence() throws Exception {
        Path file=root.resolve("rotated.pdf");positioned(file,20);
        try(var document=org.apache.pdfbox.Loader.loadPDF(file.toFile())) {document.getPage(0).setRotation(90);document.save(root.resolve("rotated-final.pdf").toFile());}
        assertThatThrownBy(()->new SourcePdfInspection().positions(root.resolve("rotated-final.pdf"))).isInstanceOf(IllegalStateException.class);
        try(var document=new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();document.addPage(page);
            try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(document,page)) {
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),4);
                for(int row=0;row<110;row++){content.beginText();content.newLineAtOffset(20,750-row*5);content.showText("A".repeat(90));content.endText();}
            }document.save(root.resolve("many.pdf").toFile());
        }
        assertThat(new SourcePdfInspection().inspect(root.resolve("many.pdf")).characterCount()).isGreaterThan(8192);
        assertThatThrownBy(()->new SourcePdfInspection().positions(root.resolve("many.pdf"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void actualWorkerReturnsOnlyBoundedNativeHashes() throws Exception {
        Path file=root.resolve("reference.pdf");Files.write(file,SourceReferenceInspectorTests.pdf());
        var result=new SourcePdfInspection().inspect(file);
        assertThat(result.textHash()).matches("[0-9a-f]{64}");
        assertThat(result.structureHash()).matches("[0-9a-f]{64}");
        assertThat(result.characterCount()).isPositive().isLessThanOrEqualTo(4_000_000);
        assertThat(result.pageCount()).isOne();
    }
    @Test void malformedInputAndDeadlineCannotLeaveAQualifyingResult() throws Exception {
        Path file=root.resolve("bad.pdf");Files.writeString(file,"%PDF-malformed");
        assertThatThrownBy(()->new SourcePdfInspection().inspect(file)).isInstanceOf(IllegalStateException.class);
        Files.write(file,SourceReferenceInspectorTests.pdf());
        assertThatThrownBy(()->new SourcePdfInspection(1).inspect(file)).isInstanceOf(IllegalStateException.class);
    }
    @Test void launchUsesOnlyFixedServerOwnedResourceArguments() {
        var command=SourcePdfInspection.command(root.resolve("reference.pdf"));
        assertThat(command).contains("-Xmx128m","-XX:MaxMetaspaceSize=96m","-XX:MaxDirectMemorySize=32m");
        assertThat(command).doesNotContain("-jar","-ExecutionPolicy","Bypass");
        assertThat(command.get(0)).startsWith(System.getProperty("java.home"));
    }
    @Test void inheritedJvmOptionsMustBeRejectedNotSilentlyRemovedOrTrusted() {
        for(String name:java.util.List.of("_JAVA_OPTIONS","JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS"))
            assertThatThrownBy(()->SourcePdfInspection.requireReviewedEnvironment(java.util.Map.of(name,"-Xmx2g"))).isInstanceOf(IllegalStateException.class);
        assertThatCode(()->SourcePdfInspection.requireReviewedEnvironment(java.util.Map.of("JAVA_HOME","trusted"))).doesNotThrowAnyException();
    }
}
