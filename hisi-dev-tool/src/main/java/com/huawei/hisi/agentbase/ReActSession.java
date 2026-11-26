package com.huawei.hisi.agentbase;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.huawei.hisi.piagent.PiAgentInstaller;
import com.huawei.hisi.piagent.PiFlowClient;
import com.huawei.hisi.piagent.context.JsonPayload;
import com.huawei.hisi.ram.contract.SchemaValidator;
import com.huawei.hisi.ram.contract.ValidationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 按 {@link AgentRegistry.ReactAgent} 声明装配并执行一次 pi 会话——统一入口下所有 ReAct agent 的公共执行方式。
 *
 * <p>装配内容全部来自声明：工具清单由 {@code flow} 键解析（PiFlowClient）、skill 由 {@code skills} 名解析到
 * pi 的 skills 目录（安装器启动时已同步）、契约由 {@code contractSchema} 校验。声明有契约时，输出不合契约
 * 就带着具体问题开新会话修正（最多 {@code repairRounds} 轮，修正轮里只改格式、不动结论——上下文预算与
 * 会话压缩由 PiFlowClient 统一处理，这里不管）。
 *
 * <p>自由文本 agent（{@code contractSchema} 为 null）不做任何解析，正文原样返回，由调用方决定怎么用。
 */
@Slf4j
@Component
public class ReActSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PiFlowClient pi;
    private final PiAgentInstaller installer;
    private final SchemaValidator validator;

    public ReActSession(PiFlowClient pi, PiAgentInstaller installer, SchemaValidator validator) {
        this.pi = pi;
        this.installer = installer;
        this.validator = validator;
    }

    /** 一次 ReAct 会话的结果：正文 + 已过契约的 JSON（自由文本 agent 时 {@code json} 为 null）。 */
    public record Result(String text, Map<String, Object> json, String note) {
        public static Result of(String text, Map<String, Object> json, String note) {
            return new Result(text, json, note);
        }
    }

    /**
     * @param scopeToken 动作工具需要的用户令牌（只读 agent 传 null）；经环境变量传给 MCP 服务，不落盘
     */
    public Result run(AgentRegistry.ReactAgent agent, String sessionId, Path workdir,
                      String systemPrompt, String userPrompt,
                      Consumer<Map<String, Object>> eventSink, BooleanSupplier cancelled, String scopeToken) {
        List<Path> skills = new ArrayList<>();
        for (String name : agent.skills()) {
            Path skill = installer.skillPath(name);
            if (java.nio.file.Files.isDirectory(skill)) {
                skills.add(skill);
            } else {
                log.warn("[ReActSession] skill 未同步到 pi 目录: {}（安装器启动时会同步，忽略即可）", name);
            }
        }
        PiFlowClient.PiResult first = pi.runWithToken(agent.flow(), sessionId, workdir,
                skills.isEmpty() ? null : skills, systemPrompt, userPrompt, eventSink, cancelled, scopeToken);
        if (first.timedOut()) {
            return Result.of(first.finalText(), null, "pi 会话超时");
        }
        if (first.note() != null && first.finalText().isBlank()) {
            return Result.of("", null, first.note());
        }
        if (agent.contractSchema() == null) {
            return Result.of(first.finalText(), null, null);
        }

        String text = first.finalText();
        Map<String, Object> json = firstObject(text);
        String problem = json == null ? "输出不是 JSON" : contractProblem(json, agent.contractSchema());
        for (int round = 0; problem != null && round < agent.repairRounds(); round++) {
            log.info("[ReActSession] agent={} 第 {} 轮修正：{}", agent.key(), round + 1, problem);
            PiFlowClient.PiResult repair = pi.runWithToken(agent.flow(), sessionId + "-fix" + (round + 1), workdir,
                    skills.isEmpty() ? null : skills, systemPrompt, repairPrompt(text, problem), eventSink, cancelled,
                    scopeToken);
            if ("cancelled".equals(repair.stopReason()) || "aborted".equals(repair.stopReason())) {
                return Result.of(repair.finalText(), json, "已取消");
            }
            if (repair.timedOut() || repair.finalText().isBlank()) {
                return Result.of(text, json, problem);
            }
            text = repair.finalText();
            json = firstObject(text);
            problem = json == null ? "输出不是 JSON" : contractProblem(json, agent.contractSchema());
        }
        if (problem != null) {
            return Result.of(text, json, problem);
        }
        return Result.of(text, json, null);
    }

    /** 契约校验的问题描述；符合契约返回 null。 */
    String contractProblem(Map<String, Object> json, String contractSchema) {
        ValidationResult result = validator.validate(contractSchema, json);
        return result.passed() ? null : "契约校验未过：" + result.missingFields() + result.violations();
    }

    /** 修正提示：带着具体的契约问题，在上一份输出基础上只修格式，不重查。 */
    static String repairPrompt(String previousOutput, String problem) {
        return "你上一次的输出没有通过契约校验。\n问题：" + problem + "\n\n"
                + "请在上一份输出基础上修正后重新输出，要求：\n"
                + "1. 只输出一个 JSON 对象，字段与系统提示词里的 schema 逐项一致；\n"
                + "2. 不要调用任何工具，不要输出 JSON 以外的任何文字、说明或标记；\n"
                + "3. 不改变已经得出的结论，只修格式与不符的字段。\n\n"
                + "上一份输出：\n" + previousOutput;
    }

    /** 正文里第一个能解析成 JSON 对象的片段（最外层优先）；整段都不是时返回 null。 */
    public static Map<String, Object> firstObject(String text) {
        for (String candidate : JsonPayload.objectCandidates(text)) {
            try {
                Map<String, Object> parsed = MAPPER.readValue(candidate, new TypeReference<Map<String, Object>>() {});
                if (!parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // 不是 JSON，试下一个片段
            }
        }
        return null;
    }
}
