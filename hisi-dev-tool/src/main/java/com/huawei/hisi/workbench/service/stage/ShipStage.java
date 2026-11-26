package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.fixengine.executor.GitExecutor;
import com.huawei.hisi.fixengine.service.WorktreeService;
import com.huawei.hisi.workbench.service.StageContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 阶段 8 · 提交推送：在 worktree 分支上提交；用户确认方案时勾选了推送
 * （且全局开关打开）则推到 origin 同名分支。从不合并主干。
 */
@Slf4j
@Component
public class ShipStage implements StageHandler {

    private final WorktreeService worktreeService;
    private final GitExecutor gitExecutor;
    private final boolean pushEnabled;

    public ShipStage(WorktreeService worktreeService,
                     GitExecutor gitExecutor,
                     @Value("${workbench.fix.push-enabled:true}") boolean pushEnabled) {
        this.worktreeService = worktreeService;
        this.gitExecutor = gitExecutor;
        this.pushEnabled = pushEnabled;
    }

    @Override
    public String key() {
        return "ship";
    }

    @Override
    public StageOutcome execute(StageContext ctx) {
        Path worktree = Path.of(ctx.run().getWorktreePath());
        String message = "fix: " + ctx.item().getTitle() + "\n\n"
                + "自动修复（SDD 流程）\n"
                + "待办: " + ctx.item().getItemKey() + "\n"
                + "方案: " + ctx.run().getChosenOption();

        ctx.log("提交改动到分支 " + ctx.run().getBranchName());
        String commit;
        try {
            commit = worktreeService.commit(ctx.run().getBranchName(), worktree.toString(), message);
        } catch (Exception e) {
            return StageOutcome.failed("提交失败：" + e.getMessage());
        }
        if (commit == null) {
            return StageOutcome.failed("提交失败：未取到 commit");
        }
        ctx.run().setCommitHash(commit);
        ctx.log("提交完成：" + commit.substring(0, Math.min(8, commit.length())));

        boolean pushed = false;
        if (ctx.run().isPushRequested() && pushEnabled) {
            ctx.log("按你的选择推送到 origin");
            pushed = gitExecutor.push(worktree.toString(), ctx.run().getBranchName());
            ctx.log(pushed ? "已推送" : "推送失败（本地提交保留，可手动推送）");
        } else {
            ctx.log(ctx.run().isPushRequested() ? "全局推送开关关闭，仅本地提交" : "未勾选推送，仅本地提交");
        }
        ctx.run().setPushed(pushed);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("branch", ctx.run().getBranchName());
        output.put("commit", commit);
        output.put("pushed", pushed);
        output.put("worktree", ctx.run().getWorktreePath());
        ctx.publish("ship", output);
        return StageOutcome.done(output);
    }
}
