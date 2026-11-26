package com.huawei.hisi.workbench.model;

import java.util.List;

/**
 * SDD 修复流程的 8 个阶段（顺序固定）。{@code stageIndex} 对应下标。
 *
 * <p>回路：{@code tests} / {@code e2e} 失败且未达迭代上限时回到 {@code develop}；
 * {@code proposal} 被驳回后带反馈重跑并再次停下。其余阶段顺序推进。
 */
public final class WorkbenchStages {

    public record Stage(String key, String label, String hint) {}

    public static final List<Stage> ALL = List.of(
            new Stage("evidence", "代码实证", "图谱定位调用链、读取源码、评估影响面"),
            new Stage("proposal", "方案确认", "给出候选方案与风险，等你确认"),
            new Stage("spec", "Spec 生成", "目标、验收标准、测试用例、非目标"),
            new Stage("tasks", "任务拆分", "基于 Spec 拆出可独立验证的任务"),
            new Stage("develop", "迭代开发", "按任务修改代码，每一步可编译"),
            new Stage("tests", "用例回归", "执行 Spec 确定的测试用例"),
            new Stage("e2e", "全链路 E2E", "构建、启动、健康检查、API 用例、前端 E2E"),
            new Stage("ship", "提交推送", "独立分支提交，按你的选择推送"));

    public static Stage of(int index) {
        return ALL.get(index);
    }

    public static int indexOf(String key) {
        for (int i = 0; i < ALL.size(); i++) {
            if (ALL.get(i).key().equals(key)) {
                return i;
            }
        }
        return -1;
    }

    private WorkbenchStages() {}
}
