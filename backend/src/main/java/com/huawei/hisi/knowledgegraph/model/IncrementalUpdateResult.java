package com.huawei.hisi.knowledgegraph.model;

import com.huawei.hisi.neo4j.model.MethodNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 增量更新结果
 * 记录增量更新的统计信息
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncrementalUpdateResult {

    /**
     * 是否成功
     */
    private boolean success;

    /**
     * 项目路径
     */
    private String projectPath;

    /**
     * 旧 commit hash
     */
    private String oldCommitHash;

    /**
     * 新 commit hash
     */
    private String newCommitHash;

    /**
     * 分支名
     */
    private String branch;

    /**
     * 总方法数
     */
    private int totalMethods;

    /**
     * 新增方法数
     */
    private int newMethods;

    /**
     * 更新方法数
     */
    private int updatedMethods;

    /**
     * 删除方法数
     */
    private int deletedMethods;

    /**
     * 变更文件数
     */
    private int changedFiles;

    /**
     * 耗时（毫秒）
     */
    private long costTimeMs;

    /**
     * 错误信息
     */
    private String errorMessage;

    /**
     * 新增的方法节点列表
     */
    private List<MethodNode> newMethodNodes;

    /**
     * 更新的方法节点列表
     */
    private List<MethodNode> updatedMethodNodes;

    /**
     * 删除的方法节点ID列表
     */
    private List<String> deletedMethodIds;
}
