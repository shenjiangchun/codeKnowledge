package com.huawei.hisi.workbench.service;

import com.huawei.hisi.workbench.model.WorkbenchItem;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenSpec 文档的确定性构建与落盘：把 Spec / 任务清单的 JSON 契约转成业界 OpenSpec
 * （Fission-AI/OpenSpec）约定的 Markdown 三件套，落到 worktree 的
 * {@code openspec/changes/<change-id>/} 目录，供 pi 编码 agent 与后续人工审阅。
 *
 * <p>约定取自 OpenSpec 官方仓库（README 与真实 changeset 样例，2026-11-28 核对）：
 * <ul>
 *   <li>{@code proposal.md}：{@code ## Why} / {@code ## What changes} / {@code ## Impact} 三段；</li>
 *   <li>{@code tasks.md}：{@code ## Implementation} + {@code - [ ]} 实现清单；</li>
 *   <li>{@code specs/<capability>/spec.md}：增量文件，{@code ## ADDED Requirements}
 *       （业界还支持 MODIFIED / REMOVED / RENAMED；本流修复类变更统一用 ADDED），
 *       每条需求 {@code ### Requirement: <名称>} + 场景 {@code #### Scenario: <名称>}，
 *       场景正文用 {@code - **WHEN** …} / {@code - **THEN** …} 要点；</li>
 *   <li>变更目录是 {@code openspec/changes/&lt;id&gt;/}，归档到 {@code openspec/changes/archive/}。</li>
 * </ul>
 *
 * <p>本类只做「JSON → Markdown」的保形转换，不发明字段：Spec 的验收标准映射为需求、
 * 测试用例映射为场景；任务清单逐条映射为 tasks.md 的勾选项。转换是确定性的，不依赖模型再写一遍文档。
 */
public final class OpenSpecDocs {

    /** 变更目录名：随运行 ID 唯一。 */
    public static String changeId(long runId) {
        return "wb-r" + runId;
    }

    /** capability 名：取待办位置里的类名转 kebab-case；没有位置信息时退回 fix。 */
    public static String capabilityOf(WorkbenchItem item) {
        String location = item.getLocation() == null ? "" : item.getLocation();
        String className = location;
        int hash = className.indexOf('#');
        if (hash >= 0) {
            className = className.substring(0, hash);
        }
        int dot = className.lastIndexOf('.');
        if (dot >= 0) {
            className = className.substring(dot + 1);
        }
        return kebab(className);
    }

    static String kebab(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    out.append('-');
                }
                out.append(Character.toLowerCase(c));
            } else if (Character.isLetterOrDigit(c)) {
                out.append(Character.toLowerCase(c));
            }
        }
        return out.length() > 0 ? out.toString() : "fix";
    }

    /**
     * proposal.md：为什么改（证据与待办）、改什么（选定方案）、影响面。
     */
    public static String proposalMd(WorkbenchItem item, Map<String, Object> evidence,
                                    Map<String, Object> proposal, String chosen) {
        StringBuilder md = new StringBuilder();
        md.append("## Why\n\n");
        md.append(item.getTitle() == null ? "（无）" : item.getTitle()).append('\n');
        if (item.getWhy() != null && !item.getWhy().isBlank()) {
            md.append("\n").append(item.getWhy()).append('\n');
        }
        md.append("\n证据：\n");
        List<String> anchors = anchorTexts(evidence);
        if (anchors.isEmpty()) {
            md.append("- （无）\n");
        } else {
            for (String anchor : anchors) {
                md.append("- ").append(anchor).append('\n');
            }
        }

        md.append("\n## What changes\n\n");
        String optionText = null;
        for (Map<String, Object> option : mapsOf(proposal.get("options"))) {
            if (chosen != null && chosen.equals(String.valueOf(option.get("id")))) {
                optionText = String.valueOf(option.getOrDefault("summary", option.get("title")));
            }
        }
        md.append(optionText == null || optionText.isBlank() ? "（选定方案：" + chosen + "）" : optionText).append('\n');

        md.append("\n## Impact\n\n");
        if (item.getLocation() != null && !item.getLocation().isBlank()) {
            md.append("- 涉及位置：").append(item.getLocation()).append('\n');
        }
        md.append("- 严重度：").append(item.getSeverity() == null ? "P2" : item.getSeverity()).append('\n');
        md.append("- 修复流程：Spec（本文档目录）→ 任务清单 → 迭代开发 → 用例回归 → 全链路 E2E → 提交推送\n");
        return md.toString();
    }

    /**
     * spec 增量：每条验收标准一个 Requirement；测试用例按序循环分配到需求下成为 Scenario。
     * 场景保留用例的 type/落点/通过标准，映射成 WHEN/THEN 要点。
     */
    public static String specDeltaMd(Map<String, Object> spec) {
        StringBuilder md = new StringBuilder();
        md.append("## ADDED Requirements\n\n");
        List<Object> criteria = new ArrayList<>();
        if (spec.get("acceptanceCriteria") instanceof List<?> list) {
            criteria.addAll(list);
        }
        if (criteria.isEmpty()) {
            criteria.add(spec.get("goal") == null ? "修复目标" : spec.get("goal"));
        }
        List<Map<String, Object>> cases = mapsOf(spec.get("testCases"));
        for (int i = 0; i < criteria.size(); i++) {
            String criterion = String.valueOf(criteria.get(i)).trim();
            md.append("### Requirement: ").append(requirementTitle(criterion)).append("\n");
            md.append(criterion).append("\n\n");
            for (int j = i; j < cases.size(); j += criteria.size()) {
                Map<String, Object> testCase = cases.get(j);
                String name = String.valueOf(testCase.getOrDefault("name", "用例 " + (j + 1)));
                String type = String.valueOf(testCase.getOrDefault("type", ""));
                String target = String.valueOf(testCase.getOrDefault("target", ""));
                String verify = String.valueOf(testCase.getOrDefault("verify", ""));
                md.append("#### Scenario: ").append(name).append('\n');
                md.append("- **WHEN** ").append(whenOf(type, target)).append('\n');
                md.append("- **THEN** ").append(verify.isBlank() ? "符合通过标准" : verify).append('\n');
                md.append('\n');
            }
        }
        if (cases.isEmpty()) {
            md.append("#### Scenario: 基线验证\n");
            md.append("- **WHEN** 按验收标准执行验证\n");
            md.append("- **THEN** 全部验收标准满足\n");
        }
        return md.toString();
    }

    private static String requirementTitle(String criterion) {
        String title = criterion.replaceAll("\\s+", " ");
        return title.length() <= 48 ? title : title.substring(0, 48) + "…";
    }

    private static String whenOf(String type, String target) {
        return switch (type) {
            case "api" -> "对真实服务发起请求 " + target;
            case "e2e" -> "执行前端端到端用例 " + target;
            default -> "执行用例，落点 " + target;
        };
    }

    /** tasks.md：实现清单，一行一个任务，附文件与验证方式。 */
    public static String tasksMd(List<Map<String, Object>> tasks) {
        StringBuilder md = new StringBuilder();
        md.append("## Implementation\n\n");
        for (Map<String, Object> task : tasks) {
            md.append("- [ ] ").append(task.get("title")).append('\n');
            if (task.get("file") != null) {
                md.append("  - 文件：`").append(task.get("file")).append("`\n");
            }
            if (task.get("anchor") != null && !String.valueOf(task.get("anchor")).isBlank()) {
                md.append("  - 涉及的类或方法：").append(task.get("anchor")).append('\n');
            }
            if (task.get("verify") != null && !String.valueOf(task.get("verify")).isBlank()) {
                md.append("  - 验证：").append(task.get("verify")).append('\n');
            }
        }
        return md.toString();
    }

    /**
     * 把三件套落盘到 worktree：{@code openspec/changes/<changeId>/proposal.md、tasks.md、
     * specs/<capability>/spec.md}。幂等（重复调用覆盖写）。
     */
    public static void write(Path worktree, String changeId, String capability,
                             String proposalMd, String tasksMd, String specDeltaMd) {
        try {
            Path changeDir = worktree.resolve("openspec/changes").resolve(changeId);
            Files.createDirectories(changeDir.resolve("specs").resolve(capability));
            Files.writeString(changeDir.resolve("proposal.md"), proposalMd, StandardCharsets.UTF_8);
            Files.writeString(changeDir.resolve("tasks.md"), tasksMd, StandardCharsets.UTF_8);
            Files.writeString(changeDir.resolve("specs").resolve(capability).resolve("spec.md"),
                    specDeltaMd, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("写入 openspec 变更目录失败: " + e.getMessage(), e);
        }
    }

    // ──────────────────────────── 工具 ────────────────────────────

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mapsOf(Object raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    out.add((Map<String, Object>) map);
                }
            }
        }
        return out;
    }

    private static List<String> anchorTexts(Map<String, Object> evidence) {
        List<String> out = new ArrayList<>();
        if (evidence.get("anchors") instanceof List<?> anchors) {
            for (Object anchor : anchors) {
                if (anchor instanceof Map<?, ?> map && map.get("text") != null) {
                    String text = String.valueOf(map.get("text"));
                    out.add(map.get("id") + " " + (text.length() > 200 ? text.substring(0, 200) + "…" : text));
                }
                if (out.size() >= 12) {
                    break;
                }
            }
        }
        return out;
    }

    private OpenSpecDocs() {
    }
}
