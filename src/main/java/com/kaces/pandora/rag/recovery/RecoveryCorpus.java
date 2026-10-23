package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.semantic.lexical.LexicalChunkDocument;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Complete immutable lexical view for one proposed routing transition. */
public record RecoveryCorpus(long baseRevision,List<LexicalChunkDocument> chunks,String fingerprint) {
    private static final Set<String> RAG_TARGETS=Set.of("official_doc","internal_doc","reference_doc");
    public RecoveryCorpus {
        if(baseRevision<0 || chunks==null || chunks.size()>1_000_000 || fingerprint==null || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Bounded corpus receipt required");
        chunks=List.copyOf(chunks);
    }
    public static RecoveryCorpus overlay(long baseRevision,List<LexicalChunkDocument> baseline,
            Map<Long,List<LexicalChunkDocument>> replacements) {
        if(baseRevision<0 || baseline==null || replacements==null || replacements.size()>10000 || baseline.size()>1_000_000)
            throw new IllegalArgumentException("Bounded corpus required");
        var result=new ArrayList<LexicalChunkDocument>();
        for(var row:baseline) {
            if(row==null)throw new IllegalArgumentException("Null corpus row");
            if(!RAG_TARGETS.contains(row.target()) || !replacements.containsKey(row.documentId()))result.add(row);
        }
        for(var entry:replacements.entrySet()) {
            var rows=entry.getValue();
            if(entry.getKey()==null || entry.getKey()<=0 || rows==null || rows.isEmpty() || rows.size()>10000)
                throw new IllegalArgumentException("Complete replacement required");
            for(var row:rows) {
                if(row==null || row.documentId()!=entry.getKey() || !RAG_TARGETS.contains(row.target()))
                    throw new IllegalArgumentException("Replacement document mismatch");
                result.add(row);
            }
        }
        if(result.size()>1_000_000)throw new IllegalArgumentException("Corpus bound exceeded");
        result.sort(Comparator.comparing(LexicalChunkDocument::target).thenComparingLong(LexicalChunkDocument::chunkId));
        var keys=new HashSet<String>();
        for(var row:result)if(!keys.add(row.target()+":"+row.chunkId()))throw new IllegalArgumentException("Duplicate corpus identity");
        var frozen=List.copyOf(result);
        return new RecoveryCorpus(baseRevision,frozen,LawHashUtils.sha256(new ObjectMapper().writeValueAsString(List.of(baseRevision,frozen))));
    }
}
