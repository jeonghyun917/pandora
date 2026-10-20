package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.rag.storage.RagOriginalDocumentStore;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Persists only server-computed, original-bound validation evidence. Does not promote. */
@Service
public class RagRecoveryValidationService {
    private final RagDocumentMapper documents;
    private final RagRecoveryMapper recovery;
    private final RagOriginalDocumentStore originals;
    private final RagRecoveryCandidateService candidates;
    private final TransactionTemplate transaction;
    private final String productSha;
    public RagRecoveryValidationService(RagDocumentMapper documents,RagRecoveryMapper recovery,
            RagOriginalDocumentStore originals,PlatformTransactionManager transactions,
            @Value("${pandora.rag.recovery.product-sha:}") String productSha) {
        this.documents=documents;this.recovery=recovery;this.originals=originals;this.productSha=productSha;
        candidates=new RagRecoveryCandidateService(documents,recovery,originals,transactions);
        transaction=new TransactionTemplate(transactions);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }
    public RecoveryValidation validate(String generationId) {
        if(generationId==null || !generationId.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))throw new IllegalArgumentException("Generation UUID required");
        if(productSha==null || !productSha.matches("[0-9a-f]{40}"))throw new IllegalStateException("Verified recovery product SHA not configured");
        return transaction.execute(status->{
            var initial=recovery.findGeneration(generationId,false);
            if(initial==null)throw new IllegalStateException("Recovery generation unavailable");
            // Same lock order as candidate creation: document, then generation and its chunks.
            var document=documents.findSourceProofDocument(initial.documentId(),true);
            var generation=recovery.findGeneration(generationId,true);
            if(document==null || generation==null || generation.documentId()!=initial.documentId()
                    || !Set.of("PENDING","VALIDATED").contains(generation.state()))throw new IllegalStateException("Generation is not validatable");
            RecoveryValidation result;
            try {
                var current=candidates.lockedPreview(generation.documentId(),productSha);
                if(!current.baselineHash().equals(generation.baselineHash()) || !current.sourceHash().equals(generation.sourceHash())) {
                    result=failed(generation,"baselineOrSourceChanged");
                } else {
                    var chunks=recovery.findCandidateChunks(generationId);
                    if(chunks.isEmpty() || chunks.size()>10000)result=failed(generation,"candidateBound");
                    else result=new RagRecoverySourceValidator().validate(originals.materialize(document),generation,productSha,document.title(),document.sourceUrl(),chunks);
                }
            } catch(java.io.IOException | RuntimeException invalid) { result=failed(generation,"originalOrSnapshotUnavailable"); }
            String state=result.passed()?"VALIDATED":"FAILED";
            if(recovery.saveValidation(generation,state,new ObjectMapper().writeValueAsString(result))!=1)throw new IllegalStateException("Validation snapshot changed");
            return result;
        });
    }
    private static RecoveryValidation failed(RecoveryGeneration generation,String reason) {
        return new RecoveryValidation(generation.id(),generation.manifest(),generation.sourceHash(),generation.productSha(),false,List.of(reason));
    }
}
