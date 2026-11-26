package com.huawei.hisi.workbench.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 工作台里的一条待办：一条日志 issue 或一条架构 issue 的聚合视图。
 * {@code itemKey} 由来源决定，聚合同步时按它 upsert，用户的状态决定不会被覆盖。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkbenchItem {

    /** 待办来源。 */
    public static final String SOURCE_LOG = "log";
    public static final String SOURCE_ARCH_RULE = "arch_rule";
    public static final String SOURCE_ARCH_AI = "arch_ai";

    /** 待办状态。 */
    public static final String STATUS_NEW = "NEW";
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_WAITING = "WAITING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_IGNORED = "IGNORED";

    private Long id;
    private String itemKey;
    private String source;
    private String projectPath;
    private String title;
    /** 代码位置，如 {@code com.example.controller.UserController#getUser} */
    private String location;
    /** P0 / P1 / P2 */
    private String severity;
    private String status;
    /** high / medium / low */
    private String confidence;
    /** 证据强度：日志报告沿用 v2 的因果链状态；架构问题用智能体的判定 */
    private String chainStatus;
    /** 为什么重要 / 根因 */
    private String why;
    private String suggestion;
    /** 证据条目列表的 JSON 数组 */
    private String evidenceJson;
    /** 来源引用的 JSON：报告 ID / 快照 ID / 会话，用于跳回原始页面 */
    private String sourceRefJson;
    private Integer occurrenceCount;
    private Long firstSeenAt;
    private Long lastSeenAt;
    private Long currentRunId;
    private Long createdAt;
    private Long updatedAt;
}
