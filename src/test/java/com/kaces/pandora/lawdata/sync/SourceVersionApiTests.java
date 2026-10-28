package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import com.kaces.pandora.app.auth.*;
import com.kaces.pandora.rag.recovery.RagRecoveryAccessInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SourceVersionApiTests {
    @Test void sourceApiRequiresAdminSessionAndSameOriginCsrfForEveryMutation() throws Exception {
        var workflow=mock(SourceVersionWorkflowService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new SourceVersionController(workflow)).addInterceptors(new RagRecoveryAccessInterceptor()).build();
        for(String action:java.util.List.of("stage","validate","prepare","prepare-rollback","promote","rollback"))
            mvc.perform(post("/api/law-data/source-versions/"+action).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        var admin=new MockHttpSession();admin.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE,new AdminSession(1,"admin","admin","ADMIN"));
        var response=mvc.perform(get("/api/law-data/source-versions/session").session(admin)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        String csrf=new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).get("csrfToken").asText();
        mvc.perform(post("/api/law-data/source-versions/promote").session(admin).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/law-data/source-versions/promote").session(admin).header(RagRecoveryAccessInterceptor.HEADER,csrf).header("Origin","https://foreign.invalid").contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post("/api/law-data/source-versions/promote").session(admin).header(RagRecoveryAccessInterceptor.HEADER,csrf).contentType("application/json").content("{\"transitionId\":\"exact-id\",\"expectedRevision\":3}")).andExpect(status().isOk());
        verify(workflow).promote("exact-id",3);verifyNoMoreInteractions(workflow);
    }
    @Test void sourceApiNeverLeaksProviderCredentialsPathsOrRawSourceInErrors() throws Exception {
        var workflow=mock(SourceVersionWorkflowService.class);when(workflow.status(anyString())).thenThrow(new IllegalStateException("credential C:/private raw source"));
        var mvc=MockMvcBuilders.standaloneSetup(new SourceVersionController(workflow)).addInterceptors(new RagRecoveryAccessInterceptor()).build();
        var admin=new MockHttpSession();admin.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE,new AdminSession(1,"admin","admin","ADMIN"));
        var body=mvc.perform(get("/api/law-data/source-versions/status").session(admin).param("groupKey","a".repeat(64))).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("credential","private","raw source");
    }
}
