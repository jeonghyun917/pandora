package com.kaces.pandora.ai.answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VersionedEvaluationDispatchTests {
    private static final String HASH="8afcc132da9282d0ef2e27262f850df0f20ad0e0466c7f3806de1beb58b9fc43";
    private static final String HEADER="X-Pandora-Evaluation-Contract-SHA256";
    private static final String ENDPOINT="/api/law-data/ai/debug/evaluate";
    private final tools.jackson.databind.ObjectMapper mapper=new tools.jackson.databind.ObjectMapper();
    private tools.jackson.databind.JsonNode row(int i) throws Exception {
        try(var input=getClass().getClassLoader().getResourceAsStream("rag-evaluation-contracts/"+HASH+".json")) {
            return mapper.readTree(input).path("cases").get(i);
        }
    }
    private String body(Object rows) throws Exception {return mapper.writeValueAsString(java.util.Map.of("cases",rows));}
    @Test void explicitRegisteredCaseIsAttestedAndCapabilitiesDoNotEvaluate() throws Exception {
        var service=mock(LawAiAnswerService.class);
        when(service.evaluate(any())).thenReturn(new LawAiEvalResponse(1,1,0,1,true,1,List.of(),0,0,List.of()));
        var mvc=MockMvcBuilders.standaloneSetup(new LawAiAnswerController(service,new EvaluationContractRegistry(HASH))).build();
        mvc.perform(get("/api/law-data/ai/debug/evaluation-contracts"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.registeredContracts[0]").value(HASH));
        verifyNoInteractions(service);
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).header(HEADER,HASH).content(body(List.of(row(0)))))
            .andExpect(status().isOk()).andExpect(header().string(HEADER,HASH));
        verify(service).evaluate(any());
    }
    @Test void changedDuplicateReverseAndShortcutRequestsNeverReachTheService() throws Exception {
        var service=mock(LawAiAnswerService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new LawAiAnswerController(service,new EvaluationContractRegistry(HASH))).build();
        var changed=(tools.jackson.databind.node.ObjectNode)row(0).deepCopy();changed.put("question","Changed question");
        var rubric=(tools.jackson.databind.node.ObjectNode)row(0).deepCopy();rubric.put("requiredMatches",0);
        var groups=(tools.jackson.databind.node.ObjectNode)row(0).deepCopy();groups.putArray("requiredPropositionGroups").addArray().add("Changed oracle");
        var nullId=(tools.jackson.databind.node.ObjectNode)row(0).deepCopy();nullId.putNull("id");
        for(String request:List.of(body(List.of(changed)),body(List.of(rubric)),body(List.of(row(0),row(0))),
            body(List.of(groups)),body(List.of(nullId)),
            body(List.of(row(1),row(0))),"{\"caseIds\":[\"project-review-target\"]}",
            "{\"cases\":[],\"maxCases\":1}",mapper.writeValueAsString(java.util.Map.of("cases",List.of(row(0)),"maxCases",1))))
            mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).header(HEADER,HASH).content(request))
                .andExpect(status().is(422));
        verifyNoInteractions(service);
    }
    @Test void taggedLegacyGateCannotPretendToApplyTheSourceQualifiedContract() throws Exception {
        var service=mock(LawAiAnswerService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new LawAiAnswerController(service,new EvaluationContractRegistry(HASH))).build();
        mvc.perform(post(ENDPOINT+"/gate").contentType(MediaType.APPLICATION_JSON).header(HEADER,HASH).content(body(List.of(row(0)))))
            .andExpect(status().is(422));
        verifyNoInteractions(service);
    }
}
