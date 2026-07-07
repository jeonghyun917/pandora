package com.kaces.pandora.app.auth;

import com.kaces.pandora.app.admin.AdminAccessPaths;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AdminAuthWebConfig implements WebMvcConfigurer {
	private final AdminAuthInterceptor adminAuthInterceptor;

	public AdminAuthWebConfig(AdminAuthInterceptor adminAuthInterceptor) {
		this.adminAuthInterceptor = adminAuthInterceptor;
	}

	@Override
	public void addInterceptors(InterceptorRegistry registry) {
		registry.addInterceptor(adminAuthInterceptor)
			.addPathPatterns("/api/**")
			.excludePathPatterns("/api/auth/**")
			.excludePathPatterns(AdminAccessPaths.PATTERNS)
			.order(Ordered.HIGHEST_PRECEDENCE);
	}
}
