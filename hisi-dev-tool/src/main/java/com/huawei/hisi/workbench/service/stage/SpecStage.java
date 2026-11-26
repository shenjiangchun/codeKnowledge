package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.agentflow.AgentLlmClient;
import com.huawei.hisi.workbench.service.StageContext;
import com.huawei.hisi.workbench.service.exec.CommandRunner;
import com.huawei.hisi.workbench.service.exec.HttpMappingScanner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 阶段 3 · Spec 生成：背景、目标、可验证的验收标准、非目标，以及——
 * 最重要的——测试用例清单（复现 / 单元 / API / E2E）。测试用例在这一步确定，
 * 后面的回归与 E2E 阶段只执行这份清单，不做临时发挥。
 *
 * <p>Spec 必须建立在证据上：提示词带上证据锚点、全部候选方案与选定的方案、代码里真实的 HTTP 接口映射。
 * 产出要过结构检查（字段齐全、至少一条可执行的回归用例、至少一条全链路用例、api 用例的路径确实存在于代码），
 * 不符时把问题反馈给模型重试一次。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SpecStage implements StageHandler {

    private static final Pattern LEADING_VERB = Pattern.compile("^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    /** api 用例的名称或通过标准里出现这些词，说明它依赖测试替身而不是真实服务。 */
    private static final Pattern TEST_DOUBLE = Pattern.compile(
            "(?i)mockbean|mockito|@mock|\\bmock\\b|testresttemplate|@springboottest|@webmvctest|webtestclient|@autowired");
    private static final List<String> CASE_TYPES = List.of("repro", "unit", "api", "e2e");

    private final AgentLlmClient llm;

    /** 与 E2E 阶段同一个开关：不启动服务做接口验证时，Spec 不要求 api / e2e 用例。 */
    @Value("${workbench.fix.e2e.start-service:true}")
    private boolean serviceE2e = true;

    @Override
    public String key() {
        return "spec";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        ctx.log("撰写 Spec：目标、验收标准与测试用例");

        Map<String, Object> evidence = ctx.artifact("evidence");
        Map<String, Object> proposal = ctx.artifact("proposal");
        String chosen = ctx.run().getChosenOption();

        String system = """
                你是 Spec-Driven Development 的规范编写者。基于证据和已确认的修复方案，产出一份修复 Spec。
                硬性要求：
                - 目标与验收标准必须可验证、可自动化，且与证据一致；证据没有支持的结论不要写成事实；
                - testCases 是这份 Spec 的核心，把验收标准落成具体用例：
                  repro=能稳定复现问题的用例，unit=单元/回归用例，api=通过 HTTP 调用服务接口的全链路用例，e2e=前端端到端用例；
                  每个用例给出 name（用例名）、target（落点）、verify（通过标准）；
                  repro/unit 的 target 写测试类的全限定名；api 的 target 写「方法 路径」，例如 GET /users/{id}；e2e 的 target 写前端测试文件；
                  POST / PUT 的 api 用例需要请求体时加 body 字段（JSON 字符串）；
                - api 用例是黑盒请求：对真实启动的服务直接发请求、断言可观察的结果（状态码、响应体字段）；
                  不能靠 mock、@MockBean、测试替身或测试上下文预设内部状态，这类场景写成 unit 用例；
                  同一路径的不同用例要靠请求本身区分（不同的 id、参数、请求体），通过标准必须是真实服务在这些请求上的实际行为；
                """ + (serviceE2e
                ? "                - 至少 1 条 repro 或 unit 用例；项目有 HTTP 接口（见「HTTP 接口清单」）时，至少 1 条 api 或 e2e 用例；\n"
                : "                - 至少 1 条 repro 或 unit 用例；这次修复不启动服务做接口验证，不要写 api / e2e 用例；\n")
                + """
                - api 用例的路径只能取自「HTTP 接口清单」，清单里没有的路径不得出现；只发不需要登录的请求；
                - verify 里涉及接口时写明期望的状态码，例如「返回 200，响应的 id 为 1」；
                - 用例要贴合项目语言与测试框架，不要写项目里不存在的框架；
                - 只输出下面这个结构的 JSON 对象：顶层必须是对象，不要代码围栏，不要解释文字，字段名逐字一致。
                {"background":"问题背景","goal":"本次修复要达到的目标","acceptanceCriteria":["可验证的验收标准"],"testCases":[{"type":"repro","name":"用例名","target":"落点","verify":"通过标准，涉及接口状态码时写明，例如：返回 404"}],"nonGoals":["明确不做的事"]}
                """;
        StringBuilder user = new StringBuilder();
        user.append("待办：").append(ctx.item().getTitle()).append('\n');
        user.append("项目：").append(ctx.item().getProjectPath()).append('\n');
        if (ctx.item().getLocation() != null) {
            user.append("位置：").append(ctx.item().getLocation()).append('\n');
        }
        user.append("背景：").append(ctx.item().getWhy() == null ? "（无）" : ctx.item().getWhy()).append('\n');

        user.append("\n候选方案：\n");
        for (Map<String, Object> option : optionsOf(proposal)) {
            user.append("- ").append(option.get("id")).append("：").append(option.get("summary")).append('\n');
        }
        user.append("已选定的方案：").append(chosen).append('\n');

        user.append("\n证据：\n");
        for (String anchor : anchorTexts(evidence)) {
            user.append("  - ").append(anchor).append('\n');
        }

        List<String> mappings = mappingsOf(evidence);
        user.append("\nHTTP 接口清单（api 用例的路径只能取自这里）：\n");
        if (mappings.isEmpty()) {
            user.append("  （代码里没有找到接口映射；不要编写 api 用例，改用 unit 用例覆盖）\n");
        } else {
            for (String mapping : mappings) {
                user.append("  - ").append(mapping).append('\n');
            }
        }
        String projectNote = CommandRunner.springContextNote(Path.of(ctx.item().getProjectPath()));
        if (!projectNote.isEmpty()) {
            user.append("\n项目现状（用例必须遵守）：\n").append(projectNote);
        }

        Map<String, Object> payload = null;
        List<String> problems = List.of("尚未生成");
        for (int attempt = 0; attempt < 2; attempt++) {
            String prompt = user.toString();
            if (attempt > 0) {
                prompt = prompt + "\n\n上一次输出不符合要求：" + problems
                        + "。请严格按约定的 JSON 对象结构重新输出，字段名逐字一致。";
            }
            try {
                payload = llm.completeJson(system, prompt, 12000);
            } catch (Exception e) {
                log.warn("[WorkbenchFix] Spec 生成失败 runId={} attempt={}: {}", ctx.run().getId(), attempt, e.getMessage());
                payload = null;
                continue;
            }
            problems = problemsOf(payload, mappings, serviceE2e);
            if (problems.isEmpty()) {
                break;
            }
            log.warn("[WorkbenchFix] Spec 结构不符 runId={} attempt={}: {}", ctx.run().getId(), attempt, problems);
        }
        if (payload == null || !problems.isEmpty()) {
            return StageOutcome.failed("Spec 生成失败：两次尝试均不符合要求（" + problems + "）");
        }

        Map<String, Object> output = new LinkedHashMap<>(payload);
        output.putIfAbsent("nonGoals", List.of());
        output.putIfAbsent("background", ctx.item().getWhy() == null ? "" : ctx.item().getWhy());
        output.put("chosenOption", chosen);
        // 接口清单跟着 Spec 往下走：任务拆分与开发阶段据此约束改动
        if (!mappings.isEmpty()) {
            output.put("httpMappings", mappings);
        }
        ctx.publish("spec", output);
        ctx.log("Spec 完成：" + caseCount(output) + " 条测试用例");
        return StageOutcome.done(output);
    }

    // ──────────────────────────── 结构检查 ────────────────────────────

    /** 只检查后续阶段与界面真正依赖的东西：字段齐全、用例可执行、覆盖完整、接口路径真实。 */
    @SuppressWarnings("unchecked")
    static List<String> problemsOf(Map<String, Object> payload, List<String> httpMappings) {
        return problemsOf(payload, httpMappings, true);
    }

    /** @param serviceE2e 这次修复是否会启动服务做接口验证；不会时不要求 api / e2e 用例 */
    static List<String> problemsOf(Map<String, Object> payload, List<String> httpMappings, boolean serviceE2e) {
        List<String> problems = new ArrayList<>();
        if (payload == null) {
            problems.add("没有返回内容");
            return problems;
        }
        if (blank(payload.get("goal"))) {
            problems.add("缺少 goal");
        }
        if (!(payload.get("acceptanceCriteria") instanceof List<?> criteria) || criteria.isEmpty()) {
            problems.add("缺少非空的 acceptanceCriteria");
        }
        if (!(payload.get("testCases") instanceof List<?> cases) || cases.isEmpty()) {
            problems.add("缺少非空的 testCases");
            return problems;
        }

        boolean hasRegression = false;
        boolean hasEndToEnd = false;
        for (int i = 0; i < cases.size(); i++) {
            if (!(cases.get(i) instanceof Map<?, ?> testCase)) {
                problems.add("testCases[" + i + "] 不是对象");
                continue;
            }
            String type = String.valueOf(testCase.get("type")).toLowerCase(Locale.ROOT);
            if (!CASE_TYPES.contains(type)) {
                problems.add("testCases[" + i + "] 的 type 必须是 repro/unit/api/e2e 之一");
                continue;
            }
            if (blank(testCase.get("name")) || blank(testCase.get("target")) || blank(testCase.get("verify"))) {
                problems.add("testCases[" + i + "] 的 name/target/verify 不能为空");
            }
            hasRegression |= type.equals("repro") || type.equals("unit");
            hasEndToEnd |= type.equals("api") || type.equals("e2e");
            if (type.equals("api")) {
                String[] request = parseRequest(String.valueOf(testCase.get("target")));
                if (request == null) {
                    problems.add("api 用例「" + testCase.get("name") + "」的 target 应形如「GET /users/{id}」");
                } else if (!httpMappings.isEmpty() && !HttpMappingScanner.matches(httpMappings, request[0], request[1])) {
                    problems.add("api 用例「" + testCase.get("name") + "」的路径 " + request[0] + " " + request[1]
                            + " 不在 HTTP 接口清单中");
                }
                // E2E 是对真实启动的服务发请求：靠 mock 预设状态的 api 用例在 E2E 里永远跑不过
                if (TEST_DOUBLE.matcher(testCase.get("name") + " " + testCase.get("verify")).find()) {
                    problems.add("api 用例「" + testCase.get("name") + "」必须是黑盒请求：对真实启动的服务直接发请求，"
                            + "不能依赖 mock / 测试上下文预设内部状态（这类场景写成 unit 用例）");
                }
            }
        }
        if (!hasRegression) {
            problems.add("至少需要 1 条 repro 或 unit 用例");
        }
        // 没有启动服务做接口验证时写不出 api 用例；有接口且会启动服务时，必须有用例打到接口上
        if (!hasEndToEnd && serviceE2e && !httpMappings.isEmpty()) {
            problems.add("项目有 HTTP 接口，至少需要 1 条 api 或 e2e 用例");
        }
        return problems;
    }

    /**
     * 把 api 用例的落点拆成「方法 + 路径」：接受 {@code GET /users/{id}}，也接受只有路径的写法（按 GET 处理）。
     * 格式不对时返回 null。
     */
    static String[] parseRequest(String target) {
        String text = target.trim();
        Matcher verb = LEADING_VERB.matcher(text);
        String method = "GET";
        if (verb.matches()) {
            method = verb.group(1).toUpperCase(Locale.ROOT);
            text = verb.group(2).trim();
        }
        if (text.startsWith("http://") || text.startsWith("https://")) {
            int pathStart = text.indexOf('/', text.indexOf("//") + 2);
            text = pathStart < 0 ? "/" : text.substring(pathStart);
        }
        int query = text.indexOf('?');
        if (query >= 0) {
            text = text.substring(0, query);
        }
        return text.startsWith("/") ? new String[]{method, text} : null;
    }

    // ──────────────────────────── 工具 ────────────────────────────

    private static boolean blank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static int caseCount(Map<String, Object> spec) {
        return spec.get("testCases") instanceof List<?> list ? list.size() : 0;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> optionsOf(Map<String, Object> proposal) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (proposal.get("options") instanceof List<?> options) {
            for (Object option : options) {
                if (option instanceof Map<?, ?> map) {
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
                    out.add(map.get("id") + " " + (text.length() > 1600 ? text.substring(0, 1600) + "…（截断）" : text));
                }
                if (out.size() >= 24) {
                    break;
                }
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> mappingsOf(Map<String, Object> evidence) {
        return evidence.get("httpMappings") instanceof List<?> mappings ? (List<String>) mappings : List.of();
    }
}
