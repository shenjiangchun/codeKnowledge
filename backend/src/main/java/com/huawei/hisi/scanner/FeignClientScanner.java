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
import com.huawei.hisi.model.FeignClientInfo;
import com.huawei.hisi.model.ScanResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feign client scanner for detecting @FeignClient interfaces and their HTTP endpoints.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Component
public class FeignClientScanner implements EndpointScanner<FeignClientInfo> {

    private static final Logger LOG = Logger.getLogger(FeignClientScanner.class.getName());

    private static final String FEIGN_CLIENT = "FeignClient";

    // HTTP method annotations
    private static final Map<String, String> HTTP_METHOD_ANNOTATIONS = Map.of(
            "GetMapping", "GET",
            "PostMapping", "POST",
            "PutMapping", "PUT",
            "DeleteMapping", "DELETE",
            "PatchMapping", "PATCH",
            "RequestMapping", "REQUEST"
    );

    private final JavaParser javaParser;
    private final Set<String> supportedAnnotations;

    public FeignClientScanner() {
        ParserConfiguration config = new ParserConfiguration();
        this.javaParser = new JavaParser(config);
        this.supportedAnnotations = Set.of(FEIGN_CLIENT);
    }

    @Override
    public String getScannerName() {
        return "FeignClientScanner";
    }

    @Override
    public ScanResult<FeignClientInfo> scanFile(Path filePath, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<FeignClientInfo> clients = new ArrayList<>();

        try {
            Optional<CompilationUnit> cuOpt = javaParser.parse(filePath).getResult();
            if (cuOpt.isEmpty()) {
                return ScanResult.failure("Failed to parse file: " + filePath, getScannerName());
            }

            CompilationUnit cu = cuOpt.get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Find all @FeignClient interfaces
            cu.accept(new VoidVisitorAdapter<Void>() {
                @Override
                public void visit(ClassOrInterfaceDeclaration classDecl, Void arg) {
                    super.visit(classDecl, arg);

                    // Check if this class has @FeignClient annotation
                    Optional<AnnotationExpr> feignAnnotation = classDecl.getAnnotations().stream()
                            .filter(a -> a.getNameAsString().equals(FEIGN_CLIENT))
                            .findFirst();

                    if (feignAnnotation.isEmpty()) {
                        return;
                    }

                    String interfaceName = classDecl.getFullyQualifiedName().orElse(classDecl.getNameAsString());

                    // Parse @FeignClient attributes
                    String serviceName = extractAnnotationValue(feignAnnotation.get(), "name", "value");
                    String serviceUrl = extractAnnotationValue(feignAnnotation.get(), "url");
                    String basePath = extractAnnotationValue(feignAnnotation.get(), "path");

                    // Clean values
                    serviceName = cleanValue(serviceName);
                    serviceUrl = cleanValue(serviceUrl);
                    basePath = cleanValue(basePath);

                    // Parse each method in the interface
                    for (MethodDeclaration method : classDecl.getMethods()) {
                        FeignClientInfo methodInfo = parseMethod(method, interfaceName,
                                serviceName, serviceUrl, basePath, packageName);
                        if (methodInfo != null) {
                            clients.add(methodInfo);
                        }
                    }
                }
            }, null);

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error scanning file: " + filePath, e);
            return ScanResult.failure("IO error: " + e.getMessage(), getScannerName());
        }

        long duration = System.currentTimeMillis() - startTime;

        return ScanResult.<FeignClientInfo>builder()
                .success(true)
                .items(clients)
                .foundCount(clients.size())
                .scannedCount(1)
                .durationMs(duration)
                .scannerType(getScannerName())
                .build();
    }

    @Override
    public ScanResult<FeignClientInfo> scanFiles(List<Path> filePaths, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<FeignClientInfo> allClients = new ArrayList<>();
        int scannedCount = 0;

        for (Path filePath : filePaths) {
            if (canScan(filePath)) {
                ScanResult<FeignClientInfo> result = scanFile(filePath, globalCache);
                if (result.isSuccess() && result.getItems() != null) {
                    allClients.addAll(result.getItems());
                }
                scannedCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Update global cache
        updateGlobalCache(allClients, globalCache);

        return ScanResult.<FeignClientInfo>builder()
                .success(true)
                .items(allClients)
                .foundCount(allClients.size())
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
     * Parse a Feign client method and extract HTTP endpoint info
     */
    private FeignClientInfo parseMethod(MethodDeclaration method, String interfaceName,
                                         String serviceName, String serviceUrl, String basePath,
                                         String packageName) {
        String httpMethod = null;
        String uriPattern = null;

        // Check method-level mapping annotations
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String annotationName = annotation.getNameAsString();

            if (HTTP_METHOD_ANNOTATIONS.containsKey(annotationName)) {
                httpMethod = HTTP_METHOD_ANNOTATIONS.get(annotationName);

                // Extract URI from annotation
                if (annotationName.equals("RequestMapping")) {
                    uriPattern = extractAnnotationValue(annotation, "path", "value");
                    // Check method attribute for RequestMapping
                    String methodAttr = extractAnnotationValue(annotation, "method");
                    if (methodAttr != null) {
                        httpMethod = parseHttpMethod(methodAttr);
                    }
                } else {
                    uriPattern = extractAnnotationValue(annotation, "value", "path");
                }
                break;
            }
        }

        // If no method-level annotation, check for @RequestMapping on interface
        if (httpMethod == null) {
            httpMethod = "GET"; // Default for Feign
            uriPattern = "/" + method.getNameAsString(); // Default URI
        }

        uriPattern = cleanValue(uriPattern);
        if (uriPattern == null || uriPattern.isEmpty()) {
            uriPattern = "";
        }

        // Build full URI
        String fullUri = buildFullUri(basePath, uriPattern);

        return FeignClientInfo.builder()
                .interfaceName(interfaceName)
                .serviceName(serviceName)
                .serviceUrl(serviceUrl)
                .basePath(basePath)
                .methodName(method.getNameAsString())
                .httpMethod(httpMethod)
                .uriPattern(uriPattern)
                .fullUri(fullUri)
                .methodSignature(buildMethodSignature(method))
                .packageName(packageName)
                .build();
    }

    /**
     * Extract value from annotation by attribute name
     */
    private String extractAnnotationValue(AnnotationExpr annotation, String... attrNames) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            return ((SingleMemberAnnotationExpr) annotation).getMemberValue().toString();
        }

        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr nae = (NormalAnnotationExpr) annotation;
            for (MemberValuePair pair : nae.getPairs()) {
                for (String attrName : attrNames) {
                    if (pair.getNameAsString().equals(attrName)) {
                        return pair.getValue().toString();
                    }
                }
            }
        }

        return null;
    }

    /**
     * Clean annotation value (remove quotes, handle arrays)
     */
    private String cleanValue(String value) {
        if (value == null) return null;

        // Remove surrounding quotes
        value = value.replace("\"", "");

        // Handle array notation {value}
        if (value.startsWith("{") && value.endsWith("}")) {
            value = value.substring(1, value.length() - 1);
            if (value.contains(",")) {
                value = value.split(",")[0].trim();
            }
        }

        return value.trim().isEmpty() ? null : value.trim();
    }

    /**
     * Parse HTTP method from string
     */
    private String parseHttpMethod(String methodStr) {
        if (methodStr == null) return "GET";

        methodStr = methodStr.toUpperCase();
        if (methodStr.contains("GET")) return "GET";
        if (methodStr.contains("POST")) return "POST";
        if (methodStr.contains("PUT")) return "PUT";
        if (methodStr.contains("DELETE")) return "DELETE";
        if (methodStr.contains("PATCH")) return "PATCH";

        return "GET";
    }

    /**
     * Build full URI from base path and method URI
     */
    private String buildFullUri(String basePath, String uriPattern) {
        StringBuilder sb = new StringBuilder();

        if (basePath != null && !basePath.isEmpty()) {
            if (!basePath.startsWith("/")) {
                sb.append("/");
            }
            sb.append(basePath);
        }

        if (uriPattern != null && !uriPattern.isEmpty()) {
            if (!uriPattern.startsWith("/") && sb.length() > 0 && sb.charAt(sb.length() - 1) != '/') {
                sb.append("/");
            }
            sb.append(uriPattern);
        }

        return sb.toString();
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
     * Update global cache with scanned Feign clients
     */
    private void updateGlobalCache(List<FeignClientInfo> clients, GlobalAnalysisCache globalCache) {
        for (FeignClientInfo client : clients) {
            String serviceName = client.getServiceName();
            if (serviceName == null || serviceName.isEmpty()) {
                serviceName = client.getInterfaceName();
            }

            String clientInfo = String.format("%s|%s|%s",
                    client.getMethodName(),
                    client.getHttpMethod(),
                    client.getFullUri());

            globalCache.getFeignClientMap()
                    .computeIfAbsent(serviceName, k -> ConcurrentHashMap.newKeySet())
                    .add(clientInfo);

            // 记录 @FeignClient 接口 FQN → "FEIGN"，用于 IMPLEMENTS 关系分类
            globalCache.getProxyIndex().put(client.getInterfaceName(), "FEIGN");
        }
    }
}