package com.huawei.hisi.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.stmt.ExpressionStmt;
import com.github.javaparser.symbolsolver.JavaSymbolSolver;
import com.github.javaparser.symbolsolver.resolution.typesolvers.CombinedTypeSolver;
import com.huawei.hisi.cache.GlobalAnalysisCache;
import com.huawei.hisi.config.AnalysisFeatureConfig;
import com.huawei.hisi.knowledgegraph.model.CallTarget;
import com.huawei.hisi.knowledgegraph.util.KnowledgeGraphCommonUtils;
import com.huawei.hisi.neo4j.model.EntryPointNode;
import com.huawei.hisi.knowledgegraph.util.MethodBodyCompressor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 代码分析核心服务
 * 提供调用链分析和知识图谱共用的核心方法
 *
 * 设计原则：
 * 1. 保持与 HisiURIMethodChainToDBServiceImpl 的逻辑一致
 * 2. 提供可复用的分析方法
 * 3. 使用 GlobalAnalysisCache 共享分析结果
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CodeAnalysisCoreService {

    private final GlobalAnalysisCache globalCache;
    private final AnalysisFeatureConfig featureConfig;

    // HTTP入口注解
    private static final Set<String> HTTP_ANNOTATIONS = Set.of(
        "RequestMapping", "GetMapping", "PostMapping", "PutMapping",
        "DeleteMapping", "PatchMapping"
    );

    /**
     * 扫描时统一排除的目录名（避免把构建产物 / IDE / VCS / git worktree 中其他分支代码扫进图谱）。
     * 特别注意：`.worktrees` 必须排除 —— git worktree 子目录是其它分支的独立 checkout，
     * 把它扫进来会导致幽灵入口点（不存在于当前分支但出现在 KG 中）。
     */
    public static final Set<String> EXCLUDED_SCAN_DIRS = Set.of(
        ".git", ".worktrees", ".idea", ".vscode", ".claude", ".codeai",
        "target", "build", "out", "dist", "node_modules", "generated-sources"
    );

    /**
     * 查找项目中的所有Java源文件根目录
     */
    public List<Path> findSourceRoots(Path projectPath) {
        List<Path> sourceRoots = new ArrayList<>();

        try {
            Files.walkFileTree(projectPath, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    String dirName = dir.getFileName().toString();
                    // 跳过常见的非源码目录（含 .worktrees / IDE / 构建产物）
                    if (EXCLUDED_SCAN_DIRS.contains(dirName)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    // 识别源码根目录
                    if (dirName.equals("java")) {
                        sourceRoots.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (Exception e) {
            log.warn("扫描源码根目录失败: {}", e.getMessage());
        }

        // 如果没找到，使用项目根目录
        if (sourceRoots.isEmpty()) {
            sourceRoots.add(projectPath);
        }

        return sourceRoots;
    }

    /**
     * 查找项目中的所有Java文件（使用默认屏蔽目录）
     */
    public List<File> findJavaFiles(String projectPath) {
        return findJavaFiles(projectPath, null);
    }

    /**
     * 查找项目中的所有Java文件，支持自定义屏蔽目录
     * @param projectPath 项目根路径
     * @param excludePaths 屏蔽目录片段列表（相对项目根，支持片段匹配，如 "src/test/" 或 "target"）；
     *                    传 null 或空列表时使用默认（target, build）
     */
    public List<File> findJavaFiles(String projectPath, List<String> excludePaths) {
        List<String> effective = (excludePaths == null || excludePaths.isEmpty())
                ? new ArrayList<>(EXCLUDED_SCAN_DIRS)
                : excludePaths;

        try {
            return Files.walk(Paths.get(projectPath))
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !KnowledgeGraphCommonUtils.shouldExclude(p.toString(), effective))
                .map(Path::toFile)
                .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("扫描Java文件失败", e);
            return Collections.emptyList();
        }
    }

    /**
     * 创建带符号解析的JavaParser
     */
    public JavaParser createJavaParser(CombinedTypeSolver solver) {
        ParserConfiguration config = new ParserConfiguration()
            .setSymbolResolver(new JavaSymbolSolver(solver))
            .setAttributeComments(false);
        return new JavaParser(config);
    }

    /**
     * 解析Java文件
     */
    public CompilationUnit parseFile(File file, JavaParser parser) {
        try (FileInputStream fis = new FileInputStream(file)) {
            return parser.parse(fis).getResult().orElse(null);
        } catch (Exception e) {
            log.warn("解析文件失败: {}", file.getPath(), e);
            return null;
        }
    }

    /**
     * 扫描HTTP入口点 - 复用调用链分析的逻辑
     * 返回 Map<URI, 方法签名>
     */
    public Map<String, String> scanHttpEntryPoints(CompilationUnit cu) {
        Map<String, String> entryPoints = new HashMap<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ?
                clazz.getNameAsString() :
                packageName + "." + clazz.getNameAsString();

            // 获取类级别的路径
            String classPath = extractPathFromClassAnnotations(clazz);

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    String annotationName = annotation.getNameAsString();
                    if (HTTP_ANNOTATIONS.contains(annotationName)) {
                        String methodPath = extractPathFromAnnotation(annotation);
                        String fullPath = combinePaths(classPath, methodPath);
                        String methodSignature = className + "." + method.getNameAsString();

                        entryPoints.put(fullPath, methodSignature);
                    }
                }
            });
        });

        return entryPoints;
    }

    /**
     * 创建EntryPoint列表 - 供知识图谱使用
     */
    public List<EntryPointNode> createEntryPoints(CompilationUnit cu, String projectPath) {
        List<EntryPointNode> entryPoints = new ArrayList<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty() ?
                clazz.getNameAsString() :
                packageName + "." + clazz.getNameAsString();

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                for (AnnotationExpr annotation : method.getAnnotations()) {
                    String annotationName = annotation.getNameAsString();
                    String type = determineEntryPointType(annotationName);
                    if (type != null) {
                        String nodeId = generateNodeId(projectPath, className, method);
                        String entryKey = extractPathFromAnnotation(annotation);
                        String entryInfo = extractEntryInfo(annotation);
                        // entryId 格式: projectPath:type_className.methodName
                        String entryId = projectPath + ":" + type + "_" + className + "." + method.getNameAsString();

                        entryPoints.add(EntryPointNode.builder()
                            .entryId(entryId)
                            .entryType(type)
                            .entryKey(entryKey)
                            .entryInfo(entryInfo)
                            .projectPath(projectPath)
                            .methodNodeId(nodeId)
                            .build());
                    }
                }
            });
        });

        return entryPoints;
    }

    /**
     * 解析方法调用 - 核心方法，复用调用链分析的逻辑
     * 这是最复杂的方法，涉及：
     * 1. this 调用解析
     * 2. 字段调用解析（需要类型推断）
     * 3. 静态方法调用解析
     * 4. 接口实现查找
     */
    public List<MethodDeclaration> findMethodCallTargets(
            MethodCallExpr methodCall,
            ClassOrInterfaceDeclaration clazz,
            MethodDeclaration currentMethod,
            JavaParser javaParser) {

        List<MethodDeclaration> results = new ArrayList<>();
        String methodName = methodCall.getNameAsString();
        String scopeName = methodCall.getScope().map(Object::toString).orElse("");

        if (featureConfig.isDebugLogging()) {
            log.debug("[findMethodCallTargets] 开始解析: methodName={}, scopeName={}, className={}",
                methodName, scopeName, clazz.getNameAsString());
        }

        // 1. this 或无 scope 的调用 - 查找当前类的方法
        if (!methodCall.getScope().isPresent() ||
            methodCall.getScope().get().toString().equals("this")) {

            List<MethodDeclaration> localMethods = clazz.getMethods()
                .stream()
                .filter(m -> m.getName().asString().equals(methodCall.getName().asString()))
                .collect(Collectors.toList());

            if (!localMethods.isEmpty()) {
                results.addAll(localMethods);
                if (featureConfig.isDebugLogging()) {
                    log.debug("[findMethodCallTargets] 找到 this/无scope 调用: {} 个方法", localMethods.size());
                }
                return results;
            }
        }

        // 2. 静态导入的静态方法调用
        if (!methodCall.getScope().isPresent()) {
            List<MethodDeclaration> staticImports = resolveStaticImportCall(methodCall, clazz);
            if (!staticImports.isEmpty()) {
                results.addAll(staticImports);
            }
        }

        // 3. 字段调用 xxx.method()
        List<MethodDeclaration> fieldCalls = resolveFieldCall(methodCall, clazz, scopeName);
        results.addAll(fieldCalls);
        if (featureConfig.isDebugLogging() && !fieldCalls.isEmpty()) {
            log.debug("[findMethodCallTargets] 字段调用找到 {} 个方法", fieldCalls.size());
        }

        // 4. Spring Bean 调用 (Jalor.getContext().getBean)
        List<MethodDeclaration> beanCalls = resolveBeanCall(methodCall, clazz, currentMethod, javaParser);
        results.addAll(beanCalls);

        // 5. 参数/局部变量/字段的类型推断
        if (results.isEmpty()) {
            String resolvedType = inferTypeFromScope(scopeName, currentMethod, clazz);
            if (resolvedType != null) {
                if (featureConfig.isDebugLogging()) {
                    log.debug("[findMethodCallTargets] 类型推断: scopeName={} -> resolvedType={}", scopeName, resolvedType);
                }
                List<MethodDeclaration> typeMethods = resolveMethodByType(methodCall, resolvedType);
                results.addAll(typeMethods);
            }
        }

        if (featureConfig.isDebugLogging()) {
            log.debug("[findMethodCallTargets] 解析完成: methodName={}, 找到 {} 个方法", methodName, results.size());
        }

        return results;
    }

    /**
     * 解析方法调用目标 - 增强版，支持 Mapper/Repository/MQ/Feign
     *
     * @param methodCall   方法调用表达式
     * @param clazz        当前类声明
     * @param currentMethod 当前方法声明
     * @param javaParser   Java解析器
     * @param cache        全局分析缓存
     * @param projectPath  项目路径
     * @return 调用目标列表
     */
    public List<CallTarget> findMethodCallTargetsEnhanced(
            MethodCallExpr methodCall,
            ClassOrInterfaceDeclaration clazz,
            MethodDeclaration currentMethod,
            JavaParser javaParser,
            GlobalAnalysisCache cache,
            String projectPath) {

        String scopeName = methodCall.getScope().map(Object::toString).orElse("");
        String methodName = methodCall.getNameAsString();

        // 1. 检查是否是 Mapper 调用
        if (cache.getMyBatisMapperMap().containsKey(scopeName)) {
            String sqlId = scopeName + "." + methodName;
            log.debug("识别到 Mapper 调用: {}", sqlId);
            return List.of(CallTarget.mapper(scopeName, methodName, sqlId));
        }

        // 2. 检查是否是 JPA Repository 调用
        if (cache.getJpaRepositoryMap().containsKey(scopeName)) {
            log.debug("识别到 JPA Repository 调用: {}.{}", scopeName, methodName);
            return List.of(CallTarget.jpaRepository(scopeName, methodName));
        }

        // 3. 检查是否是 FeignClient 调用
        CallTarget feignTarget = resolveFeignCall(scopeName, methodName, cache);
        if (feignTarget != null) {
            log.debug("识别到 Feign Client 调用: {} -> {}", scopeName, methodName);
            return List.of(feignTarget);
        }

        // 4. 检查是否是 MQ Producer 调用
        CallTarget mqTarget = resolveMQProducerCall(methodCall, currentMethod, cache);
        if (mqTarget != null) {
            log.debug("识别到 MQ Producer 调用: {}", mqTarget.getTopic());
            return List.of(mqTarget);
        }

        // 5. 检查是否是 HTTP 调用
        CallTarget httpTarget = resolveHttpCall(methodCall, cache);
        if (httpTarget != null) {
            log.debug("识别到 HTTP 调用: {}", httpTarget.getServiceName());
            return List.of(httpTarget);
        }

        // 6. 默认：普通方法调用
        List<MethodDeclaration> methods = findMethodCallTargets(methodCall, clazz, currentMethod, javaParser);
        return methods.stream()
            .map(m -> {
                String className = getMethodClassName(m, clazz);
                return CallTarget.direct(className, m.getNameAsString());
            })
            .collect(Collectors.toList());
    }

    /**
     * 检查是否是 MQ 生产者调用
     * 支持 Kafka/RabbitMQ/RocketMQ/JMS
     *
     * @param call 方法调用表达式
     * @return 如果是 MQ 生产者调用返回 true
     */
    public boolean isMQProducerCall(MethodCallExpr call) {
        String methodName = call.getNameAsString();
        String scopeName = call.getScope().map(Object::toString).orElse("").toLowerCase();

        // Kafka: kafkaTemplate.send()
        if (scopeName.contains("kafkatemplate") &&
            (methodName.equals("send") || methodName.equals("sendDefault") ||
             methodName.equals("sendAsync") || methodName.equals("sendDeferred"))) {
            return true;
        }

        // RabbitMQ: rabbitTemplate.convertAndSend(), rabbitTemplate.send()
        if (scopeName.contains("rabbittemplate") &&
            (methodName.equals("send") || methodName.equals("convertAndSend") ||
             methodName.equals("convertAndSendAsType"))) {
            return true;
        }

        // RocketMQ: rocketMQTemplate.send(), rocketMQTemplate.asyncSend()
        if (scopeName.contains("rocketmqtemplate") &&
            (methodName.equals("send") || methodName.equals("asyncSend") ||
             methodName.equals("sendOneWay") || methodName.equals("sendSync"))) {
            return true;
        }

        // JMS: jmsTemplate.send(), jmsTemplate.convertAndSend()
        if (scopeName.contains("jmstemplate") &&
            (methodName.equals("send") || methodName.equals("convertAndSend"))) {
            return true;
        }

        return false;
    }

    /**
     * 检查是否是 HTTP 调用
     * 支持 RestTemplate/WebClient/OkHttp/Apache HttpClient
     *
     * @param call 方法调用表达式
     * @return 如果是 HTTP 调用返回 true
     */
    public boolean isHttpCall(MethodCallExpr call) {
        String methodName = call.getNameAsString();
        String scopeName = call.getScope().map(Object::toString).orElse("").toLowerCase();

        // RestTemplate: getForObject, postForObject, exchange, execute
        if (scopeName.contains("resttemplate") &&
            (methodName.equals("getForObject") || methodName.equals("getForEntity") ||
             methodName.equals("postForObject") || methodName.equals("postForEntity") ||
             methodName.equals("put") || methodName.equals("delete") ||
             methodName.equals("exchange") || methodName.equals("execute"))) {
            return true;
        }

        // WebClient: get(), post(), put(), delete(), patch(), head(), options()
        if (scopeName.contains("webclient") ||
            scopeName.contains(".get()") || scopeName.contains(".post()") ||
            scopeName.contains(".put()") || scopeName.contains(".delete()")) {
            return true;
        }

        // OkHttp: newCall()
        if (scopeName.contains("okhttpclient") && methodName.equals("newCall")) {
            return true;
        }

        // Apache HttpClient: execute()
        if ((scopeName.contains("httpclient") || scopeName.contains("closeablehttpclient")) &&
            methodName.equals("execute")) {
            return true;
        }

        return false;
    }

    /**
     * 提取 MQ Topic/Queue 名称
     *
     * @param call          方法调用表达式
     * @param currentMethod 当前方法（用于查找局部变量）
     * @return Topic/Queue 名称，如果无法提取返回 null
     */
    public String extractTopic(MethodCallExpr call, MethodDeclaration currentMethod) {
        String methodName = call.getNameAsString();
        String scopeName = call.getScope().map(Object::toString).orElse("").toLowerCase();

        // Kafka: kafkaTemplate.send(topic, ...)
        if (scopeName.contains("kafkatemplate") && methodName.equals("send")) {
            return extractFirstStringArgument(call);
        }

        // RabbitMQ: rabbitTemplate.convertAndSend(exchange, routingKey, ...)
        //          rabbitTemplate.send(exchange, routingKey, ...)
        if (scopeName.contains("rabbittemplate")) {
            // 对于 RabbitMQ，优先返回 exchange+routingKey 组合
            String exchange = extractFirstStringArgument(call);
            String routingKey = extractNthStringArgument(call, 2);
            if (exchange != null && routingKey != null) {
                return exchange + ":" + routingKey;
            }
            return exchange;
        }

        // RocketMQ: rocketMQTemplate.send(destination, ...)
        if (scopeName.contains("rocketmqtemplate") &&
            (methodName.equals("send") || methodName.equals("asyncSend"))) {
            return extractFirstStringArgument(call);
        }

        // JMS: jmsTemplate.send(destination, ...)
        if (scopeName.contains("jmstemplate") && methodName.equals("send")) {
            return extractFirstStringArgument(call);
        }

        return null;
    }

    /**
     * 提取 HTTP URI
     *
     * @param call 方法调用表达式
     * @return URI 字符串，如果无法提取返回 null
     */
    public String extractUri(MethodCallExpr call) {
        String methodName = call.getNameAsString();
        String scopeName = call.getScope().map(Object::toString).orElse("").toLowerCase();

        // RestTemplate 方法通常第一个参数是 URL
        if (scopeName.contains("resttemplate")) {
            return extractFirstStringArgument(call);
        }

        // WebClient 的 URI 通常在 uri() 方法中
        if (scopeName.contains("webclient")) {
            // 需要分析调用链，这里简化处理
            return extractFirstStringArgument(call);
        }

        return null;
    }

    // ============================================================
    // 私有辅助方法 - 桥接调用解析
    // ============================================================

    /**
     * 解析 Feign Client 调用
     */
    private CallTarget resolveFeignCall(String scopeName, String methodName, GlobalAnalysisCache cache) {
        if (scopeName == null || cache == null) {
            return null;
        }

        // 检查是否是 FeignClient 接口
        Map<String, Set<String>> feignClientMap = cache.getFeignClientMap();
        if (feignClientMap.containsKey(scopeName)) {
            // 查找对应的 URI
            Map<String, String> feignUriIndex = cache.getFeignUriIndex();

            // 遍历 feignUriIndex 查找匹配的方法
            for (Map.Entry<String, String> entry : feignUriIndex.entrySet()) {
                String key = entry.getKey();
                if (key.contains(scopeName) && key.contains(methodName)) {
                    // 找到匹配的 Feign 调用
                    return CallTarget.feignClient(scopeName, methodName, extractServiceNameFromKey(key));
                }
            }

            // 没有找到 URI 索引，但仍是 Feign 调用
            return CallTarget.feignClient(scopeName, methodName, scopeName);
        }

        return null;
    }

    /**
     * 解析 MQ 生产者调用
     */
    private CallTarget resolveMQProducerCall(MethodCallExpr call, MethodDeclaration currentMethod, GlobalAnalysisCache cache) {
        if (!isMQProducerCall(call)) {
            return null;
        }

        String scopeName = call.getScope().map(Object::toString).orElse("");
        String methodName = call.getNameAsString();
        String topic = extractTopic(call, currentMethod);

        if (topic != null) {
            // 记录到 MQ Producer 索引
            String methodSignature = currentMethod.findCompilationUnit()
                .map(cu -> cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + ".")
                    .orElse("") + currentMethod.getNameAsString())
                .orElse(currentMethod.getNameAsString());

            cache.getMqProducerIndex()
                .computeIfAbsent(topic, k -> new java.util.concurrent.CopyOnWriteArrayList<>())
                .add(methodSignature);

            return CallTarget.mqProducer(scopeName, methodName, topic);
        }

        return null;
    }

    /**
     * 解析 HTTP 调用
     */
    private CallTarget resolveHttpCall(MethodCallExpr call, GlobalAnalysisCache cache) {
        if (!isHttpCall(call)) {
            return null;
        }

        String scopeName = call.getScope().map(Object::toString).orElse("");
        String methodName = call.getNameAsString();
        String uri = extractUri(call);

        // 从 URI 中提取服务名（简化处理）
        String serviceName = extractServiceNameFromUri(uri);

        return CallTarget.httpClient(scopeName, methodName, serviceName);
    }

    /**
     * 从 Feign URI 索引键中提取服务名
     */
    private String extractServiceNameFromKey(String key) {
        if (key == null) return null;
        String[] parts = key.split("\\|");
        return parts.length > 0 ? parts[0] : key;
    }

    /**
     * 从 URI 中提取服务名
     */
    private String extractServiceNameFromUri(String uri) {
        if (uri == null || uri.isEmpty()) {
            return "unknown";
        }
        try {
            // 尝试从 URL 中提取主机名作为服务名
            if (uri.startsWith("http://") || uri.startsWith("https://")) {
                int start = uri.indexOf("://") + 3;
                int end = uri.indexOf("/", start);
                if (end == -1) end = uri.length();
                String host = uri.substring(start, end);
                int colonIndex = host.indexOf(":");
                if (colonIndex > 0) {
                    return host.substring(0, colonIndex);
                }
                return host;
            }
        } catch (Exception e) {
            log.debug("无法从 URI 提取服务名: {}", uri);
        }
        return "unknown";
    }

    /**
     * 提取方法调用的第一个字符串参数
     */
    private String extractFirstStringArgument(MethodCallExpr call) {
        return extractNthStringArgument(call, 1);
    }

    /**
     * 提取方法调用的第 N 个字符串参数
     */
    private String extractNthStringArgument(MethodCallExpr call, int n) {
        if (call.getArguments().size() < n) {
            return null;
        }

        try {
            var arg = call.getArguments().get(n - 1);
            if (arg.isStringLiteralExpr()) {
                return arg.asStringLiteralExpr().getValue();
            }
            // 尝试处理变量引用（简化处理）
            return arg.toString().replace("\"", "");
        } catch (Exception e) {
            log.debug("无法提取第 {} 个字符串参数: {}", n, call);
            return null;
        }
    }

    /**
     * 获取方法所属的类名
     */
    private String getMethodClassName(MethodDeclaration method, ClassOrInterfaceDeclaration defaultClass) {
        return method.findCompilationUnit()
            .map(cu -> {
                String pkg = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString() + ".")
                    .orElse("");
                // 查找方法所属的类
                return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                    .filter(c -> c.getMethods().stream().anyMatch(m -> m.equals(method)))
                    .findFirst()
                    .map(c -> pkg + c.getNameAsString())
                    .orElse(pkg + defaultClass.getNameAsString());
            })
            .orElse(defaultClass.getNameAsString());
    }

    /**
     * 压缩方法体 - 复用已实现的方法
     */
    public String compressMethodBody(MethodDeclaration method) {
        return MethodBodyCompressor.compress(method);
    }

    /**
     * 构建接口-实现映射
     */
    public void buildImplementationMap(CompilationUnit cu) {
        // 根据配置选择新旧逻辑
        if (featureConfig.isEnhancedInterfaceResolution()) {
            buildImplementationMapEnhanced(cu);
        } else {
            buildImplementationMapLegacy(cu);
        }
    }

    /**
     * 【旧逻辑】接口实现映射构建 - 保留原有实现
     * 使用简单名称匹配，可能存在跨包接口识别问题
     */
    private void buildImplementationMapLegacy(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = packageName.isEmpty() ?
                classDecl.getNameAsString() :
                packageName + "." + classDecl.getNameAsString();

            // 接口也要处理 extends 关系（interface B extends interface A）
            if (classDecl.isInterface()) {
                classDecl.getExtendedTypes().forEach(extendedType -> {
                    String parentName = extendedType.getNameAsString();
                    parentName = resolveFullTypeName(parentName, cu);

                    // 接口继承存入 implementationMap（子接口"实现"了父接口的方法）
                    globalCache.getImplementationMap()
                        .computeIfAbsent(parentName, k -> ConcurrentHashMap.newKeySet())
                        .add(className);

                    log.debug("[ImplMap] Interface extends: child='{}' -> parent='{}'",
                        className, parentName);
                });
                return; // 接口不处理 implements
            }

            // 处理实现的接口
            classDecl.getImplementedTypes().forEach(implementedType -> {
                String rawName = implementedType.getNameAsString();
                String interfaceName = resolveFullTypeName(rawName, cu);

                globalCache.getImplementationMap()
                    .computeIfAbsent(interfaceName, k -> ConcurrentHashMap.newKeySet())
                    .add(className);
            });

            // 处理继承的父类
            classDecl.getExtendedTypes().forEach(extendedType -> {
                String parentName = extendedType.getNameAsString();
                parentName = resolveFullTypeName(parentName, cu);

                globalCache.getExtendMap()
                    .computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet())
                    .add(parentName);
            });
        });
    }

    /**
     * 【新逻辑】增强版接口实现映射构建
     * 同时存储简单名称和完整限定名作为键，确保查找时能匹配
     */
    private void buildImplementationMapEnhanced(CompilationUnit cu) {
        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(classDecl -> {
            String className = packageName.isEmpty() ?
                classDecl.getNameAsString() :
                packageName + "." + classDecl.getNameAsString();

            // 接口也要处理 extends 关系
            if (classDecl.isInterface()) {
                classDecl.getExtendedTypes().forEach(extendedType -> {
                    String simpleName = extendedType.getNameAsString();
                    String fullName = resolveFullTypeName(simpleName, cu);

                    // 同时存储简单名和 FQN 作为 key（Enhanced 模式特性）
                    globalCache.getImplementationMap()
                        .computeIfAbsent(simpleName, k -> ConcurrentHashMap.newKeySet())
                        .add(className);
                    if (!simpleName.equals(fullName)) {
                        globalCache.getImplementationMap()
                            .computeIfAbsent(fullName, k -> ConcurrentHashMap.newKeySet())
                            .add(className);
                    }

                    if (featureConfig.isDebugLogging()) {
                        log.debug("[EnhancedImpl] Interface {} extends: simpleName={}, fullName={}",
                            className, simpleName, fullName);
                    }
                });
                return;
            }

            // 提取 bean 名称并存入 beanNameMap（用于 @Qualifier 解析）
            classDecl.getAnnotations().forEach(anno -> {
                String annoName = anno.getNameAsString();
                if ("Component".equals(annoName) || "Service".equals(annoName) ||
                    "Repository".equals(annoName) || "Controller".equals(annoName) ||
                    "RestController".equals(annoName)) {
                    // 提取注解值作为显式 bean 名称
                    if (anno.isSingleMemberAnnotationExpr()) {
                        try {
                            String explicitName = anno.asSingleMemberAnnotationExpr()
                                .getMemberValue().asStringLiteralExpr().asString();
                            if (!explicitName.isBlank()) {
                                globalCache.getBeanNameMap().put(explicitName, className);
                            }
                        } catch (Exception ignored) {
                            // 非字符串值，跳过
                        }
                    }
                    // 始终存储默认 bean 名称（类名首字母小写）
                    String simpleName = classDecl.getNameAsString();
                    String defaultBeanName = Character.toLowerCase(simpleName.charAt(0)) + simpleName.substring(1);
                    globalCache.getBeanNameMap().put(defaultBeanName, className);
                }
            });

            // 处理实现的接口
            classDecl.getImplementedTypes().forEach(implementedType -> {
                String simpleName = implementedType.getNameAsString();
                String fullName = resolveFullTypeName(simpleName, cu);

                if (featureConfig.isDebugLogging()) {
                    log.debug("[EnhancedImpl] 类 {} 实现接口: simpleName={}, fullName={}",
                        className, simpleName, fullName);
                }

                // 同时存储简单名称和完整限定名作为键
                // 这样无论查找时使用哪种格式都能匹配
                globalCache.getImplementationMap()
                    .computeIfAbsent(simpleName, k -> ConcurrentHashMap.newKeySet())
                    .add(className);

                if (!simpleName.equals(fullName)) {
                    globalCache.getImplementationMap()
                        .computeIfAbsent(fullName, k -> ConcurrentHashMap.newKeySet())
                        .add(className);
                }
            });

            // 处理继承的父类
            classDecl.getExtendedTypes().forEach(extendedType -> {
                String simpleName = extendedType.getNameAsString();
                String fullName = resolveFullTypeName(simpleName, cu);

                globalCache.getExtendMap()
                    .computeIfAbsent(className, k -> ConcurrentHashMap.newKeySet())
                    .add(fullName);
            });
        });
    }

    // ============================================================
    // 私有辅助方法
    // ============================================================

    private String determineEntryPointType(String annotationName) {
        if (HTTP_ANNOTATIONS.contains(annotationName)) return EntryPointNode.TYPE_HTTP;
        if ("Scheduled".equals(annotationName)) return EntryPointNode.TYPE_SCHEDULED;
        if (Set.of("RabbitListener", "KafkaListener", "RocketMQMessageListener").contains(annotationName))
            return EntryPointNode.TYPE_MQ_CONSUMER;
        if (Set.of("EventListener", "TransactionalEventListener").contains(annotationName))
            return "EVENT";
        if (Set.of("OnMessage", "ServerEndpoint", "OnOpen", "OnClose").contains(annotationName))
            return "WEBSOCKET";
        if (Set.of("DubboService", "FeignClient", "GrpcService", "RpcService").contains(annotationName))
            return EntryPointNode.TYPE_GRPC;
        if (Set.of("PostConstruct", "PreDestroy", "AfterConstruct").contains(annotationName))
            return "LIFECYCLE";
        return null;
    }

    /**
     * 生成节点 ID
     * 格式: projectPath:className.methodName.signatureHash
     */
    private String generateNodeId(String projectPath, String className, MethodDeclaration method) {
        String methodId = className + "." + method.getNameAsString() + "." +
            signatureHash(method.getSignature().toString());
        return projectPath + ":" + methodId;
    }

    private static String signatureHash(String signature) {
        return Integer.toHexString(signature.hashCode());
    }

    private String extractPathFromClassAnnotations(ClassOrInterfaceDeclaration clazz) {
        for (AnnotationExpr annotation : clazz.getAnnotations()) {
            String path = extractPathFromAnnotation(annotation);
            if (!path.isEmpty()) {
                return path;
            }
        }
        return "";
    }

    private String extractPathFromAnnotation(AnnotationExpr annotation) {
        if (annotation instanceof SingleMemberAnnotationExpr) {
            SingleMemberAnnotationExpr single = (SingleMemberAnnotationExpr) annotation;
            return single.getMemberValue().toString().replace("\"", "");
        }
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            return normal.getPairs().stream()
                .filter(p -> "value".equals(p.getNameAsString()) || "path".equals(p.getNameAsString()))
                .findFirst()
                .map(p -> p.getValue().toString().replace("\"", ""))
                .orElse("");
        }
        return "";
    }

    private String extractEntryInfo(AnnotationExpr annotation) {
        if (annotation instanceof NormalAnnotationExpr) {
            NormalAnnotationExpr normal = (NormalAnnotationExpr) annotation;
            Map<String, String> info = new HashMap<>();
            normal.getPairs().forEach(pair ->
                info.put(pair.getNameAsString(), pair.getValue().toString())
            );
            return info.toString();
        }
        return "";
    }

    private String combinePaths(String classPath, String methodPath) {
        if (classPath.isEmpty()) return methodPath;
        if (methodPath.isEmpty()) return classPath;
        return classPath + methodPath;
    }

    private String resolveFullTypeName(String typeName, CompilationUnit cu) {
        // 如果已经是 FQN，直接返回
        if (typeName.contains(".")) {
            return typeName;
        }

        // 1. 精确 import 匹配（import com.example.UserService）
        String fullName = cu.getImports().stream()
            .filter(importDecl -> !importDecl.isAsterisk())
            .filter(importDecl -> {
                String importName = importDecl.getNameAsString();
                return importName.endsWith("." + typeName) || importName.equals(typeName);
            })
            .map(importDecl -> importDecl.getNameAsString())
            .findFirst()
            .orElse(null);

        if (fullName != null) {
            return fullName;
        }

        // 2. 通配符 import 匹配（import com.example.common.*）
        List<String> wildcardPackages = cu.getImports().stream()
            .filter(importDecl -> importDecl.isAsterisk() && !importDecl.isStatic())
            .map(importDecl -> importDecl.getNameAsString())
            .toList();

        if (!wildcardPackages.isEmpty()) {
            // 使用第一个通配符包作为候选
            // Enhanced 模式会额外存储简单名作为 key 来兜底
            String resolved = wildcardPackages.get(0) + "." + typeName;
            return resolved;
        }

        // 3. 无 import，使用当前包名构造
        String pkgResolved = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString() + "." + typeName)
            .orElse(typeName);
        return pkgResolved;
    }

    private List<MethodDeclaration> resolveStaticImportCall(MethodCallExpr methodCall, ClassOrInterfaceDeclaration clazz) {
        List<MethodDeclaration> results = new ArrayList<>();

        clazz.findCompilationUnit().ifPresent(cu -> {
            cu.getImports().stream()
                .filter(i -> i.toString().contains("import static"))
                .forEach(importDecl -> {
                    String fullName = importDecl.getNameAsString();
                    if (fullName.contains(methodCall.getNameAsString())) {
                        String className = removeLastDotAndAfter(fullName);
                        List<MethodDeclaration> methods = resolveMethodByType(methodCall, className);
                        results.addAll(methods);
                    }
                });
        });

        return results;
    }

    /**
     * 解析字段上的方法调用
     * 根据配置选择新旧逻辑
     */
    private List<MethodDeclaration> resolveFieldCall(MethodCallExpr methodCall, ClassOrInterfaceDeclaration clazz, String scopeName) {
        if (featureConfig.isEnhancedFieldCallResolution()) {
            return resolveFieldCallEnhanced(methodCall, clazz, scopeName);
        } else {
            return resolveFieldCallLegacy(methodCall, clazz, scopeName);
        }
    }

    /**
     * 【旧逻辑】解析字段调用 - 保留原有实现
     */
    private List<MethodDeclaration> resolveFieldCallLegacy(MethodCallExpr methodCall, ClassOrInterfaceDeclaration clazz, String scopeName) {
        List<MethodDeclaration> results = new ArrayList<>();

        clazz.findAll(FieldDeclaration.class).forEach(field -> {
            field.getVariables().stream()
                .filter(v -> v.getName().asString().equals(scopeName))
                .forEach(variable -> {
                    String fieldTypeName = variable.getType().asString();
                    String fullTypeName = resolveFieldTypeName(fieldTypeName, clazz);

                    List<MethodDeclaration> methods = resolveMethodByType(methodCall, fullTypeName);
                    results.addAll(methods);
                });
        });

        return results;
    }

    /**
     * 【新逻辑】增强版字段调用解析
     * 识别 @Autowired、@Lazy、@Resource 等注入字段，正确解析接口实现
     */
    private List<MethodDeclaration> resolveFieldCallEnhanced(MethodCallExpr methodCall, ClassOrInterfaceDeclaration clazz, String scopeName) {
        List<MethodDeclaration> results = new ArrayList<>();
        String methodName = methodCall.getNameAsString();

        clazz.findAll(FieldDeclaration.class).forEach(field -> {
            field.getVariables().stream()
                .filter(v -> v.getName().asString().equals(scopeName))
                .forEach(variable -> {
                    String fieldTypeName = variable.getType().asString();
                    String fullTypeName = resolveFieldTypeName(fieldTypeName, clazz);

                    if (featureConfig.isDebugLogging()) {
                        log.debug("[EnhancedFieldCall] 解析字段调用: scopeName={}, fieldType={}, fullType={}, methodName={}",
                            scopeName, fieldTypeName, fullTypeName, methodName);
                    }

                    // 检查是否有 Spring 注入注解
                    boolean isInjectedField = field.getAnnotations().stream()
                        .anyMatch(a -> {
                            String annoName = a.getNameAsString();
                            return "Autowired".equals(annoName) ||
                                   "Resource".equals(annoName) ||
                                   "Inject".equals(annoName) ||
                                   "Lazy".equals(annoName);
                        });

                    // 构造器注入检测：final 字段 + 类有构造函数接收该类型
                    if (!isInjectedField && field.isFinal()) {
                        isInjectedField = isConstructorInjectedField(clazz, variable);
                    }

                    if (isInjectedField && featureConfig.isDebugLogging()) {
                        log.debug("[EnhancedFieldCall] 字段 {} 有注入注解，将查找接口实现", scopeName);
                    }

                    // 分离接口类型和实现类类型，优先解析实现类，无实现时回退到接口
                    Set<String> interfaceTypes = new LinkedHashSet<>();
                    interfaceTypes.add(fullTypeName);
                    // 仅当简单名与FQN不同且FQN未通过import解析时，才添加简单名作为fallback
                    if (fieldTypeName.equals(fullTypeName)) {
                        // fieldTypeName 未被 import 解析过，本身可能就是简单名
                        interfaceTypes.add(fieldTypeName);
                    }

                    Set<String> implTypes = new LinkedHashSet<>();

                    if (isInjectedField) {
                        // 查找接口的所有实现类
                        for (String typeName : new ArrayList<>(interfaceTypes)) {
                            Set<String> implementations = globalCache.getImplementationMap().get(typeName);
                            if (implementations != null) {
                                implTypes.addAll(implementations);
                                if (featureConfig.isDebugLogging()) {
                                    log.debug("[EnhancedFieldCall] 接口 {} 的实现类: {}", typeName, implementations);
                                }
                            }
                        }

                        // @Qualifier 过滤：多实现时根据 bean 名称缩小范围
                        if (implTypes.size() > 1) {
                            Optional<String> qualifier = field.getAnnotations().stream()
                                .filter(a -> "Qualifier".equals(a.getNameAsString()))
                                .filter(a -> a.isSingleMemberAnnotationExpr())
                                .map(a -> {
                                    try {
                                        return a.asSingleMemberAnnotationExpr()
                                            .getMemberValue().asStringLiteralExpr().asString();
                                    } catch (Exception e) {
                                        return null;
                                    }
                                })
                                .filter(java.util.Objects::nonNull)
                                .findFirst();

                            if (qualifier.isPresent()) {
                                String qualifiedBean = globalCache.getBeanNameMap().get(qualifier.get());
                                if (qualifiedBean != null) {
                                    implTypes.clear();
                                    implTypes.add(qualifiedBean);
                                    if (featureConfig.isDebugLogging()) {
                                        log.debug("[EnhancedFieldCall] @Qualifier(\"{}\") 过滤到 bean: {}",
                                            qualifier.get(), qualifiedBean);
                                    }
                                }
                            }
                        }
                    }

                    // 第一阶段：优先尝试实现类方法（caller → impl，保持现有行为）
                    for (String typeName : implTypes) {
                        List<MethodDeclaration> methods = resolveMethodByType(methodCall, typeName);
                        if (!methods.isEmpty()) {
                            results.addAll(methods);
                            if (featureConfig.isDebugLogging()) {
                                log.debug("[EnhancedFieldCall] 通过实现类 {} 找到 {} 个方法", typeName, methods.size());
                            }
                        }
                    }

                    // 第二阶段：无实现类结果时，回退到接口本身（覆盖 @FeignClient 等无 impl 场景）
                    if (results.isEmpty()) {
                        log.info("[EnhancedFieldCall] Phase2接口回退: scopeName={}, interfaceTypes={}", scopeName, interfaceTypes);
                        for (String typeName : interfaceTypes) {
                            List<MethodDeclaration> methods = resolveMethodByType(methodCall, typeName);
                            if (!methods.isEmpty()) {
                                results.addAll(methods);
                                if (featureConfig.isDebugLogging()) {
                                    log.debug("[EnhancedFieldCall] 通过接口类型 {} 找到 {} 个方法（无实现类回退）", typeName, methods.size());
                                }
                            }
                        }
                    }
                });
        });

        return results;
    }

    private boolean isConstructorInjectedField(ClassOrInterfaceDeclaration clazz, VariableDeclarator variable) {
        String fieldType = variable.getType().asString();

        // 检查 Lombok 注解
        boolean hasLombokConstructor = clazz.getAnnotations().stream()
            .anyMatch(a -> {
                String name = a.getNameAsString();
                return "AllArgsConstructor".equals(name) ||
                       "RequiredArgsConstructor".equals(name);
            });
        if (hasLombokConstructor) return true;

        // 检查显式构造函数是否有匹配参数
        for (ConstructorDeclaration ctor : clazz.getConstructors()) {
            boolean hasMatchingParam = ctor.getParameters().stream()
                .anyMatch(p -> p.getType().asString().equals(fieldType));
            if (hasMatchingParam) return true;
        }

        return false;
    }

    private String resolveFieldTypeName(String simpleTypeName, ClassOrInterfaceDeclaration clazz) {
        return clazz.findCompilationUnit()
            .map(cu -> {
                // 1. 检查显式 import
                String importedName = cu.getImports().stream()
                    .filter(importDecl -> {
                        String importName = importDecl.getNameAsString();
                        return importName.endsWith("." + simpleTypeName);
                    })
                    .map(importDecl -> importDecl.getNameAsString())
                    .findFirst()
                    .orElse(null);

                if (importedName != null) {
                    return importedName;
                }

                // 2. 检查同包内的类型（当前类的包名 + 简单名称）
                String packageName = cu.getPackageDeclaration()
                    .map(pd -> pd.getNameAsString())
                    .orElse("");
                if (!packageName.isEmpty()) {
                    String fullTypeName = packageName + "." + simpleTypeName;
                    // 检查 implementationMap 中是否有该类型（接口或实现类）
                    if (globalCache.getImplementationMap().containsKey(fullTypeName) ||
                        globalCache.getImplementationMap().values().stream()
                            .anyMatch(impls -> impls.contains(fullTypeName))) {
                        if (featureConfig.isDebugLogging()) {
                            log.debug("[resolveFieldTypeName] 同包类型解析(implementationMap): {} -> {}", simpleTypeName, fullTypeName);
                        }
                        return fullTypeName;
                    }
                    // 检查 extendMap 中是否有该类型
                    if (globalCache.getExtendMap().containsKey(fullTypeName) ||
                        globalCache.getExtendMap().values().stream()
                            .anyMatch(exts -> exts.contains(fullTypeName))) {
                        if (featureConfig.isDebugLogging()) {
                            log.debug("[resolveFieldTypeName] 同包类型解析(extendMap): {} -> {}", simpleTypeName, fullTypeName);
                        }
                        return fullTypeName;
                    }
                }

                // 3. 尝试在 implementationMap 的 key 中查找匹配的类型
                for (String className : globalCache.getImplementationMap().keySet()) {
                    if (className.endsWith("." + simpleTypeName)) {
                        if (featureConfig.isDebugLogging()) {
                            log.debug("[resolveFieldTypeName] 全局缓存解析(implementationMap key): {} -> {}", simpleTypeName, className);
                        }
                        return className;
                    }
                }

                // 4. 如果同包名构建的完整名称看起来合理，直接返回它
                // 这样 TypeSolver 可以在同包路径下查找
                if (!packageName.isEmpty()) {
                    String fullTypeName = packageName + "." + simpleTypeName;
                    if (featureConfig.isDebugLogging()) {
                        log.debug("[resolveFieldTypeName] 尝试同包类型: {} -> {}", simpleTypeName, fullTypeName);
                    }
                    return fullTypeName;
                }

                return simpleTypeName;
            })
            .orElse(simpleTypeName);
    }

    private List<MethodDeclaration> resolveBeanCall(
            MethodCallExpr methodCall,
            ClassOrInterfaceDeclaration clazz,
            MethodDeclaration currentMethod,
            JavaParser javaParser) {

        List<MethodDeclaration> results = new ArrayList<>();

        List<ExpressionStmt> beanExpressions = currentMethod.findAll(ExpressionStmt.class)
            .stream()
            .filter(exp -> exp.toString().contains("Jalor.getContext().getBean") ||
                          exp.toString().contains("applicationContext.getBean"))
            .collect(Collectors.toList());

        String scopeName = methodCall.getScope().map(Object::toString).orElse("");

        for (ExpressionStmt expr : beanExpressions) {
            List<com.github.javaparser.ast.expr.SimpleName> names = expr.findAll(com.github.javaparser.ast.expr.SimpleName.class);
            if (names.size() >= 2) {
                String beanClassName = names.get(0).asString();
                String fieldName = names.get(1).asString();

                if (fieldName.equals(scopeName)) {
                    Path beanPath = globalCache.getBeanMap().get(beanClassName);
                    if (beanPath != null) {
                        try {
                            CompilationUnit beanCu = parseFile(beanPath.toFile(), javaParser);
                            if (beanCu != null) {
                                beanCu.findAll(MethodDeclaration.class).stream()
                                    .filter(m -> m.getName().asString().equals(methodCall.getName().asString()))
                                    .forEach(results::add);
                            }
                        } catch (Exception e) {
                            log.debug("解析Bean文件失败: {}", beanPath);
                        }
                    }
                }
            }
        }

        return results;
    }

    private String inferTypeFromScope(String scopeName, MethodDeclaration currentMethod, ClassOrInterfaceDeclaration clazz) {
        if (scopeName == null || scopeName.isEmpty()) {
            return null;
        }

        // 检查参数
        for (com.github.javaparser.ast.body.Parameter param : currentMethod.getParameters()) {
            if (param.getName().asString().equals(scopeName)) {
                return param.getType().asString();
            }
        }

        // 检查局部变量
        for (VariableDeclarator var : currentMethod.findAll(VariableDeclarator.class)) {
            if (var.getName().asString().equals(scopeName)) {
                return var.getType().asString();
            }
        }

        // 检查构造函数参数（支持构造器注入）
        for (ConstructorDeclaration ctor : clazz.getConstructors()) {
            for (com.github.javaparser.ast.body.Parameter param : ctor.getParameters()) {
                if (param.getName().asString().equals(scopeName)) {
                    return param.getType().asString();
                }
            }
        }

        // 检查字段
        for (FieldDeclaration field : clazz.findAll(FieldDeclaration.class)) {
            for (VariableDeclarator var : field.getVariables()) {
                if (var.getName().asString().equals(scopeName)) {
                    return var.getType().asString();
                }
            }
        }

        return null;
    }

    private List<MethodDeclaration> resolveMethodByType(MethodCallExpr methodCall, String typeName) {
        List<MethodDeclaration> results = new ArrayList<>();
        String methodName = methodCall.getNameAsString();

        if (featureConfig.isDebugLogging()) {
            log.debug("[resolveMethodByType] 开始解析: typeName={}, methodName={}", typeName, methodName);
        }

        // 第一层：FQN + FQN 对应的实现类
        Set<String> fqnClassNames = new LinkedHashSet<>();
        fqnClassNames.add(typeName);

        Set<String> implementations = globalCache.getImplementationMap().get(typeName);
        if (implementations != null) {
            fqnClassNames.addAll(implementations);
            if (featureConfig.isDebugLogging()) {
                log.debug("[resolveMethodByType] 用FQN {} 找到实现类: {}", typeName, implementations);
            }
        }

        // 第二层（fallback）：简单名对应的实现类
        Set<String> simpleNameClassNames = new LinkedHashSet<>();
        String simpleName = typeName.contains(".") ? typeName.substring(typeName.lastIndexOf(".") + 1) : typeName;
        if (!simpleName.equals(typeName)) {
            Set<String> implsBySimpleName = globalCache.getImplementationMap().get(simpleName);
            if (implsBySimpleName != null) {
                simpleNameClassNames.addAll(implsBySimpleName);
                // 排除已在 FQN 层中的类
                simpleNameClassNames.removeAll(fqnClassNames);
                if (featureConfig.isDebugLogging() && !simpleNameClassNames.isEmpty()) {
                    log.debug("[resolveMethodByType][SimpleName-Fallback] 用简单名 {} 找到额外实现类: {} (FQN层已有: {})",
                        simpleName, simpleNameClassNames, fqnClassNames);
                }
            }
        }

        // 使用 TypeSolver 查找方法
        CombinedTypeSolver solver = globalCache.getTypeSolver();
        if (solver == null) {
            if (featureConfig.isDebugLogging()) {
                log.debug("[resolveMethodByType] TypeSolver 为空，无法解析");
            }
            return results;
        }

        // 先用 FQN 层搜索
        Set<String> resolvedFQNs = new LinkedHashSet<>();
        try {
            java.lang.reflect.Field elementsField = CombinedTypeSolver.class.getDeclaredField("elements");
            elementsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            List<?> typeSolvers = (List<?>) elementsField.get(solver);

            for (Object typeSolver : typeSolvers) {
                if (typeSolver instanceof com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver) {
                    for (String className : fqnClassNames) {
                        // 已在某个 solver 中匹配过的 className 不再重复搜索
                        if (resolvedFQNs.contains(className)) continue;
                        try {
                            List<MethodDeclaration> found = resolveMethodsInTypeSolver(
                                (com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver) typeSolver,
                                className, methodName);
                            if (!found.isEmpty()) {
                                results.addAll(found);
                                resolvedFQNs.add(className);
                                // 接口方法匹配时始终打印 info（追踪跨模块同名误匹配）
                                if (className.contains("Feign") || className.contains("Mapper") || className.contains("Repository")) {
                                    log.info("[resolveMethodByType] FQN层命中接口方法: className={}, methodName={}, found={}",
                                        className, methodName, found.size());
                                }
                                if (featureConfig.isDebugLogging()) {
                                    log.debug("[resolveMethodByType] FQN层: 在 {} 中找到方法 {}: {} 个",
                                        className, methodName, found.size());
                                }
                            }
                        } catch (Exception e) {
                            if (featureConfig.isDebugLogging()) {
                                log.debug("[resolveMethodByType] FQN层: 解析 {} 失败: {}", className, e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("[resolveMethodByType] TypeSolver反射失败: {}", e.getMessage());
        }

        // FQN 层有结果时，跳过简单名 fallback
        if (!results.isEmpty()) {
            if (featureConfig.isDebugLogging()) {
                log.debug("[resolveMethodByType] FQN层已匹配 {} 个方法，跳过简单名fallback", results.size());
            }
        } else if (!simpleNameClassNames.isEmpty()) {
            // FQN 层无结果，尝试简单名 fallback
            if (featureConfig.isDebugLogging()) {
                log.info("[resolveMethodByType][SimpleName-Fallback] FQN层无结果，尝试简单名搜索: candidates={}", simpleNameClassNames);
            }
            try {
                java.lang.reflect.Field elementsField = CombinedTypeSolver.class.getDeclaredField("elements");
                elementsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<?> typeSolvers = (List<?>) elementsField.get(solver);

                for (Object typeSolver : typeSolvers) {
                    if (typeSolver instanceof com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver) {
                        for (String className : simpleNameClassNames) {
                            try {
                                List<MethodDeclaration> found = resolveMethodsInTypeSolver(
                                    (com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver) typeSolver,
                                    className, methodName);
                                if (!found.isEmpty()) {
                                    results.addAll(found);
                                    if (featureConfig.isDebugLogging()) {
                                        log.info("[resolveMethodByType][SimpleName-Fallback] 在 {} 中找到方法 {}: {} 个",
                                            className, methodName, found.size());
                                    }
                                }
                            } catch (Exception e) {
                                if (featureConfig.isDebugLogging()) {
                                    log.debug("[resolveMethodByType][SimpleName-Fallback] 解析 {} 失败: {}", className, e.getMessage());
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("[resolveMethodByType] SimpleName fallback TypeSolver反射失败: {}", e.getMessage());
            }
        }

        if (featureConfig.isDebugLogging()) {
            log.debug("[resolveMethodByType] 解析结果: typeName={}, 找到 {} 个方法", typeName, results.size());
        }

        return results;
    }

    /**
     * 在 TypeSolver 中解析指定类名的方法声明（自动处理类/接口两种情况）
     */
    private List<MethodDeclaration> resolveMethodsInTypeSolver(
            com.github.javaparser.symbolsolver.resolution.typesolvers.JavaParserTypeSolver typeSolver,
            String className,
            String methodName) throws Exception {
        try {
            var classDecl = (com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserClassDeclaration)
                typeSolver.solveType(className);
            java.lang.reflect.Field wrappedNodeField = classDecl.getClass().getDeclaredField("wrappedNode");
            wrappedNodeField.setAccessible(true);
            var classDeclaration = (ClassOrInterfaceDeclaration) wrappedNodeField.get(classDecl);
            return classDeclaration.getMethods().stream()
                .filter(m -> m.getName().asString().equals(methodName))
                .collect(Collectors.toList());
        } catch (ClassCastException cce) {
            var interfaceDecl = (com.github.javaparser.symbolsolver.javaparsermodel.declarations.JavaParserInterfaceDeclaration)
                typeSolver.solveType(className);
            java.lang.reflect.Field wrappedNodeField = interfaceDecl.getClass().getDeclaredField("wrappedNode");
            wrappedNodeField.setAccessible(true);
            var interfaceDeclaration = (ClassOrInterfaceDeclaration) wrappedNodeField.get(interfaceDecl);
            return interfaceDeclaration.getMethods().stream()
                .filter(m -> m.getName().asString().equals(methodName))
                .collect(Collectors.toList());
        }
    }

    /**
     * 检测目标方法是否有 Spring 代理注解（@Async、@Transactional），
     * 当 springAnnotationAware=true 时返回对应的代理调用类型。
     *
     * @param targetMethod 被调用的目标方法
     * @return "ASYNC_PROXY"、"TRANSACTIONAL_PROXY" 或 "DIRECT"
     */
    public String detectProxyCallType(MethodDeclaration targetMethod) {
        if (!featureConfig.isSpringAnnotationAware()) {
            return "DIRECT";
        }
        for (AnnotationExpr anno : targetMethod.getAnnotations()) {
            String name = anno.getNameAsString();
            if ("Async".equals(name)) {
                return "ASYNC_PROXY";
            }
            if ("Transactional".equals(name)) {
                return "TRANSACTIONAL_PROXY";
            }
        }
        return "DIRECT";
    }

    private String removeLastDotAndAfter(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        int lastDotIndex = input.lastIndexOf('.');
        if (lastDotIndex == -1) {
            return input;
        }
        return input.substring(0, lastDotIndex);
    }
}