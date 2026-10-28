package com.kaces.pandora.lawdata.version;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

/** Fences shared/existing point mutations against source registration before external I/O. */
@Service
public class SourceVersionWriteGuard {
    private final JdbcTemplate db;
    private final TransactionTemplate transaction;
    public SourceVersionWriteGuard(JdbcTemplate db,PlatformTransactionManager manager) {
        this.db=db;transaction=new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);transaction.setTimeout(10);
    }
    public void mutatePoints(List<Long> pointIds,Runnable mutation) {
        Objects.requireNonNull(mutation);
        if(pointIds==null || pointIds.size()>10000 || pointIds.stream().anyMatch(id->id==null || id<=0))
            throw new IllegalArgumentException("Bounded raw law point identities required");
        var ids=new TreeSet<>(pointIds);
        if(ids.isEmpty()) {mutation.run();return;}
        try {transaction.executeWithoutResult(status->{
            // Global source-registration mutex; serving read leases use a separate epoch.
            // Per-group ordered locks can replace this only together with stage's lock protocol.
            db.queryForObject("SELECT revision FROM law_source_version_epoch WHERE singleton_id=1 FOR UPDATE",Long.class);
            String placeholders=String.join(",",Collections.nCopies(ids.size(),"?"));
            var managed=db.queryForList("SELECT g.group_key FROM law_api_document_chunks c JOIN law_api_documents d ON d.document_id=c.document_id JOIN law_source_version_groups g ON g.target=d.target AND (g.canonical_key=d.canonical_key OR g.canonical_key="
                +SourceVersionSchemaMaintenance.canonicalSql("d")+") WHERE c.chunk_id IN ("+placeholders+") LIMIT 1 FOR UPDATE",String.class,ids.toArray());
            if(!managed.isEmpty())throw new IllegalStateException("Managed source vectors must be preserved");
            mutation.run();
        });} catch(org.springframework.dao.DataAccessException unavailable) {
            throw new IllegalStateException("Source vector write coordination unavailable",unavailable);
        }
    }
}
