package com.kaces.pandora.rag.recovery;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class RagRecoveryWebConfig implements WebMvcConfigurer {
    private final RagRecoveryAccessInterceptor access;
    public RagRecoveryWebConfig(RagRecoveryAccessInterceptor access) { this.access=access; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(access).addPathPatterns("/api/law-data/rag-recovery/**");
    }
}
