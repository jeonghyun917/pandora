package com.kaces.pandora.lawdata.version;

import java.util.*;
import org.springframework.boot.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Database-native guards also constrain an older batch runtime sharing this corpus. */
@Component
@org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
public class SourceVersionSchemaMaintenance implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    public SourceVersionSchemaMaintenance(JdbcTemplate jdbc) {this.jdbc=jdbc;}
    @Override public void run(ApplicationArguments arguments) {
        String group="EXISTS (SELECT 1 FROM law_source_version_groups g WHERE g.target=%s.target AND (g.canonical_key=%s.canonical_key OR g.canonical_key=%s))";
        String newGroup=group.formatted("NEW","NEW",canonicalSql("NEW"));
        String oldGroup=group.formatted("OLD","OLD",canonicalSql("OLD"));
        install("srcver_doc_bi","law_api_documents","INSERT","BEGIN IF "+newGroup
            +" AND NOT (NEW.use_yn='N' AND NEW.effective_status='PAST') THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed source insertion must remain inactive'; END IF; END");
        String immutable=List.of("target","external_id","title","agency_name","category_name","source_date","canonical_key","effective_date","detail_link","raw_json","content_hash","sync_status","last_error_message","fetched_at","created_at").stream()
            .map(column->"(NEW."+column+" <=> OLD."+column+")").collect(java.util.stream.Collectors.joining(" AND "));
        String active="EXISTS (SELECT 1 FROM law_source_version_groups g WHERE g.target=OLD.target AND g.canonical_key=OLD.canonical_key AND g.active_document_id=OLD.document_id)";
        String preserved="EXISTS (SELECT 1 FROM law_source_version_history h JOIN law_source_version_groups g ON g.group_key=h.group_key WHERE h.document_id=OLD.document_id AND g.target=OLD.target AND g.canonical_key=OLD.canonical_key AND (h.source_candidate_id IS NULL OR EXISTS (SELECT 1 FROM law_source_version_candidates v WHERE v.candidate_id=h.source_candidate_id AND v.candidate_document_id=OLD.document_id AND v.state='RETIRED')))";
        install("srcver_doc_bu","law_api_documents","UPDATE","BEGIN IF ("+oldGroup+" OR "+newGroup+") THEN IF NOT ("+immutable+") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed source metadata is immutable'; END IF; IF NOT (("+active+" AND NEW.use_yn='Y' AND NEW.effective_status='CURRENT') OR (NOT "+active+" AND NEW.effective_status='PAST' AND (NEW.use_yn='N' OR (NEW.use_yn='Y' AND "+preserved+")))) THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed source visibility must follow registered pointer'; END IF; END IF; END");
        install("srcver_doc_bd","law_api_documents","DELETE","BEGIN IF "+oldGroup+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed source must be preserved'; END IF; END");
        child("chunks","law_api_document_chunks",false,false);
        child("details","law_api_document_details",false,false);
        child("assets","law_api_assets",false,false);
        child("versions","law_api_document_chunk_versions",false,false);
        child("operations","law_api_document_activation_operations",false,false);
        child("embeddings","law_api_chunk_embeddings",true,true);
        install("srcver_history_bu","law_source_version_history","UPDATE","SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Preserved source receipts are immutable'");
        install("srcver_history_bd","law_source_version_history","DELETE","SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Preserved source receipts must remain'");
    }
    private void child(String name,String table,boolean byChunk,boolean embeddings) {
        for(String event:List.of("INSERT","UPDATE","DELETE")) {
            List<String> aliases=event.equals("UPDATE")?List.of("OLD","NEW"):List.of(event.equals("DELETE")?"OLD":"NEW");
            String blocked=aliases.stream().map(alias->{
                String document=byChunk?"(SELECT c.document_id FROM law_api_document_chunks c WHERE c.chunk_id="+alias+".chunk_id)":alias+".document_id";
                return "EXISTS (SELECT 1 FROM law_api_documents d JOIN law_source_version_groups g ON g.target=d.target AND g.canonical_key=d.canonical_key WHERE d.document_id="+document
                    +" AND NOT EXISTS (SELECT 1 FROM law_source_version_candidates v WHERE v.group_key=g.group_key AND v.candidate_document_id=d.document_id AND d.use_yn='N' AND d.effective_status='PAST' AND v.state IN ("+(embeddings?"'STAGED','VALIDATED'":"'STAGED'")+")))";
            }).collect(java.util.stream.Collectors.joining(" OR "));
            String suffix=event.equals("INSERT")?"bi":event.equals("UPDATE")?"bu":"bd";
            install("srcver_"+name+"_"+suffix,table,event,"BEGIN IF "+blocked+" THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='Managed source assets are immutable'; END IF; END");
        }
    }
    private void install(String name,String table,String event,String body) {
        var found=jdbc.queryForList("SELECT EVENT_OBJECT_TABLE,EVENT_MANIPULATION,ACTION_TIMING,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?",name);
        if(found.isEmpty()) {
            jdbc.execute("CREATE TRIGGER "+name+" BEFORE "+event+" ON "+table+" FOR EACH ROW "+body);
            found=jdbc.queryForList("SELECT EVENT_OBJECT_TABLE,EVENT_MANIPULATION,ACTION_TIMING,ACTION_STATEMENT FROM information_schema.TRIGGERS WHERE TRIGGER_SCHEMA=DATABASE() AND TRIGGER_NAME=?",name);
        }
        if(found.size()!=1 || !table.equals(found.get(0).get("EVENT_OBJECT_TABLE")) || !event.equals(found.get(0).get("EVENT_MANIPULATION"))
                || !"BEFORE".equals(found.get(0).get("ACTION_TIMING")) || !normalize(body).equals(normalize((String)found.get(0).get("ACTION_STATEMENT"))))
            throw new IllegalStateException("Source version database guard is unavailable or changed: "+name);
    }
    private String normalize(String text) {return text==null?"":text.replaceAll("\\s+","");}
    static String canonicalSql(String alias) {
        if(!Set.of("NEW","OLD","d").contains(alias))throw new IllegalArgumentException("Internal document alias required");
        return "CONCAT("+alias+".target,':',LOWER(REGEXP_REPLACE("+alias+".title,CONCAT('[^',CHAR(92),'p{Hangul}A-Za-z0-9]'),'')))";
    }
}
