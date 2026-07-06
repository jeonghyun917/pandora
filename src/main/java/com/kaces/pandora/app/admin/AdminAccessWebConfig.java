package com.kaces.pandora.app.admin;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminAccessWebConfig implements WebMvcConfigurer {
	private final AdminAccessInterceptor adminAccessInterceptor;

	public AdminAccessWebConfig(AdminAccessInterceptor adminAccessInterceptor) {
		this.adminAccessInterceptor = adminAccessInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminAccessInterceptor)
			.addPathPatterns(
				"/api/admin/**",
				"/api/law-data/ai/debug/**",
				"/api/rag-collection/**",
				"/api/law-data/semantic/collection",
				"/api/law-data/semantic/index-sample",
				"/api/law-data/semantic/index-documents",
				"/api/law-data/semantic/batch-file",
				"/api/law-data/semantic/batches/**"
			);
	}
}
