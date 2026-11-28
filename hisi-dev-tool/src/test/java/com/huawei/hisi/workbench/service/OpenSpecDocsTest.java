package com.huawei.hisi.workbench.service;

import com.huawei.hisi.workbench.model.WorkbenchItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OpenSpec 文档转换：锁定与业界 OpenSpec 约定一致的输出格式（2026-11-28 对照官方仓库样例）。 */
class OpenSpecDocsTest {

    @Test
    @DisplayName("capability：位置类名转 kebab-case")
    void capabilityComesFromLocationClassName() {
        WorkbenchItem item = WorkbenchItem.builder()
                .location("com.example.service.UserServiceImpl#createUser:26")
                .build();
        assertThat(OpenSpecDocs.capabilityOf(item)).isEqualTo("user-service-impl");
        WorkbenchItem noLocation = WorkbenchItem.builder().build();
        assertThat(OpenSpecDocs.capabilityOf(noLocation)).isEqualTo("fix");
    }

    @Test
    @DisplayName("spec 增量：每条验收标准一个 Requirement，测试用例按序循环成 Scenario（WHEN/THEN）")
    void specDeltaUsesAddHeadersRequirementScenarioWhenThen() {
        Map<String, Object> spec = Map.of(
                "acceptanceCriteria", List.of("接口必须拒绝空邮箱", "响应必须是 400"),
                "testCases", List.of(
                        Map.of("type", "repro", "name", "空邮箱复现", "target", "com.example.UserServiceImplTest", "verify", "抛 NPE"),
                        Map.of("type", "api", "name", "接口拒绝空邮箱", "target", "POST /users", "verify", "返回 400"),
                        Map.of("type", "unit", "name", "单元回归", "target", "com.example.UserServiceImplTest", "verify", "断言通过")));
        String md = OpenSpecDocs.specDeltaMd(spec);

        assertThat(md).contains("## ADDED Requirements");
        assertThat(md).contains("### Requirement: 接口必须拒绝空邮箱");
        assertThat(md).contains("### Requirement: 响应必须是 400");
        assertThat(md).contains("#### Scenario: 空邮箱复现");
        assertThat(md).contains("- **WHEN** 执行用例，落点 com.example.UserServiceImplTest");
        assertThat(md).contains("- **THEN** 抛 NPE");
        assertThat(md).contains("#### Scenario: 接口拒绝空邮箱");
        assertThat(md).contains("- **WHEN** 对真实服务发起请求 POST /users");
        assertThat(md).contains("- **THEN** 返回 400");
    }

    @Test
    @DisplayName("proposal：Why / What changes / Impact 三段")
    void proposalHasThreeSections() {
        WorkbenchItem item = WorkbenchItem.builder()
                .title("空邮箱触发 NPE").why("未做参数校验").severity("P0").location("a.b.C#m").build();
        String md = OpenSpecDocs.proposalMd(item, Map.of(),
                Map.of("options", List.of(Map.of("id", "o1", "summary", "增加校验"))), "o1");
        assertThat(md).contains("## Why");
        assertThat(md).contains("## What changes");
        assertThat(md).contains("增加校验");
        assertThat(md).contains("## Impact");
    }

    @Test
    @DisplayName("tasks.md：Implementation 清单，勾选项带文件与验证")
    void tasksMdIsAChecklist() {
        String md = OpenSpecDocs.tasksMd(List.of(
                Map.of("title", "补测试类", "file", "src/test/java/a/BTest.java", "verify", "跑通"),
                Map.of("title", "修复实现", "file", "src/main/java/a/B.java", "anchor", "B#m")));
        assertThat(md).contains("## Implementation");
        assertThat(md).contains("- [ ] 补测试类");
        assertThat(md).contains("src/test/java/a/BTest.java");
        assertThat(md).contains("- [ ] 修复实现");
        assertThat(md).contains("B#m");
    }
}
