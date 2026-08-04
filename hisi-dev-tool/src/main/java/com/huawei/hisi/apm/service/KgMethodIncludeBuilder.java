package com.huawei.hisi.apm.service;

import com.huawei.hisi.neo4j.model.MethodNode;
import com.huawei.hisi.neo4j.repository.Neo4jMethodNodeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds the {@code OTEL_INSTRUMENTATION_METHODS_INCLUDE} environment value
 * by traversing the KG callee tree from a given entry method.
 *
 * <p>OpenTelemetry Java agent does not auto-instrument arbitrary
 * {@code @Service}/{@code @Repository}/{@code @Component} methods. To capture
 * a method-level span tree that matches the KG call chain we need to enumerate
 * the methods up-front and pass them via the {@code methods} instrumentation:
 * {@code com.foo.Bar[m1,m2];com.baz.Qux[m3]}.
 *
 * <p>Empty / blank entry id returns an empty string — the caller should treat
 * that as "do not set the env var" so the agent falls back to defaults.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KgMethodIncludeBuilder {

    /** Default callee tree traversal depth. */
    public static final int DEFAULT_MAX_DEPTH = 5;

    /** Per-class method cap to avoid pathological include strings. */
    private static final int MAX_METHODS_PER_CLASS = 32;

    /** Total method cap to keep agent startup cost bounded. */
    private static final int MAX_TOTAL_METHODS = 256;

    /**
     * Class prefixes we never want to instrument — JDK, Spring framework, common libs.
     * Project code rarely lives under these; instrumenting them explodes the bytecode budget.
     */
    private static final List<String> FRAMEWORK_PREFIXES = List.of(
            "java.", "javax.", "jakarta.", "sun.", "com.sun.",
            "org.springframework.", "org.apache.", "org.slf4j.", "org.hibernate.",
            "io.netty.", "io.opentelemetry.", "com.fasterxml.", "lombok."
    );

    /**
     * OTel agent's {@code MethodsConfigurationParser} validates entries against
     * a strict {@code package.Class$Name[method1,method2]} grammar — class &
     * method identifiers must match {@code [A-Za-z_][A-Za-z0-9_]*}. Any single
     * malformed entry causes the parser to LOG A WARN and DROP THE WHOLE LIST,
     * so we must aggressively sanitize before joining.
     */
    private static final java.util.regex.Pattern VALID_JAVA_IDENT =
            java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    /**
     * Convert a source-level FQN like {@code com.foo.Outer.Inner.Inner2} into the
     * bytecode FQN {@code com.foo.Outer$Inner$Inner2}. Heuristic: once we hit a
     * PascalCase segment, every following segment is treated as a nested class.
     * Generics / arrays / whitespace are stripped first.
     */
    static String toBytecodeClassName(String sourceFqn) {
        if (sourceFqn == null) return null;
        String s = sourceFqn.trim();
        // strip generics & arrays
        int lt = s.indexOf('<');
        if (lt >= 0) s = s.substring(0, lt);
        while (s.endsWith("[]")) s = s.substring(0, s.length() - 2);
        if (s.isEmpty()) return null;
        String[] parts = s.split("\\.");
        StringBuilder out = new StringBuilder();
        boolean enteredClass = false;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) return null;
            if (!enteredClass) {
                if (out.length() > 0) out.append('.');
                out.append(p);
                if (Character.isUpperCase(p.charAt(0))) {
                    enteredClass = true;
                }
            } else {
                // nested class — join with $
                out.append('$').append(p);
            }
        }
        return out.toString();
    }

    /**
     * Validate that every segment of the (already $-joined) class name and the
     * method name are legal Java identifiers — rejects synthetic methods like
     * {@code lambda$0}, {@code access$000}, {@code <init>} and edge cases where
     * a generated name accidentally slips through.
     */
    static boolean isValidIdentifierPair(String bytecodeClass, String method) {
        if (bytecodeClass == null || method == null) return false;
        // method must be a plain ident (no <init>, <clinit>, lambda$, access$, etc.)
        if (!VALID_JAVA_IDENT.matcher(method).matches()) return false;
        // each .-segment and each $-segment must be a plain ident
        for (String pkgSeg : bytecodeClass.split("\\.")) {
            if (pkgSeg.isEmpty()) return false;
            for (String classSeg : pkgSeg.split("\\$")) {
                if (!VALID_JAVA_IDENT.matcher(classSeg).matches()) return false;
            }
        }
        return true;
    }

    private final Neo4jMethodNodeRepository methodNodeRepository;

    /**
     * Build the include string for the entry method's downstream callees.
     *
     * @param entryNodeId KG nodeId of the entry method (controller method)
     * @param maxDepth    callee traversal depth (use {@link #DEFAULT_MAX_DEPTH} if &le; 0)
     * @return formatted include string, or empty string if input is missing / no callees
     */
    public String build(String entryNodeId, int maxDepth) {
        if (entryNodeId == null || entryNodeId.isBlank()) {
            return "";
        }
        int depth = maxDepth > 0 ? maxDepth : DEFAULT_MAX_DEPTH;

        List<MethodNode> nodes = new ArrayList<>();
        try {
            Optional<MethodNode> entry = methodNodeRepository.findByNodeId(entryNodeId);
            entry.ifPresent(nodes::add);
            nodes.addAll(methodNodeRepository.findCalleesUpToDepth(entryNodeId, depth));
        } catch (Exception e) {
            log.warn("[KgInclude] Failed to load callee tree for entryNodeId={}: {}",
                    entryNodeId, e.getMessage());
            return "";
        }

        if (nodes.isEmpty()) {
            log.info("[KgInclude] No callee methods found for entryNodeId={}", entryNodeId);
            return "";
        }

        // Group by className, preserve insertion order, dedupe methods per class.
        Map<String, Set<String>> byClass = new LinkedHashMap<>();
        int total = 0;
        int skippedInvalid = 0;
        for (MethodNode mn : nodes) {
            String cn = mn.getClassName();
            String mname = mn.getMethodName();
            if (cn == null || cn.isBlank() || mname == null || mname.isBlank()) {
                continue;
            }
            if (isFrameworkClass(cn)) {
                continue;
            }
            // Skip constructors / static init — OTel agent rejects "<init>" in methods include
            if (mname.startsWith("<")) {
                continue;
            }
            // Skip lifecycle bootstrap methods (main / Initializer.initialize / etc.) —
            // these span entire process lifetime and would absorb all real request traces.
            if (isLifecycleBootstrapMethod(cn, mname)) {
                continue;
            }
            // Convert source FQN (com.foo.Outer.Inner) -> bytecode FQN (com.foo.Outer$Inner)
            String bytecodeClass = toBytecodeClassName(cn);
            // Reject synthetic methods (lambda$, access$, etc.) and any malformed identifiers —
            // OTel parser drops the WHOLE list on a single bad entry.
            if (!isValidIdentifierPair(bytecodeClass, mname)) {
                skippedInvalid++;
                continue;
            }
            Set<String> methods = byClass.computeIfAbsent(bytecodeClass, k -> new LinkedHashSet<>());
            if (methods.size() >= MAX_METHODS_PER_CLASS) {
                continue;
            }
            if (methods.add(mname)) {
                total++;
                if (total >= MAX_TOTAL_METHODS) {
                    log.info("[KgInclude] Reached total method cap ({}), truncating include list",
                            MAX_TOTAL_METHODS);
                    break;
                }
            }
        }
        if (skippedInvalid > 0) {
            log.info("[KgInclude] Skipped {} entries with invalid identifiers (synthetic/malformed)",
                    skippedInvalid);
        }

        if (byClass.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Set<String>> e : byClass.entrySet()) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(e.getKey()).append('[');
            boolean firstM = true;
            for (String m : e.getValue()) {
                if (!firstM) {
                    sb.append(',');
                }
                firstM = false;
                sb.append(m);
            }
            sb.append(']');
        }
        String result = sb.toString();
        log.info("[KgInclude] Built include string: {} classes, {} methods (entryNodeId={})",
                byClass.size(), total, entryNodeId);
        log.debug("[KgInclude] include = {}", result);
        return result;
    }

    /** Convenience overload using the default depth. */
    public String build(String entryNodeId) {
        return build(entryNodeId, DEFAULT_MAX_DEPTH);
    }

    /**
     * Build the include string for ALL project methods (full-project bytecode
     * instrumentation), filtering framework classes and non-instrumentable
     * methods (constructors, static init, accessors when {@code includeAccessors}
     * is false).
     *
     * @param projectPath     project root path used to scope methods in the KG
     * @param includeAccessors when false, getter/setter/equals/hashCode/toString
     *                         are excluded to keep the include string lean
     * @return formatted include string, or empty string if project has no methods
     */
    public String buildFullProject(String projectPath, boolean includeAccessors) {
        if (projectPath == null || projectPath.isBlank()) {
            return "";
        }
        List<MethodNode> nodes;
        try {
            nodes = methodNodeRepository.findByProjectPathWithoutRelationships(projectPath);
        } catch (Exception e) {
            log.warn("[KgInclude] Failed to load methods for projectPath={}: {}",
                    projectPath, e.getMessage());
            return "";
        }
        if (nodes == null || nodes.isEmpty()) {
            log.info("[KgInclude] No methods found for projectPath={}", projectPath);
            return "";
        }

        Map<String, Set<String>> byClass = new LinkedHashMap<>();
        int total = 0;
        int skippedAccessor = 0;
        int skippedInvalid = 0;
        for (MethodNode mn : nodes) {
            String cn = mn.getClassName();
            String mname = mn.getMethodName();
            if (cn == null || cn.isBlank() || mname == null || mname.isBlank()) {
                continue;
            }
            if (isFrameworkClass(cn)) {
                continue;
            }
            if (mname.startsWith("<")) {
                continue;
            }
            if (isLifecycleBootstrapMethod(cn, mname)) {
                continue;
            }
            if (!includeAccessors && isAccessorOrCommon(mname)) {
                skippedAccessor++;
                continue;
            }
            String bytecodeClass = toBytecodeClassName(cn);
            if (!isValidIdentifierPair(bytecodeClass, mname)) {
                skippedInvalid++;
                continue;
            }
            Set<String> methods = byClass.computeIfAbsent(bytecodeClass, k -> new LinkedHashSet<>());
            if (methods.size() >= MAX_METHODS_PER_CLASS) {
                continue;
            }
            if (methods.add(mname)) {
                total++;
            }
        }
        if (byClass.isEmpty()) {
            return "";
        }
        String result = formatInclude(byClass);
        log.info("[KgInclude] Built FULL_PROJECT include: {} classes, {} methods, skipped {} accessors, {} invalid (projectPath={})",
                byClass.size(), total, skippedAccessor, skippedInvalid, projectPath);
        log.debug("[KgInclude] full include = {}", result);
        return result;
    }

    private static final Set<String> ACCESSOR_LIKE = Set.of(
            "equals", "hashCode", "toString", "clone", "finalize",
            "canEqual", "compareTo"
    );

    private static boolean isAccessorOrCommon(String mname) {
        if (ACCESSOR_LIKE.contains(mname)) {
            return true;
        }
        // getXxx / setXxx / isXxx with capitalized 4th char
        if ((mname.startsWith("get") || mname.startsWith("set")) && mname.length() > 3
                && Character.isUpperCase(mname.charAt(3))) {
            return true;
        }
        if (mname.startsWith("is") && mname.length() > 2
                && Character.isUpperCase(mname.charAt(2))) {
            return true;
        }
        return false;
    }

    private static String formatInclude(Map<String, Set<String>> byClass) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, Set<String>> e : byClass.entrySet()) {
            if (!first) {
                sb.append(';');
            }
            first = false;
            sb.append(e.getKey()).append('[');
            boolean firstM = true;
            for (String m : e.getValue()) {
                if (!firstM) {
                    sb.append(',');
                }
                firstM = false;
                sb.append(m);
            }
            sb.append(']');
        }
        return sb.toString();
    }

    private static boolean isFrameworkClass(String className) {
        for (String p : FRAMEWORK_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 启动期/长生命周期方法黑名单 —— 这些方法 span 会贯穿整个进程或整个 Spring 启动阶段,
     * 一旦被纳入 methods include,所有真实请求路径都会被串到它下面,导致 trace 摘要里
     * 出现 50s 的 "main" 耗时。必须排除。
     */
    private static boolean isLifecycleBootstrapMethod(String className, String methodName) {
        // Application main entry — bootstrap span 持续整个进程生命周期
        if ("main".equals(methodName)) {
            return true;
        }
        // Spring 启动监听器 / 初始化器(ApplicationReadyEvent 处理器等)
        if (className.endsWith("Initializer") || className.endsWith("Bootstrap")
                || className.endsWith("Application")) {
            if ("initialize".equals(methodName) || "init".equals(methodName)
                    || "start".equals(methodName) || "run".equals(methodName)
                    || "onApplicationEvent".equals(methodName)
                    || "verifyInitialization".equals(methodName)) {
                return true;
            }
        }
        // @PostConstruct / @EventListener(ApplicationReadyEvent) 常用名
        if ("onApplicationReady".equals(methodName)
                || "onContextRefreshed".equals(methodName)) {
            return true;
        }
        return false;
    }
}
