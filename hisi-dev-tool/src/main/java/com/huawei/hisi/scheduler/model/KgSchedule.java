package com.huawei.hisi.scheduler.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KgSchedule {
    private Long id;
    private String projectPath;
    private String cronExpression;
    /** 构建模式：INCREMENTAL / REUSE / WIPE */
    @Builder.Default
    private String buildMode = "REUSE";
    @Builder.Default
    private boolean enabled = true;
    /** 增量执行前是否 git pull 拉最新分支代码 */
    @Builder.Default
    private boolean gitPullEnabled = false;
    /** git pull 的分支（为空则跟随仓库当前分支） */
    private String branch;
    /** 增量时是否刷新语义&向量（自然语言描述 + 向量） */
    @Builder.Default
    private boolean refreshDescription = false;
    /** 增量时是否刷新架构现状（领域划分） */
    @Builder.Default
    private boolean refreshArchitecture = false;
    private Long lastRunAt;
    private Long nextRunAt;
    private Long createdAt;
}
