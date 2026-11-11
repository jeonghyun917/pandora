package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;
import tools.jackson.databind.ObjectMapper;

/** Captured once by the server and passed explicitly to every retrieval path. */
public record RecoveryRoutingSnapshot(long revision, Map<Long,RecoverySnapshot> documents,
        Map<String,RecoveryAssets> assets, Set<String> baselineGenerations, String lexicalRevision, String lexicalNamespace,
        com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot sources,
        Map<String,RecoveryPdfSource> pdfSources) {
    public RecoveryRoutingSnapshot(long revision,Map<Long,RecoverySnapshot> documents,Map<String,RecoveryAssets> assets,
            Set<String> baselineGenerations,String lexicalRevision,String lexicalNamespace,
            com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot sources) {
        this(revision,documents,assets,baselineGenerations,lexicalRevision,lexicalNamespace,sources,Map.of());
    }
    public RecoveryRoutingSnapshot(long revision,Map<Long,RecoverySnapshot> documents,Map<String,RecoveryAssets> assets,
            Set<String> baselineGenerations,String lexicalRevision,String lexicalNamespace) {
        this(revision,documents,assets,baselineGenerations,lexicalRevision,lexicalNamespace,
            com.kaces.pandora.lawdata.version.SourceVersionRoutingSnapshot.empty());
    }
    public RecoveryRoutingSnapshot {
        documents=Map.copyOf(documents); assets=Map.copyOf(assets); baselineGenerations=Set.copyOf(baselineGenerations);
        pdfSources=Map.copyOf(pdfSources);
        if(revision<0 || documents.size()>10000 || (lexicalRevision!=null && !lexicalRevision.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("Bounded routing revision required");
        }
        if(lexicalNamespace!=null && (lexicalRevision==null || !lexicalNamespace.matches("recovery_[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) {
            throw new IllegalArgumentException("Bound lexical namespace required");
        }
        if(sources==null || sources.revision()>revision || (sources.revision()>0 && (lexicalRevision==null || lexicalNamespace==null)))
            throw new IllegalStateException("Activated source routing requires published lexical assets");
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
                    || receipt.chunkCount()<=0 || receipt.chunkCount()>10000 || lexicalRevision==null || lexicalNamespace==null) {
                throw new IllegalStateException("Active recovery assets unavailable");
            }
        }
        if(!active.containsAll(assets.keySet()) || !active.containsAll(baselineGenerations))throw new IllegalStateException("Unbound routing assets");
        for(var entry:pdfSources.entrySet()) {
            var source=entry.getValue();
            var pointer=documents.get(source.documentId());
            var receipt=assets.get(source.generationId());
            if(!entry.getKey().equals(source.generationId()) || pointer==null
                    || baselineGenerations.contains(source.generationId())
                    || !pointer.activeGeneration().equals(source.generationId())
                    || !pointer.sourceHash().equals(source.sourceHash()) || receipt==null
                    || !receipt.manifest().equals(source.manifest()))
                throw new IllegalStateException("Replacement source is not bound to active captured assets");
        }
    }
    public Set<Long> replacedDocumentIds() {
        var ids=new TreeSet<Long>();
        documents.forEach((id,pointer)->{if(!baselineGenerations.contains(pointer.activeGeneration()))ids.add(id);});
        return Collections.unmodifiableSet(ids);
    }
    public String cacheIdentity() {
        var identity=new ArrayList<Object>(Arrays.asList(revision,new TreeMap<>(documents),new TreeMap<>(assets),
            new TreeSet<>(baselineGenerations),lexicalRevision,lexicalNamespace));
        if(sources.revision()>0)identity.add(sources.cacheIdentity());
        if(!pdfSources.isEmpty())identity.add(new TreeMap<>(pdfSources));
        return LawHashUtils.sha256(new ObjectMapper().writeValueAsString(identity));
    }
}
