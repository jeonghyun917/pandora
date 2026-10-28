package com.kaces.pandora.lawdata.version;

import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.List;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.*;

/** Separate lazy pool: request-lifetime leases must never consume the ordinary query pool. */
@Configuration(proxyBeanMethods=false)
public class SourceVersionServingConfiguration {
    static final List<String> SERVING_PATHS=List.of(
        "/api/law-data/search","/api/law-data/chunk-search","/api/law-data/detail","/api/law-data/proxy",
        "/api/law-data/semantic/search","/api/law-data/ai/answer","/api/law-data/ai/answer-stream",
        "/api/law-data/ai/debug/search","/api/law-data/ai/debug/runtime-info",
        "/api/law-data/ai/debug/evaluate","/api/law-data/ai/debug/evaluate/gate","/api/admin/law-index-integrity/audit");
    public record ServingResources(HikariDataSource pool,SourceVersionReadLeases leases) implements AutoCloseable {
        @Override public void close() {pool.close();}
    }
    @Bean(destroyMethod="close")
    public ServingResources sourceVersionServingResources(Environment environment) {
        String url=environment.getRequiredProperty("spring.datasource.url");
        if(!url.startsWith("jdbc:mariadb:"))throw new IllegalStateException("Source leases require the configured MariaDB database");
        var pool=new HikariDataSource(); // Lazy constructor, no connection or health claim at application startup.
        pool.setPoolName("pandora-source-serving-leases");pool.setJdbcUrl(url);
        pool.setUsername(environment.getProperty("spring.datasource.username"));
        pool.setPassword(environment.getProperty("spring.datasource.password"));
        pool.setMaximumPoolSize(16);pool.setMinimumIdle(0);pool.setConnectionTimeout(5000);pool.setValidationTimeout(2000);
        pool.setInitializationFailTimeout(-1);
        return new ServingResources(pool,new SourceVersionReadLeases(pool,16,Duration.ofMillis(250)));
    }
    @Bean
    public WebMvcConfigurer sourceVersionServingRoutes(ServingResources resources) {
        var interceptor=new SourceVersionServingInterceptor(resources.leases());
        return new WebMvcConfigurer() {
            @Override public void addInterceptors(InterceptorRegistry registry) {
                registry.addInterceptor(interceptor).addPathPatterns(SERVING_PATHS);
            }
        };
    }
}
