# 子系统 A：采集 SDK 详细设计

> **模块名**：hisi-capture-spring-boot-starter
>
> **部署形态**：Spring Boot Starter + 二方件（发布到内部 Nexus，业务方通过 Maven 引入）
>
> **业务代码侵入**：零代码改动（除 @CaptureLog 可选注解）。线程池通过 BeanPostProcessor 自动包装，HTTP/@Async/@Scheduled/Feign 通过自动配置注入。
>
> **范围**：Java Spring（Spring Boot 2.x / 3.x 兼容）

---

## 1. 模块结构

```
hisi-capture-spring-boot-starter/
├── pom.xml
├── src/main/java/com/hisi/capture/
│   ├── autoconfig/
│   │   ├── CaptureAutoConfiguration.java              # 入口，@AutoConfiguration
│   │   ├── CaptureWebAutoConfiguration.java           # HTTP Filter
│   │   ├── CaptureAsyncAutoConfiguration.java         # @Async + TaskDecorator
│   │   ├── CaptureScheduledAutoConfiguration.java     # @Scheduled
│   │   ├── CaptureFeignAutoConfiguration.java         # Feign RequestInterceptor
│   │   ├── CaptureAopAutoConfiguration.java           # AOP 切面
│   │   ├── CaptureExceptionAutoConfiguration.java     # 异常增强
│   │   ├── CaptureCryptoAutoConfiguration.java        # 加密
│   │   └── CaptureTtlAutoConfiguration.java           # TTL 集成方式（决策 1）
│   ├── context/
│   │   ├── CaptureContext.java
│   │   ├── EntryContext.java
│   │   ├── Span.java
│   │   ├── FeignCall.java
│   │   ├── EntryType.java
│   │   └── CaptureContextHolder.java
│   ├── ingress/
│   │   ├── EntryIngressProvider.java                  # SPI 接口
│   │   ├── http/HttpCaptureFilter.java
│   │   ├── async/AsyncAspect.java
│   │   ├── async/CaptureTaskDecorator.java
│   │   ├── scheduled/ScheduledCaptureBeanPostProcessor.java
│   │   └── feign/CaptureFeignRequestInterceptor.java
│   ├── aop/
│   │   ├── CaptureAspect.java
│   │   └── CaptureLogAspect.java
│   ├── exception/
│   │   ├── CaptureExceptionEnricher.java
│   │   ├── CaptureControllerAdvice.java
│   │   ├── CaptureAsyncUncaughtExceptionHandler.java
│   │   ├── CaptureScheduledErrorHandler.java
│   │   ├── CaptureUncaughtExceptionHandler.java
│   │   └── SilentCatchDetector.java
│   ├── ttl/
│   │   ├── CaptureTtlBeanPostProcessor.java
│   │   └── CaptureTtlExecutors.java
│   ├── crypto/
│   │   ├── CaptureCrypto.java
│   │   ├── HybridEncryptor.java
│   │   ├── RsaOaepKeyPair.java
│   │   └── StaticKeyPairLoader.java
│   ├── format/
│   │   ├── CaptureFormatter.java
│   │   └── CapturePayload.java
│   ├── config/
│   │   ├── CaptureProperties.java
│   │   ├── CaptureTtlProperties.java
│   │   ├── CaptureCryptoProperties.java
│   │   └── CaptureScanProperties.java
│   └── util/
│       ├── SizeLimiter.java
│       └── SpanTruncator.java
├── src/main/resources/
│   ├── META-INF/spring/
│   │   └── org.springframework.boot.autoconfigure.AutoConfiguration.imports
│   └── META-INF/capture-public-key.pem
└── src/test/java/...
```

---

## 2. pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.hisi</groupId>
    <artifactId>hisi-capture-spring-boot-starter</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>jar</packaging>

    <properties>
        <java.version>8</java.version>  <!-- 兼容 SB 2.x(Java 8+) 和 3.x(Java 17+) -->
        <transmittable-thread-local.version>2.14.5</transmittable-thread-local.version>
    </properties>

    <dependencies>
        <!-- Spring Boot autoconfigure（不强制业务方版本，provided） -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-autoconfigure</artifactId>
            <scope>provided</scope>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>

        <!-- TTL：跨线程池上下文传播 -->
        <dependency>
            <groupId>com.alibaba</groupId>
            <artifactId>transmittable-thread-local</artifactId>
            <version>${transmittable-thread-local.version}</version>
        </dependency>

        <!-- Servlet API（HTTP Filter 用，provided） -->
        <dependency>
            <groupId>jakarta.servlet</groupId>
            <artifactId>jakarta.servlet-api</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- AOP -->
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-aop</artifactId>
            <scope>provided</scope>
        </dependency>

        <!-- Feign（可选，业务方有 Feign 才生效） -->
        <dependency>
            <groupId>org.springframework.cloud</groupId>
            <artifactId>spring-cloud-starter-openfeign</artifactId>
            <scope>provided</scope>
            <optional>true</optional>
        </dependency>

        <!-- 加密 -->
        <dependency>
            <groupId>org.bouncycastle</groupId>
            <artifactId>bcprov-jdk18on</artifactId>
            <version>1.78.1</version>
        </dependency>
    </dependencies>
</project>
```

---

## 3. 上下文数据结构

### 3.1 EntryType

```java
package com.hisi.capture.context;

public enum EntryType {
    HTTP,           // OncePerRequestFilter
    SCHEDULED,      // @Scheduled
    ASYNC,          // @Async
    FEIGN_INGRESS,  // 下游 Feign 调用进入（罕见，通常 HTTP 已覆盖）
    CUSTOM          // SPI 扩展
}
```

### 3.2 EntryContext（必打印）

```java
package com.hisi.capture.context;

import java.util.Map;

public class EntryContext {
    /** 入口标签：UUID，跨进程关联 */
    private String entryTag;
    /** 入口类型 */
    private EntryType entryType;
    /** 入口 URI（HTTP 是 path，@Scheduled 是 task name，Feign 是 url） */
    private String entryUri;
    /** 入参（脱敏 + 限大小 + 加密） */
    private Map<String, Object> params;
    /** 入口开始时间 */
    private long startMillis;

    public EntryContext(String entryTag, EntryType entryType, String entryUri,
                        Map<String, Object> params, long startMillis) {
        this.entryTag = entryTag;
        this.entryType = entryType;
        this.entryUri = entryUri;
        this.params = params;
        this.startMillis = startMillis;
    }

    public String getEntryTag() { return entryTag; }
    public EntryType getEntryType() { return entryType; }
    public String getEntryUri() { return entryUri; }
    public Map<String, Object> getParams() { return params; }
    public long getStartMillis() { return startMillis; }
}
```

### 3.3 Span（N+3 限制）

```java
package com.hisi.capture.context;

public class Span {
    /** 方法签名：ClassName.methodName(ParamType1,ParamType2) */
    private String methodSignature;
    /** 方法入参（限大小 + 加密） */
    private Object[] args;
    /** 方法返回值（限大小 + 加密） */
    private Object retVal;
    /** 抛出的异常（如有） */
    private Throwable exception;
    private long startMillis, endMillis;

    public Span(String methodSignature, long startMillis) {
        this.methodSignature = methodSignature;
        this.startMillis = startMillis;
    }

    // getters / setters 略
    public String getMethodSignature() { return methodSignature; }
    public Object[] getArgs() { return args; }
    public void setArgs(Object[] args) { this.args = args; }
    public Object getRetVal() { return retVal; }
    public void setRetVal(Object retVal) { this.retVal = retVal; }
    public Throwable getException() { return exception; }
    public void setException(Throwable exception) { this.exception = exception; }
    public long getStartMillis() { return startMillis; }
    public long getEndMillis() { return endMillis; }
    public void setEndMillis(long endMillis) { this.endMillis = endMillis; }
}
```

### 3.4 FeignCall

```java
package com.hisi.capture.context;

import java.util.Map;

public class FeignCall {
    private String url;
    private Map<String, Object> params;  // 加密
    private int status;
    private long duration;

    public FeignCall(String url, Map<String, Object> params, int status, long duration) {
        this.url = url;
        this.params = params;
        this.status = status;
        this.duration = duration;
    }

    // getters 略
}
```

### 3.5 CaptureContext

```java
package com.hisi.capture.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class CaptureContext {
    private EntryContext entry;
    /** span 栈，最大 50（栈深保护） */
    private final Deque<Span> spanStack = new ArrayDeque<>();
    private final List<FeignCall> feignCalls = new ArrayList<>();

    public EntryContext getEntry() { return entry; }
    public void setEntry(EntryContext entry) { this.entry = entry; }
    public Deque<Span> getSpanStack() { return spanStack; }
    public List<FeignCall> getFeignCalls() { return feignCalls; }

    public void pushSpan(Span span) { spanStack.push(span); }
    public Span popSpan() { return spanStack.poll(); }
    public void addFeignCall(FeignCall call) { feignCalls.add(call); }
}
```

### 3.6 CaptureContextHolder（TTL）

```java
package com.hisi.capture.context;

import com.alibaba.ttl.TransmittableThreadLocal;

public final class CaptureContextHolder {
    private static final TransmittableThreadLocal<CaptureContext> CTX = new TransmittableThreadLocal<>();

    public static CaptureContext get() { return CTX.get(); }
    public static void set(CaptureContext ctx) { CTX.set(ctx); }
    public static void clear() { CTX.remove(); }

    private CaptureContextHolder() {}
}
```

**为什么用 TTL 而非 InheritableThreadLocal**：InheritableThreadLocal 在线程池复用场景有脏数据问题（线程复用时继承的是创建时的父线程值，不是任务提交时的值）。TTL 通过 TtlRunnable 包装或 agent 字节码改写解决。

---

## 4. 入口层（5 入口 + SPI 预留）

### 4.1 SPI 接口（EntryIngressProvider）

```java
package com.hisi.capture.ingress;

import com.hisi.capture.context.EntryContext;
import com.hisi.capture.context.EntryType;

/**
 * SPI 扩展点：业务方自定义入口（gRPC / WebSocket / Netty 等）实现此接口，
 * 通过 META-INF/services 注册即可接入。
 *
 * MVP 不写实现，仅预留接口。
 */
public interface EntryIngressProvider {
    EntryType type();
    void setupEntry(EntryContext ctx);
    void clearEntry();
}
```

### 4.2 HTTP 入口（HttpCaptureFilter）

```java
package com.hisi.capture.ingress.http;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.OncePerRequestFilter;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.*;

@Component
public class HttpCaptureFilter extends OncePerRequestFilter {

    @Autowired
    private SizeLimiter sizeLimiter;

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        if (!shouldCapture(req)) {
            chain.doFilter(req, resp);
            return;
        }

        // 构造入口上下文
        String entryTag = UUID.randomUUID().toString();
        Map<String, Object> params = new HashMap<>();
        params.put("uri", req.getRequestURI());
        params.put("method", req.getMethod());
        params.put("headers", sanitizeHeaders(req));
        params.put("body", sizeLimiter.limitBody(req));

        EntryContext entry = new EntryContext(
            entryTag, EntryType.HTTP, req.getRequestURI(), params, System.currentTimeMillis());

        CaptureContext ctx = new CaptureContext();
        ctx.setEntry(entry);
        CaptureContextHolder.set(ctx);

        try {
            chain.doFilter(req, resp);
        } finally {
            CaptureContextHolder.clear();
        }
    }

    private boolean shouldCapture(HttpServletRequest req) {
        // URI 白名单 + 采样率判断（详见 §8 配置）
        return true;
    }

    private Map<String, String> sanitizeHeaders(HttpServletRequest req) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> names = req.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String value = req.getHeader(name);
            // 脱敏：Authorization / Cookie / X-Token 等
            if (name.toLowerCase().matches("authorization|cookie|x-token|x-auth")) {
                value = "***REDACTED***";
            }
            headers.put(name, value);
        }
        return headers;
    }
}
```

### 4.3 @Async 入口（AsyncAspect + TaskDecorator）

```java
package com.hisi.capture.ingress.async;

import com.hisi.capture.context.*;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.core.task.TaskDecorator;

import java.util.*;

@Aspect
@Component
public class AsyncAspect {

    @Autowired
    private SizeLimiter sizeLimiter;

    @Around("@annotation(org.springframework.scheduling.annotation.Async)")
    public Object captureAsync(ProceedingJoinPoint pjp) throws Throwable {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx == null) {
            // TaskDecorator 没装上，降级
            return pjp.proceed();
        }

        // 构造 @Async 入口上下文（如果上层没有 HTTP 入口，则新建；否则复用上层 entryTag）
        if (ctx.getEntry() == null) {
            String entryTag = UUID.randomUUID().toString();
            Map<String, Object> params = new HashMap<>();
            params.put("args", sizeLimiter.limitArgs(pjp.getArgs()));
            EntryContext entry = new EntryContext(
                entryTag, EntryType.ASYNC, pjp.getSignature().toShortString(),
                params, System.currentTimeMillis());
            ctx.setEntry(entry);
        }

        return pjp.proceed();
    }
}
```

```java
package com.hisi.capture.ingress.async;

import com.hisi.capture.context.*;
import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * TaskDecorator：@Async 线程池提交任务时包装 Runnable，
 * 把父线程的 CaptureContext 复制到子线程。
 *
 * TTL 在普通 ThreadPoolTaskExecutor 也能传播，但 TaskDecorator 是双保险：
 * 1. 如果业务方 ExecutorPoolConfig 没被 BeanPostProcessor 包装（决策 1 切到 agent/explicit），
 *    TaskDecorator 仍能传播；
 * 2. 如果业务方用了 Spring 默认 ThreadPoolTaskExecutor（@Async 默认），TaskDecorator 生效。
 */
@Component
public class CaptureTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        CaptureContext parent = CaptureContextHolder.get();
        return () -> {
            CaptureContextHolder.set(parent);
            try {
                runnable.run();
            } finally {
                CaptureContextHolder.clear();
            }
        };
    }
}
```

### 4.4 @Scheduled 入口（ScheduledCaptureBeanPostProcessor）

```java
package com.hisi.capture.ingress.scheduled;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * 包装 @Scheduled 任务：在 task 执行前后 setup/clear CaptureContext。
 *
 * 实现方式：postProcessAfterInitialization 时扫描 @Scheduled 方法，
 * 返回一个代理（或包装 Runnable）。
 *
 * 简化实现：通过 ScheduledTaskRegistrar 注册自定义 TaskScheduler。
 */
@Component
public class ScheduledCaptureBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        // 扫描 bean 中所有 @Scheduled 方法，返回包装后的代理
        // 实际实现见 ScheduledAnnotationBeanPostProcessor 的 customizationHook
        return bean;  // 简化：实际方案见下文 ScheduledErrorHandler
    }
}
```

**简化方案**：@Scheduled 异常抓取通过 ScheduledTaskRegistrar.setErrorHandler 注入，入口上下文在 ErrorHandler 触发时构造（因为没有 HTTP Filter 那样的入口拦截点）。详见 §5.2 CaptureScheduledErrorHandler。

### 4.5 Feign 出向（CaptureFeignRequestInterceptor）

```java
package com.hisi.capture.ingress.feign;

import com.hisi.capture.context.*;
import feign.RequestInterceptor;
import org.springframework.stereotype.Component;

/**
 * Feign 出向：在 header 注入 X-Hisi-Entry-Tag，下游服务收到后可关联回上游日志。
 *
 * 注意：中间 span 不跨进程复制（数据量太大），只传 entryTag 用于关联。
 */
@Component
public class CaptureFeignRequestInterceptor implements RequestInterceptor {

    @Override
    public void apply(feign.RequestTemplate template) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null && ctx.getEntry() != null) {
            template.header("X-Hisi-Entry-Tag", ctx.getEntry().getEntryTag());
        }
    }
}
```

---

## 5. AOP 采集层（Span 栈 + N+3）

### 5.1 CaptureAspect

```java
package com.hisi.capture.aop;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import com.hisi.capture.util.SpanTruncator;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class CaptureAspect {

    @Autowired
    private SizeLimiter sizeLimiter;

    @Autowired
    private SpanTruncator spanTruncator;

    /**
     * 切所有 Service/Component/Repository 的 public 方法。
     *
     * 注意 Spring AOP 代理局限：self-invocation（this.xxx()）不走代理，无法采集。
     */
    @Around("@within(org.springframework.stereotype.Service) " +
            "|| @within(org.springframework.stereotype.Component) " +
            "|| @within(org.springframework.stereotype.Repository)")
    public Object captureSpan(ProceedingJoinPoint pjp) throws Throwable {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx == null || ctx.getEntry() == null) {
            // 入口没装采集器（如 main 方法直调），降级
            return pjp.proceed();
        }
        if (ctx.getSpanStack().size() >= 50) {
            // 栈深保护
            return pjp.proceed();
        }

        Span span = new Span(pjp.getSignature().toLongString(), System.currentTimeMillis());
        span.setArgs(sizeLimiter.limitArgs(pjp.getArgs()));
        ctx.pushSpan(span);

        boolean escaped = false;
        try {
            Object ret = pjp.proceed();
            span.setRetVal(sizeLimiter.limitRetVal(ret));
            return ret;
        } catch (Throwable e) {
            span.setException(e);
            escaped = true;
            throw e;
        } finally {
            span.setEndMillis(System.currentTimeMillis());
            ctx.popSpan();
            // 兜底 3：silent_catch 检测
            if (span.getException() != null && !escaped) {
                SilentCatchDetector.detect(span, ctx);
            }
        }
    }
}
```

### 5.2 SilentCatchDetector（兜底 3）

```java
package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class SilentCatchDetector {

    @Autowired
    private CaptureExceptionEnricher enricher;

    /** 去重缓存：同一 entryTag + 异常 hash 5 秒内只打一次 */
    private static final Cache<String, Long> DEDUP = CacheBuilder.newBuilder()
        .expireAfterWrite(5, TimeUnit.SECONDS).maximumSize(10000).build();

    public static void detect(Span span, CaptureContext ctx) {
        if (!shouldDetect()) return;

        String key = ctx.getEntry().getEntryTag() + ":" +
                     span.getException().getClass().getName() + ":" +
                     hashStack(span.getException());
        Long last = DEDUP.getIfPresent(key);
        if (last != null) return;
        DEDUP.put(key, System.currentTimeMillis());

        // silent_catch：span 有异常但未冒泡（catch-return-fallback）
        enricher.enrichAndLog(span.getException(), ctx, true);
    }

    private static boolean shouldDetect() {
        // 读配置 hisi.capture.silent-catch.enabled（决策 3 默认 true）
        return Boolean.getBoolean("hisi.capture.silent-catch.enabled");
    }

    private static String hashStack(Throwable ex) {
        StringBuilder sb = new StringBuilder();
        for (StackTraceElement e : ex.getStackTrace()) {
            sb.append(e.getClassName()).append(":").append(e.getLineNumber()).append("|");
        }
        return Integer.toHexString(sb.toString().hashCode());
    }
}
```

---

## 6. 异常增强层（5 出口 + 3 兜底）

### 6.1 出口总览

| 异常源 | 抓取机制 | 实现类 |
|--------|---------|--------|
| HTTP Controller 异常 | @ControllerAdvice @ExceptionHandler | CaptureControllerAdvice |
| @Async 异常 | AsyncUncaughtExceptionHandler | CaptureAsyncUncaughtExceptionHandler |
| @Scheduled 异常 | ErrorHandler 注入 ScheduledTaskRegistrar | CaptureScheduledErrorHandler |
| 线程未捕获异常 | 全局 Thread.UncaughtExceptionHandler | CaptureUncaughtExceptionHandler |
| silent_catch | AOP @Around finally 块检测 | SilentCatchDetector（兜底 3） |
| @CaptureLog 显式 | @AfterThrowing 切面 | CaptureLogAspect（兜底 2） |

### 6.2 CaptureControllerAdvice（HTTP 出口）

```java
package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public class CaptureControllerAdvice {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @ExceptionHandler(Exception.class)
    public org.springframework.http.ResponseEntity<String> handle(Exception ex) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(ex, ctx, false);
        }
        return org.springframework.http.ResponseEntity
            .status(500).body("internal error (entryTag=" +
                (ctx != null ? ctx.getEntry().getEntryTag() : "N/A") + ")");
    }
}
```

### 6.3 CaptureAsyncUncaughtExceptionHandler（@Async 出口）

```java
package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Component
public class CaptureAsyncUncaughtExceptionHandler implements AsyncUncaughtExceptionHandler {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @Override
    public void handleUncaughtException(Throwable ex, Method method, Object... params) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(ex, ctx, false);
        }
    }
}
```

### 6.4 CaptureScheduledErrorHandler（@Scheduled 出口）

```java
package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import com.hisi.capture.util.SizeLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.AsyncConfigurable;
import org.springframework.stereotype.Component;
import org.springframework.util.ErrorHandler;

import java.util.*;

@Component
public class CaptureScheduledErrorHandler implements ErrorHandler {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @Autowired
    private SizeLimiter sizeLimiter;

    @Override
    public void handleError(Throwable t) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx == null) {
            // @Scheduled 没经过 Filter，需要在此构造入口上下文
            String entryTag = UUID.randomUUID().toString();
            Map<String, Object> params = new HashMap<>();
            params.put("task", t.getStackTrace()[0].getClassName() + "." +
                              t.getStackTrace()[0].getMethodName());
            EntryContext entry = new EntryContext(
                entryTag, EntryType.SCHEDULED, "scheduled:" + params.get("task"),
                params, System.currentTimeMillis());
            ctx = new CaptureContext();
            ctx.setEntry(entry);
            CaptureContextHolder.set(ctx);
        }
        enricher.enrichAndLog(t, ctx, false);
        CaptureContextHolder.clear();
    }
}
```

### 6.5 CaptureUncaughtExceptionHandler（兜底 1）

```java
package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CaptureUncaughtExceptionHandler implements Thread.UncaughtExceptionHandler {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @Override
    public void uncaughtException(Thread t, Throwable e) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(e, ctx, false);
        } else {
            // 没有上下文，打原始异常
            java.util.logging.Logger.getLogger("CaptureUncaught")
                .severe("Uncaught exception in thread " + t.getName() + ": " + e);
        }
    }
}
```

### 6.6 CaptureLogAspect（兜底 2：@CaptureLog）

```java
package com.hisi.capture.aop;

import com.hisi.capture.context.*;
import com.hisi.capture.exception.CaptureExceptionEnricher;
import org.aspectj.lang.*;
import org.aspectj.lang.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 兜底 2：业务方在 silent_catch 场景显式标注 @CaptureLog，
 * AOP @AfterThrowing 触发 enricher 打印。
 */
@Aspect
@Component
public class CaptureLogAspect {

    @Autowired
    private CaptureExceptionEnricher enricher;

    @AfterThrowing(pointcut = "@annotation(com.hisi.capture.annotation.CaptureLog)",
                   throwing = "ex")
    public void afterThrowing(JoinPoint jp, Throwable ex) {
        CaptureContext ctx = CaptureContextHolder.get();
        if (ctx != null) {
            enricher.enrichAndLog(ex, ctx, false);
        }
    }
}
```

### 6.7 @CaptureLog 注解定义

```java
package com.hisi.capture.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CaptureLog {
    String value() default "";
}
```

### 6.8 CaptureExceptionEnricher（核心：message 注入主流程）

```java
package com.hisi.capture.exception;

import com.hisi.capture.context.*;
import com.hisi.capture.crypto.CaptureCrypto;
import com.hisi.capture.format.CaptureFormatter;
import com.hisi.capture.util.SpanTruncator;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class CaptureExceptionEnricher {

    private static final Logger log = LoggerFactory.getLogger(CaptureExceptionEnricher.class);

    @Autowired
    private CaptureFormatter formatter;

    @Autowired
    private CaptureCrypto crypto;

    @Autowired
    private SpanTruncator spanTruncator;

    /**
     * @param silentCatch true 表示来自兜底 3 的 silent_catch 检测
     */
    public void enrichAndLog(Throwable ex, CaptureContext ctx, boolean silentCatch) {
        try {
            // N+3 = 4：保留异常抛出点 + 3 层上游
            List<Span> kept = spanTruncator.bottomN(ctx.getSpanStack(), 4);

            // 格式化 + 加密
            String plain = formatter.format(ctx.getEntry(), kept, ctx.getFeignCalls(), ex);
            String encrypted = crypto.encrypt(plain);

            // 注入 message
            String tag = ctx.getEntry().getEntryTag();
            if (silentCatch) {
                log.warn("[SilentCatch][EntryTag={}] {} \n{}",
                    tag, ex.getMessage(), encrypted, ex);
            } else {
                log.error("[EntryTag={}] {} \n{}",
                    tag, ex.getMessage(), encrypted, ex);
            }
        } catch (Exception e) {
            // 加密失败不能吞掉原始异常
            log.error("[CaptureFailed][EntryTag={}] {}",
                ctx.getEntry().getEntryTag(), ex.getMessage(), ex);
        }
    }
}
```

---

## 7. TTL 集成（决策 1：默认 BeanPostProcessor 自动包装）

### 7.1 CaptureTtlBeanPostProcessor（默认实现）

```java
package com.hisi.capture.ttl;

import com.alibaba.ttl.threadpool.TtlExecutors;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 决策 1 默认实现：BeanPostProcessor 自动包装业务方 ExecutorPoolConfig Bean。
 *
 * 业务方零代码改动：只要线程池通过 Spring Bean 暴露（业务方扫描结果显示
 * 100% 通过 com.hisilicon.<module>.basic.config.ExecutorPoolConfig 暴露），
 * 即可自动包装为 TTL-aware。
 *
 * 开关：hisi.capture.ttl.mode=auto（默认）/ agent / explicit
 *   - auto: 本类生效
 *   - agent: 本类不生效，依赖 TTL javaagent 字节码改写
 *   - explicit: 本类不生效，业务方手动 TtlExecutors.getTtlExecutorService(pool) 包装
 */
@Component
public class CaptureTtlBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (!"auto".equalsIgnoreCase(System.getProperty("hisi.capture.ttl.mode", "auto"))) {
            return bean;
        }

        // 包装 ThreadPoolTaskExecutor
        if (bean instanceof ThreadPoolTaskExecutor) {
            ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) bean;
            // 通过 TtlExecutors 包装底层 ThreadPoolExecutor
            ThreadPoolExecutor raw = executor.getThreadPoolExecutor();
            ExecutorService ttlWrapped = TtlExecutors.getTtlExecutorService(raw);
            return new TtlWrappedTaskExecutor(executor, ttlWrapped);
        }

        // 包装原生 ExecutorService
        if (bean instanceof ExecutorService) {
            return TtlExecutors.getTtlExecutorService((ExecutorService) bean);
        }

        return bean;
    }
}
```

### 7.2 TtlWrappedTaskExecutor（适配器）

```java
package com.hisi.capture.ttl;

import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ExecutorService;

/**
 * 适配器：把 TTL 包装后的 ExecutorService 适配回 ThreadPoolTaskExecutor 接口。
 *
 * 业务方调用 @Async 时不感知，仍用原 ThreadPoolTaskExecutor 接口。
 */
public class TtlWrappedTaskExecutor implements TaskExecutor {

    private final ThreadPoolTaskExecutor original;
    private final ExecutorService ttlWrapped;

    public TtlWrappedTaskExecutor(ThreadPoolTaskExecutor original, ExecutorService ttlWrapped) {
        this.original = original;
        this.ttlWrapped = ttlWrapped;
    }

    @Override
    public void execute(Runnable task) {
        ttlWrapped.execute(task);
    }

    public ThreadPoolTaskExecutor getOriginal() { return original; }
    public ExecutorService getTtlWrapped() { return ttlWrapped; }
}
```

### 7.3 CaptureTtlExecutors（explicit 模式辅助工具）

```java
package com.hisi.capture.ttl;

import com.alibaba.ttl.threadpool.TtlExecutors;

import java.util.concurrent.ExecutorService;

/**
 * 业务方手动包装时用此工具类（explicit 模式）。
 *
 * 用法：
 *   ExecutorService pool = CaptureTtlExecutors.wrap(new ThreadPoolExecutor(...));
 */
public class CaptureTtlExecutors {

    public static ExecutorService wrap(ExecutorService pool) {
        return TtlExecutors.getTtlExecutorService(pool);
    }
}
```

---

## 8. 加密层（决策 5：AES-256-GCM + 决策 6：静态非对称）

### 8.1 静态公钥加载（StaticKeyPairLoader）

```java
package com.hisi.capture.crypto;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.security.*;
import java.security.spec.X509EncodedKeySpec;

@Component
public class StaticKeyPairLoader {

    static {
        Security.addProvider(new BouncyCastleProvider());
    }

    /**
     * 加载内置公钥（META-INF/capture-public-key.pem）。
     * 私钥不发布到业务方，仅在 codeknowledge 内部。
     */
    public PublicKey loadPublicKey() {
        try (var is = new ClassPathResource("META-INF/capture-public-key.pem").getInputStream()) {
            String pem = new String(is.readAllBytes())
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replaceAll("\\s", "");
            byte[] der = java.util.Base64.getDecoder().decode(pem);
            X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
            return KeyFactory.getInstance("RSA").generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load capture public key", e);
        }
    }
}
```

### 8.2 HybridEncryptor（混合加密核心）

```java
package com.hisi.capture.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 混合加密（JWE 风格）：
 *   1. 随机生成 32B DEK（AES-256 密钥）
 *   2. AES-256-GCM(DEK, IV, plaintext) → 密文 + tag
 *   3. RSA-OAEP-2048(公钥, DEK) → 加密后的 DEK
 *   4. 输出：base64(rsa_wrapped_dek[256B] || iv[12B] || ciphertext || gcm_tag[16B])
 */
@Component
public class HybridEncryptor {

    private static final int DEK_LEN = 32;       // AES-256
    private static final int IV_LEN = 12;        // GCM 推荐 12B
    private static final int TAG_LEN_BITS = 128; // GCM tag 16B
    private static final int RSA_WRAPPED_LEN = 256; // RSA-2048 密文 256B

    @Autowired
    private StaticKeyPairLoader keyLoader;

    private final SecureRandom random = new SecureRandom();

    public String encrypt(String plaintext) {
        try {
            // 1. 生成 DEK
            byte[] dek = new byte[DEK_LEN];
            random.nextBytes(dek);

            // 2. AES-GCM 加密
            byte[] iv = new byte[IV_LEN];
            random.nextBytes(iv);
            Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
            aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                           new GCMParameterSpec(TAG_LEN_BITS, iv));
            byte[] ct = aesCipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            // 3. RSA-OAEP 加密 DEK
            Cipher rsaCipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            rsaCipher.init(Cipher.ENCRYPT_MODE, keyLoader.loadPublicKey());
            byte[] wrappedDek = rsaCipher.doFinal(dek);
            if (wrappedDek.length != RSA_WRAPPED_LEN) {
                throw new IllegalStateException("RSA wrapped DEK length mismatch");
            }

            // 4. 拼接 + base64
            byte[] out = new byte[RSA_WRAPPED_LEN + IV_LEN + ct.length];
            System.arraycopy(wrappedDek, 0, out, 0, RSA_WRAPPED_LEN);
            System.arraycopy(iv, 0, out, RSA_WRAPPED_LEN, IV_LEN);
            System.arraycopy(ct, 0, out, RSA_WRAPPED_LEN + IV_LEN, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encrypt failed", e);
        }
    }
}
```

### 8.3 CaptureCrypto（对外门面）

```java
package com.hisi.capture.crypto;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class CaptureCrypto {

    @Autowired
    private HybridEncryptor encryptor;

    public String encrypt(String plaintext) {
        return encryptor.encrypt(plaintext);
    }
}
```

---

## 9. 格式化层

### 9.1 CapturePayload

```java
package com.hisi.capture.format;

import java.util.List;
import java.util.Map;

public class CapturePayload {
    private String alg;
    private Map<String, String> enc;  // entry / spans / feign → 密文 base64
    private Map<String, Object> meta; // tag / uri / method / ts

    // getters / setters 略
}
```

### 9.2 CaptureFormatter

```java
package com.hisi.capture.format;

import com.hisi.capture.context.*;
import com.hisi.capture.crypto.CaptureCrypto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class CaptureFormatter {

    @Autowired
    private CaptureCrypto crypto;

    public String format(EntryContext entry, List<Span> spans,
                         List<FeignCall> feignCalls, Throwable ex) {
        // 1. 明文 JSON 构造
        Map<String, Object> entryJson = new HashMap<>();
        entryJson.put("tag", entry.getEntryTag());
        entryJson.put("type", entry.getEntryType().name());
        entryJson.put("uri", entry.getEntryUri());
        entryJson.put("params", entry.getParams());

        List<Map<String, Object>> spanJson = new ArrayList<>();
        for (Span s : spans) {
            Map<String, Object> sj = new HashMap<>();
            sj.put("sig", s.getMethodSignature());
            sj.put("args", s.getArgs());
            sj.put("ret", s.getRetVal());
            sj.put("dur", s.getEndMillis() - s.getStartMillis());
            if (s.getException() != null) {
                sj.put("exc", s.getException().toString());
            }
            spanJson.add(sj);
        }

        List<Map<String, Object>> feignJson = new ArrayList<>();
        for (FeignCall f : feignCalls) {
            Map<String, Object> fj = new HashMap<>();
            fj.put("url", f.getUrl());
            fj.put("params", f.getParams());
            fj.put("status", f.getStatus());
            fj.put("dur", f.getDuration());
            feignJson.add(fj);
        }

        Map<String, Object> entryPlain = Map.of("entry", entryJson, "spans", spanJson, "feign", feignJson);
        Map<String, Object> spansPlain = Map.of("spans", spanJson);
        Map<String, Object> feignPlain = Map.of("feign", feignJson);

        // 2. 分别加密
        Map<String, String> enc = new HashMap<>();
        enc.put("entry", crypto.encrypt(toJson(entryPlain)));
        enc.put("spans", crypto.encrypt(toJson(spansPlain)));
        if (!feignCalls.isEmpty()) {
            enc.put("feign", crypto.encrypt(toJson(feignPlain)));
        }

        // 3. meta 明文
        Map<String, Object> meta = new HashMap<>();
        meta.put("tag", entry.getEntryTag());
        meta.put("uri", entry.getEntryUri());
        meta.put("method", ex.getStackTrace().length > 0 ?
            ex.getStackTrace()[0].getClassName() + "." + ex.getStackTrace()[0].getMethodName() : "unknown");
        meta.put("ts", System.currentTimeMillis());

        // 4. 拼最终 payload
        CapturePayload payload = new CapturePayload();
        payload.setAlg("hybrid-rsa-aes-gcm");
        payload.setEnc(enc);
        payload.setMeta(meta);
        return "HISI_CAPTURE_BEGIN" + toJson(payload) + "HISI_CAPTURE_END";
    }

    private String toJson(Object o) {
        // 用 Jackson 或 Hutool JSONUtil
        return com.fasterxml.jackson.databind.ObjectMapper.writeValueAsString(o);
    }
}
```

---

## 10. 工具类

### 10.1 SizeLimiter

```java
package com.hisi.capture.util;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

@Component
public class SizeLimiter {

    @Value("${hisi.capture.max-arg-size:1024}")     // 1KB
    private int maxArgSize;

    @Value("${hisi.capture.max-body-size:4096}")    // 4KB
    private int maxBodySize;

    public Object[] limitArgs(Object[] args) {
        if (args == null) return null;
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = truncate(args[i], maxArgSize);
        }
        return out;
    }

    public Object limitRetVal(Object ret) {
        return truncate(ret, maxArgSize);
    }

    public String limitBody(jakarta.servlet.http.HttpServletRequest req) {
        // 读取 body（缓存以便后续 Controller 用），截断到 maxBodySize
        try {
            byte[] buf = req.getInputStream().readAllBytes();
            int len = Math.min(buf.length, maxBodySize);
            return new String(buf, 0, len, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "[body-read-failed]";
        }
    }

    private Object truncate(Object o, int maxBytes) {
        if (o == null) return null;
        String s = String.valueOf(o);
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        if (b.length <= maxBytes) return o;
        return new String(b, 0, maxBytes, StandardCharsets.UTF_8) + "...[truncated]";
    }
}
```

### 10.2 SpanTruncator（N+3）

```java
package com.hisi.capture.util;

import com.hisi.capture.context.Span;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
public class SpanTruncator {

    /**
     * N+3 = 4：保留异常抛出点（栈顶）+ 3 层上游。
     * 输入是 Deque（栈顶在前），输出按从栈底到栈顶顺序。
     */
    public List<Span> bottomN(Deque<Span> stack, int n) {
        if (stack == null || stack.isEmpty()) return Collections.emptyList();
        List<Span> list = new ArrayList<>(stack);
        // list[0] 是栈顶（异常抛出点），保留前 n 个
        if (list.size() <= n) return list;
        return list.subList(0, n);
    }
}
```

---

## 11. 自动配置

### 11.1 CaptureAutoConfiguration

```java
package com.hisi.capture.autoconfig;

import com.hisi.capture.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@AutoConfiguration
@ConditionalOnProperty(prefix = "hisi.capture", name = "enabled", havingValue = "true", matchIfMissing = true)
@Import({
    CaptureWebAutoConfiguration.class,
    CaptureAsyncAutoConfiguration.class,
    CaptureScheduledAutoConfiguration.class,
    CaptureFeignAutoConfiguration.class,
    CaptureAopAutoConfiguration.class,
    CaptureExceptionAutoConfiguration.class,
    CaptureCryptoAutoConfiguration.class,
    CaptureTtlAutoConfiguration.class,
})
public class CaptureAutoConfiguration {
}
```

### 11.2 spring.factories（SB 2.x）/ AutoConfiguration.imports（SB 3.x）

src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports：

```
com.hisi.capture.autoconfig.CaptureAutoConfiguration
```

src/main/resources/META-INF/spring.factories（兼容 SB 2.x）：

```properties
org.springframework.boot.autoconfigure.EnableAutoConfiguration=\
com.hisi.capture.autoconfig.CaptureAutoConfiguration
```

---

## 12. 输出格式示例

```
2026-07-01 10:23:45 ERROR [order-svc,550e8400-e29b-41d4-a716-446655440000]
  c.example.OrderController - createOrder failed
java.lang.NullPointerException: ...
HISI_CAPTURE_BEGIN{
  "alg": "hybrid-rsa-aes-gcm",
  "enc": {
    "entry": "base64(rsa_wrapped_dek||iv||ciphertext||gcm_tag)",
    "spans": "base64(rsa_wrapped_dek||iv||ciphertext||gcm_tag)",
    "feign": "base64(rsa_wrapped_dek||iv||ciphertext||gcm_tag)"
  },
  "meta": {
    "tag": "550e8400-e29b-41d4-a716-446655440000",
    "uri": "/api/orders",
    "method": "com.example.OrderService.create",
    "ts": 1719804225123
  }
}HISI_CAPTURE_END
```

---

## 13. 限制清单（必须告知业务方）

| 场景 | 能采集吗 | 说明 |
|------|---------|------|
| HTTP 入口异常冒泡 | ✅ | ControllerAdvice 抓 |
| Service/DAO 异常冒泡 | ✅ | AOP catch 抓 |
| @Async 异常 | ✅ | AsyncUncaughtExceptionHandler |
| @Scheduled 异常 | ✅ | ErrorHandler |
| Thread 未捕获异常 | ✅ | 兜底 1: UncaughtExceptionHandler |
| silent_catch (catch-log-返回 fallback) | ✅ | 兜底 2/3（决策 3 默认开） |
| self-invocation (this.xxx()) | ❌ | Spring AOP 代理局限 |
| 第三方库内部异常 | ❌ | 不在 AOP 切面范围 |
| OOM / JVM 致命错误 | ❌ | 任何 Java 方案都抓不到 |
| 跨进程 HTTP 调下游 | ⚠️ | 只传 entryTag header 关联 |

---

## 14. 线程池改造开放问题（决策 1 待 MVP 实测）

**用户顾虑（2026-07-01）**："可以强制业务方改线程池，但是我还是觉得有点难用且引入风险。"

| 路径 | 优点 | 缺点 |
|------|------|------|
| (a) TTL javaagent 字节码改写 | 零业务代码改动 | 需挂 agent，业务方运维复杂 |
| (b) 显式 TtlExecutors 包装 | 直观 | 改造点多，引入风险 |
| **(c) BeanPostProcessor 自动包装（默认）** | **业务零代码改动 + 无 agent** | 仅能包装 Spring Bean 暴露的线程池 |

业务方扫描结果（详见 [04-decrypt-script-business-scan.md](./04-decrypt-script-business-scan.md)）显示所有 ExecutorPoolConfig 都通过 Spring Bean 暴露 → (c) 方案可行。

**待后续确认**：MVP 阶段实测一两个业务仓库后定夺最终方案。

---

## 15. 业务代码改造场景清单（零代码改动）

| 场景 | 是否需改业务代码 | 说明 |
|------|----------------|------|
| HTTP 入口 | ❌ | 自动配置 HttpCaptureFilter |
| @Async | ❌ | 自动配置 AsyncAspect + TaskDecorator |
| @Scheduled | ❌ | 自动配置 ErrorHandler |
| Feign 出向 | ❌ | 自动配置 RequestInterceptor |
| 线程池 | ❌（决策 1 默认 (c)） | BeanPostProcessor 自动包装 |
| silent_catch 抓取 | 可选 @CaptureLog | 不加注解也能用兜底 3（默认开） |
| 自定义入口（gRPC/WS） | 需实现 SPI | MVP 不写实现，业务方暂不用 |

**结论**：业务方零代码改动，只需 Maven 引入 starter + 配置 hisi.capture.enabled=true。
