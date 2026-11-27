package com.huawei.hisi.mergeanalysis.service;

import com.huawei.hisi.mergeanalysis.model.DiffResult;
import com.huawei.hisi.workflow.ClarifyRequiredException;
import com.huawei.hisi.workflow.DagNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 注册为 bean：合入分析的节点链才能被 {@code WorkflowRegistry} 解析，专用入口与通用 /v2/workflow 入口同源。 */
@Slf4j
@Component
public class DiffExtractDagNode implements DagNode {

    private final DiffExtractService diffExtractService;

    public DiffExtractDagNode(DiffExtractService diffExtractService) {
        this.diffExtractService = diffExtractService;
    }

    @Override
    public String name() {
        return "diff_extract";
    }

    @Override
    public String agentId() {
        return "diff-extract-v1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) throws ClarifyRequiredException {
        String projectPath = stringVal(input, "projectPath");
        String sourceBranch = stringVal(input, "sourceBranch");
        String targetBranch = stringVal(input, "targetBranch");

        if (projectPath == null || sourceBranch == null || targetBranch == null) {
            throw new IllegalArgumentException(
                    "DiffExtractDagNode requires projectPath, sourceBranch, targetBranch in input");
        }

        log.info("[DiffExtractDagNode] projectPath={} source={} target={}", projectPath, sourceBranch, targetBranch);
        DiffResult diffResult = diffExtractService.extractDiff(projectPath, sourceBranch, targetBranch);

        Map<String, Object> output = new LinkedHashMap<>(input);
        output.put("diffResult", diffResult);
        return output;
    }

    private static String stringVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v instanceof String s ? s : null;
    }
}
