package com.kaces.pandora.rag.recovery;

import com.kaces.pandora.semantic.lexical.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Normal full rebuild keeps recovery replacements, never silently returns to legacy READY. */
@Service
public class RagRecoveryCorpusRefreshService {
    private final RagRecoveryMapper recovery;
    private final SemanticLexicalMapper lexical;
    private final RagRecoveryRoutingService routing;
    private final SemanticLexicalIndexService index;
    private final TransactionTemplate transaction;
    private com.kaces.pandora.lawdata.version.SourceVersionCorpusService sources;
    @org.springframework.beans.factory.annotation.Autowired
    public void configureSourceVersions(com.kaces.pandora.lawdata.version.SourceVersionCorpusService sources,
            com.kaces.pandora.lawdata.version.SourceVersionRoutingReader reader) {
        this.sources=Objects.requireNonNull(sources);routing.configureSourceVersionRouting(reader);
    }
    public RagRecoveryCorpusRefreshService(RagRecoveryMapper recovery,SemanticLexicalMapper lexical,
            RagRecoveryRoutingService routing,SemanticLexicalIndexService index,PlatformTransactionManager manager) {
        this.recovery=recovery;this.lexical=lexical;this.routing=routing;this.index=index;
        transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }
    public boolean isRequired() {
        var epoch=recovery.findRoutingEpoch();
        if(epoch==null)throw new IllegalStateException("Routing epoch unavailable");
        return epoch.revision()>0;
    }
    public String currentRevision() { return routing.capture().lexicalRevision(); }
    public SemanticLexicalIndexService.BuildResult rebuild() {
        var before=transaction.execute(status->capture());
        String id=UUID.randomUUID().toString();
        var result=index.rebuildRecovery(id,before.corpus().chunks());
        index.verifyRecovery(id,before.corpus().chunks());
        transaction.executeWithoutResult(status->{
            var now=capture();
            if(!now.equals(before) || recovery.casCorpusEpoch(before.routingRevision(),before.corpusRevision(),id,result.contentFingerprint())!=1)
                throw new IllegalStateException("Corpus changed during rebuild; unpublished namespace preserved");
        });
        return result;
    }
    private Snapshot capture() {
        if(sources!=null)sources.lockSourceEpoch();
        var epoch=recovery.lockRoutingEpoch();
        Long revision=recovery.findCorpusRevision();
        if(epoch==null || epoch.revision()<=0 || revision==null || revision<0)throw new IllegalStateException("Recovery corpus unavailable");
        var captured=routing.captureForRebuildInTransaction();
        var replacements=new TreeMap<Long,List<LexicalChunkDocument>>();
        for(var id:captured.replacedDocumentIds())replacements.put(id,recovery.findGenerationLexicalChunks(captured.documents().get(id).activeGeneration()));
        var corpus=RecoveryCorpus.overlay(epoch.revision(),lexical.findActiveSearchableChunks(),replacements);
        if(sources!=null)corpus=sources.overlay(corpus,captured.sources());
        return new Snapshot(epoch.revision(),revision,captured.cacheIdentity(),corpus);
    }
    private record Snapshot(long routingRevision,long corpusRevision,String routingIdentity,RecoveryCorpus corpus) { }
}
