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
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixSession {

    private Long id;
    private Long reportId;
    private Long chatSessionId;
    private String sessionType;
    private String status;
    private String worktreePath;
    private String branchName;
    private String commitHash;
    private String throwPointSig;
    private String errorMsg;
    private long createdAt;
    private long updatedAt;

    /**
     * Build a fresh session in RUNNING state for the given report. Timestamps
     * and id are populated by the repository on save.
     */
    public static FixSession newRunning(Long reportId, Long chatSessionId, String branchName) {
        long now = System.currentTimeMillis() / 1000L;
        return FixSession.builder()
                .reportId(reportId)
                .chatSessionId(chatSessionId)
                .sessionType("FIX")
                .status("RUNNING")
                .branchName(branchName)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }
}
