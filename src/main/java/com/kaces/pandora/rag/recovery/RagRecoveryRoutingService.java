package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.semantic.lexical.SemanticLexicalMapper;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** One repeatable-read DB snapshot; no ThreadLocal and no client-controlled generation. */
@Service
public class RagRecoveryRoutingService {
    private final RagRecoveryMapper recovery;
    private final SemanticLexicalMapper lexical;
    private final TransactionTemplate transaction;
    public RagRecoveryRoutingService(RagRecoveryMapper recovery,SemanticLexicalMapper lexical,PlatformTransactionManager manager) {
        this.recovery=recovery; this.lexical=lexical;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setReadOnly(true);
    }
    public RecoveryRoutingSnapshot capture() {
        try { return transaction.execute(status->{
            var epoch=recovery.findRoutingEpoch();
            if(epoch==null || epoch.revision()<0)throw new IllegalStateException("Routing epoch unavailable");
            var pointers=recovery.listRoutingPointers();
            var baselines=recovery.listActiveBaselines();
            if(pointers==null || baselines==null || pointers.size()>10000 || baselines.size()>10000)throw new IllegalStateException("Routing snapshot bound exceeded");
            String lexicalRevision;
            if(epoch.revision()==0) {
                if(epoch.lexicalGeneration()!=null || epoch.lexicalFingerprint()!=null)throw new IllegalStateException("Initial routing epoch changed");
                lexicalRevision=lexical.findReadyRevision();
            } else {
                if(epoch.lexicalGeneration()==null || !epoch.lexicalGeneration().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
                    throw new IllegalStateException("Routing lexical generation unavailable");
                }
                var state=lexical.findRecoveryState("recovery_"+epoch.lexicalGeneration());
                if(state==null || epoch.lexicalFingerprint()==null || !epoch.lexicalFingerprint().equals(state.contentFingerprint())) {
                    throw new IllegalStateException("Routing lexical assets unavailable");
                }
                lexicalRevision=epoch.lexicalFingerprint();
            }
            var documents=new TreeMap<Long,RecoverySnapshot>();
            var assets=new TreeMap<String,RecoveryAssets>();
            var baselineIds=new TreeSet<>(baselines);
            for(var pointer:pointers) {
                if(documents.put(pointer.documentId(),pointer)!=null)throw new IllegalStateException("Duplicate routing document");
                var generation=recovery.findGeneration(pointer.activeGeneration(),false);
                if(generation==null || generation.documentId()!=pointer.documentId() || !"ACTIVE".equals(generation.state())
                        || !generation.sourceHash().equals(pointer.sourceHash()) || !generation.baselineHash().equals(pointer.baselineHash())) {
                    throw new IllegalStateException("Routing pointer generation mismatch");
                }
                if(!baselineIds.contains(generation.id())) {
                    var receipt=recovery.findAssets(generation.id());
                    if(receipt==null || !generation.manifest().equals(receipt.manifest()) || recovery.verifiedEmbeddingCount(generation.id())!=receipt.chunkCount()) {
                        throw new IllegalStateException("Active recovery receipt unavailable");
                    }
                    assets.put(generation.id(),receipt);
                }
            }
            return new RecoveryRoutingSnapshot(epoch.revision(),documents,assets,baselineIds,lexicalRevision);
        }); } catch(RuntimeException unavailable) {
            throw new IllegalStateException("Recovery routing unavailable",unavailable);
        }
    }
}
