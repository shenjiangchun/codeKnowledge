package com.huawei.hisi.otelext;

import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isSynthetic;
import static net.bytebuddy.matcher.ElementMatchers.isBridge;
import static net.bytebuddy.matcher.ElementMatchers.isTypeInitializer;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.not;

/**
 * 命中由 OTEL_INSTRUMENTATION_METHODS_INCLUDE 指定的目标类,对其 public 方法
 * 织入 {@link MethodArgsCaptureAdvice} 以捕获入参/返回值。
 * <p>
 * 注意:OTel 内置 methods instrumentation 已经会基于同一环境变量为这些方法创建 Span;
 * 我们在其之上再叠加一层 advice,只负责往 current span 写 code.input.* / code.output 属性。
 * <p>
 * 配置来源按优先级 fallback:
 *  1) 环境变量 OTEL_INSTRUMENTATION_METHODS_INCLUDE
 *  2) 系统属性 otel.instrumentation.methods.include
 *  3) -Dotel.javaagent.configuration-file 指向的 .properties 文件中的
 *     otel.instrumentation.methods.include 字段(后端在 include 字符串超过
 *     16KB 时会改走这条路径)
 */
final class MethodArgsCaptureInstrumentation implements TypeInstrumentation {

    private static final String METHODS_INCLUDE_ENV = "OTEL_INSTRUMENTATION_METHODS_INCLUDE";
    private static final String METHODS_INCLUDE_PROP = "otel.instrumentation.methods.include";
    private static final String CONFIG_FILE_PROP = "otel.javaagent.configuration-file";

    /** 解析 env: "pkg.A[m1,m2];pkg.B[m3]" -> {pkg.A, pkg.B} */
    private static final Set<String> TARGET_CLASSES = parseTargetClasses();

    private static Set<String> parseTargetClasses() {
        String raw = resolveRawInclude();
        if (raw == null || raw.isBlank()) {
            System.err.println("[hisi-otel-ext] No methods include found via env/sysprop/config-file; "
                    + "args capture disabled.");
            return Collections.emptySet();
        }
        Set<String> result = Arrays.stream(raw.split(";"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> {
                    int idx = s.indexOf('[');
                    return idx > 0 ? s.substring(0, idx).trim() : s;
                })
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        System.err.println("[hisi-otel-ext] Args capture armed for " + result.size() + " classes.");
        return result;
    }

    private static String resolveRawInclude() {
        String env = System.getenv(METHODS_INCLUDE_ENV);
        if (env != null && !env.isBlank()) {
            return env;
        }
        String prop = System.getProperty(METHODS_INCLUDE_PROP);
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        String configFile = System.getProperty(CONFIG_FILE_PROP);
        if (configFile != null && !configFile.isBlank()) {
            Path p = Paths.get(configFile);
            if (Files.isRegularFile(p)) {
                try {
                    Properties props = new Properties();
                    try (var reader = Files.newBufferedReader(p)) {
                        props.load(reader);
                    }
                    String v = props.getProperty(METHODS_INCLUDE_PROP);
                    if (v != null && !v.isBlank()) {
                        return v;
                    }
                } catch (IOException ioe) {
                    System.err.println("[hisi-otel-ext] Failed to read " + p + ": " + ioe);
                }
            }
        }
        return null;
    }

    @Override
    public ElementMatcher<TypeDescription> typeMatcher() {
        if (TARGET_CLASSES.isEmpty()) {
            // 返回一个永远不匹配的 matcher
            return named("__hisi_no_class_will_ever_match__");
        }
        return namedOneOf(TARGET_CLASSES.toArray(new String[0]));
    }

    @Override
    public void transform(TypeTransformer transformer) {
        // 不限可见性：OTel 内置 methods instrumentation 会基于 include 列表为
        // public/protected/private/package-private 方法都创建 Span,我们这层
        // advice 必须与其对齐,否则非 public 方法的 Span 拿不到 code.input.*。
        // 排除合成方法 / 桥接方法 / 构造器 / static initializer,避免 lambda$ / access$
        // 之类的胡乱命中。
        transformer.applyAdviceToMethod(
                isMethod()
                        .and(not(isConstructor()))
                        .and(not(isTypeInitializer()))
                        .and(not(isSynthetic()))
                        .and(not(isBridge()))
                        .and(not(named("toString")))
                        .and(not(named("hashCode")))
                        .and(not(named("equals"))),
                MethodArgsCaptureAdvice.class.getName());
    }
}
