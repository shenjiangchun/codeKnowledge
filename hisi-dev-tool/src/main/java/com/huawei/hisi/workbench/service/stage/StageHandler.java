package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.workbench.service.StageContext;

import java.util.Map;

/**
 * SDD 修复流程的一个阶段。实现类用 {@link #key()} 对应 {@code WorkbenchStages} 的阶段键。
 */
public interface StageHandler {

    String key();

    /**
     * 执行阶段。
     *
     * @return kind 为 done（完成）/ wait（停下等人，只用于方案确认）/
     *         failed（失败结束）/ retry（结果不通过，由 runner 按迭代上限决定回开发还是失败）
     */
    StageOutcome execute(StageContext ctx) throws Exception;

    record StageOutcome(String kind, Map<String, Object> output, String message) {

        public static final String DONE = "done";
        public static final String WAIT = "wait";
        public static final String FAILED = "failed";
        public static final String RETRY = "retry";

        public static StageOutcome done(Map<String, Object> output) {
            return new StageOutcome(DONE, output, null);
        }

        public static StageOutcome wait(Map<String, Object> output) {
            return new StageOutcome(WAIT, output, null);
        }

        public static StageOutcome failed(String message) {
            return new StageOutcome(FAILED, null, message);
        }

        public static StageOutcome retry(Map<String, Object> output, String message) {
            return new StageOutcome(RETRY, output, message);
        }
    }
}
