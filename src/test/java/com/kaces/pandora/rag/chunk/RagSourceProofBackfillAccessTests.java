package com.kaces.pandora.rag.chunk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;

class RagSourceProofBackfillAccessTests {
    @Test void bothPreviewAndApplyRequireNormalAuthentication() throws Exception {
        var service=mock(RagSourceProofBackfillService.class);
        var matcher=new org.springframework.util.AntPathMatcher();
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new RagSourceProofBackfillController(service))
            .addInterceptors(new com.kaces.pandora.app.auth.AdminAuthInterceptor(new com.kaces.pandora.app.auth.AdminAuthProperties())).build();
        for(String route:java.util.List.of("preview","apply")) {
            String path="/api/law-data/rag-source-proof/"+route;
            assertThat(java.util.Arrays.stream(com.kaces.pandora.app.admin.AdminAccessPaths.PATTERNS).anyMatch(p->matcher.match(p,path))).isFalse();
        }
        mvc.perform(get("/api/law-data/rag-source-proof/preview").param("documentId","42")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/law-data/rag-source-proof/apply").contentType("application/json")
            .content("{\"documentId\":42,\"expectedPlanToken\":\""+"a".repeat(64)+"\"}")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
