package com.huawei.hisi.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 一次 SDD 自动修复运行（{@code workbench_fix_run} 一行）。
 *
 * <p>与待办一对一（同一待办同时只允许一个活跃运行）。事件与阶段产物写在
 * {@code agent_event}（复用工作流的 SSE 推流与回放），这里只存状态机需要的字段。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchFixRun {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING_CONFIRM = "WAITING_CONFIRM";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_ABORTED = "ABORTED";
    /** 后端重启时仍在 RUNNING 的运行：等待手动继续 */
    public static final String STATUS_INTERRUPTED = "INTERRUPTED";

    private Long id;
    private String itemKey;
    /** SSE 与接口用的会话 UUID */
    private String sessionUuid;
    /** agent_session.id：事件挂在它下面 */
    private Long sessionId;
    private String projectPath;
    private String status;
    private String currentStage;
    private int stageIndex;
    private int iteration;
    /** 方案确认时选定的选项（A/B/C） */
    private String chosenOption;
    /** 用户确认时勾选了推送（受全局 push-enabled 约束） */
    private boolean pushRequested;
    private String worktreePath;
    private String branchName;
    private String commitHash;
    private boolean pushed;
    private String errorMessage;
    private Long createdAt;
    private Long updatedAt;

    public boolean isActive() {
        return STATUS_RUNNING.equals(status) || STATUS_WAITING_CONFIRM.equals(status);
    }
}
