package com.huawei.hisi.piagent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 一个 pi RPC 子进程：stdio 上的流式 JSONL 协议（一行一条）。
 *
 * <p>命令对象写入 stdin（带自增 id），stdout 上带相同 id 的 {@code response} 是对应结果；
 * 不带 id 的 JSON 对象是异步事件（{@code message_update}/{@code tool_execution_*}/{@code agent_settled}…），
 * 逐条交给事件回调。终止：先 RPC {@code abort}，再关 stdin（有序关停），超时强杀。
 */
@Slf4j
public class PiProcess {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Process process;
    private final AtomicLong commandSeq = new AtomicLong();
    private final Map<Long, CompletableFuture<Map<String, Object>>> pending = new ConcurrentHashMap<>();
    private final Consumer<Map<String, Object>> eventListener;
    private final Thread reader;
    private volatile boolean closed = false;

    public PiProcess(List<String> command, Path workdir, Map<String, String> env,
                     Consumer<Map<String, Object>> eventListener) throws Exception {
        this.eventListener = eventListener;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workdir.toFile());
        pb.redirectErrorStream(true);
        pb.environment().putAll(env);
        this.process = pb.start();
        this.reader = new Thread(this::readLoop, "pi-rpc-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    /** 发送命令并等待响应（响应可能早于 agent_settled 事件——只确认命令被接受）。 */
    public CompletableFuture<Map<String, Object>> send(String commandType, Map<String, Object> payload) {
        long id = commandSeq.incrementAndGet();
        CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
        pending.put(id, future);
        Map<String, Object> envelope = new java.util.LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("type", commandType);
        envelope.putAll(payload);
        try {
            synchronized (this) {
                BufferedWriter writer = new BufferedWriter(
                        new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
                writer.write(objectMapper.writeValueAsString(envelope));
                writer.write('\n');
                writer.flush();
            }
        } catch (Exception e) {
            pending.remove(id);
            future.completeExceptionally(e);
        }
        return future;
    }

    public CompletableFuture<Map<String, Object>> prompt(String message) {
        return send("prompt", Map.of("message", message));
    }

    public CompletableFuture<Map<String, Object>> abort() {
        return send("abort", Map.of());
    }

    private void readLoop() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> record;
                try {
                    record = objectMapper.readValue(line, new TypeReference<Map<String, Object>>() {});
                } catch (Exception e) {
                    log.debug("[PiAgent] 无法解析的输出行: {}", line.substring(0, Math.min(120, line.length())));
                    continue;
                }
                Object idObj = record.get("id");
                if (idObj instanceof Number id) {
                    CompletableFuture<Map<String, Object>> future = pending.remove(id.longValue());
                    if (future != null) {
                        future.complete(record);
                    }
                    continue;
                }
                try {
                    eventListener.accept(record);
                } catch (Exception e) {
                    log.warn("[PiAgent] 事件回调异常: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            if (!closed) {
                log.warn("[PiAgent] 读取线程退出: {}", e.getMessage());
            }
        }
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    /** 有序关停：abort → 关 stdin → 等退出 → 强杀。 */
    public void stop() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (process.isAlive()) {
                try {
                    abort().get(5, TimeUnit.SECONDS);
                } catch (Exception ignored) {
                }
            }
            try {
                process.getOutputStream().close();
            } catch (Exception ignored) {
            }
            boolean exited = process.waitFor(10, TimeUnit.SECONDS);
            if (!exited) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (Exception e) {
            log.warn("[PiAgent] 停止 pi 进程异常: {}", e.getMessage());
        }
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("pi process stopped")));
        pending.clear();
    }

    /** 强杀（deadline 到点用）。 */
    public void kill() {
        closed = true;
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        pending.values().forEach(f -> f.completeExceptionally(new IllegalStateException("pi process killed")));
        pending.clear();
    }
}
