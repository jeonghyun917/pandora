package com.kaces.pandora.lawdata.sync;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDPropertyList;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TaggedPdfParagraphTests {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path temp;
    @Test void extractsOnlyAdjacentOrderedSpansUnderTheSameParagraph() throws Exception {
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            var marks = new PDMarkInfo();
            marks.setMarked(true);
            pdf.getDocumentCatalog().setMarkInfo(marks);
            var tree = new PDStructureTreeRoot();
            pdf.getDocumentCatalog().setStructureTreeRoot(tree);
            var paragraph = new PDStructureElement("P", tree);
            paragraph.setPage(page);
            tree.appendKid(paragraph);
            paragraph.appendKid(0);
            paragraph.appendKid(1);
            var following = new PDStructureElement("P", tree);
            following.setPage(page);
            tree.appendKid(following);
            following.appendKid(2);
            try (var output = new PDPageContentStream(pdf,page)) {
                line(output, 0, "Temporary installations", 700);
                line(output, 1, "are exempt only during the trial.", 680);
                line(output, 2, "A different paragraph", 650);
            }
            assertThat(TaggedPdfParagraph.read(pdf,1,0,1))
                .contains(new TaggedPdfParagraph.Lines("Temporary installations", "are exempt only during the trial."));
            assertThat(TaggedPdfParagraph.read(pdf,1,1,2)).isEmpty();
            assertThat(TaggedPdfParagraph.read(pdf,1,1,0)).isEmpty();
            assertThat(TaggedPdfParagraph.read(pdf,2,0,1)).isEmpty();
            var file=temp.resolve("tagged.pdf");
            pdf.save(file.toFile());
            var hash=SourceReferenceInspector.artifactHash(java.nio.file.Files.readAllBytes(file));
            var inspection=new SourcePdfInspection();
            assertThat(inspection.paragraph(file,hash,1,0,1))
                .isEqualTo(new TaggedPdfParagraph.Lines("Temporary installations", "are exempt only during the trial."));
            assertThatThrownBy(()->inspection.paragraph(file,"0".repeat(64),1,0,1)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(()->inspection.paragraph(file,hash,1,1,2)).isInstanceOf(IllegalStateException.class);
            String literal="Temporary installations\nare exempt only during the trial.";
            String raw="Scope\n"+literal+"\nSeparate paragraph.";
            var row=new com.kaces.pandora.rag.document.RagSourceProofRow(12,23,4,1,raw,hash,null);
            var route=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(3,java.util.Map.of(),
                java.util.Map.of(),java.util.Set.of(),null,null);
            var otherRoute=new com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot(4,java.util.Map.of(),
                java.util.Map.of(),java.util.Set.of(),null,null);
            var receipt=PdfParagraphReceipt.prepare(file,row,route,1,0,1,6,6+literal.length(),-1).orElseThrow();
            assertThat(receipt.selectedParagraph(row,route)).contains("Temporary installations are exempt only during the trial.");
            assertThat(receipt.selectedParagraph(row,otherRoute)).isEmpty();
            assertThat(receipt.selectedParagraph(new com.kaces.pandora.rag.document.RagSourceProofRow(
                13,23,4,1,raw,hash,null),route)).isEmpty();
            assertThat(receipt.selectedParagraph(new com.kaces.pandora.rag.document.RagSourceProofRow(
                12,23,4,1,raw+" ",hash,null),route)).isEmpty();
            assertThat(receipt.selectedBody(row,route))
                .contains("Scope\nTemporary installations are exempt only during the trial.\nSeparate paragraph.");
            assertThat(receipt.selectedBody(row,otherRoute)).isEmpty();
            following.appendKid(1);
            assertThat(TaggedPdfParagraph.read(pdf,1,0,1)).isEmpty();
        }
    }

    private static void line(PDPageContentStream output, int id, String value, int y) throws Exception {
        var props = new COSDictionary();
        props.setInt(COSName.MCID,id);
        output.beginMarkedContent(COSName.getPDFName("Span"),PDPropertyList.create(props));
        output.beginText();
        output.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);
        output.newLineAtOffset(50,y);
        output.showText(value);
        output.endText();
        output.endMarkedContent();
    }
}
