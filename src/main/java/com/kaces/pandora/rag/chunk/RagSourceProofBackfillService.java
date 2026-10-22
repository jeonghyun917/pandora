package com.kaces.pandora.rag.chunk;

import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import com.kaces.pandora.rag.document.*;
import java.util.*;
import java.nio.file.*;
import com.kaces.pandora.common.text.LawHashUtils;

@Service
public class RagSourceProofBackfillService {
    public record Preview(long documentId, String status, String token, int eligibleChunks,
        int storedChunkCount, int regeneratedChunkCount, String mismatchReason) { }
    public record Applied(long documentId, int updatedChunks) { }
    private record Plan(Preview preview,List<RagDocumentChunkRow> stored,List<RagDocumentChunkRow> generated,String fileHash) { }
    private final RagDocumentMapper mapper;
    private final RagOriginalDocumentStore originals;
    private final RagTextExtractor extractor;
    private final RagChunker chunker;
    private final RagChunkQualityGate quality;
    private final TransactionTemplate transaction;
    private com.kaces.pandora.rag.recovery.RagRecoveryWriteGuard recoveryWriteGuard;
    @org.springframework.beans.factory.annotation.Autowired
    void configureRecoveryWriteGuard(com.kaces.pandora.rag.recovery.RagRecoveryWriteGuard guard) {
        recoveryWriteGuard=Objects.requireNonNull(guard);
    }
    public RagSourceProofBackfillService(RagDocumentMapper mapper, RagOriginalDocumentStore originals,
        RagTextExtractor extractor, RagChunker chunker, RagChunkQualityGate quality,
        PlatformTransactionManager transactions) {
        this.mapper=mapper;this.originals=originals;this.extractor=extractor;this.chunker=chunker;this.quality=quality;
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Preview preview(Long documentId, String fileHash) {
        if(documentId==null) {
            if(fileHash==null || !fileHash.matches("[0-9a-fA-F]{64}")) throw new IllegalArgumentException("documentId or SHA-256 required");
            var found=mapper.findDocumentByHash(fileHash.toLowerCase(Locale.ROOT));
            if(found==null) return rejected(0,0,0,"document").preview();
            documentId=found.documentId();
        }
        if(documentId<=0) throw new IllegalArgumentException("Positive documentId required");
        return plan(documentId,false).preview();
    }
    public Applied apply(long documentId, String token) {
        if(documentId<=0 || token==null || !token.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Valid plan token required");
        return transaction.execute(status->{
            if(recoveryWriteGuard!=null)recoveryWriteGuard.assertMutableDocument(documentId);
            Plan plan=plan(documentId,true);
            if(plan.preview().token()==null || !token.equals(plan.preview().token())) throw new IllegalStateException("Source proof plan changed or unavailable");
            int updated=0;
            for(int i=0;i<plan.stored().size();i++) {
                var expected=plan.stored().get(i);String proof=plan.generated().get(i).sourceProvenance();
                if(expected.sourceProvenance()==null && proof!=null) {
                    if(mapper.backfillSourceProofIfUnchanged(expected,plan.fileHash(),proof)!=1) throw new IllegalStateException("Source proof compare-and-set conflict");
                    updated++;
                }
            }
            return new Applied(documentId,updated);
        });
    }
    private Plan plan(long id,boolean lock) {
        int storedCount=0,generatedCount=0;
        try {
            var document=mapper.findSourceProofDocument(id,lock);
            if(document==null || document.documentId()!=id || document.fileHash()==null || !document.fileHash().matches("[0-9a-fA-F]{64}")) return rejected(id,0,0,"document");
            var rows=mapper.findActiveChunksForSourceProof(id,lock);
            if(rows==null) return rejected(id,0,0,"chunks");
            storedCount=rows.size();
            if(rows.isEmpty() || rows.size()>10000) return rejected(id,storedCount,0,"chunkCount");
            if(rows.stream().anyMatch(c->c==null || c.documentId()!=id || c.chunkId()<=0 || c.chunkVersion()!=4)) return rejected(id,storedCount,0,"documentId/chunkVersion");
            if(rows.stream().map(RagDocumentChunkRow::chunkId).distinct().count()!=rows.size()
                || rows.stream().map(RagDocumentChunkRow::sortOrder).distinct().count()!=rows.size()) return rejected(id,storedCount,0,"ambiguousIdentity");
            var original=originals.materialize(document);
            String hash=sha(original);
            if(!hash.equalsIgnoreCase(document.fileHash())) return rejected(id,storedCount,0,"sourceFileHash");
            var extracted=extractor.extract(original);
            if(!hash.equals(sha(original))) return rejected(id,storedCount,0,"sourceFileHash");
            var generated=quality.evaluate(document.title(),chunker.chunkV4(id,extracted,document.sourceUrl(),document.title(),hash)).retainedChunks();
            generatedCount=generated.size();
            if(generated.size()!=rows.size()) return rejected(id,storedCount,generatedCount,"chunkCount");
            var sorted=rows.stream().sorted(Comparator.comparingInt(RagDocumentChunkRow::sortOrder)).toList();
            generated=generated.stream().sorted(Comparator.comparingInt(RagDocumentChunkRow::sortOrder)).toList();
            int eligible=0;
            for(int i=0;i<sorted.size();i++) {
                var old=sorted.get(i);var fresh=generated.get(i);
                String mismatch=mismatch(old,fresh);
                if(mismatch!=null) return rejected(id,storedCount,generatedCount,mismatch);
                if(fresh.sourceProvenance()!=null && TableSourceSegments.duplicateIdentity(fresh)==null) return rejected(id,storedCount,generatedCount,"sourceProvenance");
                if(old.sourceProvenance()!=null && !old.sourceProvenance().equals(fresh.sourceProvenance())) return rejected(id,storedCount,generatedCount,"sourceProvenance");
                if(old.sourceProvenance()==null && fresh.sourceProvenance()!=null) eligible++;
            }
            var proofInputs=generated.stream().filter(c->c.sourceProvenance()!=null)
                .map(c->new SourceTableBindings.Input(id,c.chunkVersion(),c.pageNo(),c.chunkText(),hash,c.sourceProvenance())).toList();
            if(proofInputs.size()>64) return rejected(id,storedCount,generatedCount,"sourceProofBound");
            if(!proofInputs.isEmpty() && new SourceTableBindings().validate(original,proofInputs).isEmpty()) {
                return rejected(id,storedCount,generatedCount,"sourceProvenance");
            }
            if(!hash.equals(sha(original))) return rejected(id,storedCount,generatedCount,"sourceFileHash");
            String snapshot=new tools.jackson.databind.ObjectMapper().writeValueAsString(List.of(document,sorted,generated));
            return new Plan(new Preview(id,eligible>0?"ELIGIBLE":"NO_CHANGE",LawHashUtils.sha256(snapshot),eligible,storedCount,generatedCount,null),sorted,generated,hash);
        } catch(java.io.IOException | RuntimeException unavailable) {
            return rejected(id,storedCount,generatedCount,"sourceUnavailable");
        }
    }
    private static String mismatch(RagDocumentChunkRow a,RagDocumentChunkRow b) {
        for(var component:RagDocumentChunkRow.class.getRecordComponents()) {
            if(component.getName().equals("chunkId") || component.getName().equals("sourceProvenance")) continue;
            try { if(!Objects.equals(component.getAccessor().invoke(a),component.getAccessor().invoke(b))) return component.getName(); }
            catch(ReflectiveOperationException impossible) { throw new IllegalStateException(impossible); }
        }
        return null;
    }
    private static String sha(Path file) throws java.io.IOException {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Plan rejected(long id,int stored,int generated,String reason) {
        return new Plan(new Preview(id,"BLOCKED",null,0,stored,generated,reason),List.of(),List.of(),null);
    }
}
