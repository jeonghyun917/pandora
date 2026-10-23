package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.document.RagDocumentChunkRow;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Immutable, content-addressed input to inactive candidate persistence. */
public record RecoveryCandidatePlan(long documentId,String sourceHash,String productSha,
        String baselineHash,String manifest,String previewHash,List<RagDocumentChunkRow> chunks) {
    public RecoveryCandidatePlan { chunks=List.copyOf(chunks); }

    public static RecoveryCandidatePlan of(long documentId,String sourceHash,String productSha,
            List<RagDocumentChunkRow> baseline,List<RagDocumentChunkRow> candidates) {
        if(documentId<=0 || sourceHash==null || !sourceHash.matches("[0-9a-f]{64}")
                || productSha==null || !productSha.matches("[0-9a-f]{40}")) {
            throw new IllegalArgumentException("Exact document/source/product identity required");
        }
        var old=canonical(documentId,baseline,true);
        var fresh=canonical(documentId,candidates,false);
        String baselineHash=hash(old);
        String manifest=manifestFor(documentId,sourceHash,productSha,fresh);
        return new RecoveryCandidatePlan(documentId,sourceHash,productSha,baselineHash,manifest,
                hash(List.of(documentId,sourceHash,productSha,baselineHash,manifest)),fresh);
    }
    static String manifestFor(long documentId,String sourceHash,String productSha,List<RagDocumentChunkRow> chunks) {
        return hash(List.of(documentId,sourceHash,productSha,canonical(documentId,chunks,false)));
    }
    static String baselineHashFor(long documentId,List<RagDocumentChunkRow> chunks) {
        return hash(canonical(documentId,chunks,true));
    }

    private static List<RagDocumentChunkRow> canonical(long documentId,List<RagDocumentChunkRow> rows,boolean stored) {
        if(rows==null || rows.isEmpty() || rows.size()>10000)throw new IllegalArgumentException("Bounded nonempty chunks required");
        var ids=new HashSet<Long>();var orders=new HashSet<Integer>();
        for(var row:rows) {
            if(row==null || row.documentId()!=documentId || row.chunkVersion()!=4
                    || (stored ? row.chunkId()<=0 || !ids.add(row.chunkId()) : row.chunkId()!=0)
                    || row.sortOrder()<0 || !orders.add(row.sortOrder())
                    || row.chunkText()==null || row.chunkText().isBlank()
                    || row.contentHash()==null || !row.contentHash().matches("[0-9a-f]{64}")
                    || !Set.of("PASS","REVIEW","CONTEXT_ONLY").contains(row.qualityStatus())) {
                throw new IllegalArgumentException("Invalid chunk identity/content/quality");
            }
        }
        return rows.stream().sorted(Comparator.comparingInt(RagDocumentChunkRow::sortOrder)).toList();
    }
    private static String hash(Object value) {
        return LawHashUtils.sha256(new ObjectMapper().writeValueAsString(value));
    }
}
