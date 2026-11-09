package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.lawdata.chunk.LawSemanticChunkRow;
import com.kaces.pandora.rag.document.RagSourceProofRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.recovery.RagRecoveryRoutingService;
import com.kaces.pandora.rag.recovery.RecoveryRoutingSnapshot;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Opt-in preparation from an operator-pinned source, never from an answer request. */
@Component
public class PdfParagraphEvidence implements ApplicationRunner {
    private final RagDocumentMapper mapper;
    private final RagOriginalDocumentStore originals;
    private final RagRecoveryRoutingService routing;
    private final String specification;
    private volatile Prepared prepared;

    private record Prepared(long chunkId,PdfParagraphReceipt receipt) {}

    public PdfParagraphEvidence(RagDocumentMapper mapper,RagOriginalDocumentStore originals,
            RagRecoveryRoutingService routing,@Value("${pandora.rag.pdf-paragraph-spec:}") String specification) {
        this.mapper=mapper;
        this.originals=originals;
        this.routing=routing;
        this.specification=specification;
    }

    @Override public void run(ApplicationArguments ignored) {
        if(!configured()) return;
        try {
            String[] fields=specification.split(",",-1);
            if(fields.length!=9 || !fields[7].matches("[0-9a-f]{64}") || !fields[8].matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid PDF paragraph specification");
            long id=Long.parseLong(fields[0]);
            int page=Integer.parseInt(fields[1]), first=Integer.parseInt(fields[2]), second=Integer.parseInt(fields[3]);
            int start=Integer.parseInt(fields[4]),end=Integer.parseInt(fields[5]),bell=Integer.parseInt(fields[6]);
            var snapshot=routing.capture();
            var row=read(id,snapshot);
            if(row==null || !fields[7].equals(row.sourceFileHash()) || !fields[8].equals(LawHashUtils.sha256(row.chunkText())))
                throw new IllegalStateException("PDF paragraph source identity changed");
            var document=mapper.findDocumentById(row.documentId());
            if(document==null || document.documentId()!=row.documentId() || !fields[7].equals(document.fileHash()))
                throw new IllegalStateException("PDF paragraph original identity changed");
            var receipt=PdfParagraphReceipt.prepare(originals.materialize(document),row,snapshot,page,first,second,start,end,bell)
                .orElseThrow(()->new IllegalStateException("PDF paragraph proof unavailable"));
            prepared=new Prepared(id,receipt);
        } catch(java.io.IOException | RuntimeException invalid) {
            org.slf4j.LoggerFactory.getLogger(PdfParagraphEvidence.class).warn("Configured PDF paragraph proof unavailable");
        }
    }

    public boolean configured() { return !specification.isBlank(); }

    public String configurationIdentity(String baseline) {
        var current=prepared;
        return configured()?LawHashUtils.sha256Json(List.of(baseline,"pdf-paragraph-v1",specification,current!=null)):baseline;
    }

    public LawSemanticChunkRow projectSelected(LawSemanticChunkRow chunk,RecoveryRoutingSnapshot snapshot) {
        return projectSelectedPair(chunk,chunk,snapshot).map(Projection::child).orElse(chunk);
    }

    public record Projection(LawSemanticChunkRow child,LawSemanticChunkRow context) {}

    public java.util.Optional<Projection> projectSelectedPair(LawSemanticChunkRow chunk,LawSemanticChunkRow display,
            RecoveryRoutingSnapshot snapshot) {
        var active=prepared;
        if(active==null || snapshot==null || chunk==null || display==null || chunk.chunkId()!=active.chunkId()
                || chunk.chunkId()!=display.chunkId() || chunk.documentId()!=display.documentId()
                || !Objects.equals(chunk.target(),display.target()) || !Objects.equals(chunk.pageNo(),display.pageNo())
                || !Objects.equals(chunk.chunkVersion(),display.chunkVersion())
                || !("official_doc".equals(chunk.target()) || "internal_doc".equals(chunk.target()))) return java.util.Optional.empty();
        try {
            var row=read(chunk.chunkId(),snapshot);
            if(row==null || row.documentId()!=chunk.documentId() || !Objects.equals(row.pageNo(),chunk.pageNo())
                    || !Objects.equals(row.chunkVersion(),chunk.chunkVersion()) || !Objects.equals(row.chunkText(),chunk.chunkText()))
                return java.util.Optional.empty();
            var child=active.receipt().selectedBody(row,snapshot);
            var context=active.receipt().selectedContext(row,snapshot,display.chunkText());
            return child.flatMap(body->context.map(text->new Projection(copyWithBody(chunk,body),copyWithBody(display,text))));
        } catch(RuntimeException unavailable) {
            return java.util.Optional.empty();
        }
    }

    private RagSourceProofRow read(long id,RecoveryRoutingSnapshot snapshot) {
        if(id<=0) return null;
        var rows=mapper.findSourceProofChunksByIdsRouted(List.of(id),snapshot);
        return rows!=null && rows.size()==1 && rows.get(0).chunkId()==id?rows.get(0):null;
    }

    private static LawSemanticChunkRow copyWithBody(LawSemanticChunkRow c,String body) {
        return new LawSemanticChunkRow(c.chunkId(),c.documentId(),c.target(),c.externalId(),c.title(),c.agencyName(),
            c.categoryName(),c.sourceDate(),c.effectiveStatus(),c.chunkNo(),c.chunkTitle(),body,c.pageNo(),c.sourcePath(),
            c.sourceUrl(),c.sortOrder(),c.contentHash(),c.parentSectionTitle(),c.sectionType(),c.qualityStatus(),
            c.embeddingText(),c.parentKey(),c.chunkVersion());
    }
}
