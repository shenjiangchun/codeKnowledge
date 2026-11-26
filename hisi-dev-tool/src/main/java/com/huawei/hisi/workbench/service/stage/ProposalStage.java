package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.workbench.service.StageContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 2 · 方案确认：基于证据包生成 2–3 个候选方案（风险 / 工作量 / 触及文件），
 * 停下等用户确认。被驳回时带上反馈重跑，再次停下。
 *
 * <p>提示词给出准确的 JSON 结构；结构不符时把缺失项反馈给模型重试一次。模型经常第一次
 * 直接返回一个数组（没有 options 外壳），所以「顶层必须是对象」要写明。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProposalStage implements StageHandler {

    private final AgentLlmClient llm;

    @Override
    public String key() {
        return "proposal";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        ctx.log("归纳根因假设，生成候选方案");

        String system = """
                你是资深工程师，负责为一个技术债待办生成修复方案。
                要求：
                - 给出 2–3 个候选方案，id 依次为 A、B、C，分别对应「按建议直接修」「修复并补上防护」「先止血」三种思路；
                - 每个方案写清楚：做什么、怎么验证、风险（low/medium/high）、预计工作量、预计触及的文件数；
                - 方案必须基于给出的证据，不得编造；证据不足以确定根因时，在 summary 里明确说还缺什么；
                - 只输出下面这个结构的 JSON 对象：顶层必须是对象，不要输出数组，不要代码围栏，不要解释文字。
                {"options":[{"id":"A","title":"一句话方案名","summary":"做什么、怎么验证、还缺什么","risk":"low","effort":"例如 0.5 天","touchedFiles":1}],"recommendation":"B"}
                """;
        StringBuilder user = new StringBuilder();
        user.append("待办：").append(ctx.item().getTitle()).append('\n');
        user.append("项目：").append(ctx.item().getProjectPath()).append('\n');
        if (ctx.item().getLocation() != null) {
            user.append("位置：").append(ctx.item().getLocation()).append('\n');
        }
        user.append("为什么重要：").append(ctx.item().getWhy() == null ? "（无）" : ctx.item().getWhy()).append('\n');
        user.append("已有建议：").append(ctx.item().getSuggestion() == null ? "（无）" : ctx.item().getSuggestion()).append('\n');
        user.append("证据：\n");
        for (String anchor : anchorsOf(ctx)) {
            user.append("  - ").append(anchor).append('\n');
        }
        if (ctx.rejectFeedback() != null && !ctx.rejectFeedback().isBlank()) {
            user.append("\n上一轮方案被驳回，反馈如下，请据此重新生成：\n").append(ctx.rejectFeedback()).append('\n');
        }

        Map<String, Object> payload = null;
        List<String> problems = List.of("尚未生成");
        for (int attempt = 0; attempt < 2; attempt++) {
            String prompt = user.toString();
            if (attempt > 0) {
                prompt = prompt + "\n\n上一次输出不符合要求：" + problems
                        + "。请严格按约定的 JSON 对象结构重新输出，顶层必须有 options 数组。";
            }
            try {
                payload = llm.completeJson(system, prompt, 6000);
            } catch (Exception e) {
                log.warn("[WorkbenchFix] 方案生成失败 runId={} attempt={}: {}", ctx.run().getId(), attempt, e.getMessage());
                payload = null;
                continue;
            }
            problems = problemsOf(payload);
            if (problems.isEmpty()) {
                break;
            }
            log.warn("[WorkbenchFix] 方案结构不符 runId={} attempt={}: {}", ctx.run().getId(), attempt, problems);
        }
        if (payload == null || !problems.isEmpty()) {
            return StageOutcome.failed("方案生成失败：两次尝试均不符合结构要求（" + problems + "）");
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("options", normalizeOptions(payload.get("options")));
        output.put("recommendation", payload.get("recommendation"));
        ctx.publish("proposal", output);
        ctx.log("候选方案已就绪，等待确认");
        return StageOutcome.wait(output);
    }

    /** 确认界面与后续阶段依赖 id / title / summary / risk / effort / touchedFiles；缺的补默认值，id 按顺序补 A/B/C。 */
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> normalizeOptions(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> options)) {
            return out;
        }
        String[] defaultIds = {"A", "B", "C", "D"};
        for (int i = 0; i < options.size(); i++) {
            if (!(options.get(i) instanceof Map<?, ?> option)) {
                continue;
            }
            Map<String, Object> normalized = new LinkedHashMap<>((Map<String, Object>) option);
            String summary = option.get("summary") == null ? "" : String.valueOf(option.get("summary")).trim();
            normalized.put("id", option.get("id") == null || String.valueOf(option.get("id")).isBlank()
                    ? (i < defaultIds.length ? defaultIds[i] : String.valueOf(i + 1)) : String.valueOf(option.get("id")));
            if (option.get("title") == null || String.valueOf(option.get("title")).isBlank()) {
                int cut = summary.indexOf('。');
                String title = cut > 0 ? summary.substring(0, cut) : summary;
                normalized.put("title", title.length() > 40 ? title.substring(0, 40) + "…" : title);
            }
            normalized.put("summary", summary);
            normalized.putIfAbsent("risk", "medium");
            normalized.putIfAbsent("effort", "未评估");
            normalized.putIfAbsent("touchedFiles", 0);
            out.add(normalized);
        }
        return out;
    }

    /** 结构检查：只看后续阶段和确认界面真正依赖的字段。 */
    @SuppressWarnings("unchecked")
    static List<String> problemsOf(Map<String, Object> payload) {
        List<String> problems = new ArrayList<>();
        if (payload == null) {
            problems.add("没有返回内容");
            return problems;
        }
        if (!(payload.get("options") instanceof List<?> options) || options.isEmpty()) {
            problems.add("缺少非空的 options 数组");
            return problems;
        }
        for (int i = 0; i < options.size(); i++) {
            if (!(options.get(i) instanceof Map<?, ?> option)) {
                problems.add("options[" + i + "] 不是对象");
                continue;
            }
            if (option.get("summary") == null || String.valueOf(option.get("summary")).isBlank()) {
                problems.add("options[" + i + "] 缺少 summary");
            }
        }
        return problems;
    }

    private static List<String> anchorsOf(StageContext ctx) {
        Object anchors = ctx.artifact("evidence").get("anchors");
        if (!(anchors instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object anchor : list) {
            if (anchor instanceof Map<?, ?> map && map.get("text") != null) {
                String text = String.valueOf(map.get("text"));
                out.add(map.get("id") + " " + (text.length() > 1500 ? text.substring(0, 1500) + "…（截断）" : text));
            }
            if (out.size() >= 24) {
                break;
            }
        }
        return out;
    }
}
