package com.kaces.pandora.rag.recovery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import com.kaces.pandora.app.auth.*;

class RagRecoveryAccessTests {
    @Test void rejectedOperationNeverReturnsProviderMessagesOrInternalPaths() throws Exception {
        var workflow=mock(RagRecoveryWorkflowService.class);
        when(workflow.preview(42)).thenThrow(new IllegalStateException("secret credential C:/internal/private"));
        var mvc=MockMvcBuilders.standaloneSetup(new RagRecoveryController(workflow)).addInterceptors(new RagRecoveryAccessInterceptor()).build();
        var admin=new MockHttpSession();admin.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE,new AdminSession(1,"admin","admin","ADMIN"));
        var response=mvc.perform(get("/api/law-data/rag-recovery/preview").session(admin).param("documentId","42"))
            .andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("secret","credential","internal","private");
    }
    @Test void sessionRoleAndCsrfAreRequiredWithoutLocalOrTokenBypass() throws Exception {
        var workflow=mock(RagRecoveryWorkflowService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new RagRecoveryController(workflow))
            .addInterceptors(new RagRecoveryAccessInterceptor()).build();
        String path="/api/law-data/rag-recovery/create";
        mvc.perform(post(path).header("X-Pandora-Admin-Token","not-authentication").contentType("application/json")
            .content("{\"documentId\":42,\"expectedPreviewHash\":\""+"a".repeat(64)+"\"}")).andExpect(status().isUnauthorized());
        var user=new MockHttpSession();user.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE,new AdminSession(1,"user","user","USER"));
        mvc.perform(get("/api/law-data/rag-recovery/session").session(user)).andExpect(status().isForbidden());
        var admin=new MockHttpSession();admin.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE,new AdminSession(1,"admin","admin","ADMIN"));
        var response=mvc.perform(get("/api/law-data/rag-recovery/session").session(admin)).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        String csrf=new tools.jackson.databind.ObjectMapper().readTree(response.getContentAsString()).get("csrfToken").asText();
        mvc.perform(post(path).session(admin).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(path).session(admin).header("X-Pandora-Recovery-CSRF",csrf).header("Origin","https://foreign.invalid")
            .contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(post(path).session(admin).header("X-Pandora-Recovery-CSRF",csrf).contentType("application/json")
            .content("{\"documentId\":42,\"expectedPreviewHash\":\""+"a".repeat(64)+"\"}")).andExpect(status().isOk());
        verify(workflow).create(42,"a".repeat(64));verifyNoMoreInteractions(workflow);
    }
}
