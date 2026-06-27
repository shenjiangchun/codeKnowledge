// hisi-dev-tool/src/main/java/com/huawei/hisi/ram/phase2v2/DynamicToolRegistry.java
package com.huawei.hisi.ram.phase2v2;

import com.huawei.hisi.ram.phase2v2.model.ChainComplexity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 根据链路复杂度动态分配 Claude SDK 工具权限。
 */
@Component
public class DynamicToolRegistry {

    private static final List<String> BASE_TOOLS = List.of(
        "KG_MCP", "Read", "Grep", "Glob", "Artifacts"
    );

    /**
     * 根据复杂度返回允许的工具集。
     */
    public List<String> getTools(ChainComplexity complexity) {
        List<String> tools = new ArrayList<>(BASE_TOOLS);

        switch (complexity) {
            case SIMPLE:
                // 基础工具集，无需添加
                break;

            case CROSS_SERVICE:
                // 跨服务链路，增加 WebFetch 查询外部文档
                tools.add("WebFetch");
                break;

            case DOMAIN_ANALYSIS:
                // 领域级分析，增加 Bash 执行构建/依赖分析
                tools.add("WebFetch");
                tools.add("Bash");
                break;

            case VERIFICATION:
                // 需要验证，增加 Agent 嵌套 (仅允许一层)
                tools.add("WebFetch");
                tools.add("Bash");
                tools.add("Agent");
                break;
        }

        return tools;
    }
}