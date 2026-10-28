package com.kaces.pandora.lawdata.version;

import java.sql.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;

/** Request-lifetime shared DB locks. Production must supply a separate bounded connection pool. */
public final class SourceVersionReadLeases {
    private final DataSource pool;
    private final Semaphore admission;
    private final long waitMillis;
    public SourceVersionReadLeases(DataSource dedicatedLeasePool,int capacity,Duration wait) {
        if(dedicatedLeasePool==null || capacity<1 || capacity>64 || wait==null || wait.isNegative() || wait.isZero() || wait.compareTo(Duration.ofSeconds(30))>0)
            throw new IllegalArgumentException("Bounded dedicated lease pool required");
        pool=dedicatedLeasePool;admission=new Semaphore(capacity,true);waitMillis=Math.max(1,wait.toMillis());
    }
    public Lease acquire() {
        boolean acquired=false,transferred=false;Connection connection=null;
        try {
            acquired=admission.tryAcquire(waitMillis,TimeUnit.MILLISECONDS);
            if(!acquired)throw new IllegalStateException("Source serving capacity unavailable");
            connection=pool.getConnection();
            connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            connection.setReadOnly(true);connection.setAutoCommit(false);
            long revision;
            try(var statement=connection.prepareStatement("SELECT revision FROM law_source_version_visibility_epoch WHERE singleton_id=1 LOCK IN SHARE MODE")) {
                statement.setQueryTimeout((int)Math.max(1,(waitMillis+999)/1000));
                try(var result=statement.executeQuery()) {
                    if(!result.next())throw new IllegalStateException("Source visibility epoch unavailable");
                    revision=result.getLong(1);
                    if(result.wasNull() || revision<0 || result.next())throw new IllegalStateException("Source visibility epoch invalid");
                }
            }
            // Result/statement cleanup may throw; transfer only after both close successfully.
            var lease=new Lease(connection,revision,admission);transferred=true;return lease;
        } catch(InterruptedException interrupted) {
            Thread.currentThread().interrupt();throw new IllegalStateException("Source serving interrupted",interrupted);
        } catch(SQLException failed) {
            throw new IllegalStateException("Source serving lock unavailable",failed);
        } finally {
            // Ownership is transferred only after a successful SELECT and lease construction.
            if(!transferred) {
                if(connection!=null)closeFailed(connection);
                if(acquired)admission.release();
            }
        }
    }
    private void closeFailed(Connection connection) {
        try {connection.rollback();}catch(SQLException ignored) { }
        try {connection.close();}catch(SQLException ignored) { }
    }
    public static final class Lease implements AutoCloseable {
        private final Connection connection;
        private final long revision;
        private final Semaphore admission;
        private final AtomicBoolean closed=new AtomicBoolean();
        private Lease(Connection connection,long revision,Semaphore admission) {this.connection=connection;this.revision=revision;this.admission=admission;}
        public long revision() {return revision;}
        @Override public void close() {
            if(!closed.compareAndSet(false,true))return;
            SQLException failure=null;
            try {connection.rollback();}catch(SQLException error) {failure=error;}
            try {connection.close();}catch(SQLException error) {if(failure==null)failure=error;else failure.addSuppressed(error);}
            finally {admission.release();}
            if(failure!=null)throw new IllegalStateException("Source serving lease cleanup failed",failure);
        }
    }
}
