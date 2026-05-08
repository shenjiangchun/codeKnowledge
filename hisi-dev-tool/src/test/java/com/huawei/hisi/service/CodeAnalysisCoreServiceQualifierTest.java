package com.huawei.hisi.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.config.AnalysisFeatureConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAnalysisCoreServiceQualifierTest {

    private GlobalAnalysisCache globalCache;
    private AnalysisFeatureConfig featureConfig;
    private CodeAnalysisCoreService service;

    @BeforeEach
    void setUp() {
        globalCache = new GlobalAnalysisCache();
        featureConfig = new AnalysisFeatureConfig();
        service = new CodeAnalysisCoreService(globalCache, featureConfig);
    }

    @Test
    @DisplayName("buildImplementationMapEnhanced populates beanNameMap with default and explicit names")
    void buildImplementationMap_populatesBeanNameMap() throws Exception {
        String code = """
                package com.example;

                import org.springframework.stereotype.Service;

                @Service("myCustomName")
                public class OrderServiceImpl implements OrderService {
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();

        Method method = CodeAnalysisCoreService.class.getDeclaredMethod(
                "buildImplementationMapEnhanced", CompilationUnit.class);
        method.setAccessible(true);
        method.invoke(service, cu);

        // Default bean name: lcfirst of simple class name
        assertThat(globalCache.getBeanNameMap())
                .containsEntry("orderServiceImpl", "com.example.OrderServiceImpl");

        // Explicit bean name from @Service("myCustomName")
        assertThat(globalCache.getBeanNameMap())
                .containsEntry("myCustomName", "com.example.OrderServiceImpl");
    }

    @Test
    @DisplayName("buildImplementationMapEnhanced stores default bean name for @Component without value")
    void buildImplementationMap_defaultBeanNameForBarAnnotation() throws Exception {
        String code = """
                package com.example;

                import org.springframework.stereotype.Component;

                @Component
                public class CacheManager {
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();

        Method method = CodeAnalysisCoreService.class.getDeclaredMethod(
                "buildImplementationMapEnhanced", CompilationUnit.class);
        method.setAccessible(true);
        method.invoke(service, cu);

        assertThat(globalCache.getBeanNameMap())
                .containsEntry("cacheManager", "com.example.CacheManager");
        // No explicit name entry since @Component has no value
        assertThat(globalCache.getBeanNameMap()).hasSize(1);
    }

    @Test
    @DisplayName("beanNameMap is cleared in GlobalAnalysisCache.clearAll")
    void clearAll_clearsBeanNameMap() {
        globalCache.getBeanNameMap().put("test", "com.example.Test");
        assertThat(globalCache.getBeanNameMap()).isNotEmpty();

        globalCache.clearAll();
        assertThat(globalCache.getBeanNameMap()).isEmpty();
    }
}
