package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.importing.ExtractedPage;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReviewedPdfUnitTests {
    private final List<ExtractedPage> pages=List.of(new ExtractedPage(1,"Heading\nThe agency must"),
            new ExtractedPage(2,"- 2 -\nnotify before launch.\nNext rule"));
    private final ReviewedPdfUnit unit=new ReviewedPdfUnit(List.of(
            new ReviewedPdfUnit.Line(1,2,"The agency must"),
            new ReviewedPdfUnit.Line(2,2,"notify before launch.")));

    @Test void replaysReviewedTextWithoutPageFurnitureOrAdjacentRule() {
        assertThat(unit.replay(pages)).isEqualTo("The agency must notify before launch.");
    }

    @Test void rejectsChangedSourceLineAndWrongPageIdentity() {
        assertThatThrownBy(() -> unit.replay(List.of(pages.get(0),new ExtractedPage(2,"- 2 -\nmay notify."))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> unit.replay(List.of(pages.get(0),new ExtractedPage(3,pages.get(1).text()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void rejectsReversedAndRepeatedCoordinates() {
        var a=new ReviewedPdfUnit.Line(1,2,"The agency must");
        var b=new ReviewedPdfUnit.Line(2,2,"notify before launch.");
        assertThatThrownBy(() -> new ReviewedPdfUnit(List.of(b,a))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReviewedPdfUnit(List.of(a,a))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void replayRequiresPinnedArtifactAndReview(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        byte[] bytes;
        try (var pdf=new org.apache.pdfbox.pdmodel.PDDocument();
             var output=new java.io.ByteArrayOutputStream()) {
            var page=new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                content.beginText();
                content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                content.newLineAtOffset(40,700);
                content.showText("Notify before launch.");
                content.endText();
            }
            pdf.save(output);
            bytes=output.toByteArray();
        }
        var reviewed=new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"Notify before launch.")));
        String artifact=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        String review=reviewed.reviewHash(artifact);
        var file=java.nio.file.Files.write(root.resolve("reviewed.pdf"),bytes);
        assertThat(reviewed.replayPinned(file,artifact,review)).isEqualTo("Notify before launch.");
        var draft=new ReviewedPdfDraft(12,artifact,"b".repeat(64),"c".repeat(40),
                "Official rule","https://example.gov/rule.pdf",
                List.of(new ReviewedPdfDraft.Unit("procedure","Article 1",reviewed)));
        var replay=draft.replay(file,draft.reviewHash());
        assertThat(replay).singleElement().satisfies(result -> {
            assertThat(result.text()).isEqualTo("Notify before launch.");
            assertThat(result.positions()).containsExactly(new ReviewedPdfUnit.Line(1,1,"Notify before launch."));
        });
        assertThatThrownBy(() -> reviewed.replayPinned(file,artifact,"0".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
        var changed=new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"Notify after launch.")));
        assertThatThrownBy(() -> changed.replayPinned(file,artifact,review))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> changed.replayPinned(file,artifact,changed.reviewHash(artifact)))
                .isInstanceOf(IllegalArgumentException.class);
        bytes[0]^=1;
        java.nio.file.Files.write(file,bytes);
        assertThatThrownBy(() -> reviewed.replayPinned(file,artifact,review))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void combinesTwoPhysicalPagesOnlyUnderTheSameReview(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var file=root.resolve("two-pages.pdf");
        try(var pdf=new org.apache.pdfbox.pdmodel.PDDocument()) {
            for(String literal:List.of("The agency must","notify before launch.")) {
                var page=new org.apache.pdfbox.pdmodel.PDPage();pdf.addPage(page);
                try(var content=new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf,page)) {
                    content.beginText();
                    content.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                            org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA),12);
                    content.newLineAtOffset(40,700);content.showText(literal);content.endText();
                }
            }
            pdf.save(file.toFile());
        }
        var reviewed=new ReviewedPdfUnit(List.of(new ReviewedPdfUnit.Line(1,1,"The agency must"),
                new ReviewedPdfUnit.Line(2,1,"notify before launch.")));
        String artifact=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(java.nio.file.Files.readAllBytes(file)));
        String review=reviewed.reviewHash(artifact);
        assertThat(reviewed.replayPinned(file,artifact,review)).isEqualTo("The agency must notify before launch.");
        var omitted=new ReviewedPdfUnit(List.of(reviewed.lines().get(1)));
        assertThatThrownBy(()->omitted.replayPinned(file,artifact,review)).isInstanceOf(IllegalArgumentException.class);
    }
}
