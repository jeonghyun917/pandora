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
    @Test void reviewedSourceActionsUseExistingAdminAndCsrfBoundary() throws Exception {
        var legacy=mock(RagRecoveryWorkflowService.class);var pdf=mock(RagPdfWorkflowService.class);
        var controller=new RagRecoveryController(legacy);controller.configureReviewedPdf(pdf);
        var mvc=MockMvcBuilders.standaloneSetup(controller).addInterceptors(new RagRecoveryAccessInterceptor()).build();
        String path="/api/law-data/rag-recovery/reviewed-pdf";
        for(String action:java.util.List.of("register","stage","create","validate","prepare","promote","rollback"))
            mvc.perform(post(path+"/"+action).contentType("application/json").content("{}")).andExpect(status().isUnauthorized());
        var admin=new MockHttpSession();admin.setAttribute(AdminSessionSupport.SESSION_ATTRIBUTE,new AdminSession(1,"admin","admin","ADMIN"));
        for(String action:java.util.List.of("register","stage","create","validate","prepare","promote","rollback"))
            mvc.perform(post(path+"/"+action).session(admin).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        verifyNoInteractions(pdf,legacy);
        var session=mvc.perform(get("/api/law-data/rag-recovery/session").session(admin)).andExpect(status().isOk()).andReturn().getResponse();
        String csrf=new tools.jackson.databind.ObjectMapper().readTree(session.getContentAsString()).get("csrfToken").asText();
        String id="11111111-1111-1111-1111-111111111111",manifest="a".repeat(64);
        mvc.perform(post(path+"/promote").session(admin).header(RagRecoveryAccessInterceptor.HEADER,csrf).contentType("application/json")
                .content("{\"generationId\":\""+id+"\",\"expectedRevision\":3,\"expectedManifest\":\""+manifest+"\"}"))
                .andExpect(status().isOk());
        verify(pdf).promote(id,3,manifest);
        when(pdf.stage()).thenThrow(new IllegalStateException("private original C:/internal/file"));
        var response=mvc.perform(post(path+"/stage").session(admin).header(RagRecoveryAccessInterceptor.HEADER,csrf)
                .contentType("application/json").content("{}")).andExpect(status().isConflict()).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("private","internal","C:/");
    }
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
