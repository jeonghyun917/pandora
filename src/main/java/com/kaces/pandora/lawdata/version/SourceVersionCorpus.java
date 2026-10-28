package com.kaces.pandora.lawdata.version;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.recovery.RecoveryCorpus;
import com.kaces.pandora.semantic.lexical.LexicalChunkDocument;
import java.util.*;

/** Law-only overlay applied after the existing RAG overlay; its target guards stay unchanged. */
public final class SourceVersionCorpus {
    private SourceVersionCorpus() { }
    public record Replacement(String target,String canonicalKey,Set<Long> preservedDocumentIds,long activeDocumentId,List<LexicalChunkDocument> chunks) {
        public Replacement {
            if(target==null || !Set.of("law","admrul").contains(target) || canonicalKey==null || canonicalKey.isBlank()
                    || preservedDocumentIds==null || preservedDocumentIds.isEmpty() || preservedDocumentIds.size()>10000
                    || preservedDocumentIds.stream().anyMatch(id->id==null || id<=0) || activeDocumentId<=0
                    || preservedDocumentIds.contains(activeDocumentId) || chunks==null || chunks.isEmpty() || chunks.size()>10000)
                throw new IllegalArgumentException("Complete registered source replacement required");
            preservedDocumentIds=Set.copyOf(preservedDocumentIds);chunks=List.copyOf(chunks);
            for(var row:chunks)if(row.documentId()!=activeDocumentId || !target.equals(row.target())
                    || !canonicalKey.equals(LawVersionUtils.canonicalKey(target,row.documentTitle()))
                    || row.contentHash()==null || !row.contentHash().matches("[0-9a-f]{64}") || row.body().isBlank())
                throw new IllegalArgumentException("Source replacement scope or content mismatch");
        }
    }
    public static RecoveryCorpus overlay(long revision,List<LexicalChunkDocument> baseline,List<Replacement> replacements) {
        if(revision<0 || baseline==null || baseline.size()>1_000_000 || replacements==null || replacements.size()>10000)
            throw new IllegalArgumentException("Bounded source corpus required");
        var groups=new HashSet<String>();var old=new HashMap<Long,Replacement>();var active=new HashSet<Long>();
        for(var replacement:replacements) {
            if(replacement==null || !groups.add(replacement.target()+":"+replacement.canonicalKey()) || !active.add(replacement.activeDocumentId()))
                throw new IllegalArgumentException("Ambiguous source corpus replacement");
            for(long id:replacement.preservedDocumentIds())if(old.put(id,replacement)!=null)throw new IllegalArgumentException("Overlapping source history");
        }
        if(active.stream().anyMatch(old::containsKey))throw new IllegalArgumentException("Active source is also retired");
        var rows=new ArrayList<LexicalChunkDocument>();
        for(var row:baseline) {
            if(row==null)throw new IllegalArgumentException("Null source corpus row");
            var replacement=old.get(row.documentId());
            if(replacement!=null && Set.of("law","admrul").contains(row.target())) {
                if(!replacement.target().equals(row.target()) || !replacement.canonicalKey().equals(LawVersionUtils.canonicalKey(row.target(),row.documentTitle())))
                    throw new IllegalArgumentException("Registered source history scope mismatch");
            } else rows.add(row);
        }
        for(var replacement:replacements)rows.addAll(replacement.chunks());
        if(rows.size()>1_000_000)throw new IllegalArgumentException("Source corpus bound exceeded");
        rows.sort(Comparator.comparing(LexicalChunkDocument::target).thenComparingLong(LexicalChunkDocument::chunkId));
        var identities=new HashSet<String>();
        for(var row:rows)if(!identities.add(row.target()+":"+row.chunkId()))throw new IllegalArgumentException("Duplicate source corpus chunk");
        return new RecoveryCorpus(revision,rows,LawHashUtils.sha256Json(List.of(revision,rows)));
    }
}
