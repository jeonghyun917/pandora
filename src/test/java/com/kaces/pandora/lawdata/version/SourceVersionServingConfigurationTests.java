package com.kaces.pandora.lawdata.version;

import static org.assertj.core.api.Assertions.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

class SourceVersionServingConfigurationTests {
    @Test void dedicatedPoolIsBoundedLazyAndDoesNotExposeADatasourceBean() {
        var config=new SourceVersionServingConfiguration();
        var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:mariadb://127.0.0.1:1/source_lease_test");
        try(var resources=config.sourceVersionServingResources(env)) {
            assertThat(resources.pool().getMaximumPoolSize()).isEqualTo(16);
            assertThat(resources.pool().getMinimumIdle()).isZero();
            assertThat(resources.pool().getConnectionTimeout()).isEqualTo(5000);
            assertThat(resources.pool().getHikariPoolMXBean()).isNull();
            assertThat(resources.leases()).isNotNull();
            assertThat(Arrays.stream(SourceVersionServingConfiguration.class.getDeclaredMethods())
                .filter(method->method.isAnnotationPresent(org.springframework.context.annotation.Bean.class))
                .noneMatch(method->javax.sql.DataSource.class.isAssignableFrom(method.getReturnType()))).isTrue();
        }
    }
    @Test void servingRoutesCoverAnswerStreamSearchContextAndEvaluationButNotMutation() {
        var config=new SourceVersionServingConfiguration();
        var env=new MockEnvironment().withProperty("spring.datasource.url","jdbc:mariadb://127.0.0.1:1/source_lease_test");
        try(var resources=config.sourceVersionServingResources(env)) {
            var registry=new InterceptorRegistry();
            config.sourceVersionServingRoutes(resources).addInterceptors(registry);
            var matcher=new org.springframework.util.AntPathMatcher();
            var paths=SourceVersionServingConfiguration.SERVING_PATHS;
            for(String path:List.of("/api/law-data/search","/api/law-data/chunk-search","/api/law-data/detail",
                    "/api/law-data/semantic/search","/api/law-data/ai/answer","/api/law-data/ai/answer-stream",
                    "/api/law-data/ai/debug/search","/api/law-data/ai/debug/runtime-info","/api/law-data/ai/debug/evaluate/gate"))
                assertThat(paths.stream().anyMatch(pattern->matcher.match(pattern,path))).as(path).isTrue();
            for(String path:List.of("/api/law-data/source-versions/promote","/api/law-data/source-versions/rollback",
                    "/api/law-data/rag-recovery/promote","/api/law-data/sync","/api/law-data/semantic/index-documents"))
                assertThat(paths.stream().anyMatch(pattern->matcher.match(pattern,path))).as(path).isFalse();
        }
    }
}
