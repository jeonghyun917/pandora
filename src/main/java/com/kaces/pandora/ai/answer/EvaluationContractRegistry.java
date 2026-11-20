package com.kaces.pandora.ai.answer;

import java.util.*;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Explicitly configured, classpath-only request catalogue. Default: no contracts. */
@org.springframework.stereotype.Component
public class EvaluationContractRegistry {
    private static final int MAX_BYTES=4_000_000;
    private final String selectedHash;
    private final Map<String,LawAiEvalRequest.EvalCase> cases;
    private final Map<String,Integer> positions;

    public EvaluationContractRegistry() {this("");}
    @org.springframework.beans.factory.annotation.Autowired
    public EvaluationContractRegistry(@org.springframework.beans.factory.annotation.Value("${pandora.evaluation.contract-sha256:}") String selectedHash) {
        this(selectedHash,EvaluationContractRegistry::readResource);
    }
    EvaluationContractRegistry(String selectedHash,Function<String,byte[]> reader) {
        if(selectedHash==null || selectedHash.isEmpty()) {
            this.selectedHash=null;this.cases=Map.of();this.positions=Map.of();return;
        }
        require(canonical(selectedHash),"Expected one canonical configured contract hash");
        this.selectedHash=selectedHash;
        try {
            byte[] bytes=reader.apply("rag-evaluation-contracts/"+selectedHash+".json");
            require(bytes!=null && bytes.length>0 && bytes.length<=MAX_BYTES,"Contract resource unavailable or oversized");
            String actual=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            require(selectedHash.equals(actual),"Contract resource hash mismatch");
            var mapper=new ObjectMapper();var root=mapper.readTree(bytes);
            require(root.isObject() && "1".equals(root.path("schemaVersion").toString())
                && "pandora-versioned-evaluation".equals(text(root.path("kind")))
                && "requires-explicit-registration-and-payload-approval".equals(text(root.path("authorization")))
                && text(root.path("version")).matches("[a-z0-9][a-z0-9._-]{2,100}")
                && canonical(text(root.path("baselineDatasetSha256"))) && canonical(text(root.path("preparationSha256")))
                && canonical(text(root.path("catalogueBuilderSha256"))),"Malformed contract identity");
            var entries=root.path("cases");require(entries.isArray() && entries.size()==1003,"Complete1003 catalogue required");
            var byId=new LinkedHashMap<String,LawAiEvalRequest.EvalCase>();var order=new HashMap<String,Integer>();
            for(var entry:entries) {
                validateCaseSchema(entry);
                var row=mapper.treeToValue(entry,LawAiEvalRequest.EvalCase.class);
                require(row!=null && row.id()!=null && !row.id().isBlank() && row.question()!=null && !row.question().isBlank()
                    && !byId.containsKey(row.id()),"Unique complete case identities required");
                order.put(row.id(),byId.size());byId.put(row.id(),row);
            }
            validateScorers(root,byId.keySet());
            this.cases=Map.copyOf(byId);this.positions=Map.copyOf(order);
        } catch(IllegalArgumentException rejected) {throw rejected;}
        catch(Exception malformed) {throw new IllegalArgumentException("Unable to load configured evaluation contract",malformed);}
    }
    private static void validateCaseSchema(JsonNode entry) {
        require(entry.isObject(),"Complete case object required");
        for(String name:List.of("id","question","answerDirection"))
            require(entry.path(name).isTextual(),"Case text field required: "+name);
        for(String name:List.of("targets","expectedTerms","expectedTitleTerms","expectedSectionTypes","forbiddenTerms",
            "expectedDocumentTerms","expectedPageNumbers","expectedParentTerms","expectedResultMsgs","expectedAnswerTerms","forbiddenAnswerTerms")) {
            var values=entry.path(name);require(values.isArray(),"Case array field required: "+name);
            for(var value:values)require(value.isTextual(),"Case array member must be text");
        }
        require(!entry.path("targets").isEmpty(),"Explicit case targets required");
        require(entry.path("requiredMatches").toString().matches("[0-9]{1,9}"),"Nonnegative case match count required");
        var verify=entry.path("answerVerificationRequired");
        require(!verify.isMissingNode() && (verify.isNull() || verify.isBoolean()),"Case verification flag must be boolean or null");
        for(String name:List.of("requiredPropositionGroups","requiredConditionGroups")) {
            var groups=entry.path(name);require(groups.isArray(),"Case group field required: "+name);
            for(var group:groups) {
                require(group.isArray(),"Case group must be an array");
                for(var value:group)require(value.isTextual(),"Case group member must be text");
            }
        }
    }
    public List<String> registeredHashes() {return selectedHash==null?List.of():List.of(selectedHash);}
    void assertRequestBound(String hash,LawAiEvalRequest request) {
        require(selectedHash!=null && selectedHash.equals(hash),"Evaluation contract is not registered");
        require(request!=null && request.cases()!=null && !request.cases().isEmpty() && request.cases().size()<=cases.size()
            && (request.caseIds()==null || request.caseIds().isEmpty()) && request.maxCases()==null,"Explicit catalogue subset required");
        int previous=-1;
        for(var row:request.cases()) {
            require(row!=null && row.id()!=null && cases.containsKey(row.id()) && cases.get(row.id()).equals(row),"Evaluation case differs from registered catalogue");
            int position=positions.get(row.id());require(position>previous,"Unique original subset order required");previous=position;
        }
    }
    private static void validateScorers(JsonNode root,Set<String> ids) {
        var overrides=root.path("scoringOverrides");var contracts=root.path("sourceContracts");
        require(overrides.isObject() && overrides.size()==2 && contracts.isObject() && contracts.size()==2,"Known scorer bindings required");
        var kinds=new HashSet<String>();
        for(var binding:overrides.properties()) {
            String kind=text(binding.getValue());
            require(ids.contains(binding.getKey()) && Set.of("qualified-approval","reference-commencement").contains(kind)
                && kinds.add(kind),"Unknown or duplicate scorer binding");
        }
        var approval=contracts.path("qualified-approval");var reference=contracts.path("reference-commencement");
        require(text(approval.path("indexedEdition")).matches("[0-9]+"),"Approval edition required");
        var scope=approval.path("qualification");var remedy=approval.path("remedy");
        validateOwner(scope.path("owner"));validateOwner(remedy.path("owner"));
        require(!text(scope.path("heading")).isBlank() && !text(scope.path("literal")).isBlank()
            && scope.path("owner").path("target").equals(remedy.path("owner").path("target"))
            && positive(scope.path("owner").path("documentId"))==positive(remedy.path("owner").path("documentId"))
            && positive(scope.path("owner").path("chunkId"))!=positive(remedy.path("owner").path("chunkId")),"Owned approval scope and remedy required");
        for(String role:List.of("actor","projectScope","trigger","action","modality"))
            require(!text(remedy.path("anchors").path(role)).isBlank(),"Complete approval anchors required");
        validateOwner(reference.path("owner"));
        require("law".equals(text(reference.path("owner").path("target"))) && !text(reference.path("title")).isBlank()
            && text(reference.path("lawId")).matches("[0-9]+") && text(reference.path("edition")).matches("[0-9]+")
            && positive(reference.path("chunkVersion"))>0,"Reference identity required");
        var from=java.time.LocalDate.parse(text(reference.path("effectiveFrom")));
        var asOf=java.time.LocalDate.parse(text(reference.path("asOf")));
        require(!asOf.isBefore(from),"Reference date precedes general commencement");
    }
    private static void validateOwner(JsonNode owner) {
        require(owner.isObject() && Set.of("law","admrul","official_doc","internal_doc").contains(text(owner.path("target")))
            && positive(owner.path("documentId"))>0 && positive(owner.path("chunkId"))>0,"Positive source owner required");
    }
    private static long positive(JsonNode value) {
        require(value.toString().matches("[1-9][0-9]{0,15}"),"Positive safe integer required");
        long number=Long.parseLong(value.toString());require(number<=9_007_199_254_740_991L,"Unsafe numeric identity");return number;
    }
    private static String text(JsonNode value) {return value.isTextual()?value.asString():"";}
    private static boolean canonical(String value) {return value!=null && value.matches("[a-f0-9]{64}");}
    private static void require(boolean condition,String message) {if(!condition)throw new IllegalArgumentException(message);}
    private static byte[] readResource(String path) {
        try(var input=EvaluationContractRegistry.class.getClassLoader().getResourceAsStream(path)) {
            return input==null?null:input.readNBytes(MAX_BYTES+1);
        } catch(java.io.IOException failed) {throw new IllegalArgumentException("Contract resource unavailable",failed);}
    }
}
