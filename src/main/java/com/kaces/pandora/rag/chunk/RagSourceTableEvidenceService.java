package com.kaces.pandora.rag.chunk;

import java.util.List;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import org.springframework.stereotype.Service;

/** Source verification side channel; does not add grounds or authorize claims. */
@Service
public class RagSourceTableEvidenceService {
    private final RagDocumentMapper mapper;
    private final RagOriginalDocumentStore originals;
    public RagSourceTableEvidenceService(RagDocumentMapper mapper,RagOriginalDocumentStore originals) {
        this.mapper=mapper;this.originals=originals;
    }
    public List<SourceTableBindings.Binding> fromSelectedChunks(List<Long> ids) {
        if(ids==null || ids.isEmpty() || ids.size()>64 || ids.stream().anyMatch(id->id==null || id<=0)) { return List.of(); }
        try {
            var rows=mapper.findSourceProofChunksByIds(ids);
            if(rows==null || rows.size()>64 || rows.stream().anyMatch(r->r==null || !ids.contains(r.chunkId()))) { return List.of(); }
            var byDocument=rows.stream().filter(r->r.sourceProvenance()!=null)
                .collect(java.util.stream.Collectors.groupingBy(com.kaces.pandora.rag.document.RagSourceProofRow::documentId));
            var result=new java.util.ArrayList<SourceTableBindings.Binding>();
            for(var group:byDocument.entrySet()) {
                try {
                    var document=mapper.findDocumentById(group.getKey());
                    if(document==null || document.documentId()!=group.getKey() || document.fileHash()==null
                        || group.getValue().stream().anyMatch(r->!document.fileHash().equalsIgnoreCase(r.sourceFileHash()))) { continue; }
                    var original=originals.materialize(document);
                    result.addAll(new SourceTableBindings().validate(original,group.getValue().stream().map(r->r.validationInput()).toList()));
                } catch(java.io.IOException | RuntimeException unavailable) { /* This document supplies no new proof. */ }
            }
            return List.copyOf(result);
        } catch(RuntimeException unavailable) { return List.of(); }
    }
}
