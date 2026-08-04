package com.huawei.hisi.otelext;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * OTel Java agent extension entry point.
 * <p>
 * 通过 SPI 注册到 agent classloader (见
 * META-INF/services/io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule)。
 * <p>
 * 仅在 OTEL_INSTRUMENTATION_METHODS_INCLUDE 非空时生效:目标类型由该环境变量驱动,
 * 实际增强逻辑见 {@link MethodArgsCaptureInstrumentation}。
 */
@AutoService(InstrumentationModule.class)
public final class MethodArgsCaptureInstrumentationModule extends InstrumentationModule {

    public MethodArgsCaptureInstrumentationModule() {
        // 命名与下方 SPI 文件无关,仅用于日志/排序
        super("hisi-method-args-capture");
    }

    @Override
    public List<TypeInstrumentation> typeInstrumentations() {
        return Collections.singletonList(new MethodArgsCaptureInstrumentation());
    }

    /**
     * 必须比 OTel 内置 methods instrumentation 的 order(0)更低,这样我们的 advice
     * 才会被 ByteBuddy 注册为 INNERMOST,onEnter 才会在内置 advice 打开 span scope
     * 之后执行 —— 此时 {@link io.opentelemetry.api.trace.Span#current()} 返回的才是
     * 当前方法对应的新 span,我们写入的 {@code code.input.*}/{@code code.output}
     * 才会落到正确的 span 上。
     * <p>
     * 历史版本设为 1000(以为"after"是数值大),反而让我们的 advice 包在最外层,
     * 进入时 scope 还没开 → {@code Span.current()} 取到父 span → 所有中间方法的
     * 入参/返回值都写错了对象,显示出来就是"intermediate span 全是空白"。
     */
    @Override
    public int order() {
        return -1000;
    }

    /**
     * OTel 2.x 默认 {@code isIndyModule()=true},走 invokedynamic 注入路径 ——
     * 该路径依赖 muzzle-codegen 生成的 helper 索引,而我们是纯 Maven 构建,
     * 没跑该 Gradle 插件。强制走 legacy 注入路径,让 {@link #getAdditionalHelperClassNames()}
     * 与 {@link #isHelperClass(String)} 真正生效。
     */
    @Override
    public boolean isIndyModule() {
        return false;
    }

    /**
     * 显式列出本扩展所有 helper 类,确保 agent 把它们从扩展 JAR 注入到目标类的
     * classloader。少一个都会报 {@code NoClassDefFoundError}。
     */
    @Override
    public List<String> getAdditionalHelperClassNames() {
        return Arrays.asList(
                "com.huawei.hisi.otelext.MethodArgsCaptureAdvice");
    }

    /** legacy 注入路径会调用此方法二次确认 helper 归属。 */
    @Override
    public boolean isHelperClass(String className) {
        return className != null && className.startsWith("com.huawei.hisi.otelext.");
    }
}

