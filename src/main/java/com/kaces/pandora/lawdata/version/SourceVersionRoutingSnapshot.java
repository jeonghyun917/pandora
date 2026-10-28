package com.kaces.pandora.lawdata.version;

import com.kaces.pandora.common.text.LawHashUtils;
import java.util.*;

/** Activated source versions only; staged candidates cannot invalidate serving/cache identity. */
public record SourceVersionRoutingSnapshot(long revision,Map<String,Route> routes) {
    public record Route(String groupKey,long revision,String target,String canonicalKey,long baselineDocumentId,long activeDocumentId,
            Set<Long> historyDocumentIds,String candidateId,String manifest,String vectorCollection,String vectorFingerprint,
            String contentSnapshotHash,String model,int chunkCount,int chunkVersion) {
        public Route {
            if(!hash(groupKey) || revision<1 || target==null || !Set.of("law","admrul").contains(target)
                    || canonicalKey==null || !canonicalKey.startsWith(target+":") || canonicalKey.length()>600
                    || baselineDocumentId<=0 || activeDocumentId<=0 || historyDocumentIds==null || historyDocumentIds.size()>10000
                    || historyDocumentIds.stream().anyMatch(id->id==null || id<=0) || !historyDocumentIds.contains(baselineDocumentId)
                    || !historyDocumentIds.contains(activeDocumentId) || !hash(vectorFingerprint) || !hash(contentSnapshotHash)
                    || model==null || model.isBlank() || model.length()>200 || chunkCount<1 || chunkCount>10000)
                throw new IllegalStateException("Activated source routing identity invalid");
            historyDocumentIds=Set.copyOf(historyDocumentIds);
            if(candidateId==null) {
                if(activeDocumentId!=baselineDocumentId || manifest!=null || vectorCollection!=null || chunkVersion!=0)
                    throw new IllegalStateException("Only preserved baseline may use shared source vectors");
            } else if(!candidateId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                    || activeDocumentId==baselineDocumentId || !hash(manifest) || chunkVersion<1
                    || !("law_source_"+candidateId.replace("-","")).equals(vectorCollection))
                throw new IllegalStateException("Activated source vector receipt invalid");
        }
    }
    public SourceVersionRoutingSnapshot {
        if(revision<0 || routes==null || routes.size()>64 || (revision==0)!=routes.isEmpty())
            throw new IllegalStateException("Bounded activated source routing required");
        routes=Map.copyOf(routes);
        var documents=new HashSet<Long>();var candidates=new HashSet<String>();
        for(var entry:routes.entrySet()) {
            var route=entry.getValue();
            if(!entry.getKey().equals(route.groupKey()) || route.revision()>revision)
                throw new IllegalStateException("Unbound source route");
            for(long document:route.historyDocumentIds())if(!documents.add(document))throw new IllegalStateException("Overlapping source version groups");
            if(route.candidateId()!=null && !candidates.add(route.candidateId()))throw new IllegalStateException("Duplicate source candidate route");
        }
    }
    public static SourceVersionRoutingSnapshot empty() {return new SourceVersionRoutingSnapshot(0,Map.of());}
    public Set<Long> excludedDocumentIds() {
        var excluded=new TreeSet<Long>();
        for(var route:routes.values())for(long document:route.historyDocumentIds())
            if(route.candidateId()!=null || document!=route.activeDocumentId())excluded.add(document);
        return Collections.unmodifiableSet(excluded);
    }
    public Map<String,Route> activeVectors() {
        var active=new TreeMap<String,Route>();
        for(var route:routes.values())if(route.candidateId()!=null)active.put(route.candidateId(),route);
        return Collections.unmodifiableMap(active);
    }
    public String cacheIdentity() {
        var canonical=new TreeMap<String,Object>();
        routes.forEach((key,route)->canonical.put(key,Arrays.asList(route.groupKey(),route.revision(),route.target(),route.canonicalKey(),
            route.baselineDocumentId(),route.activeDocumentId(),new TreeSet<>(route.historyDocumentIds()),route.candidateId(),route.manifest(),
            route.vectorCollection(),route.vectorFingerprint(),route.contentSnapshotHash(),route.model(),route.chunkCount(),route.chunkVersion())));
        return LawHashUtils.sha256Json(List.of(revision,canonical));
    }
    private static boolean hash(String value) {return value!=null && value.matches("[0-9a-f]{64}");}
}
