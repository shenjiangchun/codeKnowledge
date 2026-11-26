package com.huawei.hisi.workbench.repository;

import com.huawei.hisi.workbench.config.WorkbenchSchemaInitializer;
import com.huawei.hisi.workbench.model.WorkbenchItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 待办状态在真实 SQLite 里的持久化。
 *
 * <p>以前仓库里只有 upsertItem 一个写入口，它的冲突分支不写 status：修复流程改状态、用户点忽略，
 * 全都没有落库——待办永远显示「待处理」，清零进度永远是 0。业务单测里仓库是 mock 的，所以一直没被发现。
 */
class WorkbenchRepositoryTest {

    private WorkbenchRepository repository;

    @BeforeEach
    void setUp() {
        // SQLite 的内存库按连接存在，所以全程复用同一个连接
        SingleConnectionDataSource dataSource = new SingleConnectionDataSource("jdbc:sqlite::memory:", "", "", true);
        dataSource.setDriverClassName("org.sqlite.JDBC");
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        new WorkbenchSchemaInitializer(jdbcTemplate).initialize();
        repository = new WorkbenchRepository(jdbcTemplate);
    }

    private static WorkbenchItem item(String key, String title) {
        return WorkbenchItem.builder()
                .itemKey(key).source(WorkbenchItem.SOURCE_LOG).projectPath("P").title(title)
                .severity("P1").status(WorkbenchItem.STATUS_NEW).build();
    }

    @Test
    void statusChangesArePersistedForItemsThatAlreadyExist() {
        repository.upsertItem(item("log:P:1", "空指针"));
        WorkbenchItem stored = repository.findByKey("log:P:1");
        assertEquals(WorkbenchItem.STATUS_NEW, stored.getStatus());

        // 发起修复 → 等待确认 → 完成，是三条对同一行的状态更新
        repository.updateStatus(stored.getId(), WorkbenchItem.STATUS_RUNNING, 9L);
        WorkbenchItem running = repository.findByKey("log:P:1");
        assertEquals(WorkbenchItem.STATUS_RUNNING, running.getStatus());
        assertEquals(9L, running.getCurrentRunId());

        repository.updateStatus(stored.getId(), WorkbenchItem.STATUS_WAITING, 9L);
        assertEquals(WorkbenchItem.STATUS_WAITING, repository.findByKey("log:P:1").getStatus());

        repository.updateStatus(stored.getId(), WorkbenchItem.STATUS_DONE, null);
        WorkbenchItem done = repository.findByKey("log:P:1");
        assertEquals(WorkbenchItem.STATUS_DONE, done.getStatus());
        assertNull(done.getCurrentRunId());
    }

    @Test
    void ignoringAnItemSticks() {
        repository.upsertItem(item("log:P:1", "空指针"));
        WorkbenchItem stored = repository.findByKey("log:P:1");

        repository.updateStatus(stored.getId(), WorkbenchItem.STATUS_IGNORED, null);

        assertEquals(WorkbenchItem.STATUS_IGNORED, repository.findByKey("log:P:1").getStatus());
    }

    @Test
    void theAggregationSyncRefreshesContentWithoutTouchingTheStatus() {
        repository.upsertItem(item("log:P:1", "空指针"));
        WorkbenchItem stored = repository.findByKey("log:P:1");
        repository.updateStatus(stored.getId(), WorkbenchItem.STATUS_IGNORED, null);

        // 聚合同步：同一条待办带着新标题再来一次
        repository.upsertItem(item("log:P:1", "空指针（更新）"));

        WorkbenchItem after = repository.findByKey("log:P:1");
        assertEquals("空指针（更新）", after.getTitle());
        assertEquals(WorkbenchItem.STATUS_IGNORED, after.getStatus(), "同步不能覆盖用户的状态决定");
    }
}
