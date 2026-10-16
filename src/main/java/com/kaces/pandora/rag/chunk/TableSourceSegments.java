package com.kaces.pandora.rag.chunk;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.importing.ExtractedPage;
import com.kaces.pandora.rag.importing.SourceTableRow;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.ObjectMapper;

/** Carries original row intervals; it never reconstructs ownership from rendered delimiters. */
final class TableSourceSegments {
    private static final ObjectMapper JSON = new ObjectMapper();
    record Segment(String text, String provenance, String precedingContext) {
        Segment(String text, String provenance) { this(text,provenance,null); }
    }
    record Proof(int schemaVersion, String sourceFileHash, Integer pageNo, SourceTableRow row,
        String rowHash, int fragmentStart, int fragmentEnd, boolean completeRow) { }

    static List<Segment> split(ExtractedPage page, Function<String,List<String>> bodySplitter,
        int maxChars, int overlap, String sourceFileHash) {
        String text=page.text();
        if(text==null) { return body(null,bodySplitter); }
        int previous=0;
        for(var row:page.tableRows()) {
            if(row.textStart()<previous || row.textEnd()<row.textStart() || row.textEnd()>text.length()
                || !text.substring(row.textStart(),row.textEnd()).equals(row.renderedText())) {
                return body(text,bodySplitter); // Unknown or stale range supplies no proof.
            }
            previous=row.textEnd();
        }
        if(page.tableRows().isEmpty()) { return body(text,bodySplitter); }
        List<Segment> result=new ArrayList<>();
        previous=0;
        for(var row:page.tableRows()) {
            result.addAll(body(text.substring(previous,row.textStart()),bodySplitter));
            result.add(new Segment(null,null,text.substring(previous,row.textStart())));
            String rendered=row.renderedText();
            int start=0;
            while(start<rendered.length()) {
                int end=Math.min(rendered.length(),start+maxChars);
                if(end<rendered.length() && Character.isHighSurrogate(rendered.charAt(end-1))) { end--; }
                int left=start,right=end;
                while(left<right && rendered.charAt(left)<=' ') { left++; }
                while(right>left && rendered.charAt(right-1)<=' ') { right--; }
                if(left<right) {
                    var proof=new Proof(1,sourceFileHash,page.pageNo(),row,LawHashUtils.sha256(rendered),left,right,
                        left==0 && right==rendered.length());
                    result.add(new Segment(rendered.substring(left,right),JSON.writeValueAsString(proof)));
                }
                if(end==rendered.length()) { break; }
                int next=Math.max(end-overlap,start+1);
                if(next>0 && Character.isLowSurrogate(rendered.charAt(next))) { next--; }
                start=next;
            }
            previous=row.textEnd();
        }
        result.addAll(body(text.substring(previous),bodySplitter));
        return List.copyOf(result);
    }

    private static List<Segment> body(String text,Function<String,List<String>> splitter) {
        return splitter.apply(text).stream().map(value->new Segment(value,null)).toList();
    }

    // This identity only controls duplicate retention; it does not authorize semantic claims.
    static String duplicateIdentity(com.kaces.pandora.rag.document.RagDocumentChunkRow chunk) {
        if(chunk.sourceProvenance()==null) { return null; }
        try {
            Proof p=JSON.readValue(chunk.sourceProvenance(),Proof.class);
            SourceTableRow r=p.row();
            if(p.schemaVersion()!=1 || p.sourceFileHash()==null || !p.sourceFileHash().matches("[0-9a-fA-F]{64}")
                || !java.util.Objects.equals(p.pageNo(),chunk.pageNo()) || r==null
                || r.sourcePart()==null || r.sourcePart().isBlank() || r.tablePath()==null || r.tablePath().isBlank()
                || r.rowIndex()<0 || r.renderedText()==null || r.textStart()<0
                || r.textEnd()-r.textStart()!=r.renderedText().length()
                || !LawHashUtils.sha256(r.renderedText()).equals(p.rowHash())
                || p.fragmentStart()<0 || p.fragmentEnd()<=p.fragmentStart() || p.fragmentEnd()>r.renderedText().length()
                || !r.renderedText().substring(p.fragmentStart(),p.fragmentEnd()).equals(chunk.chunkText())
                || p.completeRow()!=(p.fragmentStart()==0 && p.fragmentEnd()==r.renderedText().length())) { return null; }
            return JSON.writeValueAsString(List.of(chunk.documentId(),chunk.chunkVersion(),p.sourceFileHash().toLowerCase(java.util.Locale.ROOT),
                String.valueOf(p.pageNo()),r.sourcePart(),r.tablePath(),r.rowIndex(),p.rowHash(),p.fragmentStart(),p.fragmentEnd()));
        } catch(RuntimeException invalid) { return null; }
    }
}
