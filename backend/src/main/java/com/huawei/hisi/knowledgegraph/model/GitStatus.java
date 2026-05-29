package com.huawei.hisi.knowledgegraph.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Git 状态模型
 * 用于表示 Git 仓库的当前状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GitStatus {
    /**
     * 工作目录是否干净（无未提交的更改）
     */
    private boolean clean;

    /**
     * 当前 commit hash
     */
    private String commitHash;

    /**
     * 当前分支名
     */
    private String branch;

    /**
     * 是否有未提交的更改
     */
    private boolean hasUncommittedChanges;

    /**
     * 是否有未推送的提交
     */
    private boolean hasUnpushedCommits;

    /**
     * 未推送的提交数量
     */
    private int unpushedCommitCount;
}
