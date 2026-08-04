# 配置开关 + 决策点详述 + 工作量/风险 + 后续规划

> 本文档覆盖：
> - 采集 SDK + 修复引擎 + 历史会话 的配置开关全集（含决策 1-4 开关）
> - 决策点 1-6 详述与默认值/切换选项
> - 工作量评估
> - 风险评估
> - 后续规划（v2）
> - 变更记录

---

## 1. 配置开关全集

### 1.1 采集 SDK 开关（hisi-capture-spring-boot-starter）

```yaml
hisi:
  capture:
    # === 全局开关 ===
    enabled: true                              # 全局开关，默认开
    uris:                                      # URI 白名单，空=全采
      - /api/**
    sample-rate: 1.0                           # 采样率 0-1，默认 100%

    # === 采集层 ===
    max-span-depth: 50                         # span 栈深上限，防递归爆炸
    max-arg-size: 1024                         # 单参数 size 上限 1KB
    max-body-size: 4096                        # HTTP body size 上限 4KB
    max-ret-size: 1024                         # 返回值 size 上限 1KB

    # === TTL 集成（决策 1） ===
    ttl:
      mode: auto                               # 默认 auto，可选 agent / explicit
      # auto: BeanPostProcessor 自动包装业务方 ExecutorPoolConfig Bean
      # agent: 依赖 TTL javaagent 字节码改写（业务方挂 -javaagent）
      # explicit: 业务方手动 TtlExecutors.getTtlExecutorService(pool) 包装

    # === 异常增强 ===
    silent-catch:
      enabled: true                            # 决策 3：默认开
      dedup-window: 5s                         # 去重窗口，同一 entryTag+异常 5s 内只打一次

    capture-log-scan:
      strict: true                             # 决策 2：默认 strict，静态扫描违规告警
      # true: 静态扫描发现 silent_catch 未加 @CaptureLog → 编译告警
      # false: 仅提示不告警

    # === 加密（决策 5 + 6） ===
    crypto:
      enabled: true                            # 加密开关，默认开
      algorithm: hybrid-rsa-aes-gcm           # 固定：RSA-OAEP-2048 + AES-256-GCM
      encrypt-fields:                          # 加密字段范围
        - entry.params
        - span.args
        - span.retVal
        - feign.params
      fallback-plaintext: false                # 密钥缺失时启动失败（不降级明文）
```

### 1.2 HiSi DevTool 修复引擎开关

```yaml
hisi:
  fix:
    # === worktree ===
    worktree:
      base-dir: D:/codeknowledge/fix-worktrees # worktree 根目录
      branch-prefix: bugfix_                   # 分支前缀
      push-remote: false                       # 决策：本地分支不 push

    # === 单测生成（决策 4） ===
    test-gen:
      max-iterate-rounds: 3                    # 默认 3 轮，可切 1（第一次失败即降级）
      # 3: AI 自动迭代修测试最多 3 轮，超过降级为草拟+暂停
      # 1: 第一次失败即降级

    # === 复现判定 ===
    repro:
      exception-match-mode: contains           # 异常 message 匹配模式，contains/exact/regex

    # === commit ===
    commit:
      message-template: |                      # commit message 模板
        fix: {methodName} {exceptionType} reproduced and fixed

        Root cause: {rootCause}
        Fix: {fixDescription}
        Test: {testFile}
        EntryTag: {entryTag}

    # === 解密（codeknowledge 内部私钥） ===
    crypto:
      private-key-path: /etc/hisi/capture-private.pem   # 私钥文件路径
      # 或
      private-key-b64: ${HISI_CAPTURE_PRIVATE_KEY_B64}  # 私钥 base64（环境变量）

  # === 历史会话 ===
  fix-session:
    retention-days: 30                         # 历史会话保留天数
    auto-cleanup-worktree: true                # 会话结束自动清理 worktree
```

### 1.3 决策点 1-4 开关切换对照表

| 决策 | 配置 key | 默认值 | 切换选项 | 说明 |
|------|---------|--------|---------|------|
| 1. TTL 集成方式 | hisi.capture.ttl.mode | auto | agent / explicit | auto: BeanPostProcessor 自动包装；agent: javaagent；explicit: 业务方手动包装 |
| 2. @CaptureLog 强制性 | hisi.capture.capture-log-scan.strict | true | false | true: 静态扫描告警；false: 仅提示 |
| 3. silent_catch 兜底 3 | hisi.capture.silent-catch.enabled | true | false | true: 开 silent_catch 检测；false: 关 |
| 4. 单测生成失败兜底 | hisi.fix.test-gen.max-iterate-rounds | 3 | 1 | 3: 3 轮迭代；1: 第一次失败即降级 |

---

## 2. 决策点详述

### 2.1 决策 1：TTL 集成方式

**背景**：TransmittableThreadLocal (TTL) 在线程池复用场景传播上下文，有三种集成路径。

**默认实现 (c) BeanPostProcessor 自动包装**：

```java
@Component
public class CaptureTtlBeanPostProcessor implements BeanPostProcessor {
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (!"auto".equalsIgnoreCase(System.getProperty("hisi.capture.ttl.mode", "auto"))) {
            return bean;
        }
        if (bean instanceof ThreadPoolTaskExecutor) {
            // 包装底层 ThreadPoolExecutor
            return new TtlWrappedTaskExecutor(...);
        }
        if (bean instanceof ExecutorService) {
            return TtlExecutors.getTtlExecutorService((ExecutorService) bean);
        }
        return bean;
    }
}
```

**切换到 agent 模式**：

```bash
# 业务方 JVM 参数加
java -javaagent:/path/to/ttl-agent.jar -jar app.jar
```

```yaml
hisi:
  capture:
    ttl:
      mode: agent  # 关闭 BeanPostProcessor，依赖 agent
```

**切换到 explicit 模式**：

```java
// 业务方手动包装
@Bean
public ExecutorService myPool() {
    return CaptureTtlExecutors.wrap(new ThreadPoolExecutor(...));
}
```

```yaml
hisi:
  capture:
    ttl:
      mode: explicit  # 关闭 BeanPostProcessor，业务方手动
```

**待 MVP 实测**：业务方扫描结果显示所有 ExecutorPoolConfig 都通过 Spring Bean 暴露，默认 (c) 可行。MVP 阶段实测一两个业务仓库后定夺最终方案。详见 [01-capture-sdk.md §14 线程池改造开放问题](./01-capture-sdk.md#14-线程池改造开放问题决策-1-待-mvp-实测)。

### 2.2 决策 2：@CaptureLog 强制性

**背景**：silent_catch 场景（业务 catch-log-return fallback）AOP 默认抓不到，需业务方加 @CaptureLog 注解显式触发。

**默认实现 (a) 静态扫描告警**：

```java
@Component
public class CaptureLogStaticScanner {
    /**
     * 编译期扫描：发现 catch-log-return 但未加 @CaptureLog 的方法 → 告警
     */
    public void scan(Class<?> clazz) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (isSilentCatch(m) && !m.isAnnotationPresent(CaptureLog.class)) {
                log.warn("Method {} has silent-catch but no @CaptureLog", m);
            }
        }
    }
}
```

**切换到 (b) 仅提示**：

```yaml
hisi:
  capture:
    capture-log-scan:
      strict: false  # 只记录 INFO 日志，不告警
```

### 2.3 决策 3：silent_catch 兜底 3 默认开关

**背景**：AOP @Around finally 块检测 span.exception != null && !escaped = silent_catch。

**默认实现 (a) 默认开**：

```java
@Component
public class SilentCatchDetector {
    public static void detect(Span span, CaptureContext ctx) {
        if (!shouldDetect()) return;
        // ... 检测 + 去重 + enricher.enrichAndLog
    }
    private static boolean shouldDetect() {
        return Boolean.parseBoolean(
            System.getProperty("hisi.capture.silent-catch.enabled", "true"));
    }
}
```

**切换到 (b) 默认关**：

```yaml
hisi:
  capture:
    silent-catch:
      enabled: false  # 关闭 silent_catch 兜底检测
```

### 2.4 决策 4：单测生成失败兜底

**背景**：AI 第一次生成的测试跑不通概率高（经验 50%+），需迭代修测试。

**默认实现 (a) 3 轮迭代**：

```java
@Component
public class TestGenAgent {
    public String generate(TestGenInput input, int maxRounds) {
        String testCode = llm.complete(prompt);
        for (int round = 1; round <= maxRounds; round++) {
            TestRunResult result = runner.run(testCode);
            if (result.isPassed() || result.isReproduced()) return testCode;
            testCode = llm.complete(buildFixPrompt(testCode, result));
        }
        return testCode + "\n// [DRAFT] max-iterate-rounds exceeded";
    }
}
```

```yaml
hisi:
  fix:
    test-gen:
      max-iterate-rounds: 3  # 默认 3 轮
```

**切换到 (b) 第一次失败即降级**：

```yaml
hisi:
  fix:
    test-gen:
      max-iterate-rounds: 1  # 第一次失败即降级
```

### 2.5 决策 5：加密算法（已定）

**决策**：AES-256-GCM（认证加密）

**不可切换**，固定在 HybridEncryptor 中：

```java
Cipher aesCipher = Cipher.getInstance("AES/GCM/NoPadding");
aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
               new GCMParameterSpec(128, iv));
```

**理由**：工业标准，Java 原生支持，性能优。除非有合规要求才考虑 SM4-GCM。

### 2.6 决策 6：加密方案（已定）

**决策**：静态非对称（RSA-OAEP-2048 + AES-256-GCM 混合加密）

**不可切换**，固定方案：

- 公钥硬编码在 META-INF/capture-public-key.pem
- 私钥仅在 codeknowledge 内部（/etc/hisi/capture-private.pem 或环境变量）
- 任何日志都能用同一私钥解密，零历史日解密失败风险

**理由**：用户 2026-07-01 确认，简化运维（零轮换负担）+ 历史日志可解密。

---

## 3. 工作量评估

### 3.1 子系统拆分

| 子系统 | 模块 | 文件数 | 工作量 |
|--------|------|-------|-------|
| A 采集 SDK | starter 框架 + 自动配置 | 5 | 1 天 |
| A 采集 SDK | HTTP 入口 + ControllerAdvice | 3 | 0.5 天 |
| A 采集 SDK | @Async + TaskDecorator + TTL BeanPostProcessor | 4 | 1 天 |
| A 采集 SDK | @Scheduled + Feign | 3 | 0.5 天 |
| A 采集 SDK | AOP 采集 + Span 栈 + N+3 | 4 | 1 天 |
| A 采集 SDK | 异常增强 + 3 兜底 | 5 | 1.5 天 |
| A 采集 SDK | 加密（混合）+ 公钥加载 | 4 | 1.5 天 |
| A 采集 SDK | 静态扫描器（@CaptureLog） | 2 | 0.5 天 |
| A 采集 SDK | 单测 | 10 | 2 天 |
| B 日志识别 | ParseNode 改造 + CaptureDecoder | 3 | 1 天 |
| C 修复引擎 | TestGenAgent + prompt | 5 | 3 天 |
| C 修复引擎 | ReproAgent + MavenExecutor | 3 | 1 天 |
| C 修复引擎 | FixAgent + prompt | 3 | 1.5 天 |
| C 修复引擎 | WorktreeService + GitExecutor | 3 | 1 天 |
| D 多轮对话 | FixChatController + FixChatService | 4 | 1 天 |
| D 多轮对话前端 | FixChatView.vue + API | 3 | 1.5 天 |
| E 历史会话 | fix_session 表 + Mapper + Controller | 3 | 0.5 天 |
| F 独立解密脚本 | Python 脚本 + 文档 | 2 | 0.5 天 |
| **合计** | | **67** | **~18 天** |

### 3.2 节省项

| 节省项 | 节省工作量 | 原因 |
|--------|----------|------|
| Rabbit/Kafka 入口 | 1.5 天 | 业务方扫描结果显示无使用 |
| gRPC/WebSocket/Netty SPI | 0.5 天 | 业务方扫描结果显示无使用 |
| **节省合计** | **2 天** | MVP 范围收敛 |

**MVP 实际工作量：~16 天**

---

## 4. 风险评估

| 风险 | 可能性 | 影响 | 应对 |
|------|--------|------|------|
| 单测自动生成跑通率低 | 高 | 高 | 决策 4 默认 3 轮迭代，超过降级为草拟 + 暂停 |
| TTL 在第三方线程池失效 | 中 | 中 | 决策 1 默认 BeanPostProcessor 自动包装业务方 ExecutorPoolConfig；扫描结果显示 100% 通过 Bean 暴露 |
| 加密密钥丢失 | 低 | 高 | 静态非对称方案：私钥长周期不变，零轮换负担；fallback-plaintext: false 强制启动失败 |
| 日志体积膨胀 | 中 | 中 | 默认采样率可调 + URI 白名单 + span 栈深上限 50 + 单参数 1KB 限制 |
| AI 整改引入新 bug | 中 | 高 | 复现测试 + java-reviewer + 用户 review 三道关 |
| worktree 残留 | 低 | 低 | 会话结束自动清理（auto-cleanup-worktree: true） |
| 业务方 self-invocation 漏采 | 中 | 中 | Spring AOP 代理局限，文档明确告知，建议业务方重构或加 @CaptureLog |
| 私钥泄露 | 低 | 高 | 私钥仅在 codeknowledge 内部，不发布到业务方；泄露应急：轮换 SK+PK 对，发版 SDK 内置新 PK，旧日志用旧 SK 解（保留旧 SK 备份） |

---

## 5. 后续规划（v2）

| 项 | 说明 | 优先级 |
|----|------|--------|
| FastAPI 采集 SDK | hisi-capture-fastapi pip 包，Python 装饰器 + contextvars | 高 |
| 跨进程 span 复制 | 基于 OTel trace context，下游服务重建完整 span | 中 |
| 整改自动提 MR | 不只是本地分支，自动 push + 创 MR | 中 |
| Rabbit/Kafka 入口 | 业务方有需求时再加 | 低 |
| gRPC/WebSocket/Netty SPI | 业务方有需求时再加 | 低 |
| 多语言 reviewer Agent | java-reviewer 已有，扩展 python/go 等 | 低 |
| 密钥轮换 re-encrypt 工具 | 静态方案下不急需，但私钥泄露时有用 | 低 |

---

## 6. 关键决策点状态总览

| 决策 | 默认实现 | 开关切换 | 状态 |
|------|---------|---------|------|
| 1. TTL 集成方式 | (c) BeanPostProcessor 自动包装 | hisi.capture.ttl.mode=auto/agent/explicit | 默认 (c)，待 MVP 实测 |
| 2. @CaptureLog 强制性 | (a) 静态扫描告警 | hisi.capture.capture-log-scan.strict=true/false | 默认 (a) |
| 3. silent_catch 兜底 3 | (a) 默认开 | hisi.capture.silent-catch.enabled=true/false | 默认 (a) |
| 4. 单测生成失败兜底 | (a) 3 轮迭代 | hisi.fix.test-gen.max-iterate-rounds=3/1 | 默认 (a) |
| 5. 加密算法 | AES-256-GCM | — | ✅ 已决策 |
| 6. 加密方案 | 静态非对称 RSA-OAEP-2048 + AES-256-GCM | — | ✅ 已决策 |

---

## 7. 变更记录

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|---------|------|
| v1.0 | 2026-07-01 | 初始版本 | AI + 用户讨论 |
| v1.1 | 2026-07-01 | 加密方案改静态非对称；记录线程池顾虑；新增业务方扫描结果；MVP 范围收敛 | AI + 用户讨论 |
| v2.0 | 2026-07-01 | 拆分为索引 + 5 个子文档；决策 1-4 改为「默认实现 + 预留开关」；决策 5 定 AES-GCM；全部刷新到代码粒度 | AI + 用户讨论 |
