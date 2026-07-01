package com.huawei.hisi.fixengine.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Persistent state of a fix-engine session.
 *
 * <p>Stored in the {@code fix_session} table; one row per fix attempt. Links
 * a log-analysis report to a RAM chat session and tracks the worktree /
 * branch / commit produced by the automated fix flow.
 *
 * <p>ID is a snowflake-style String generated at save time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixSession {

    private String id;
    private String reportId;
    private String chatSessionId;
    private String sessionType;
    private String status;
    private String worktreePath;
    private String branchName;
    private String commitHash;
    private String throwPointSig;
    private String errorMsg;
    /** 租户 ID（多租户隔离） */
    private String tenantId;
    /** 创建人 */
    private String createBy;
    /** 更新人 */
    private String updateBy;
    /** 软删除标记：0=正常，1=已删除 */
    private int delFlag;
    private long createdAt;
    private long updatedAt;

    /**
     * Build a fresh session in RUNNING state for the given report. Timestamps
     * and id are populated by the repository on save.
     */
    public static FixSession newRunning(String reportId, String chatSessionId, String branchName) {
        long now = System.currentTimeMillis() / 1000L;
        return FixSession.builder()
                .reportId(reportId)
                .chatSessionId(chatSessionId)
                .sessionType("FIX")
                .status("RUNNING")
                .branchName(branchName)
                .tenantId("default")
                .createBy("system")
                .updateBy("system")
                .delFlag(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
