package com.huawei.hisi.agentflow;

import com.huawei.hisi.ram.nodes.impl.RamClaudeJsonClient;
import com.huawei.hisi.ram.sdk.SendOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 把现有 {@link RamClaudeJsonClient} 适配为 {@link AgentLlmClient}。
 *
 * <p>v2 agent 流只依赖 {@link AgentLlmClient}。底层客户端已被标记为待移除，替换时只需换掉本适配器，
 * 各业务流不受影响。保留它的原因是它积累了 JSON 容错解析等经过线上验证的逻辑，重写风险更高。
 *
 * <p>在底层客户端之上补了两件它没有做的事：
 * <ul>
 *   <li>空正文重试：底层客户端遇到「模型没有输出正文」时返回空串而不抛异常。会先思考的推理模型在输出预算偏小时
 *       就会出现这种情况，预算全部用在思考上。这里放宽预算重试一次。</li>
 *   <li>单次调用时限：底层客户端的读超时长达 10 分钟，一次卡住的调用会让整个分析停在那里。
 *       超过时限即中断该调用并抛出 {@link AgentLlmTimeoutException}，由调用方走确定性兜底。</li>
 * </ul>
 */
@Slf4j
@Component
@SuppressWarnings("deprecation")
public class RamClaudeLlmAdapter implements AgentLlmClient {

    /** 证据推理与综合写作都需要稳定输出，统一用较低温度。 */
    private static final double TEMPERATURE = 0.2;

    /** 首次调用返回空正文时，输出预算放宽到的倍数。 */
    static final int RETRY_MULTIPLIER = 3;

    /** 重试时的输出预算上限，与配置里模型的最大输出保持一致。 */
    static final int MAX_TOKENS_CAP = 32_000;

    /** 执行底层阻塞调用的线程池；调用被时限中断时会取消这些线程上的任务。 */
    private static final ExecutorService CALL_EXECUTOR = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "agent-llm");
        thread.setDaemon(true);
        return thread;
    });

    private final RamClaudeJsonClient delegate;
    private final int callTimeoutSeconds;
    private final boolean singleShotThinking;

    public RamClaudeLlmAdapter(RamClaudeJsonClient delegate,
                               @Value("${agentflow.llm.call-timeout-seconds:300}") int callTimeoutSeconds,
                               @Value("${agentflow.llm.single-shot-thinking:false}") boolean singleShotThinking) {
        this.delegate = delegate;
        this.callTimeoutSeconds = callTimeoutSeconds;
        this.singleShotThinking = singleShotThinking;
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    @Override
    public String completeText(String systemPrompt, String userPrompt, int maxTokens) {
        long start = System.currentTimeMillis();
        String text = callWithTimeout(
                () -> delegate.callText(systemPrompt, userPrompt, options(maxTokens)), "文本调用");
        if (text.isBlank()) {
            int retryTokens = retryBudget(maxTokens);
            log.warn("[AgentLlm] 首次调用返回空正文（maxTokens={}，{}ms），放宽到 {} 重试一次",
                    maxTokens, System.currentTimeMillis() - start, retryTokens);
            text = callWithTimeout(
                    () -> delegate.callText(systemPrompt, userPrompt, options(retryTokens)), "文本调用重试");
        }
        log.info("[AgentLlm] 文本调用完成: {}ms，输出 {} 字符", System.currentTimeMillis() - start, text.length());
        return text;
    }

    @Override
    public Map<String, Object> completeJson(String systemPrompt, String userPrompt, int maxTokens) {
        long start = System.currentTimeMillis();
        Map<String, Object> json;
        try {
            json = callWithTimeout(
                    () -> delegate.callJson(systemPrompt, userPrompt, options(maxTokens)), "JSON 调用");
        } catch (IllegalStateException cutOff) {
            // 底层客户端遇到「输出被截断、解析不出 JSON」时抛异常而不是返回空：同样是输出预算不够，走同一条放宽重试的路
            if (!isTruncatedOutput(cutOff)) {
                throw cutOff;
            }
            json = null;
        }
        if (json == null || json.isEmpty()) {
            int retryTokens = retryBudget(maxTokens);
            log.warn("[AgentLlm] 首次调用没有返回 JSON（maxTokens={}，{}ms），放宽到 {} 重试一次",
                    maxTokens, System.currentTimeMillis() - start, retryTokens);
            json = callWithTimeout(
                    () -> delegate.callJson(systemPrompt, userPrompt, options(retryTokens)), "JSON 调用重试");
        }
        log.info("[AgentLlm] JSON 调用完成: {}ms", System.currentTimeMillis() - start);
        if (json == null || json.isEmpty()) {
            throw new IllegalStateException("LLM 未返回可解析的 JSON");
        }
        return json;
    }

    /** 在独立线程上执行阻塞调用并限时；超时即取消该调用。 */
    <T> T callWithTimeout(Supplier<T> call, String what) {
        Future<T> future = CALL_EXECUTOR.submit(call::get);
        try {
            return future.get(callTimeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new AgentLlmTimeoutException(what + "超过 " + callTimeoutSeconds + " 秒未返回");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException(what + "失败: " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException(what + "被中断");
        }
    }

    /** 是「输出被截断所以解析不出 JSON」，而不是超时或调用本身出错（那两种不该重试）。 */
    static boolean isTruncatedOutput(IllegalStateException e) {
        if (e instanceof AgentLlmTimeoutException) {
            return false;
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        return message.contains("not valid JSON") || message.contains("截断")
                || message.toLowerCase(java.util.Locale.ROOT).contains("truncat");
    }

    static int retryBudget(int maxTokens) {
        return (int) Math.min((long) maxTokens * RETRY_MULTIPLIER, MAX_TOKENS_CAP);
    }

    private SendOptions options(int maxTokens) {
        SendOptions options = new SendOptions(delegate.defaultModel(), maxTokens, TEMPERATURE, null);
        // 这里的调用都是一次性的结构化输出：会先思考的模型把思考 token 也算在预算里，
        // 提示词一长就出现「预算全花在思考上、正文为空」。实测同一个生成测试类的请求，
        // 默认设置 78 秒且 89% 的输出预算是思考，关掉思考 8 秒、正文完整。
        return singleShotThinking ? options : options.withoutThinking();
    }
}
