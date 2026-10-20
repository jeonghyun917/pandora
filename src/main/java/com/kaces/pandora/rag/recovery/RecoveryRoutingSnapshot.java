package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Captured once by the server and passed explicitly to every retrieval path. */
public record RecoveryRoutingSnapshot(long revision, Map<Long,RecoverySnapshot> documents,
        Map<String,RecoveryAssets> assets, Set<String> baselineGenerations, String lexicalRevision) {
    public RecoveryRoutingSnapshot {
        documents=Map.copyOf(documents); assets=Map.copyOf(assets); baselineGenerations=Set.copyOf(baselineGenerations);
        if(revision<0 || documents.size()>10000 || (lexicalRevision!=null && !lexicalRevision.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Bounded routing revision required");
        }
        var active=new HashSet<String>();
        for(var entry:documents.entrySet()) {
            var pointer=entry.getValue();
            if(entry.getKey()<=0 || entry.getKey()!=pointer.documentId() || pointer.revision()<0 || pointer.revision()>revision
                    || pointer.activeGeneration()==null || !pointer.activeGeneration().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || pointer.sourceHash()==null || !pointer.sourceHash().matches("[0-9a-f]{64}")
                    || pointer.baselineHash()==null || !pointer.baselineHash().matches("[0-9a-f]{64}") || !active.add(pointer.activeGeneration())) {
                throw new IllegalStateException("Invalid recovery pointer snapshot");
            }
            var receipt=assets.get(pointer.activeGeneration());
            if(baselineGenerations.contains(pointer.activeGeneration())) {
                if(receipt!=null)throw new IllegalStateException("Ambiguous baseline assets");
            } else if(receipt==null || pointer.revision()==0 || !pointer.activeGeneration().equals(receipt.generationId())
                    || receipt.vectorCollection()==null || !receipt.vectorCollection().equals("rag_recovery_"+receipt.generationId().replace("-",""))
                    || receipt.manifest()==null || !receipt.manifest().matches("[0-9a-f]{64}")
                    || receipt.vectorFingerprint()==null || !receipt.vectorFingerprint().matches("[0-9a-f]{64}")
                    || receipt.lexicalFingerprint()==null || !receipt.lexicalFingerprint().matches("[0-9a-f]{64}")
                    || receipt.chunkCount()<=0 || receipt.chunkCount()>10000 || lexicalRevision==null) {
                throw new IllegalStateException("Active recovery assets unavailable");
            }
        }
        if(!active.containsAll(assets.keySet()) || !active.containsAll(baselineGenerations))throw new IllegalStateException("Unbound routing assets");
    }
    public Set<Long> replacedDocumentIds() {
        var ids=new TreeSet<Long>();
        documents.forEach((id,pointer)->{if(!baselineGenerations.contains(pointer.activeGeneration()))ids.add(id);});
        return Collections.unmodifiableSet(ids);
    }
    public String cacheIdentity() {
        return LawHashUtils.sha256(new ObjectMapper().writeValueAsString(Arrays.asList(revision,new TreeMap<>(documents),
            new TreeMap<>(assets),new TreeSet<>(baselineGenerations),lexicalRevision)));
    }
}
