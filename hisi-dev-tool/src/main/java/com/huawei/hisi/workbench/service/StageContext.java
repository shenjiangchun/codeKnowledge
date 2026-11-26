package com.huawei.hisi.workbench.service;

import com.huawei.hisi.workbench.model.WorkbenchFixRun;
import com.huawei.hisi.workbench.model.WorkbenchItem;

import java.util.Map;

/**
 * 单个阶段执行时可见的上下文：运行与待办、事件发射、全部上游产物、驳回反馈与取消检查。
 *
 * <p>产物按阶段键存放（{@code evidence / proposal / spec / tasks / develop / tests / e2e}）。
 * 阶段一律用 {@link #artifact} 按键取自己需要的输入，不要假设「上一个阶段的输出」是什么：
 * 回归失败回到开发、重启后继续，都会让阶段顺序不再是简单的上一个 → 下一个。
 */
public class StageContext {

    private final WorkbenchFixRun run;
    private final WorkbenchItem item;
    private final WorkbenchEventSink events;
    private final Map<String, Object> artifacts;
    private String rejectFeedback;

    public StageContext(WorkbenchFixRun run, WorkbenchItem item, WorkbenchEventSink events,
                        Map<String, Object> artifacts, String rejectFeedback) {
        this.run = run;
        this.item = item;
        this.events = events;
        this.artifacts = artifacts;
        this.rejectFeedback = rejectFeedback;
    }

    public WorkbenchFixRun run() { return run; }
    public WorkbenchItem item() { return item; }
    public String rejectFeedback() { return rejectFeedback; }
    public long sessionId() { return run.getSessionId(); }

    /** 某个阶段的产物；该阶段还没有产物时返回空 Map。 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> artifact(String stageKey) {
        Object value = artifacts.get(stageKey);
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    public void log(String message) {
        events.log(run.getSessionId(), run.getCurrentStage(), message);
    }

    /** 写一份阶段产物，供报告页与后续阶段读取。 */
    public void publish(String stageKey, Map<String, Object> output) {
        events.checkpoint(run.getSessionId(), stageKey, output);
    }

    public boolean cancelled() {
        return AgentFlowCancelHolder.isCancelled();
    }
}
