package com.huawei.hisi.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.config.AnalysisFeatureConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CodeAnalysisCoreServiceProxyDetectionTest {

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
    @DisplayName("detectProxyCallType returns ASYNC_PROXY for @Async method when flag enabled")
    void detectProxyCallType_asyncAnnotation_returnsAsyncProxy() {
        featureConfig.setSpringAnnotationAware(true);

        String code = """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class MyService {
                    @Async
                    public void asyncMethod() {}
                }
                """;

        MethodDeclaration method = parseFirstMethod(code);
        assertThat(service.detectProxyCallType(method)).isEqualTo("ASYNC_PROXY");
    }

    @Test
    @DisplayName("detectProxyCallType returns TRANSACTIONAL_PROXY for @Transactional method when flag enabled")
    void detectProxyCallType_transactionalAnnotation_returnsTransactionalProxy() {
        featureConfig.setSpringAnnotationAware(true);

        String code = """
                package com.example;
                import org.springframework.transaction.annotation.Transactional;
                public class MyService {
                    @Transactional
                    public void txMethod() {}
                }
                """;

        MethodDeclaration method = parseFirstMethod(code);
        assertThat(service.detectProxyCallType(method)).isEqualTo("TRANSACTIONAL_PROXY");
    }

    @Test
    @DisplayName("detectProxyCallType returns DIRECT for unannotated method when flag enabled")
    void detectProxyCallType_noAnnotation_returnsDirect() {
        featureConfig.setSpringAnnotationAware(true);

        String code = """
                package com.example;
                public class MyService {
                    public void plainMethod() {}
                }
                """;

        MethodDeclaration method = parseFirstMethod(code);
        assertThat(service.detectProxyCallType(method)).isEqualTo("DIRECT");
    }

    @Test
    @DisplayName("detectProxyCallType returns DIRECT when flag disabled regardless of annotations")
    void detectProxyCallType_flagDisabled_returnsDirect() {
        featureConfig.setSpringAnnotationAware(false);

        String code = """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                public class MyService {
                    @Async
                    public void asyncMethod() {}
                }
                """;

        MethodDeclaration method = parseFirstMethod(code);
        assertThat(service.detectProxyCallType(method)).isEqualTo("DIRECT");
    }

    @Test
    @DisplayName("detectProxyCallType prefers @Async over @Transactional when both present")
    void detectProxyCallType_bothAnnotations_returnsAsyncProxy() {
        featureConfig.setSpringAnnotationAware(true);

        String code = """
                package com.example;
                import org.springframework.scheduling.annotation.Async;
                import org.springframework.transaction.annotation.Transactional;
                public class MyService {
                    @Async
                    @Transactional
                    public void dualMethod() {}
                }
                """;

        MethodDeclaration method = parseFirstMethod(code);
        assertThat(service.detectProxyCallType(method)).isEqualTo("ASYNC_PROXY");
    }

    private MethodDeclaration parseFirstMethod(String code) {
        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(code).getResult().orElseThrow();
        return cu.findFirst(MethodDeclaration.class).orElseThrow();
    }
}
