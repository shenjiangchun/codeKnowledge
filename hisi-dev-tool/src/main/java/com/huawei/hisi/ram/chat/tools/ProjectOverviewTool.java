package com.huawei.hisi.ram.chat.tools;

import com.huawei.hisi.ram.kg.KgMcpClient;
import com.huawei.hisi.ram.nodes.ProjectOverviewLlmClient;
import com.huawei.hisi.ram.nodes.ProjectOverviewNode;
import com.huawei.hisi.ram.sdk.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class ProjectOverviewTool {

    private final KgMcpClient kgClient;
    private final ProjectOverviewLlmClient llmClient;

    public ToolDefinition buildDefinition() {
        String schema = """
                {
                  "type": "object",
                  "properties": {
                    "projectPath": {
                      "type": "string",
                      "description": "Project root path to analyze"
                    },
                    "question": {
                      "type": "string",
                      "description": "Optional focus question to customize the overview"
                    }
                  },
                  "required": ["projectPath"]
                }
                """;
        return new ToolDefinition(
                "generate_project_overview",
                "Generate a comprehensive project overview including entry points, core call chains, tech stack, module analysis, and recommendations. Use this when user asks for project-wide analysis or 'what does this project do'.",
                schema);
    }

    public Function<Map<String, Object>, Object> buildHandler(List<String> projectPaths) {
        String primaryPath = (projectPaths == null || projectPaths.isEmpty()) ? "" : projectPaths.get(0);
        return input -> {
            String projectPath = (String) input.get("projectPath");
            String question = (String) input.getOrDefault("question", "");
            // Allow LLM to override with its own projectPath; fallback to primary
            if (projectPath == null || projectPath.isBlank()) {
                projectPath = primaryPath;
            }
            if (projectPath == null || projectPath.isBlank()) {
                return Map.of("error", "projectPath is required");
            }

            log.info("[ProjectOverviewTool] execute projectPath={} question={}", projectPath, question);
            try {
                ProjectOverviewNode node = new ProjectOverviewNode(kgClient, llmClient);
                Map<String, Object> nodeInput = new LinkedHashMap<>();
                nodeInput.put("projectPath", projectPath);
                nodeInput.put("mode", "quick");
                nodeInput.put("question", question);
                Map<String, Object> result = node.execute(nodeInput);
                log.info("[ProjectOverviewTool] done, markdown_report.len={}",
                        String.valueOf(result.get("markdown_report")).length());
                return result;
            } catch (Exception e) {
                log.error("[ProjectOverviewTool] failed: {}", e.getMessage(), e);
                return Map.of("error", e.getMessage());
            }
        };
    }
}
