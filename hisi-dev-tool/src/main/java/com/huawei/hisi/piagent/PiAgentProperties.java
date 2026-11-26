package com.huawei.hisi.piagent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * pi 基建配置。每流一个开关（piagent.flows.*），关闭时走老 LLM 客户端实现，可独立回退。
 */
@Data
@Component
@ConfigurationProperties(prefix = "piagent")
public class PiAgentProperties {

    /** 总开关。关闭时安装器与运行时都不工作，所有流走老实现。 */
    private boolean enabled = true;

    /**
     * 安装目录（后端首次启动时 npm install 到这里）。默认在后端项目目录内（lib/pi，gitignore），
     * 与用户主目录解耦：每台拉取分支的机器各有自己的一份，远端无需共享路径。
     */
    private String installDir = "lib/pi";

    /** pi 包的锁定版本。 */
    private String packageVersion = "1.6.0";

    /** npm 源。 */
    private String registry = "https://registry.npmmirror.com";

    /** 配置目录（models.json / mcp.json / settings.json / skills），默认同样在项目内（lib/pi-agent）。 */
    private String agentDir = "lib/pi-agent";

    /** 模型网关（Anthropic 协议）。 */
    private String modelBaseUrl = "http://1.95.145.190:8888";
    private String modelId = "deepseek-v4-pro-cc";

    /** hisi-mcp-server 的 dist 入口（只读工具源）。 */
    private String mcpDistPath = "";

    /** 同时运行的 pi 进程上限。每个流的每次模型调用都是一个进程，几个流并行时都要占槽位。 */
    private int maxConcurrentProcesses = 4;

    /** 单次流的 deadline（分钟），超时 abort 并强杀进程。 */
    private int deadlineMinutes = 60;

    /** 每流开关：开启后该流的 LLM 层走 pi。arch-react 已是唯一实现（手动循环已删除），默认开启。 */
    private Map<String, Boolean> flows = Map.of(
            "arch-react", true,
            "one-shot", true,
            "workbench-sdd", false,
            "log-v2", false,
            "ram", false);

    /** 通用上下文管理：所有流提交给 pi 的提示词在发出前都先做预算检查与保形压缩（见 {@link com.huawei.hisi.piagent.context.PromptBudget}）。 */
    private Context context = new Context();

    @Data
    public static class Context {

        /**
         * 单次提交（system + user）允许占用的 token 上限，超出就压缩。
         * 给工具定义、工具结果和最终回答留出窗口；pi 自带的会话压缩管的是运行中越长越大的那部分。
         */
        private int maxInputTokens = 32000;

        /** pi 会话压缩的触发保留量：上下文超过「窗口 − 保留量」时总结较早轮次。pi 按 4 字符/token 估算，对中文偏乐观，所以调大。 */
        private int compactionReserveTokens = 32000;

        /** pi 会话压缩时保留的最近内容（token）。 */
        private int compactionKeepRecentTokens = 20000;
    }

    public boolean flowEnabled(String flow) {
        return enabled && Boolean.TRUE.equals(flows.getOrDefault(flow, false));
    }
}
