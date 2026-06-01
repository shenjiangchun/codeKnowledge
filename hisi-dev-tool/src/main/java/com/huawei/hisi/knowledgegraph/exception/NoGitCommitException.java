package com.huawei.hisi.knowledgegraph.exception;

/**
 * Thrown when a valid Git commit hash cannot be determined for a project.
 * Knowledge graph generation requires a Git commit to persist as a checkpoint
 * for subsequent incremental refreshes.
 */
public class NoGitCommitException extends RuntimeException {

    public NoGitCommitException(String projectPath) {
        super("无法获取项目 Git commit hash，知识图谱生成需要有效的 Git 仓库以保存增量刷新 checkpoint。项目路径: " + projectPath);
    }
}
