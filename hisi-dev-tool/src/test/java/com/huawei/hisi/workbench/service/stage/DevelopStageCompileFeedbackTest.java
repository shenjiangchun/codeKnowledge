package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.workbench.service.exec.CommandRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * develop 阶段的编译反馈。模型没有查代码的工具，只能凭记忆猜包名：
 * 真机上一条 NPE 待办连修三轮失败，全是这两类它自己改不动的错误——臆造类型、直接调 private 方法；
 * 另有一次编译失败发生在没有任何改动的文件里（环境抖动），不该算到模型头上。
 */
@DisplayName("DevelopStage compile feedback")
class DevelopStageCompileFeedbackTest {

    @TempDir
    Path worktree;

    @Test
    @DisplayName("臆造的类型：告诉模型工作区里没有，并列出名字相近的真实类型")
    void aHallucinatedTypeIsReplacedByRealOnes() throws Exception {
        type("com/huawei/hisi/skill/model/SkillDefinition");
        type("com/huawei/hisi/skill/model/SkillDetail");
        String output = "[ERROR] /C:/w/src/test/java/com/huawei/hisi/controller/SkillMarketControllerTest.java:[3,35] 找不到符号\n"
                + "[ERROR]   符号:   类 Skill\n"
                + "[ERROR]   位置: 程序包 com.huawei.hisi.neo4j.model\n";

        String hints = DevelopStage.compileHints(worktree, output);

        assertThat(hints).startsWith("\n\n—— 编译提示 ——")
                .contains("工作区里没有名为 Skill 的类型")
                .contains("com.huawei.hisi.skill.model.SkillDefinition")
                .contains("com.huawei.hisi.skill.model.SkillDetail");
    }

    @Test
    @DisplayName("类型存在但 import 的包不对：给出真实的全限定名（中英文报错都认）")
    void aMisplacedImportGetsTheRealPackage() throws Exception {
        type("com/huawei/hisi/skill/model/Skill");

        assertThat(DevelopStage.compileHints(worktree,
                "[ERROR]   符号:   类 Skill\n[ERROR]   位置: 程序包 com.huawei.hisi.neo4j.model\n"))
                .contains("Skill 实际位于 com.huawei.hisi.skill.model.Skill");
        assertThat(DevelopStage.compileHints(worktree,
                "[ERROR] /C:/w/A.java:[1,1] cannot find symbol\n  symbol:   class Skill\n"))
                .contains("com.huawei.hisi.skill.model.Skill");
    }

    @Test
    @DisplayName("测试直接调了 private 方法：提醒改测公开入口")
    void privateAccessGetsARuleReminder() {
        String output = "[ERROR] /C:/w/src/test/java/com/huawei/hisi/controller/SkillMarketControllerTest.java:[63,33] "
                + "createSkill(java.lang.String) 在 com.huawei.hisi.controller.SkillMarketController 中是 private 访问控制\n";

        assertThat(DevelopStage.compileHints(worktree, output))
                .contains("【访问权限】").contains("private").contains("公开方法");
    }

    @Test
    @DisplayName("与这两类无关的报错不加提示；完整反馈以报错原文为主")
    void otherErrorsGetNoHintAndTheFeedbackKeepsTheRawOutput() {
        assertThat(DevelopStage.compileHints(worktree, "[ERROR] /C:/w/A.java:[5,3] 需要(';')")).isEmpty();

        String feedback = DevelopStage.compileFeedback(worktree,
                new CommandRunner.Result(1, "[ERROR] /C:/w/A.java:[3,35] 找不到符号\n[ERROR]   符号:   类 Skill\n", false));

        assertThat(feedback).startsWith("编译失败，错误输出：\n[ERROR] /C:/w/A.java:[3,35] 找不到符号")
                .contains("—— 编译提示 ——").contains("没有名为 Skill 的类型");
    }

    @Test
    @DisplayName("报错全在没动过的文件里才算与改动无关；动过的文件报错、或解析不出位置，都算有关")
    void onlyErrorsInUntouchedFilesCountAsUnrelated() {
        Set<Path> touched = Set.of(worktree.resolve("src/main/java/com/huawei/hisi/controller/SkillMarketController.java"));

        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(worktree,
                "[ERROR] /C:/x/src/main/java/com/huawei/hisi/knowledgegraph/service/KnowledgeGraphBuilder.java:[312,9] 找不到符号\n"
                        + "[ERROR]   符号:   变量 log\n", touched)).isTrue();
        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(worktree,
                "[ERROR] " + touched.iterator().next().toString().replace('\\', '/') + ":[312,9] 找不到符号\n", touched)).isFalse();
        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(worktree, "[ERROR] Failed to execute goal … Compilation failure", touched)).isFalse();
        assertThat(DevelopStage.errorsOnlyInUntouchedFiles(worktree, null, touched)).isFalse();
    }

    @Test
    @DisplayName("动过的文件清单换算成绝对路径")
    void changedFilesResolveAgainstTheWorktree() throws Exception {
        Path file = worktree.resolve("src/main/java/A.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, "class A {}", StandardCharsets.UTF_8);

        assertThat(DevelopStage.touched(worktree, java.util.List.of("src/main/java/A.java")))
                .containsExactly(file.toAbsolutePath().normalize());
    }

    private void type(String relativePath) throws Exception {
        Path file = worktree.resolve("src/main/java").resolve(relativePath + ".java");
        Files.createDirectories(file.getParent());
        String simpleName = file.getFileName().toString().replace(".java", "");
        String pkg = relativePath.replace('/', '.').substring(0, relativePath.replace('/', '.').length() - simpleName.length() - 1);
        Files.writeString(file, "package " + pkg + ";\n\npublic class " + simpleName + " {\n}\n", StandardCharsets.UTF_8);
    }
}
