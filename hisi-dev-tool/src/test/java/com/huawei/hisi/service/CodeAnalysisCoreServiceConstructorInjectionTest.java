package com.huawei.hisi.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.config.AnalysisFeatureConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class CodeAnalysisCoreServiceConstructorInjectionTest {

    @Mock
    private GlobalAnalysisCache globalCache;

    @Mock
    private AnalysisFeatureConfig featureConfig;

    private CodeAnalysisCoreService service;

    @BeforeEach
    void setUp() {
        service = new CodeAnalysisCoreService(globalCache, featureConfig);
    }

    private boolean invokeIsConstructorInjectedField(ClassOrInterfaceDeclaration clazz, VariableDeclarator variable) throws Exception {
        Method method = CodeAnalysisCoreService.class.getDeclaredMethod(
                "isConstructorInjectedField", ClassOrInterfaceDeclaration.class, VariableDeclarator.class);
        method.setAccessible(true);
        return (boolean) method.invoke(service, clazz, variable);
    }

    private String invokeInferTypeFromScope(String scopeName, MethodDeclaration currentMethod, ClassOrInterfaceDeclaration clazz) throws Exception {
        Method method = CodeAnalysisCoreService.class.getDeclaredMethod(
                "inferTypeFromScope", String.class, MethodDeclaration.class, ClassOrInterfaceDeclaration.class);
        method.setAccessible(true);
        return (String) method.invoke(service, scopeName, currentMethod, clazz);
    }

    @Test
    @DisplayName("final field + explicit constructor with matching param is treated as injected")
    void finalFieldWithExplicitConstructor_treatedAsInjected() throws Exception {
        String code = """
                public class OrderService {
                    private final OrderRepository orderRepository;

                    public OrderService(OrderRepository orderRepository) {
                        this.orderRepository = orderRepository;
                    }

                    public void doWork() {}
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        VariableDeclarator variable = clazz.getFieldByName("orderRepository").orElseThrow()
                .getVariable(0);

        assertThat(invokeIsConstructorInjectedField(clazz, variable)).isTrue();
    }

    @Test
    @DisplayName("final field + @RequiredArgsConstructor is treated as injected")
    void finalFieldWithRequiredArgsConstructor_treatedAsInjected() throws Exception {
        String code = """
                @RequiredArgsConstructor
                public class OrderService {
                    private final OrderRepository orderRepository;

                    public void doWork() {}
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        VariableDeclarator variable = clazz.getFieldByName("orderRepository").orElseThrow()
                .getVariable(0);

        assertThat(invokeIsConstructorInjectedField(clazz, variable)).isTrue();
    }

    @Test
    @DisplayName("non-final field without annotation is NOT treated as injected")
    void nonFinalFieldWithoutAnnotation_notTreatedAsInjected() throws Exception {
        String code = """
                public class OrderService {
                    private OrderRepository orderRepository;

                    public void doWork() {}
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        VariableDeclarator variable = clazz.getFieldByName("orderRepository").orElseThrow()
                .getVariable(0);

        assertThat(invokeIsConstructorInjectedField(clazz, variable)).isFalse();
    }

    @Test
    @DisplayName("inferTypeFromScope resolves constructor parameter type")
    void inferTypeFromScope_resolvesConstructorParam() throws Exception {
        String code = """
                public class OrderService {
                    private final OrderRepository repo;

                    public OrderService(OrderRepository repo) {
                        this.repo = repo;
                    }

                    public void doWork() {
                        repo.save();
                    }
                }
                """;

        CompilationUnit cu = new JavaParser().parse(code).getResult().orElseThrow();
        ClassOrInterfaceDeclaration clazz = cu.findFirst(ClassOrInterfaceDeclaration.class).orElseThrow();
        MethodDeclaration method = clazz.getMethodsByName("doWork").get(0);

        // "repo" should resolve via constructor param (or field, both return same type)
        String result = invokeInferTypeFromScope("repo", method, clazz);
        assertThat(result).isEqualTo("OrderRepository");
    }
}
