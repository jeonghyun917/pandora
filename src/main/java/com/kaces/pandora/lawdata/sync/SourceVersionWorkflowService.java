package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.lawdata.version.*;
import java.util.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Admin workflow: all source bytes/metadata and product authority originate on the server. */
@Service
public class SourceVersionWorkflowService {
    public record Status(Map<String,Object> group,List<Map<String,Object>> candidates,List<Map<String,Object>> transitions,
            boolean workflowConfigured,boolean mutationsEnabled) { }
    private final JdbcTemplate db;
    private final SourceVersionCandidateStore store;
    private final LawOpenApiService official;
    private final LawOpenApiPayloadParser parser;
    private final ObjectProvider<SourceVersionPreparationService> preparation;
    private final ObjectProvider<SourceVersionTransitionService> transitions;
    private final String product;
    private final boolean configured,enabled;
    private final tools.jackson.databind.ObjectMapper json=new tools.jackson.databind.ObjectMapper();
    public SourceVersionWorkflowService(JdbcTemplate db,SourceVersionCandidateStore store,LawOpenApiService official,LawOpenApiPayloadParser parser,
            ObjectProvider<SourceVersionPreparationService> preparation,ObjectProvider<SourceVersionTransitionService> transitions,
            @Value("${pandora.rag.recovery.product-sha:}") String product,
            @Value("${pandora.source-version.workflow-enabled:false}") boolean configured,
            @Value("${pandora.source-version.mutations-enabled:false}") boolean enabled) {
        this.db=db;this.store=store;this.official=official;this.parser=parser;this.preparation=preparation;this.transitions=transitions;
        this.product=product;this.configured=configured;this.enabled=enabled;
    }
    public SourceVersionCandidateStore.StoredCandidate stage(long baseline,long revision,String external) {
        requireMutation();
        if(baseline<=0 || revision<0 || revision==Long.MAX_VALUE || external==null || !external.matches("[A-Za-z0-9._-]{1,100}"))
            throw new IllegalArgumentException("Exact baseline/revision/official identity required");
        var current=db.queryForMap("SELECT target,title,canonical_key,use_yn,effective_status FROM law_api_documents WHERE document_id=?",baseline);
        String target=(String)current.get("target"),title=(String)current.get("title");
        if(!Set.of("law","admrul").contains(target) || !"Y".equals(current.get("use_yn")) || !"CURRENT".equals(current.get("effective_status")))
            throw new IllegalStateException("Current official baseline required");
        var found=parser.parseSearchDocuments(target,official.search(target,title,1,100)).stream()
            .filter(row->external.equals(row.externalId()) && target.equals(row.target())
                && Objects.equals(current.get("canonical_key"),LawVersionUtils.canonicalKey(row.target(),row.title())) && officialIdentity(row)).toList();
        if(found.size()!=1)throw new IllegalStateException("Exact unambiguous official source search result required");
        var row=found.get(0);
        // Never fetch a caller-provided or search-result-provided arbitrary URL.
        String link="/DRF/lawService.do?target="+target+("law".equals(target)?"&MST=":"&ID=")+external+"&type=JSON";
        var source=new SearchDocument(row.target(),row.externalId(),row.title(),row.agencyName(),row.categoryName(),row.sourceDate(),row.canonicalKey(),row.effectiveDate(),row.effectiveStatus(),link,row.rawJson());
        String raw=official.detail(link);verifyDetailIdentity(source,raw);
        return store.stage(baseline,revision,source,raw,product);
    }
    private void verifyDetailIdentity(SearchDocument source,String raw) {
        if(raw==null || raw.isBlank() || raw.length()>4_000_000)throw new IllegalStateException("Bounded official detail required");
        try {
            var detail=json.readTree(raw);
            if(detail==null || !detail.isObject())throw new IllegalStateException("Structured official detail required");
            String title="law".equals(source.target())?first(detail,"법령명_한글","법령명한글","법령명"):first(detail,"행정규칙명");
            String date=LawVersionUtils.normalizeEffectiveDate(first(detail,"시행일자","발령일자"));
            String serial="law".equals(source.target())?first(detail,"법령일련번호"):first(detail,"행정규칙일련번호");
            if(title==null || !Objects.equals(source.canonicalKey(),LawVersionUtils.canonicalKey(source.target(),title))
                    || !Objects.equals(source.effectiveDate(),date) || (serial!=null && !source.externalId().equals(serial)))
                throw new IllegalStateException("Official detail identity differs from exact search source");
        } catch(RuntimeException invalid) {throw new IllegalStateException("Official detail identity verification failed",invalid);}
    }
    private String first(tools.jackson.databind.JsonNode node,String... keys) {
        for(String key:keys) {
            var value=node.findValue(key);
            if(value!=null && value.isValueNode() && !value.asText().isBlank())return value.asText().trim();
        }
        return null;
    }
    private boolean officialIdentity(SearchDocument source) {
        if(source.rawJson()==null || source.rawJson().length()>1_000_000)return false;
        try {
            var metadata=json.readTree(source.rawJson());
            var fields="law".equals(source.target())?List.of("법령일련번호","ID","id"):List.of("행정규칙일련번호","행정규칙ID","ID","id");
            for(String field:fields) {
                var value=metadata.get(field);
                if(value!=null && value.isValueNode() && !value.asText().isBlank())return source.externalId().equals(value.asText().trim());
            }
            return false;
        } catch(RuntimeException invalid) {return false;}
    }
    public SourceVersionPreparationService.Validation validate(String candidate) {requireMutation();return preparation.getIfAvailable().validate(candidate);}
    public SourceVersionTransitionService.Transition prepare(String candidate) {requireMutation();return transitions.getIfAvailable().prepare(candidate);}
    public SourceVersionTransitionService.Transition prepareRollback(String group,long revision,long restore) {requireMutation();return transitions.getIfAvailable().prepareRollback(group,revision,restore);}
    public SourceVersionTransitionService.Active promote(String transition,long revision) {requireMutation();return transitions.getIfAvailable().promote(transition,revision);}
    public SourceVersionTransitionService.Active rollback(String transition,long revision) {requireMutation();return transitions.getIfAvailable().rollback(transition,revision);}
    @org.springframework.transaction.annotation.Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Status status(String group) {
        if(group==null || !group.matches("[0-9a-f]{64}"))throw new IllegalArgumentException("Source group identity required");
        var groups=db.queryForList("SELECT group_key,target,canonical_key,active_document_id,baseline_document_id,revision,baseline_hash FROM law_source_version_groups WHERE group_key=?",group);
        var candidates=db.queryForList("SELECT candidate_id,state,candidate_document_id,baseline_document_id,baseline_revision,external_id,effective_date,product_sha,validation_manifest,validated_chunk_count,prepared_id FROM law_source_version_candidates WHERE group_key=? ORDER BY created_at,candidate_id LIMIT 101",group);
        var attempts=db.queryForList("SELECT transition_id,kind,state,target_document_id,expected_group_revision,manifest,corpus_fingerprint,lexical_fingerprint FROM law_source_version_transitions WHERE group_key=? ORDER BY transition_id LIMIT 101",group);
        if(groups.size()>1 || candidates.size()>100 || attempts.size()>100)throw new IllegalStateException("Source status bound exceeded");
        return new Status(groups.isEmpty()?null:Collections.unmodifiableMap(new LinkedHashMap<>(groups.get(0))),List.copyOf(candidates),List.copyOf(attempts),configured,enabled&&configured&&validProduct());
    }
    private boolean validProduct() {return product!=null && product.matches("[0-9a-f]{40}");}
    private void requireMutation() {
        if(!enabled || !configured || !validProduct())throw new IllegalStateException("Source version mutation release gate is closed");
        if(preparation.getIfAvailable()==null || transitions.getIfAvailable()==null)throw new IllegalStateException("Source version workflow is unavailable");
    }
}
