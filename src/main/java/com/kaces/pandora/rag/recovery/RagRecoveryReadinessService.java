package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.semantic.lexical.SemanticLexicalMapper;
import org.springframework.stereotype.Service;

/** Run before a cached answer can be returned; no stale-success shortcut for active assets. */
@Service
public class RagRecoveryReadinessService {
    private final QdrantClient qdrant;
    private final SemanticLexicalMapper lexical;
    public RagRecoveryReadinessService(QdrantClient qdrant,SemanticLexicalMapper lexical) { this.qdrant=qdrant;this.lexical=lexical; }
    public void verify(RecoveryRoutingSnapshot routing) {
        if(routing.lexicalNamespace()!=null && !lexical.recoveryAvailable(routing.lexicalNamespace(),routing.lexicalRevision()))
            throw new IllegalStateException("Active recovery lexical assets unavailable");
        qdrant.assertRecoveryReady(routing);
    }
}
