package com.kaces.pandora.ai.answer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EvaluationContractRegistryTests {
    private LawAiEvalRequest.EvalCase row(int i) {
        return new LawAiEvalRequest.EvalCase("case-"+i,"Question "+i,List.of("law"),List.of("ground"),1,
            List.of("Example"),List.of(),List.of(),List.of(),List.of(),List.of(),"Explain supported evidence");
    }
    private List<LawAiEvalRequest.EvalCase> rows() {
        return java.util.stream.IntStream.range(0,1003).mapToObj(this::row).toList();
    }
    private Map<String,Object> catalogue(List<LawAiEvalRequest.EvalCase> rows) {
        var owner=Map.of("target","admrul","documentId",42,"chunkId",1);
        var remedyOwner=Map.of("target","admrul","documentId",42,"chunkId",2);
        var approval=Map.of("indexedEdition","100","qualification",Map.of("owner",owner,"heading","Scope","literal","Scope text"),
            "remedy",Map.of("owner",remedyOwner,"anchors",Map.of("actor","Actor","projectScope","Scope","trigger","Trigger","action","Action","modality","May")));
        var reference=Map.of("title","Example law","lawId","100","edition","200","chunkVersion",1,
            "effectiveFrom","2026-08-28","asOf","2026-11-18","owner",Map.of("target","law","documentId",43,"chunkId",3));
        var result=new LinkedHashMap<String,Object>();
        result.put("schemaVersion",1);result.put("kind","pandora-versioned-evaluation");result.put("version","fixture-v2");
        result.put("authorization","requires-explicit-registration-and-payload-approval");
        result.put("baselineDatasetSha256","a".repeat(64));result.put("preparationSha256","b".repeat(64));
        result.put("catalogueBuilderSha256","c".repeat(64));
        result.put("scoringOverrides",Map.of("case-1","qualified-approval","case-2","reference-commencement"));
        result.put("sourceContracts",Map.of("qualified-approval",approval,"reference-commencement",reference));
        result.put("cases",rows);return result;
    }
    private byte[] bytes(Map<String,Object> value) throws Exception {return new tools.jackson.databind.ObjectMapper().writeValueAsBytes(value);}
    private String hash(byte[] bytes) throws Exception {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    @Test void defaultsEmptyAndNeverReadsAResourceWithoutExplicitSelection() {
        var reads=new AtomicInteger();
        var registry=new EvaluationContractRegistry("",path->{reads.incrementAndGet();return null;});
        assertThat(registry.registeredHashes()).isEmpty();assertThat(reads.get()).isZero();
        assertThatThrownBy(()->registry.assertRequestBound("a".repeat(64),new LawAiEvalRequest(List.of(row(1)),List.of(),null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void exactRegisteredCatalogueBindsFullCaseValuesAndOriginalSubsetOrder() throws Exception {
        byte[] bytes=bytes(catalogue(rows()));String hash=hash(bytes);
        var registry=new EvaluationContractRegistry(hash,path->{assertThat(path).isEqualTo("rag-evaluation-contracts/"+hash+".json");return bytes;});
        assertThat(registry.registeredHashes()).containsExactly(hash);
        registry.assertRequestBound(hash,new LawAiEvalRequest(List.of(row(1),row(3)),List.of(),null));
        for(var request:List.of(new LawAiEvalRequest(List.of(row(3),row(1)),List.of(),null),
            new LawAiEvalRequest(List.of(row(1),row(1)),List.of(),null),
            new LawAiEvalRequest(List.of(row(1004)),List.of(),null),
            new LawAiEvalRequest(List.of(),List.of("case-1"),null),
            new LawAiEvalRequest(List.of(row(1)),List.of(),1),
            new LawAiEvalRequest(List.of(new LawAiEvalRequest.EvalCase("case-1","Changed question",List.of("law"),List.of("ground"),1,
                List.of("Example"),List.of(),List.of(),List.of(),List.of(),List.of(),"Explain supported evidence")),List.of(),null)))
            assertThatThrownBy(()->registry.assertRequestBound(hash,request)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void selectedResourceMustMatchItsHashAndCompleteKnownSchema() throws Exception {
        byte[] valid=bytes(catalogue(rows()));String hash=hash(valid);
        assertThatThrownBy(()->new EvaluationContractRegistry(hash,path->null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EvaluationContractRegistry(hash,path->"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new EvaluationContractRegistry("../escape",path->valid)).isInstanceOf(IllegalArgumentException.class);
        for(var bad:List.of(catalogue(rows().subList(0,1002)),catalogue(java.util.stream.IntStream.range(0,1003).mapToObj(i->row(0)).toList()))) {
            byte[] raw=bytes(bad);String identity=hash(raw);
            assertThatThrownBy(()->new EvaluationContractRegistry(identity,path->raw)).isInstanceOf(IllegalArgumentException.class);
        }
        var bad=catalogue(rows());bad.put("sourceContracts",Map.of("qualified-approval",Map.of(),"reference-commencement",Map.of()));
        byte[] raw=bytes(bad);String identity=hash(raw);
        assertThatThrownBy(()->new EvaluationContractRegistry(identity,path->raw)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void malformedNullIdentityIsAControlledRefusal() throws Exception {
        byte[] raw=bytes(catalogue(rows()));String identity=hash(raw);
        var registry=new EvaluationContractRegistry(identity,path->raw);
        var malformed=new LawAiEvalRequest.EvalCase(null,"Question",List.of("law"),List.of(),0,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),"Explain");
        assertThatThrownBy(()->registry.assertRequestBound(identity,new LawAiEvalRequest(List.of(malformed),List.of(),null)))
            .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void sealedRealCatalogueIsLoadableButNotAutomaticallyRegistered() throws Exception {
        String identity="8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43";
        byte[] raw;
        try(var input=getClass().getClassLoader().getResourceAsStream("rag-evaluation-contracts/"+identity+".json")) {
            assertThat(input).isNotNull();raw=input.readAllBytes();
        }
        assertThat(hash(raw)).isEqualTo(identity);
        var configured=new EvaluationContractRegistry(identity);
        var mapper=new tools.jackson.databind.ObjectMapper();
        var entries=new ArrayList<LawAiEvalRequest.EvalCase>();
        for(var row:mapper.readTree(raw).path("cases")) entries.add(mapper.treeToValue(row,LawAiEvalRequest.EvalCase.class));
        configured.assertRequestBound(identity,new LawAiEvalRequest(entries,List.of(),null));
        assertThat(new EvaluationContractRegistry().registeredHashes()).isEmpty();
    }
}
