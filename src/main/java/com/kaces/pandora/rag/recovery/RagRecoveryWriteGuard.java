package com.kaces.pandora.rag.recovery;

import java.util.*;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.*;

/** Serializes legacy mutation with recovery creation using document -> pointer -> chunk lock order. */
@Service
public class RagRecoveryWriteGuard {
    private final RagRecoveryMapper mapper;
    private final TransactionTemplate transaction;
    public RagRecoveryWriteGuard(RagRecoveryMapper mapper,PlatformTransactionManager manager) {
        this.mapper=mapper;transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }
    public void assertMutableDocument(long documentId) {
        requireTransaction();
        if(documentId<=0 || !Objects.equals(documentId,mapper.lockDocument(documentId)))
            throw new IllegalStateException("Document mutation identity unavailable");
        // A locking/current read is mandatory even when the outer transaction already has an older snapshot.
        if(mapper.findPointer(documentId,true)!=null)throw new IllegalStateException("Managed recovery document is immutable");
    }
    public void assertMutableHash(String hash) {
        requireTransaction();
        if(hash==null || !hash.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Source hash required");
        var id=mapper.lockDocumentByHash(hash);
        if(id!=null)assertMutableDocument(id);
    }
    public void assertMutablePaths(List<String> paths) {
        requireTransaction();
        if(paths==null || paths.size()>10000 || paths.stream().anyMatch(p->p==null || p.isBlank() || p.length()>4096))
            throw new IllegalArgumentException("Bounded document paths required");
        if(paths.isEmpty())return;
        var ids=mapper.lockDocumentsByPaths(paths.stream().distinct().sorted().toList());
        if(ids==null || ids.size()>10000)throw new IllegalStateException("Document mutation bound exceeded");
        for(var id:new TreeSet<>(ids))assertMutableDocument(id);
    }
    public <T> T mutateDocuments(List<Long> documentIds,Supplier<T> mutation) {
        Objects.requireNonNull(mutation);
        if(documentIds==null || documentIds.size()>10000 || documentIds.stream().anyMatch(id->id==null || id<=0))
            throw new IllegalArgumentException("Bounded document identities required");
        var ids=new TreeSet<>(documentIds);
        return transaction.execute(status->{
            for(var id:ids)assertMutableDocument(id);
            return mutation.get();
        });
    }
    public void mutateChunk(long chunkId,Runnable mutation) {
        if(chunkId<=0)throw new IllegalArgumentException("Positive chunk required");
        Objects.requireNonNull(mutation);
        transaction.executeWithoutResult(status->{
            var documentId=mapper.findChunkDocument(chunkId);
            if(documentId==null)throw new IllegalStateException("Chunk mutation identity unavailable");
            assertMutableDocument(documentId);
            if(!documentId.equals(mapper.lockChunkDocument(chunkId)))throw new IllegalStateException("Chunk mutation identity changed");
            mutation.run();
        });
    }
    private void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Document mutation requires a transaction");
    }
}
