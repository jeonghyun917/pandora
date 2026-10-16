package com.kaces.pandora.lawdata.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import org.junit.jupiter.api.Test;

class CandidateChunkReadinessAccessTests {
    @Test void candidateStatusRequiresNormalAuthenticationWithoutInvokingTheService() throws Exception {
        String path="/api/law-data/chunks/candidate-status";
        var matcher=new org.springframework.util.AntPathMatcher();
        assertThat(java.util.Arrays.stream(com.kaces.pandora.app.admin.AdminAccessPaths.PATTERNS)
            .anyMatch(pattern->matcher.match(pattern,path))).isFalse();
        var service=mock(LawOpenApiSyncService.class);
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new LawSyncController(service))
            .addInterceptors(new com.kaces.pandora.app.auth.AdminAuthInterceptor(new com.kaces.pandora.app.auth.AdminAuthProperties())).build();
        mvc.perform(get(path).param("documentId","42").param("candidateVersion","3")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
