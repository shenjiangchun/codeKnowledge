package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.config.Neo4jInitializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 回归：sql_vector_index 轮询 120s 超时 → vectorIndexAvailable=false → 混合检索降级全表扫描。
 *
 * <p>根因是两个组件各管一部分向量索引却管重叠了：{@code Neo4jInitializer} 建 sql_node_vector_index
 * 并把 sql_vector_index 当废弃索引 DROP；{@code Neo4jVectorIndexService} 又去建 sql_vector_index
 * ——与 sql_node_vector_index 同 label + property。Neo4j 对同 label+property 的第二个向量索引是
 * 静默 no-op（{@code CREATE … IF NOT EXISTS} 不报错也不创建，受控实验已证实），于是两边每次启动
 * 互相挤掉对方，VIS 轮询不到自己那个就等满 120s。
 *
 * <p>这些用例不连 Neo4j，锁的是「索引清单必须自洽」这个结构不变量——比锁某个具体索引名更能防止同类问题。
 */
@DisplayName("向量索引清单：两个组件不得管理同 label+property 的索引")
class VectorIndexOwnershipTest {

    /** Initializer 的 VectorIndexSpec 是私有 record，用反射读 name/label/property。 */
    private static List<String[]> initializerSpecs() throws Exception {
        Field f = Neo4jInitializer.class.getDeclaredField("VECTOR_INDEX_SPECS");
        f.setAccessible(true);
        List<?> specs = (List<?>) f.get(null);
        List<String[]> out = new ArrayList<>();
        for (Object spec : specs) {
            Class<?> c = spec.getClass();
            out.add(new String[]{call(c, spec, "name"), call(c, spec, "label"), call(c, spec, "property")});
        }
        return out;
    }

    private static List<String> initializerDrops() throws Exception {
        Field f = Neo4jInitializer.class.getDeclaredField("DROP_INDEXES");
        f.setAccessible(true);
        return (List<String>) f.get(null);
    }

    private static String call(Class<?> c, Object target, String accessor) throws Exception {
        Method m = c.getDeclaredMethod(accessor);
        m.setAccessible(true);
        return (String) m.invoke(target);
    }

    @Test
    @DisplayName("同 label+property 的索引，两个组件必须用同一个索引名（不同名会互相挤掉对方）")
    void indexesOnTheSameLabelAndPropertyShareOneName() throws Exception {
        // Initializer：label.property -> 索引名
        java.util.Map<String, String> byKey = new java.util.HashMap<>();
        for (String[] s : initializerSpecs()) {
            byKey.put(s[1] + "." + s[2], s[0]);
        }
        for (Neo4jVectorIndexService.VectorIndexConfig c : Neo4jVectorIndexService.MANAGED_INDEXES) {
            String initializerName = byKey.get(c.label() + "." + c.property());
            if (initializerName == null) {
                continue; // 只由 VIS 管理，没有重叠
            }
            assertThat(initializerName)
                    .as("VIS 的 %s 与 Initializer 的 %s 同 label+property 但不同名：Neo4j 对等价索引静默 no-op，"
                          + "谁后建谁建不出来", c.indexName(), initializerName)
                    .isEqualTo(c.indexName());
        }
    }

    @Test
    @DisplayName("废弃的 sql_vector_index 不再由 VIS 创建；Initializer 的 DROP 保留（老库迁移靠它）")
    void deprecatedSqlIndexIsDroppedNotCreated() throws Exception {
        List<String> visNames = Neo4jVectorIndexService.MANAGED_INDEXES.stream()
                .map(Neo4jVectorIndexService.VectorIndexConfig::indexName).toList();
        assertThat(visNames).as("VIS 不得再创建已废弃的 sql_vector_index").doesNotContain("sql_vector_index");

        List<String> initializerNames = initializerSpecs().stream().map(s -> s[0]).toList();
        assertThat(initializerNames).as("Initializer 不得再创建已废弃的 sql_vector_index").doesNotContain("sql_vector_index");

        assertThat(initializerDrops())
                .as("老库上 sql_vector_index 还在时，sql_node_vector_index 会被等价索引限制挡住建不出来；这条 DROP 是迁移路径")
                .anyMatch(d -> d.matches("(?i)DROP\\s+INDEX\\s+sql_vector_index\\s+IF\\s+EXISTS"));
    }

    @Test
    @DisplayName("业务真正查询的向量索引，必须真的有人创建（从仓储源码里读出查询用的索引名，不硬编码）")
    void everyQueriedVectorIndexIsCreatedBySomeone() throws Exception {
        // db.index.vector.queryNodes('<name>', …) —— 实际发起向量查询的地方
        Pattern queryCall = Pattern.compile("db\\.index\\.vector\\.queryNodes\\(\\s*'([^']+)'");
        java.util.Set<String> queried = new java.util.TreeSet<>();
        try (var walk = java.nio.file.Files.walk(java.nio.file.Path.of("src/main/java/com/huawei/hisi/neo4j"))) {
            for (java.nio.file.Path p : walk.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = queryCall.matcher(java.nio.file.Files.readString(p));
                while (m.find()) {
                    queried.add(m.group(1));
                }
            }
        }
        assertThat(queried).as("应能在仓储源码里找到向量查询").isNotEmpty();

        java.util.Set<String> created = new java.util.HashSet<>();
        initializerSpecs().forEach(s -> created.add(s[0]));
        Neo4jVectorIndexService.MANAGED_INDEXES.forEach(c -> created.add(c.indexName()));

        assertThat(created)
                .as("有向量查询在用、却没有任何组件负责创建的索引（查询会报 There is no such vector schema index）")
                .containsAll(queried);
    }
}
