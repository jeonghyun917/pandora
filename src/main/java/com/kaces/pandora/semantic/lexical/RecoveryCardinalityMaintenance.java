package com.kaces.pandora.semantic.lexical;

import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** A counter is trustworthy only for chunks born under an uninterrupted trigger epoch. */
final class RecoveryCardinalityMaintenance {
    private RecoveryCardinalityMaintenance() {}

    static void ensure(JdbcTemplate jdbc) {
        jdbc.execute((ConnectionCallback<Void>) connection -> {
            var db = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            if (!Integer.valueOf(1).equals(db.queryForObject("SELECT GET_LOCK('pandora_recovery_cardinality',30)", Integer.class)))
                throw new IllegalStateException("Recovery cardinality maintenance lock unavailable");
            try { install(db); }
            finally { db.queryForObject("SELECT RELEASE_LOCK('pandora_recovery_cardinality')", Integer.class); }
            return null;
        });
    }

    private static void install(JdbcTemplate db) {
        db.execute("CREATE TABLE IF NOT EXISTS semantic_lexical_cardinality_epoch (id INT PRIMARY KEY, epoch CHAR(36) NOT NULL, enabled BOOLEAN NOT NULL)");
        db.execute("CREATE TABLE IF NOT EXISTS semantic_lexical_cardinality_triggers (trigger_name VARCHAR(64) PRIMARY KEY, created_stamp VARCHAR(32) NOT NULL, body_hash CHAR(64) NOT NULL)");
        db.execute("ALTER TABLE semantic_lexical_term_cardinality ADD COLUMN IF NOT EXISTS epoch CHAR(36) NULL");
        db.execute("ALTER TABLE semantic_lexical_recovery_integrity ADD COLUMN IF NOT EXISTS cardinality_epoch CHAR(36) NULL");
        db.update("INSERT IGNORE INTO semantic_lexical_cardinality_epoch VALUES(1,?,false)", UUID.randomUUID().toString());
        String matches = """
            SELECT COUNT(*) FROM information_schema.TRIGGERS t
            JOIN semantic_lexical_cardinality_triggers a ON a.trigger_name=t.TRIGGER_NAME
             AND a.created_stamp=DATE_FORMAT(t.CREATED,'%Y-%m-%d %H:%i:%s.%f')
             AND a.body_hash=SHA2(t.ACTION_STATEMENT,256)
            WHERE t.TRIGGER_SCHEMA=DATABASE()
            """;
        if (Boolean.TRUE.equals(db.queryForObject("SELECT enabled FROM semantic_lexical_cardinality_epoch WHERE id=1",Boolean.class))
                && Integer.valueOf(5).equals(db.queryForObject(matches,Integer.class))) return;
        // Durable before DDL: a failed or interrupted install cannot attest old counts.
        db.update("UPDATE semantic_lexical_cardinality_epoch SET enabled=false,epoch=? WHERE id=1",UUID.randomUUID().toString());
        String current = " AND epoch=(SELECT epoch FROM semantic_lexical_cardinality_epoch WHERE id=1 AND enabled=true)";
        String moved = " AND (OLD.index_version<>NEW.index_version OR OLD.target<>NEW.target OR OLD.chunk_id<>NEW.chunk_id)";
        String oldKey = " WHERE index_version=OLD.index_version AND target=OLD.target AND chunk_id=OLD.chunk_id";
        String newKey = " WHERE index_version=NEW.index_version AND target=NEW.target AND chunk_id=NEW.chunk_id";
        Map<String,String> triggers=Map.of(
            "recovery_chunks_insert_count", "AFTER INSERT ON semantic_lexical_chunks FOR EACH ROW INSERT INTO semantic_lexical_term_cardinality(index_version,target,chunk_id,term_count,epoch) SELECT NEW.index_version,NEW.target,NEW.chunk_id,0,e.epoch FROM semantic_lexical_cardinality_epoch e WHERE e.id=1 AND e.enabled=true AND EXISTS(SELECT 1 FROM semantic_lexical_index_state s WHERE s.index_version=NEW.index_version AND s.status='BUILDING')",
            "recovery_terms_insert_count", "AFTER INSERT ON semantic_lexical_terms FOR EACH ROW UPDATE semantic_lexical_term_cardinality SET term_count=term_count+1"+newKey+current,
            "recovery_terms_delete_count", "AFTER DELETE ON semantic_lexical_terms FOR EACH ROW UPDATE semantic_lexical_term_cardinality SET term_count=term_count-1"+oldKey+current,
            "recovery_terms_update_old_count", "BEFORE UPDATE ON semantic_lexical_terms FOR EACH ROW UPDATE semantic_lexical_term_cardinality SET term_count=term_count-1"+oldKey+current+moved,
            "recovery_terms_update_new_count", "AFTER UPDATE ON semantic_lexical_terms FOR EACH ROW UPDATE semantic_lexical_term_cardinality SET term_count=term_count+1"+newKey+current+moved);
        triggers.forEach((name,body)->db.execute("CREATE OR REPLACE TRIGGER "+name+" "+body));
        db.update("DELETE FROM semantic_lexical_cardinality_triggers");
        db.update("""
            INSERT INTO semantic_lexical_cardinality_triggers
            SELECT TRIGGER_NAME,DATE_FORMAT(CREATED,'%Y-%m-%d %H:%i:%s.%f'),SHA2(ACTION_STATEMENT,256)
            FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME IN
            ('recovery_chunks_insert_count','recovery_terms_insert_count','recovery_terms_delete_count','recovery_terms_update_old_count','recovery_terms_update_new_count')
            """);
        if (!Integer.valueOf(5).equals(db.queryForObject(matches,Integer.class)))
            throw new IllegalStateException("Recovery cardinality trigger attestation incomplete");
        db.update("UPDATE semantic_lexical_cardinality_epoch SET enabled=true WHERE id=1");
    }
}
