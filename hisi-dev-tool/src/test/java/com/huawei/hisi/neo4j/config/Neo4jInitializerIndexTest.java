package com.huawei.hisi.neo4j.config;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class Neo4jInitializerIndexTest {

    @Test
    void rangeIndexes_doNotContainPublicProjectPath() throws Exception {
        List<String> indexes = readListConstant(Neo4jInitializer.class, "RANGE_INDEXES");
        assertThat(indexes).noneMatch(s -> s.contains("publicProjectPath"));
    }

    @Test
    void dropIndexes_includePublicProjectPathCleanup() throws Exception {
        List<String> drops = readListConstant(Neo4jInitializer.class, "DROP_INDEXES");
        assertThat(drops).anyMatch(s -> s.contains("method_pub_path"));
        assertThat(drops).anyMatch(s -> s.contains("entrypoint_pub_path"));
        assertThat(drops).anyMatch(s -> s.contains("sql_pub_path"));
        assertThat(drops).anyMatch(s -> s.contains("service_pub_path"));
    }

    @Test
    void backfillStatements_removePublicProjectPathProperty() throws Exception {
        List<String> backfill = readListConstant(Neo4jInitializer.class, "BACKFILL_STATEMENTS");
        assertThat(backfill).anyMatch(s ->
                s.contains("REMOVE n.publicProjectPath")
                        && s.contains("n.publicProjectPath IS NOT NULL"));
    }

    @Test
    void rangeIndexes_useIfNotExistsForIdempotence() throws Exception {
        List<String> indexes = readListConstant(Neo4jInitializer.class, "RANGE_INDEXES");
        indexes.forEach(s -> assertThat(s).contains("IF NOT EXISTS"));
    }

    @Test
    void rangeIndexes_containsCodegraphRelationshipIndexes() throws Exception {
        List<String> indexes = readListConstant(Neo4jInitializer.class, "RANGE_INDEXES");
        assertThat(indexes).anyMatch(s -> s.contains("CONTAINS") && s.contains("projectPath"));
        assertThat(indexes).anyMatch(s -> s.contains("IMPORTS") && s.contains("projectPath"));
        assertThat(indexes).anyMatch(s -> s.contains("REFERENCES") && s.contains("projectPath"));
    }

    @SuppressWarnings("unchecked")
    private static List<String> readListConstant(Class<?> cls, String name) throws Exception {
        Field f = cls.getDeclaredField(name);
        f.setAccessible(true);
        return (List<String>) f.get(null);
    }
}
