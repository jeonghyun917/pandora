package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.chunk.*;
import com.kaces.pandora.rag.document.*;
import com.kaces.pandora.rag.importing.RagTextExtractor;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.nio.file.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** No endpoint is exposed until generation-aware routing and promotion are verified. */
@Service
public class RagRecoveryCandidateService {
    public record Preview(long documentId,String sourceHash,String productSha,String baselineHash,
            String manifest,String previewHash,int baselineCount,int candidateCount) { }
    private record Plan(Preview preview,List<RagDocumentChunkRow> baseline,List<RagDocumentChunkRow> candidates) { }
    private final RagDocumentMapper documents;
    private final RagRecoveryMapper recovery;
    private final RagOriginalDocumentStore originals;
    private final TransactionTemplate transaction;
    public RagRecoveryCandidateService(RagDocumentMapper documents,RagRecoveryMapper recovery,
            RagOriginalDocumentStore originals,PlatformTransactionManager transactions) {
        this.documents=documents;this.recovery=recovery;this.originals=originals;
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public Preview preview(long documentId,String productSha) { return plan(documentId,productSha,false).preview(); }
    Preview lockedPreview(long documentId,String productSha) { return plan(documentId,productSha,true).preview(); }
    public RecoveryGeneration create(long documentId,String expectedPreviewHash,String productSha) {
        if(expectedPreviewHash==null || !expectedPreviewHash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Exact preview required");
        return transaction.execute(status->{
            var plan=plan(documentId,productSha,true);var preview=plan.preview();
            if(!expectedPreviewHash.equals(preview.previewHash()))throw new IllegalStateException("Recovery snapshot changed");
            var existing=recovery.findByPreviewHash(documentId,expectedPreviewHash);
            if(existing!=null)return existing;
            var pointer=recovery.findPointer(documentId,true);
            if(pointer==null) {
                String baselineId=UUID.randomUUID().toString();
                recovery.insertGeneration(new RecoveryGeneration(baselineId,documentId,preview.sourceHash(),productSha,
                        preview.baselineHash(),preview.baselineHash(),hash(List.of("baseline",documentId,preview.baselineHash())),"ACTIVE"));
                for(var chunk:plan.baseline())addMember(baselineId,chunk.chunkId());
                recovery.insertPointer(new RecoverySnapshot(documentId,0,baselineId,preview.sourceHash(),preview.baselineHash()));
            } else if(!pointer.sourceHash().equals(preview.sourceHash()) || !pointer.baselineHash().equals(preview.baselineHash()) || pointer.revision()!=0) {
                // Subsequent generations require the routing-aware snapshot reader from Task 4.
                throw new IllegalStateException("Baseline changed or routing-aware snapshot required");
            }
            String id=UUID.randomUUID().toString();
            var generation=new RecoveryGeneration(id,documentId,preview.sourceHash(),productSha,preview.baselineHash(),
                    preview.manifest(),preview.previewHash(),"PENDING");
            recovery.insertGeneration(generation);
            for(var chunk:plan.candidates()) {
                recovery.insertInactiveChunk(chunk);
                long newId=recovery.lastChunkId();
                if(newId<=0)throw new IllegalStateException("Candidate identity unavailable");
                addMember(id,newId);
            }
            return generation;
        });
    }
    private Plan plan(long documentId,String productSha,boolean lock) {
        if(documentId<=0)throw new IllegalArgumentException("Positive document required");
        var document=documents.findSourceProofDocument(documentId,lock);
        if(document==null || !"official_doc".equals(document.documentType()))throw new IllegalStateException("Active official document required");
        var baseline=documents.findActiveChunksForSourceProof(documentId,lock);
        try {
            var source=originals.materialize(document);String sourceHash=sha(source);
            if(!sourceHash.equals(document.fileHash()))throw new IllegalStateException("Source identity changed");
            var extracted=new RagTextExtractor().extract(source);
            var candidates=new RagChunkQualityGate().evaluate(document.title(),new RagChunker().chunkV4(documentId,
                    extracted,document.sourceUrl(),document.title(),sourceHash)).retainedChunks();
            if(!sourceHash.equals(sha(source)))throw new IllegalStateException("Source changed during extraction");
            var candidate=RecoveryCandidatePlan.of(documentId,sourceHash,productSha,baseline,candidates);
            // Bind metadata and index status as well as the immutable chunk payload.
            String baselineHash=hash(List.of(document,candidate.baselineHash(),bounded(recovery.baselineStorageSnapshot(documentId),10001),
                    bounded(recovery.embeddingSnapshot(documentId),100001),bounded(recovery.keywordSnapshot(documentId),10001),
                    bounded(recovery.keywordTermSnapshot(documentId),100001)));
            var preview=new Preview(documentId,sourceHash,productSha,baselineHash,candidate.manifest(),
                    hash(List.of(documentId,sourceHash,productSha,baselineHash,candidate.manifest())),baseline.size(),candidates.size());
            return new Plan(preview,List.copyOf(baseline),candidate.chunks());
        } catch(java.io.IOException unavailable) { throw new IllegalStateException("Original unavailable",unavailable); }
    }
    private void addMember(String generation,long chunkId) {
        if(recovery.insertMembership(generation,chunkId)!=1)throw new IllegalStateException("Candidate membership conflict");
    }
    private static List<Map<String,Object>> bounded(List<Map<String,Object>> rows,int sentinel) {
        if(rows==null || rows.size()>=sentinel)throw new IllegalStateException("Recovery asset snapshot bound exceeded");
        return rows.stream().map(row->(Map<String,Object>)new TreeMap<>(row)).toList();
    }
    private static String hash(Object value) { return LawHashUtils.sha256(new ObjectMapper().writeValueAsString(value)); }
    private static String sha(Path file)throws java.io.IOException {
        try{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
