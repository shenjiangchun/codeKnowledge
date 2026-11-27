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
    private final Thread errReader;
    private volatile boolean closed = false;

    public PiProcess(List<String> command, Path workdir, Map<String, String> env,
                     Consumer<Map<String, Object>> eventListener) throws Exception {
        this.eventListener = eventListener;
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workdir.toFile());
        // stderr 不并入 stdout：pi 的 RPC 协议是 stdout 上一行一条 JSON，stderr 是它的诊断输出。
        // 以前并流后，启动崩溃（配置错误、参数不认等）打印在 stderr 的原因会被当成「无法解析的行」
        // 降成 DEBUG 吞掉——进程秒退时只剩一句「管道已结束」，无从定位。
        pb.redirectErrorStream(false);
        pb.environment().putAll(env);
        this.process = pb.start();
        this.reader = new Thread(this::readLoop, "pi-rpc-reader");
        this.reader.setDaemon(true);
        this.reader.start();
        this.errReader = new Thread(this::readErrorLoop, "pi-rpc-stderr");
        this.errReader.setDaemon(true);
        this.errReader.start();
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
        if (!closed) {
            // stdout 关闭通常意味着 pi 已退出：把退出码和刚才 stderr 上的最后几行一起记下来，
            // 否则调用方只能看到一句「管道已结束」
            Integer rc = null;
            try {
                rc = process.waitFor(2, TimeUnit.SECONDS) ? process.exitValue() : null;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            log.warn("[PiAgent] pi 的 stdout 已关闭（exitValue={}）{}",
                    rc == null ? "still-running" : rc, lastStderrSummary());
            // 进程已结束：还在等响应的调用方必须被通知到。以前只是记一条日志，pending 里的 future
            // 无人完成——往已死进程的 stdin 写不一定抛异常（进了缓冲区、flush 未报错），调用方就一直挂着，
            // 只能靠外层超时兜底。
            IllegalStateException gone = new IllegalStateException(
                    "pi 进程已结束（exitValue=" + (rc == null ? "unknown" : rc) + "），不再响应命令");
            failAllPending(gone);
        }
    }

    private void failAllPending(Exception cause) {
        // 先标记关闭，避免 stop()/kill() 与这里重复完成同一个 future
        closed = true;
        pending.values().forEach(f -> f.completeExceptionally(cause));
        pending.clear();
    }

    /** stderr 上的最近几行，供进程异常退出时一并记录（pi 的崩溃原因都在这里）。 */
    private final java.util.ArrayDeque<String> lastStderr = new java.util.ArrayDeque<>();
    private static final int STDERR_TAIL = 8;

    private void readErrorLoop() {
        try (BufferedReader er = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = er.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                // pi 正常运行时 stderr 也会有进度/警告输出：逐行 DEBUG，但留最近几行用于异常退出时定位
                log.debug("[PiAgent:stderr] {}", line);
                synchronized (lastStderr) {
                    lastStderr.addLast(line.length() > 300 ? line.substring(0, 300) + "…" : line);
                    if (lastStderr.size() > STDERR_TAIL) {
                        lastStderr.removeFirst();
                    }
                }
            }
        } catch (Exception e) {
            if (!closed) {
                log.warn("[PiAgent] stderr 读取线程退出: {}", e.getMessage());
            }
        }
    }

    private String lastStderrSummary() {
        synchronized (lastStderr) {
            if (lastStderr.isEmpty()) {
                return "（stderr 无输出）";
            }
            return " | stderr 尾部: " + String.join(" ⏎ ", lastStderr);
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
