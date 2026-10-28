package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.common.text.LawHashUtils;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import com.kaces.pandora.semantic.lexical.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/** Restores the designated preserved baseline without rewriting a shared vector or keyword asset. */
@Service
public class RagRecoveryRollbackService {
    private final RagRecoveryMapper recovery;
    private final RagRecoveryRoutingService routing;
    private final RagRecoveryBaselineService baseline;
    private final SemanticLexicalMapper lexical;
    private final SemanticLexicalIndexService lexicalIndex;
    private final TransactionTemplate transaction;
    private com.kaces.pandora.lawdata.version.SourceVersionCorpusService sources;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureSourceVersions(com.kaces.pandora.lawdata.version.SourceVersionCorpusService sources,
            com.kaces.pandora.lawdata.version.SourceVersionRoutingReader reader) {
        this.sources=Objects.requireNonNull(sources);routing.configureSourceVersionRouting(reader);
    }
    public RagRecoveryRollbackService(RagRecoveryMapper recovery,RagDocumentMapper documents,RagRecoveryRoutingService routing,
            RagRecoveryBaselineService baseline,SemanticLexicalMapper lexical,SemanticLexicalIndexService lexicalIndex,PlatformTransactionManager manager) {
        this.recovery=recovery;this.routing=routing;this.baseline=baseline;this.lexical=lexical;this.lexicalIndex=lexicalIndex;
        transaction=new TransactionTemplate(manager);transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    public RecoverySnapshot rollback(long documentId,String expectedActive,long expectedRevision,String restoreGeneration) {
        if(documentId<=0 || expectedRevision<0 || expectedRevision==Long.MAX_VALUE || !uuid(expectedActive) || !uuid(restoreGeneration)
                || expectedActive.equals(restoreGeneration))throw new IllegalArgumentException("Exact rollback identity required");
        // Reject stale requests before any external inspection or preparation.
        var pointer=recovery.findPointer(documentId,false);
        if(pointer==null || pointer.revision()!=expectedRevision || !pointer.activeGeneration().equals(expectedActive))
            throw new IllegalStateException("Stale rollback request");
        var preserved=baseline.verify(documentId);
        if(!preserved.generationId().equals(restoreGeneration))throw new IllegalStateException("Only the preserved baseline can be restored");
        var initial=transaction.execute(status->{
            var snapshot=capture(documentId,expectedActive,expectedRevision,restoreGeneration,preserved);
            if(recovery.findRollbackByInput(snapshot.intent().inputHash())==null && recovery.insertRollback(snapshot.intent())!=1)
                throw new IllegalStateException("Rollback reservation conflict");
            return snapshot;
        });
        var intent=initial.intent();String namespace="recovery_"+intent.id();
        if(!Set.of("PREPARING","READY").contains(intent.state()))throw new IllegalStateException("Rollback attempt unavailable");
        if(lexical.findRecoveryState(namespace)==null)lexicalIndex.rebuildRecovery(intent.id(),initial.corpus().chunks());
        String fingerprint=lexicalIndex.verifyRecovery(intent.id(),initial.corpus().chunks());
        if(!preserved.equals(baseline.verify(documentId)))throw new IllegalStateException("Baseline changed during rollback preparation");
        transaction.executeWithoutResult(status->{
            var current=capture(documentId,expectedActive,expectedRevision,restoreGeneration,preserved);
            requireSame(intent,current.intent());
            if("READY".equals(current.intent().state())) {
                if(!fingerprint.equals(current.intent().lexicalFingerprint()))throw new IllegalStateException("Rollback lexical receipt conflict");
            } else if(!"PREPARING".equals(current.intent().state()) || recovery.readyRollback(intent.id(),fingerprint)!=1)
                throw new IllegalStateException("Rollback readiness conflict");
        });
        return transaction.execute(status->{
            var current=capture(documentId,expectedActive,expectedRevision,restoreGeneration,preserved);
            requireSame(intent,current.intent());
            if(!"READY".equals(current.intent().state()) || !fingerprint.equals(current.intent().lexicalFingerprint()))
                throw new IllegalStateException("Rollback receipt unavailable");
            var next=new RecoverySnapshot(documentId,expectedRevision+1,restoreGeneration,current.pointer().sourceHash(),current.pointer().baselineHash());
            if(recovery.changeGenerationState(expectedActive,"ACTIVE","RETIRED")!=1
                    || recovery.changeGenerationState(restoreGeneration,"RETIRED","ACTIVE")!=1
                    || recovery.casPointer(current.pointer(),next)!=1
                    || recovery.casEpoch(intent.baseRevision(),intent.id(),fingerprint)!=1
                    || recovery.appliedRollback(intent.id())!=1)throw new IllegalStateException("Rollback compare-and-set failed");
            return next;
        });
    }
    private Snapshot capture(long documentId,String active,long revision,String restore,RecoveryBaselineAssets preserved) {
        if(sources!=null)sources.lockSourceEpoch();
        if(recovery.lockRoutingEpoch()==null)throw new IllegalStateException("Routing epoch unavailable");
        baseline.recheckStored(documentId,preserved);
        var captured=routing.captureInTransaction();
        var pointer=recovery.findPointer(documentId,true);
        var restored=recovery.findGeneration(restore,true);
        if(pointer==null || !pointer.equals(captured.documents().get(documentId)) || pointer.revision()!=revision
                || !pointer.activeGeneration().equals(active) || !restore.equals(recovery.findBaseline(documentId))
                || restored==null || restored.documentId()!=documentId || !"RETIRED".equals(restored.state())
                || !restored.sourceHash().equals(pointer.sourceHash()) || !restored.baselineHash().equals(pointer.baselineHash()))
            throw new IllegalStateException("Rollback snapshot changed");
        var replacements=new TreeMap<Long,List<LexicalChunkDocument>>();
        for(var id:captured.replacedDocumentIds()) {
            String generation=id==documentId?restore:captured.documents().get(id).activeGeneration();
            replacements.put(id,List.copyOf(recovery.findGenerationLexicalChunks(generation)));
        }
        if(!replacements.containsKey(documentId))throw new IllegalStateException("Rollback source is not a replacement");
        var corpus=RecoveryCorpus.overlay(captured.revision(),lexical.findActiveSearchableChunks(),replacements);
        if(sources!=null)corpus=sources.overlay(corpus,captured.sources());
        String identity=LawHashUtils.sha256(new ObjectMapper().writeValueAsString(
            List.of(documentId,active,revision,restore,preserved,captured.cacheIdentity(),corpus.fingerprint())));
        var intent=recovery.findRollbackByInput(identity);
        if(intent==null)intent=new RecoveryRollbackIntent(UUID.randomUUID().toString(),documentId,identity,captured.revision(),revision,
            active,restore,captured.cacheIdentity(),corpus.fingerprint(),null,"PREPARING");
        if(intent.documentId()!=documentId || intent.baseRevision()!=captured.revision() || intent.expectedRevision()!=revision
                || !active.equals(intent.activeGeneration()) || !restore.equals(intent.restoreGeneration())
                || !captured.cacheIdentity().equals(intent.routingIdentity()) || !corpus.fingerprint().equals(intent.corpusFingerprint())
                || !uuid(intent.id()))throw new IllegalStateException("Rollback intent mismatch");
        return new Snapshot(pointer,corpus,intent);
    }
    private static void requireSame(RecoveryRollbackIntent expected,RecoveryRollbackIntent current) {
        if(!expected.id().equals(current.id()) || !expected.inputHash().equals(current.inputHash()))throw new IllegalStateException("Stale rollback preparation");
    }
    private static boolean uuid(String value) { return value!=null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"); }
    private record Snapshot(RecoverySnapshot pointer,RecoveryCorpus corpus,RecoveryRollbackIntent intent) { }
}
