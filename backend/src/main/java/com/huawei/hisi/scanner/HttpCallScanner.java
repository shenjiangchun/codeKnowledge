package com.huawei.hisi.scanner;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.model.HttpCallInfo;
import com.huawei.hisi.model.ScanResult;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * HTTP call scanner for detecting RestTemplate and WebClient HTTP calls.
 * Identifies cross-service HTTP invocations for call chain bridging.
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@Component
public class HttpCallScanner implements EndpointScanner<HttpCallInfo> {

    private static final Logger LOG = Logger.getLogger(HttpCallScanner.class.getName());

    // RestTemplate methods
    private static final Set<String> REST_TEMPLATE_METHODS = Set.of(
            "getForObject", "getForEntity",
            "postForObject", "postForEntity", "postForLocation",
            "put", "patchForObject",
            "delete",
            "exchange", "execute",
            "headForHeaders", "optionsForAllow"
    );

    // WebClient methods
    private static final Set<String> WEB_CLIENT_METHODS = Set.of(
            "retrieve", "exchange", "bodyToMono", "bodyToFlux"
    );

    private final JavaParser javaParser;

    public HttpCallScanner() {
        ParserConfiguration config = new ParserConfiguration();
        this.javaParser = new JavaParser(config);
    }

    @Override
    public String getScannerName() {
        return "HttpCallScanner";
    }

    @Override
    public ScanResult<HttpCallInfo> scanFile(Path filePath, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<HttpCallInfo> httpCalls = new ArrayList<>();

        try {
            Optional<CompilationUnit> cuOpt = javaParser.parse(filePath).getResult();
            if (cuOpt.isEmpty()) {
                return ScanResult.failure("Failed to parse file: " + filePath, getScannerName());
            }

            CompilationUnit cu = cuOpt.get();
            String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");

            // Get class name
            String[] classNameHolder = {""};
            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(c -> {
                classNameHolder[0] = c.getFullyQualifiedName().orElse(c.getNameAsString());
            });

            String className = classNameHolder[0];

            // Find all HTTP calls
            cu.accept(new VoidVisitorAdapter<Void>() {
                private String currentMethod = "";

                @Override
                public void visit(MethodDeclaration method, Void arg) {
                    currentMethod = method.getNameAsString();
                    super.visit(method, arg);
                    currentMethod = "";
                }

                @Override
                public void visit(MethodCallExpr call, Void arg) {
                    super.visit(call, arg);

                    String methodName = call.getNameAsString();

                    // Check for RestTemplate calls
                    if (REST_TEMPLATE_METHODS.contains(methodName)) {
                        HttpCallInfo info = parseRestTemplateCall(call, className, currentMethod, packageName);
                        if (info != null) {
                            httpCalls.add(info);
                        }
                    }

                    // Check for WebClient calls (more complex, need to trace method chain)
                    if (WEB_CLIENT_METHODS.contains(methodName) || methodName.equals("uri")) {
                        HttpCallInfo info = parseWebClientCall(call, className, currentMethod, packageName);
                        if (info != null) {
                            httpCalls.add(info);
                        }
                    }
                }
            }, null);

        } catch (IOException e) {
            LOG.log(Level.WARNING, "Error scanning file: " + filePath, e);
            return ScanResult.failure("IO error: " + e.getMessage(), getScannerName());
        }

        long duration = System.currentTimeMillis() - startTime;

        return ScanResult.<HttpCallInfo>builder()
                .success(true)
                .items(httpCalls)
                .foundCount(httpCalls.size())
                .scannedCount(1)
                .durationMs(duration)
                .scannerType(getScannerName())
                .build();
    }

    @Override
    public ScanResult<HttpCallInfo> scanFiles(List<Path> filePaths, GlobalAnalysisCache globalCache) {
        long startTime = System.currentTimeMillis();
        List<HttpCallInfo> allCalls = new ArrayList<>();
        int scannedCount = 0;

        for (Path filePath : filePaths) {
            if (canScan(filePath)) {
                ScanResult<HttpCallInfo> result = scanFile(filePath, globalCache);
                if (result.isSuccess() && result.getItems() != null) {
                    allCalls.addAll(result.getItems());
                }
                scannedCount++;
            }
        }

        long duration = System.currentTimeMillis() - startTime;

        // Update global cache
        updateGlobalCache(allCalls, globalCache);

        return ScanResult.<HttpCallInfo>builder()
                .success(true)
                .items(allCalls)
                .foundCount(allCalls.size())
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
        return Collections.emptySet();
    }

    /**
     * Parse RestTemplate method call
     */
    private HttpCallInfo parseRestTemplateCall(MethodCallExpr call, String className,
                                                String methodName, String packageName) {
        String callMethod = call.getNameAsString();
        String httpMethod = inferHttpMethod(callMethod);

        // Try to extract URL from arguments
        String url = extractUrlFromArguments(call);

        return HttpCallInfo.builder()
                .clientType("RestTemplate")
                .sourceClass(className)
                .sourceMethod(className + "." + methodName)
                .httpMethod(httpMethod)
                .url(url)
                .packageName(packageName)
                .build();
    }

    /**
     * Parse WebClient method call
     */
    private HttpCallInfo parseWebClientCall(MethodCallExpr call, String className,
                                             String methodName, String packageName) {
        // WebClient is more complex - typically method chain like:
        // webClient.get().uri("/path").retrieve()
        String httpMethod = "GET"; // Default
        String url = null;

        String callMethod = call.getNameAsString();
        if (callMethod.equals("uri")) {
            url = extractUrlFromArguments(call);
        }

        return HttpCallInfo.builder()
                .clientType("WebClient")
                .sourceClass(className)
                .sourceMethod(className + "." + methodName)
                .httpMethod(httpMethod)
                .url(url)
                .packageName(packageName)
                .build();
    }

    /**
     * Extract URL from method arguments
     */
    private String extractUrlFromArguments(MethodCallExpr call) {
        if (call.getArguments().isEmpty()) {
            return null;
        }

        String arg = call.getArguments().get(0).toString();

        // Clean up the URL string
        arg = arg.replace("\"", "");

        // Check if it's a string literal
        if (arg.startsWith("\"") && arg.endsWith("\"")) {
            arg = arg.substring(1, arg.length() - 1);
        }

        return arg;
    }

    /**
     * Infer HTTP method from RestTemplate method name
     */
    private String inferHttpMethod(String methodName) {
        if (methodName.startsWith("get")) return "GET";
        if (methodName.startsWith("post")) return "POST";
        if (methodName.startsWith("put")) return "PUT";
        if (methodName.startsWith("delete")) return "DELETE";
        if (methodName.startsWith("patch")) return "PATCH";
        if (methodName.startsWith("head")) return "HEAD";
        if (methodName.startsWith("options")) return "OPTIONS";
        return "GET"; // Default
    }

    /**
     * Update global cache with scanned HTTP calls
     */
    private void updateGlobalCache(List<HttpCallInfo> httpCalls, GlobalAnalysisCache globalCache) {
        for (HttpCallInfo call : httpCalls) {
            if (call.getUrl() == null || call.getUrl().isEmpty()) continue;

            String key = call.getSourceMethod() + "|" + call.getHttpMethod() + "|" + call.getUrl();
            globalCache.getRestEndpointMap().put(key, call.getSourceMethod());
        }
    }

    }