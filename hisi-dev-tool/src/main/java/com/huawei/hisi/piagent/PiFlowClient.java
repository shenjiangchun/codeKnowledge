package com.huawei.hisi.piagent;

import com.huawei.hisi.piagent.context.PromptBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 修复/分析流与 pi 之间的高层客户端：按流清单组装进程参数、并发限流、
 * 把 pi 事件转发给监听器、施加 deadline 并保证进程回收。
 *
 * <p>上下文管理是底座的通用能力，对流透明：提交前由 {@link PromptBudget} 做预算检查与保形压缩
 * （输入这一层）；运行中由 pi 自带的会话压缩接管（阈值触发、溢出后压缩重试），这里的
 * {@code settings.json} 把它显式配置好，压缩事件原样透给监听器，失败原因记进 {@link PiResult#note()}。
 */
@Slf4j
@Component
public class PiFlowClient {

    private final PiAgentProperties properties;
    private final PiAgentInstaller installer;
    private final Semaphore concurrency;
    private final int deadlineMinutes;
    private final String anthropicApiKey;
    /** 通用上下文管理的第一层：提交前预算检查与保形压缩（所有流自动生效）。 */
    private final PromptBudget promptBudget;

    private final Set<PiProcess> live = ConcurrentHashMap.newKeySet();

    public PiFlowClient(PiAgentProperties properties,
                        PiAgentInstaller installer,
                        @Value("${anthropic.api-key:}") String anthropicApiKey) {
        this.properties = properties;
        this.installer = installer;
        this.anthropicApiKey = anthropicApiKey;
        this.promptBudget = new PromptBudget(properties.getContext());
        this.concurrency = new Semaphore(properties.getMaxConcurrentProcesses());
        this.deadlineMinutes = properties.getDeadlineMinutes();
    }

    /**
     * 一次 pi 流的结果。
     *
     * @param note 没拿到正常结论时的原因说明（上下文压缩失败、输出被截断等），正常结束时为 null
     */
    public record PiResult(String finalText, List<Map<String, Object>> toolCalls,
                           String stopReason, boolean timedOut, String note) {
        public static final String SETTLED = "agent_settled";

        public PiResult(String finalText, List<Map<String, Object>> toolCalls, String stopReason, boolean timedOut) {
            this(finalText, toolCalls, stopReason, timedOut, null);
        }
    }

    /**
     * 跑一次流：起一个 pi 进程，prompt 后收集事件直到 {@code agent_settled} 或 deadline。
     *
     * @param flow        清单键（arch-react / workbench-dev / …）
     * @param sessionId   会话 id（跨进程恢复用）
     * @param workdir     pi 的工作目录（写流=worktree，只读流=项目根）
     * @param skillPaths  注入的 skill 路径（可空）
     * @param systemPrompt 系统提示词（--system-prompt）
     * @param userPrompt  用户任务（prompt 命令）
     * @param listener    事件回调（进度展示）；可为 null
     */
    public PiResult run(String flow, String sessionId, Path workdir, List<Path> skillPaths,
                        String systemPrompt, String userPrompt, Consumer<Map<String, Object>> listener,
                        java.util.function.BooleanSupplier cancelled) {
        return runWithToken(flow, sessionId, workdir, skillPaths, systemPrompt, userPrompt, listener, cancelled, null);
    }

    /**
     * 带用户令牌的运行：动作工具（如 RAM 对话的发起分析/提交澄清）要以用户身份调后端，
     * 令牌经 {@code HISI_SCOPE_TOKEN} 环境变量传给 MCP 服务（mcp.json 里用 ${HISI_SCOPE_TOKEN} 引用），不落盘。
     */
    public PiResult runWithToken(String flow, String sessionId, Path workdir, List<Path> skillPaths,
                                 String systemPrompt, String userPrompt, Consumer<Map<String, Object>> listener,
                                 java.util.function.BooleanSupplier cancelled, String scopeToken) {
        // 启动时没装上不算定局：这里是所有流的必经入口，缺就在这儿再装一次
        installer.ensureReady();
        if (!installer.ready()) {
            throw new IllegalStateException("pi 未就绪（piagent.enabled=false，或安装失败——见后端日志 [PiAgent]），无法执行流 " + flow);
        }
        FlowToolManifest manifest = FlowToolManifest.forFlow(flow);
        boolean acquired = false;
        try {
            if (!concurrency.tryAcquire(2, TimeUnit.MINUTES)) {
                throw new IllegalStateException("等待 pi 进程槽位超时（并发上限 " + properties.getMaxConcurrentProcesses() + "）");
            }
            acquired = true;
            // 提交前的上下文预算：所有流都过这一道，超预算先压缩再发给 pi（运行中的压缩由 pi 自己做）
            Path sessionDir = sessionDir(sessionId);
            try {
                Files.createDirectories(sessionDir);
            } catch (IOException e) {
                throw new IllegalStateException("无法创建会话目录: " + sessionDir, e);
            }
            PromptBudget.Fitted fitted = promptBudget.fit(sessionDir, systemPrompt, userPrompt);
            if (fitted.report().compacted()) {
                PromptBudget.Report report = fitted.report();
                log.info("[PiAgent] 上下文预算 flow={} session={}：提示词 {} → {} 字符（约 {} → {} token，{}，截断 {} 个列表，完整原文 {}）",
                        flow, sessionId, report.beforeChars(), report.afterChars(),
                        report.estimatedTokensBefore(), report.estimatedTokensAfter(),
                        report.structured() ? "保形压缩" : "只保留开头", report.truncatedLists(), report.fullPromptFile());
                notifyListener(listener, contextCompactionEvent(report));
            }
            return runProcess(flow, sessionId, sessionDir, workdir, skillPaths, fitted.systemPrompt(), fitted.userPrompt(),
                    manifest, listener, cancelled, scopeToken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("等待 pi 进程槽位被中断");
        } finally {
            if (acquired) {
                concurrency.release();
            }
        }
    }

    /** 不带取消检查的便利重载。 */
    public PiResult run(String flow, String sessionId, Path workdir, List<Path> skillPaths,
                        String systemPrompt, String userPrompt, Consumer<Map<String, Object>> listener) {
        return run(flow, sessionId, workdir, skillPaths, systemPrompt, userPrompt, listener, () -> false);
    }

    private Path sessionDir(String sessionId) {
        return installer.agentDir().resolve("sessions").resolve(sessionId);
    }

    /** 底座自己做了一次输入压缩：告诉监听器压缩了多少，界面上能看见。 */
    static Map<String, Object> contextCompactionEvent(PromptBudget.Report report) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "context_compaction");
        event.put("phase", "prompt");
        event.put("beforeChars", report.beforeChars());
        event.put("afterChars", report.afterChars());
        event.put("estimatedTokensBefore", report.estimatedTokensBefore());
        event.put("estimatedTokensAfter", report.estimatedTokensAfter());
        event.put("structured", report.structured());
        event.put("truncatedLists", report.truncatedLists());
        event.put("fullPromptFile", String.valueOf(report.fullPromptFile()));
        return event;
    }

    private static void notifyListener(Consumer<Map<String, Object>> listener, Map<String, Object> event) {
        if (listener == null) {
            return;
        }
        try {
            listener.accept(event);
        } catch (Exception e) {
            log.warn("[PiAgent] 事件监听器异常: {}", e.getMessage());
        }
    }

    private PiResult runProcess(String flow, String sessionId, Path sessionDir, Path workdir, List<Path> skillPaths,
                                String systemPrompt, String userPrompt, FlowToolManifest manifest,
                                Consumer<Map<String, Object>> listener, java.util.function.BooleanSupplier cancelled,
                                String scopeToken) {
        List<String> command = new ArrayList<>();
        command.add("node");
        command.add(installer.cliEntry().toString());
        command.add("--mode"); command.add("rpc");
        command.add("--session-id"); command.add(sessionId);
        command.add("--session-dir"); command.add(sessionDir.toString());
        command.add("--model"); command.add(modelRef(manifest));
        command.add("--system-prompt"); command.add(systemPrompt);
        command.addAll(manifest.cliArgs());
        if (skillPaths != null) {
            for (Path skill : skillPaths) {
                command.add("--skill");
                command.add(skill.toString());
            }
        }

        Map<String, String> env = new LinkedHashMap<>();
        boolean withToken = scopeToken != null && !scopeToken.isBlank();
        // 带用户令牌的流用 act 配置目录：它的 mcp.json 里声明了令牌请求头（接口写工具要用），
        // 只读流用的普通目录里没有这个请求头——pi 解析不到环境变量时整个 MCP 服务都连不上
        env.put("PI_CODING_AGENT_DIR", installer.agentDirFor(withToken).toString());
        env.put("HISI_LLM_KEY", anthropicApiKey == null ? "" : anthropicApiKey);
        // Node 版 MCP 服务是 pi 拉起的子进程，直接继承这份环境：后端地址每次都给；
        // 动作工具以用户身份调后端时用的令牌只在带用户的流里给——只读流的 MCP 进程里拿不到它，动作工具一律拒绝
        env.put("HISI_API_URL", installer.backendBaseUrl());
        if (withToken) {
            env.put("HISI_SCOPE_TOKEN", scopeToken);
        }

        PiProcess pi = null;
        RunState state = new RunState(listener);
        Map<String, Object> stop = new LinkedHashMap<>();

        try {
            pi = new PiProcess(command, workdir, env, state::accept);
            live.add(pi);
            log.info("[PiAgent] flow={} session={} workdir={} tools={}", flow, sessionId, workdir, manifest.tools().size());

            // prompt 的响应只表示「已受理」：success=false 或被扩展直接处理（没有起 run）时不能干等 agent_settled
            Map<String, Object> accepted = pi.prompt(userPrompt).get(5, TimeUnit.MINUTES);
            if (!Boolean.TRUE.equals(accepted.get("success"))) {
                throw new IllegalStateException("pi 未受理 prompt：" + accepted.get("error"));
            }
            Object disposition = accepted.get("data") instanceof Map<?, ?> data ? data.get("disposition") : null;
            if ("handled".equals(disposition)) {
                log.info("[PiAgent] flow={} session={} prompt 被扩展直接处理，没有启动 run", flow, sessionId);
                return state.result("handled", false);
            }

            // 轮询等待 agent_settled：期间响应取消、进程意外退出与 deadline
            long deadlineAt = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(deadlineMinutes);
            while (true) {
                try {
                    stop = state.settled.get(5, TimeUnit.SECONDS);
                    break;
                } catch (java.util.concurrent.TimeoutException ignored) {
                    if (cancelled.getAsBoolean()) {
                        log.info("[PiAgent] flow={} session={} 已取消，中止 pi 进程", flow, sessionId);
                        pi.kill();
                        return state.result("cancelled", false);
                    }
                    if (!pi.isAlive()) {
                        log.warn("[PiAgent] flow={} session={} pi 进程在结束前退出", flow, sessionId);
                        return state.result("process_died", false);
                    }
                    if (System.currentTimeMillis() > deadlineAt) {
                        log.warn("[PiAgent] flow={} session={} 超时（{} 分钟），强杀", flow, sessionId, deadlineMinutes);
                        pi.kill();
                        return state.result("timeout", true);
                    }
                }
            }
            if (Boolean.TRUE.equals(stop.get("aborted"))) {
                return state.result("aborted", false);
            }
            PiResult result = state.result(PiResult.SETTLED, false);
            if (result.note() != null) {
                log.warn("[PiAgent] flow={} session={} 结束但没有拿到完整结论：{}（stopReason={}）",
                        flow, sessionId, result.note(), result.stopReason());
            }
            return result;
        } catch (Exception e) {
            if (pi != null) {
                pi.kill();
            }
            throw new IllegalStateException("pi 流执行失败（flow=" + flow + "）: " + e.getMessage(), e);
        } finally {
            if (pi != null) {
                live.remove(pi);
                pi.stop();
            }
        }
    }

    static boolean isAssistant(Map<String, Object> event) {
        return event.get("message") instanceof Map<?, ?> message && "assistant".equals(String.valueOf(message.get("role")));
    }

    /** 流式增量：{@code message_update.assistantMessageEvent} 的 {@code text_delta.delta}。 */
    static void appendDelta(Map<String, Object> event, StringBuilder buffer) {
        if (event.get("assistantMessageEvent") instanceof Map<?, ?> update
                && "text_delta".equals(String.valueOf(update.get("type")))
                && update.get("delta") != null) {
            buffer.append(update.get("delta"));
        }
    }

    /**
     * 权威文本：{@code message_end.message.content} 里的 text 块（content 也可能是纯字符串）。
     * 只有带正文的消息才覆盖——最后一条回复没有正文时（比如被截断、只剩思考），不能把前面的结论冲掉。
     */
    static void replaceFinalText(Map<String, Object> event, StringBuilder buffer) {
        String text = assistantText(event);
        if (text != null && !text.isBlank()) {
            buffer.setLength(0);
            buffer.append(text);
        }
    }

    /** 助手消息里的正文（text 块拼接）；不是助手消息时返回 null。 */
    static String assistantText(Map<String, Object> event) {
        if (!isAssistant(event)) {
            return null;
        }
        Object content = ((Map<?, ?>) event.get("message")).get("content");
        if (content instanceof String text) {
            return text;
        }
        if (!(content instanceof List<?> blocks)) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (Object block : blocks) {
            if (block instanceof Map<?, ?> b && "text".equals(String.valueOf(b.get("type"))) && b.get("text") != null) {
                text.append(b.get("text"));
            }
        }
        return text.toString();
    }

    static boolean isCompactionEvent(String type) {
        return "auto_compaction_start".equals(type) || "auto_compaction_end".equals(type)
                || "compaction_start".equals(type) || "compaction_end".equals(type);
    }

    /** pi 的会话压缩结束事件里的失败原因；成功时为 null。 */
    static String compactionError(Map<String, Object> event) {
        Object error = event.get("errorMessage");
        return error == null ? null : String.valueOf(error);
    }

    static String stopReasonOf(Map<String, Object> event) {
        if (!isAssistant(event)) {
            return null;
        }
        Object reason = ((Map<?, ?>) event.get("message")).get("stopReason");
        return reason == null ? null : String.valueOf(reason);
    }

    /** 助手消息里的模型报错（stopReason=error 时才有）；没有返回 null。 */
    static String modelErrorOf(Map<String, Object> event) {
        if (!isAssistant(event)) {
            return null;
        }
        Object error = ((Map<?, ?>) event.get("message")).get("errorMessage");
        return error == null ? null : String.valueOf(error);
    }

    /** 清单决定用哪个 provider：关思考的流走声明了推理能力的那个副本，pi 才会显式发送 thinking: disabled。 */
    private String modelRef(FlowToolManifest manifest) {
        String provider = PiAgentInstaller.MODEL_PROVIDER
                + (manifest.thinkingOff() ? PiAgentInstaller.NO_THINK_PROVIDER_SUFFIX : "");
        return provider + "/" + properties.getModelId();
    }

    /**
     * 一次运行里从 pi 事件流累积的状态：最终文本、工具调用、结束原因，以及没拿到完整结论时的说明。
     */
    static final class RunState {

        final CompletableFuture<Map<String, Object>> settled = new CompletableFuture<>();
        final StringBuilder finalText = new StringBuilder();
        final List<Map<String, Object>> toolCalls = new ArrayList<>();
        final AtomicReference<String> lastStopReason = new AtomicReference<>();
        final AtomicReference<String> compactionError = new AtomicReference<>();
        final AtomicReference<String> modelError = new AtomicReference<>();
        private final Consumer<Map<String, Object>> listener;

        RunState(Consumer<Map<String, Object>> listener) {
            this.listener = listener;
        }

        void accept(Map<String, Object> event) {
            String type = String.valueOf(event.getOrDefault("type", ""));
            if ("agent_settled".equals(type)) {
                settled.complete(event);
            } else if ("message_update".equals(type)) {
                appendDelta(event, finalText);
            } else if ("message_end".equals(type) && isAssistant(event)) {
                // message_end.message 是权威的完整消息：覆盖之前按增量拼出来的文本
                replaceFinalText(event, finalText);
                lastStopReason.set(stopReasonOf(event));
                // 模型调用出错（鉴权失败、限流…）时 pi 照常「结束」：把原因记下来，别让调用方只看到空结果
                if (modelErrorOf(event) != null) {
                    modelError.set(modelErrorOf(event));
                }
            } else if ("tool_execution_start".equals(type) || "tool_execution_end".equals(type)) {
                toolCalls.add(event);
            } else if (isCompactionEvent(type)) {
                // pi 自带的会话压缩：事件原样交给监听器展示；失败原因记下来，结束时说明白
                if (type.endsWith("_end") && compactionError(event) != null) {
                    compactionError.set(compactionError(event));
                }
            }
            notifyListener(listener, event);
        }

        /** 没拿到完整结论时的原因；正常结束返回 null。 */
        String note() {
            String error = modelError.get();
            if (error != null) {
                return "模型调用失败：" + error;
            }
            error = compactionError.get();
            if (error != null) {
                return "pi 会话压缩失败：" + error;
            }
            if ("length".equals(lastStopReason.get())) {
                return "模型最后一次回复没写完就被截断（stop=length，"
                        + (finalText.toString().isBlank() ? "没有产出正文" : "只拿到之前的正文") + "），通常是输入占满了上下文窗口";
            }
            return null;
        }

        PiResult result(String stopReason, boolean timedOut) {
            return new PiResult(finalText.toString(), List.copyOf(toolCalls), stopReason, timedOut, note());
        }
    }

    @PreDestroy
    public void shutdown() {
        for (PiProcess pi : live) {
            pi.kill();
        }
        live.clear();
    }
}
