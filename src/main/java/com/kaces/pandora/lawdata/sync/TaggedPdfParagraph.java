package com.kaces.pandora.lawdata.sync;

import java.io.IOException;
import java.util.*;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.*;
import org.apache.pdfbox.pdmodel.documentinterchange.markedcontent.PDMarkedContent;
import org.apache.pdfbox.text.PDFMarkedContentExtractor;
import org.apache.pdfbox.text.TextPosition;

/** Native tagged text; only called within the bounded PDF worker. */
final class TaggedPdfParagraph {
    record Lines(String first, String second) {}
    private record Reference(int id, COSDictionary owner) {}

    static Optional<Lines> read(PDDocument pdf, int page, int first, int second) throws IOException {
        if (page < 1 || page > pdf.getNumberOfPages() || first < 0 || second < 0 || first == second) return Optional.empty();
        var catalog=pdf.getDocumentCatalog();
        var root=catalog.getStructureTreeRoot();
        if (root==null || catalog.getMarkInfo()==null || !catalog.getMarkInfo().isMarked()) return Optional.empty();
        var target=pdf.getPage(page-1);
        var references=new ArrayList<Reference>();
        collectReferences(root,target,null,null,root.getRoleMap(),references,0,new int[1]);
        if(references.stream().filter(r->r.id()==first).count()!=1
                || references.stream().filter(r->r.id()==second).count()!=1) return Optional.empty();
        boolean sameParagraph=false;
        for(int i=0;i+1<references.size();i++) {
            var a=references.get(i); var b=references.get(i+1);
            if(a.id()==first && b.id()==second && a.owner()!=null && a.owner()==b.owner()) sameParagraph=true;
        }
        if(!sameParagraph) return Optional.empty();
        // Form and transparency streams have their own MCID namespace.
        var extractor=new PDFMarkedContentExtractor() {
            @Override public void showForm(org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject form) {}
            @Override public void showTransparencyGroup(org.apache.pdfbox.pdmodel.graphics.form.PDTransparencyGroup group) {}
        };
        extractor.processPage(target);
        var contents=new ArrayList<PDMarkedContent>();
        collectContents(extractor.getMarkedContents(),contents,0);
        if(contents.stream().filter(c->c.getMCID()==first).count()!=1
                || contents.stream().filter(c->c.getMCID()==second).count()!=1) return Optional.empty();
        for(int i=0;i+1<contents.size();i++) {
            if(contents.get(i).getMCID()==first && contents.get(i+1).getMCID()==second) {
                String a=line(contents.get(i)), b=line(contents.get(i+1));
                if(a!=null && b!=null) return Optional.of(new Lines(a,b));
            }
        }
        return Optional.empty();
    }

    private static void collectReferences(PDStructureNode node,PDPage target,PDPage inherited,
            COSDictionary owner,Map<String,Object> roles,List<Reference> result,int depth,int[] visited) throws IOException {
        if(depth>64 || ++visited[0]>100_000) throw new IOException("Tagged PDF tree bound exceeded");
        if(node instanceof PDStructureElement element) {
            if(element.getPage()!=null) inherited=element.getPage();
            String type=element.getStructureType();
            if("P".equals(roles.getOrDefault(type,type))) owner=element.getCOSObject();
        }
        for(Object child:node.getKids()) {
            if(++visited[0]>100_000) throw new IOException("Tagged PDF tree bound exceeded");
            if(child instanceof PDStructureElement element) {
                collectReferences(element,target,inherited,owner,roles,result,depth+1,visited);
            } else if(child instanceof PDMarkedContentReference reference) {
                var refPage=reference.getPage()==null?inherited:reference.getPage();
                if(samePage(refPage,target)) {
                    boolean pageStream=!reference.getCOSObject().containsKey(COSName.getPDFName("Stm"))
                        && !reference.getCOSObject().containsKey(COSName.getPDFName("StmOwn"));
                    result.add(new Reference(reference.getMCID(),pageStream?owner:null));
                }
            } else if(child instanceof Integer id && samePage(inherited,target)) {
                result.add(new Reference(id,owner));
            }
        }
    }

    private static boolean samePage(PDPage a,PDPage b) { return a!=null && a.getCOSObject()==b.getCOSObject(); }

    private static void collectContents(List<PDMarkedContent> source,List<PDMarkedContent> result,int depth) throws IOException {
        if(depth>64) throw new IOException("Marked content depth exceeded");
        for(var content:source) {
            if(content.getMCID()>=0) {
                if(result.size()>=16_384) throw new IOException("Marked content count exceeded");
                result.add(content);
            }
            for(var part:content.getContents()) {
                if(part instanceof PDMarkedContent nested) collectContents(List.of(nested),result,depth+1);
            }
        }
    }

    private static String line(PDMarkedContent content) {
        var text=new StringBuilder();
        if(!appendInline(content,text,0)) return null;
        String value=text.toString().replaceAll("[ \\t]+"," ").strip();
        return value.isEmpty() || value.codePoints().anyMatch(Character::isISOControl)?null:value;
    }

    private static boolean appendInline(PDMarkedContent content,StringBuilder text,int depth) {
        if(depth>16) return false;
        for(Object part:content.getContents()) {
            if(part instanceof TextPosition glyph && glyph.getUnicode()!=null
                    && text.length()<=4096-glyph.getUnicode().length()) {
                text.append(glyph.getUnicode());
            } else if(!(part instanceof PDMarkedContent span) || span.getMCID()>=0
                    || !"Span".equals(span.getTag()) || !appendInline(span,text,depth+1)) {
                return false;
            }
        }
        return true;
    }
}
