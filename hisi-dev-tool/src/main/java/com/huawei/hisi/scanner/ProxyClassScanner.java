package com.huawei.hisi.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.model.ProxyMetadata;
import com.huawei.hisi.model.ScanResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Proxy class scanner for detecting MyBatis Mapper, JPA Repository, and Spring AOP Aspect.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Component
public class ProxyClassScanner implements EndpointScanner<ProxyMetadata> {

    private static final Logger LOG = Logger.getLogger(ProxyClassScanner.class.getName());

    // MyBatis annotations
    private static final String MYBATIS_MAPPER = "Mapper";
    private static final Set<String> MYBATIS_SQL_ANNOTATIONS = Set.of(
            "Select", "Insert", "Update", "Delete"
    );

    // JPA annotations
    private static final String JPA_REPOSITORY = "Repository";
    private static final String ENTITY = "Entity";

    // AOP annotations
    private static final String ASPECT = "Aspect";
    private static final Set<String> ADVICE_ANNOTATIONS = Set.of(
            "Before", "After", "Around", "AfterReturning", "AfterThrowing", "Pointcut"
    );

    private final JavaParser javaParser;
    private final Set<String> supportedAnnotations;

    public ProxyClassScanner() {
        ParserConfiguration config = new ParserConfiguration();
        this.javaParser = new JavaParser(config);
        this.supportedAnnotations = new HashSet<>();
        this.supportedAnnotations.add(MYBATIS_MAPPER);
        this.supportedAnnotations.addAll(MYBATIS_SQL_ANNOTATIONS);
        this.supportedAnnotations.add(JPA_REPOSITORY);
        this.supportedAnnotations.add(ASPECT);
        this.supportedAnnotations.addAll(ADVICE_ANNOTATIONS);
    }

    @Override
    public String getScannerName() {
        return "ProxyClassScanner";
    }

    @Override
    public ScanResult<ProxyMetadata> scanFile(Path filePath, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<ProxyMetadata> proxies = new ArrayList<>();

        try {
            Optional<CompilationUnit> cuOpt = javaParser.parse(filePath).getResult();
            if (cuOpt.isEmpty()) {
                return ScanResult.failure("Failed to parse file: " + filePath, getScannerName());
            }

            CompilationUnit cu = cuOpt.get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Scan for different proxy types
            proxies.addAll(scanMyBatisMappers(cu, packageName));
            proxies.addAll(scanJPARepositories(cu, packageName));
            proxies.addAll(scanAOPAspects(cu, packageName));

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error scanning file: " + filePath, e);
            return ScanResult.failure("IO error: " + e.getMessage(), getScannerName());
        }

        long duration = System.currentTimeMillis() - startTime;

        return ScanResult.<ProxyMetadata>builder()
                .success(true)
                .items(proxies)
                .foundCount(proxies.size())
                .scannedCount(1)
                .durationMs(duration)
                .scannerType(getScannerName())
                .build();
    }

    @Override
    public ScanResult<ProxyMetadata> scanFiles(List<Path> filePaths, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<ProxyMetadata> allProxies = new ArrayList<>();
        int scannedCount = 0;

        for (Path filePath : filePaths) {
            if (canScan(filePath)) {
                ScanResult<ProxyMetadata> result = scanFile(filePath, globalCache);
                if (result.isSuccess() && result.getItems() != null) {
                    allProxies.addAll(result.getItems());
                }
                scannedCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Update global cache
        updateGlobalCache(allProxies, globalCache);

        return ScanResult.<ProxyMetadata>builder()
                .success(true)
                .items(allProxies)
                .foundCount(allProxies.size())
                .scannedCount(scannedCount)
                .durationMs(duration)
                .scannerType(getScannerName())
                .build();
    }

    @Override
    public boolean canScan(Path filePath) {
        String fileName = filePath.toString().toLowerCase();
        return fileName.endsWith(".java");
    }

    @Override
    public Set<String> getSupportedAnnotations() {
        return supportedAnnotations;
    }

    /**
     * Scan for MyBatis Mapper interfaces
     */
    private List<ProxyMetadata> scanMyBatisMappers(CompilationUnit cu, String packageName) {
        List<ProxyMetadata> mappers = new ArrayList<>();

        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration classDecl, Void arg) {
                super.visit(classDecl, arg);

                // Check for @Mapper annotation
                boolean isMapper = classDecl.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(MYBATIS_MAPPER));

                if (!isMapper) {
                    return;
                }

                String interfaceName = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());

                // Scan each method for SQL annotations
                for (MethodDeclaration method : classDecl.getMethods()) {
                    for (AnnotationExpr annotation : method.getAnnotations()) {
                        String annotationName = annotation.getNameAsString();

                        if (MYBATIS_SQL_ANNOTATIONS.contains(annotationName)) {
                            String sql = extractSqlFromAnnotation(annotation);

                            ProxyMetadata proxy = ProxyMetadata.builder()
                                    .interfaceName(interfaceName)
                                    .interfaceType(ProxyMetadata.InterfaceType.MYBATIS)
                                    .proxyType(ProxyMetadata.ProxyType.MYBATIS_MAPPER)
                                    .methodName(method.getNameAsString())
                                    .methodSignature(buildMethodSignature(method))
                                    .sqlStatement(sql)
                                    .sqlOperationType(annotationName.toUpperCase())
                                    .packageName(packageName)
                                    .build();

                            mappers.add(proxy);
                        }
                    }
                }
            }
        }, null);

        return mappers;
    }

    /**
     * Scan for JPA Repository interfaces
     */
    private List<ProxyMetadata> scanJPARepositories(CompilationUnit cu, String packageName) {
        List<ProxyMetadata> repositories = new ArrayList<>();

        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration classDecl, Void arg) {
                super.visit(classDecl, arg);

                // Check for @Repository annotation and extends JpaRepository
                boolean isRepository = classDecl.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(JPA_REPOSITORY));

                boolean extendsJpaRepository = classDecl.getExtendedTypes().stream()
                        .anyMatch(t -> t.getNameAsString().contains("JpaRepository") ||
                                t.getNameAsString().contains("CrudRepository") ||
                                t.getNameAsString().contains("Repository"));

                if (!isRepository && !extendsJpaRepository) {
                    return;
                }

                String interfaceName = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());

                // Try to extract entity type from generic parameters
                String entityType = classDecl.getExtendedTypes().stream()
                        .findFirst()
                        .map(t -> {
                            if (t.getTypeArguments().isPresent() && !t.getTypeArguments().get().isEmpty()) {
                                return t.getTypeArguments().get().get(0).asString();
                            }
                            return null;
                        })
                        .orElse(null);

                // Add repository info
                ProxyMetadata proxy = ProxyMetadata.builder()
                        .interfaceName(interfaceName)
                        .interfaceType(ProxyMetadata.InterfaceType.JPA)
                        .proxyType(ProxyMetadata.ProxyType.JPA_REPOSITORY)
                        .entityType(entityType)
                        .packageName(packageName)
                        .build();

                repositories.add(proxy);

                // Scan custom methods
                for (MethodDeclaration method : classDecl.getMethods()) {
                    String methodInfo = String.format("%s|%s",
                            method.getNameAsString(),
                            buildMethodSignature(method));

                    ProxyMetadata methodProxy = ProxyMetadata.builder()
                            .interfaceName(interfaceName)
                            .interfaceType(ProxyMetadata.InterfaceType.JPA)
                            .proxyType(ProxyMetadata.ProxyType.JPA_REPOSITORY)
                            .methodName(method.getNameAsString())
                            .methodSignature(buildMethodSignature(method))
                            .entityType(entityType)
                            .packageName(packageName)
                            .build();

                    repositories.add(methodProxy);
                }
            }
        }, null);

        return repositories;
    }

    /**
     * Scan for AOP Aspect classes
     */
    private List<ProxyMetadata> scanAOPAspects(CompilationUnit cu, String packageName) {
        List<ProxyMetadata> aspects = new ArrayList<>();

        cu.accept(new VoidVisitorAdapter<Void>() {
            @Override
            public void visit(ClassOrInterfaceDeclaration classDecl, Void arg) {
                super.visit(classDecl, arg);

                // Check for @Aspect annotation
                boolean isAspect = classDecl.getAnnotations().stream()
                        .anyMatch(a -> a.getNameAsString().equals(ASPECT));

                if (!isAspect) {
                    return;
                }

                String className = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());

                // Scan each method for advice annotations
                for (MethodDeclaration method : classDecl.getMethods()) {
                    for (AnnotationExpr annotation : method.getAnnotations()) {
                        String annotationName = annotation.getNameAsString();

                        if (ADVICE_ANNOTATIONS.contains(annotationName)) {
                            String pointcut = extractPointcutFromAnnotation(annotation);

                            ProxyMetadata proxy = ProxyMetadata.builder()
                                    .interfaceName(className)
                                    .interfaceType(ProxyMetadata.InterfaceType.AOP)
                                    .proxyType(ProxyMetadata.ProxyType.ASPECT)
                                    .methodName(method.getNameAsString())
                                    .methodSignature(buildMethodSignature(method))
                                    .adviceType(annotationName)
                                    .pointcutExpression(pointcut)
                                    .packageName(packageName)
                                    .build();

                            aspects.add(proxy);
                        }
                    }
                }
            }
        }, null);

        return aspects;
    }

    /**
     * Extract SQL from MyBatis annotation
     */
    private String extractSqlFromAnnotation(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return ((SingleMemberAnnotationExpr) annotation).getMemberValue().toString();
        }

        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr nae = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : nae.getPairs()) {
                if (pair.getNameAsString().equals("value")) {
                    return pair.getValue().toString();
                }
            }
        }

        return null;
    }

    /**
     * Extract pointcut expression from advice annotation
     */
    private String extractPointcutFromAnnotation(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return ((SingleMemberAnnotationExpr) annotation).getMemberValue().toString();
        }

        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr nae = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : nae.getPairs()) {
                String name = pair.getNameAsString();
                if (name.equals("value") || name.equals("pointcut")) {
                    return pair.getValue().toString();
                }
            }
        }

        return null;
    }

    /**
     * Build method signature string
     */
    private String buildMethodSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getNameAsString()).append("(");

        method.getParameters().forEach(param -> {
            sb.append(param.getType().asString());
            if (param != method.getParameters().getLast().orElse(null)) {
                sb.append(", ");
            }
        });

        sb.append(")");
        return sb.toString();
    }

    /**
     * Update global cache with scanned proxy metadata
     */
    private void updateGlobalCache(List<ProxyMetadata> proxies, GlobalAnalysisCache globalCache) {
        for (ProxyMetadata proxy : proxies) {
            String interfaceName = proxy.getInterfaceName();

            switch (proxy.getInterfaceType()) {
                case MYBATIS:
                    String methodInfo = String.format("%s|%s|%s",
                            proxy.getMethodName(),
                            proxy.getSqlOperationType() != null ? proxy.getSqlOperationType() : "",
                            proxy.getSqlStatement() != null ? proxy.getSqlStatement() : "");

                    globalCache.getMyBatisMapperMap()
                            .computeIfAbsent(interfaceName, k -> ConcurrentHashMap.newKeySet())
                            .add(methodInfo);
                    break;

                case JPA:
                    if (proxy.getEntityType() != null) {
                        globalCache.getJpaRepositoryMap().put(interfaceName, proxy.getEntityType());
                    }
                    break;

                case AOP:
                    String aspectInfo = String.format("%s|%s|%s",
                            proxy.getMethodName(),
                            proxy.getAdviceType() != null ? proxy.getAdviceType() : "",
                            proxy.getPointcutExpression() != null ? proxy.getPointcutExpression() : "");

                    globalCache.getAspectMap()
                            .computeIfAbsent(interfaceName, k -> ConcurrentHashMap.newKeySet())
                            .add(aspectInfo);
                    break;

                default:
                    break;
            }
        }
    }
}