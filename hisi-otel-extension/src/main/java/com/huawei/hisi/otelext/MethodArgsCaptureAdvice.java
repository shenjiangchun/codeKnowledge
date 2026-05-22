package com.huawei.hisi.otelext;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;

/**
 * 完全 self-contained advice: 所有逻辑都内联在 onEnter / onExit 方法体内,
 * 不引用本类的任何静态字段或私有方法。这是为了避免 inlined advice 里残留
 * {@code GETSTATIC MethodArgsCaptureAdvice.PROBED} / {@code INVOKESTATIC
 * MethodArgsCaptureAdvice.safeToString} 之类的跨类引用 —— 在没有跑
 * muzzle-codegen 的 Maven 构建里,helper 注入路径未必能把本类放进目标
 * classloader, 那时跨类引用就抛 {@code NoClassDefFoundError}。
 * <p>
 * 关键限制:
 *  - 必须是 static 方法
 *  - OTel API 类 (Span / AttributeKey / SpanContext) 由 OTel agent 放到
 *    bootstrap classloader, 对目标类可见, 因此可以安全引用
 *  - 不引用本扩展自己的其它类 / 静态字段 / 私有方法
 */
public final class MethodArgsCaptureAdvice {

    private MethodArgsCaptureAdvice() {}

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.AllArguments Object[] args) {
        if (args == null || args.length == 0) {
            return;
        }
        Span span = Span.current();
        SpanContext ctx = span.getSpanContext();
        if (!ctx.isValid()) {
            return;
        }
        for (int i = 0; i < args.length; i++) {
            // ---- inline safeToString ----
            Object o = args[i];
            String s;
            if (o == null) {
                s = "null";
            } else {
                try {
                    if (o instanceof CharSequence) {
                        s = ((CharSequence) o).toString();
                    } else if (o instanceof java.util.Collection) {
                        java.util.Collection<?> c = (java.util.Collection<?>) o;
                        s = "Collection(size=" + c.size() + ")=" + c;
                    } else if (o instanceof java.util.Map) {
                        java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                        s = "Map(size=" + m.size() + ")=" + m;
                    } else if (o.getClass().isArray()) {
                        if (o instanceof Object[]) s = java.util.Arrays.deepToString((Object[]) o);
                        else if (o instanceof int[]) s = java.util.Arrays.toString((int[]) o);
                        else if (o instanceof long[]) s = java.util.Arrays.toString((long[]) o);
                        else if (o instanceof double[]) s = java.util.Arrays.toString((double[]) o);
                        else if (o instanceof boolean[]) s = java.util.Arrays.toString((boolean[]) o);
                        else if (o instanceof byte[]) s = "byte[" + ((byte[]) o).length + "]";
                        else if (o instanceof char[]) s = new String((char[]) o);
                        else s = o.toString();
                    } else {
                        s = String.valueOf(o);
                    }
                    if (s.length() > 2048) {
                        s = s.substring(0, 2048) + "...<truncated " + (s.length() - 2048) + " chars>";
                    }
                } catch (Throwable t) {
                    s = "<toString failed: " + t.getClass().getSimpleName() + ">";
                }
            }
            // ---- end inline ----
            span.setAttribute(AttributeKey.stringKey("code.input." + i), s);
        }
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(@Advice.Return(typing = Assigner.Typing.DYNAMIC) Object returnValue,
                              @Advice.Thrown Throwable thrown) {
        Span span = Span.current();
        SpanContext ctx = span.getSpanContext();
        if (!ctx.isValid()) {
            return;
        }
        if (thrown != null) {
            String msg = thrown.getMessage();
            String safeMsg = msg == null ? "null" : (msg.length() > 2048
                    ? msg.substring(0, 2048) + "...<truncated " + (msg.length() - 2048) + " chars>"
                    : msg);
            span.setAttribute(AttributeKey.stringKey("code.exception"),
                    thrown.getClass().getName() + ": " + safeMsg);
            return;
        }
        if (returnValue == null) {
            return;
        }
        // ---- inline safeToString (same logic as above) ----
        Object o = returnValue;
        String s;
        try {
            if (o instanceof CharSequence) {
                s = ((CharSequence) o).toString();
            } else if (o instanceof java.util.Collection) {
                java.util.Collection<?> c = (java.util.Collection<?>) o;
                s = "Collection(size=" + c.size() + ")=" + c;
            } else if (o instanceof java.util.Map) {
                java.util.Map<?, ?> m = (java.util.Map<?, ?>) o;
                s = "Map(size=" + m.size() + ")=" + m;
            } else if (o.getClass().isArray()) {
                if (o instanceof Object[]) s = java.util.Arrays.deepToString((Object[]) o);
                else if (o instanceof int[]) s = java.util.Arrays.toString((int[]) o);
                else if (o instanceof long[]) s = java.util.Arrays.toString((long[]) o);
                else if (o instanceof double[]) s = java.util.Arrays.toString((double[]) o);
                else if (o instanceof boolean[]) s = java.util.Arrays.toString((boolean[]) o);
                else if (o instanceof byte[]) s = "byte[" + ((byte[]) o).length + "]";
                else if (o instanceof char[]) s = new String((char[]) o);
                else s = o.toString();
            } else {
                s = String.valueOf(o);
            }
            if (s.length() > 2048) {
                s = s.substring(0, 2048) + "...<truncated " + (s.length() - 2048) + " chars>";
            }
        } catch (Throwable t) {
            s = "<toString failed: " + t.getClass().getSimpleName() + ">";
        }
        // ---- end inline ----
        span.setAttribute(AttributeKey.stringKey("code.output"), s);
    }
}
