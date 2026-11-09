package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.rag.document.RagSourceProofRow;
import com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot;
import com.kaces.pandora.rag.chunk.PdfParagraphSpanBinding;
import com.kaces.pandora.common.text.LawHashUtils;
import java.nio.file.Path;
import java.util.Optional;

/** Immutable proof prepared outside the online request. */
public final class PdfParagraphReceipt {
    private record Identity(long document,long chunk,int version,int page,String artifactHash,String chunkHash,String route) {}
    private final Identity identity;
    private final String paragraph;
    private final String sourcePair;
    private final int lineBreak;

    private PdfParagraphReceipt(Identity identity,String paragraph,String sourcePair,int lineBreak) {
        this.identity=identity;
        this.paragraph=paragraph;
        this.sourcePair=sourcePair;
        this.lineBreak=lineBreak;
    }

    static Optional<PdfParagraphReceipt> prepare(Path original, RagSourceProofRow row, RecoveryRoutingSnapshot routing,
            int page, int first, int second, int start, int end, int legacyBellOffset) {
        Identity identity=identity(row,routing);
        if(original==null || identity==null || identity.page()!=page || start<0 || end<=start || end>row.chunkText().length())
            return Optional.empty();
        try {
            var lines=new SourcePdfInspection().paragraph(original,identity.artifactHash(),page,first,second);
            return PdfParagraphSpanBinding.bind(row.chunkText(),identity.chunkHash(),start,end,
                    lines.first(),lines.second(),legacyBellOffset)
                .map(value->new PdfParagraphReceipt(identity,value,lines.first()+"\n"+lines.second(),row.chunkText().indexOf('\n',start)));
        } catch(IllegalArgumentException | IllegalStateException invalid) {
            return Optional.empty();
        }
    }

    public Optional<String> selectedParagraph(RagSourceProofRow selected, RecoveryRoutingSnapshot routing) {
        return identity.equals(identity(selected,routing))?Optional.of(paragraph):Optional.empty();
    }

    public Optional<String> selectedBody(RagSourceProofRow selected, RecoveryRoutingSnapshot routing) {
        if(!identity.equals(identity(selected,routing))) return Optional.empty();
        String raw=selected.chunkText();
        return Optional.of(raw.substring(0,lineBreak)+" "+raw.substring(lineBreak+1));
    }

    Optional<String> selectedContext(RagSourceProofRow selected,RecoveryRoutingSnapshot routing,String context) {
        if(context==null || !identity.equals(identity(selected,routing))) return Optional.empty();
        if(context.equals(selected.chunkText())) return selectedBody(selected,routing);
        int start=context.indexOf(sourcePair);
        if(start<0 || context.lastIndexOf(sourcePair)!=start) return Optional.empty();
        int end=start+sourcePair.length();
        if((start>0 && context.charAt(start-1)!='\n') || (end<context.length() && context.charAt(end)!='\n'))
            return Optional.empty();
        return Optional.of(context.substring(0,start)+paragraph+context.substring(end));
    }

    private static Identity identity(RagSourceProofRow row,RecoveryRoutingSnapshot routing) {
        if(row==null || routing==null || row.documentId()<=0 || row.chunkId()<=0 || row.chunkVersion()!=4
                || row.pageNo()==null || row.pageNo()<1 || row.pageNo()>1000 || row.sourceFileHash()==null
                || !row.sourceFileHash().matches("[0-9a-f]{64}") || row.chunkText()==null || row.chunkText().isBlank())
            return null;
        return new Identity(row.documentId(),row.chunkId(),row.chunkVersion(),row.pageNo(),
                row.sourceFileHash(),LawHashUtils.sha256(row.chunkText()),routing.cacheIdentity());
    }
}
