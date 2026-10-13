package com.huawei.hisi.neo4j.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 旧分层枚举脏标签迁移单测：启动回填必须把 ModuleNode.layerRole 的 DATA→MODEL、MAPPER→REPOSITORY
 * 归一为现行枚举（CONTROLLER/SERVICE/REPOSITORY/MODEL/UTILITY/UNKNOWN）。
 */
class Neo4jInitializerLayerRoleMigrationTest {

    @Test
    @DisplayName("回填语句包含 DATA→MODEL 与 MAPPER→REPOSITORY 迁移，且带 WHERE 保证幂等")
    void backfillMigratesLegacyLayerRoles() {
        assertThat(Neo4jInitializer.BACKFILL_STATEMENTS).anySatisfy(sql -> {
            assertThat(sql).contains("layerRole = 'DATA'").contains("layerRole = 'MODEL'");
        });
        assertThat(Neo4jInitializer.BACKFILL_STATEMENTS).anySatisfy(sql -> {
            assertThat(sql).contains("layerRole = 'MAPPER'").contains("layerRole = 'REPOSITORY'");
        });
        // 幂等性：迁移必须限定在旧枚举值上（WHERE 条件），重复执行无副作用
        assertThat(Neo4jInitializer.BACKFILL_STATEMENTS).allSatisfy(sql ->
            assertThat(sql).contains("WHERE"));
    }
}
