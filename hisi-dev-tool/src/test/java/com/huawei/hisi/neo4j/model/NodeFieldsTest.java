package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证 4 类 Neo4j 节点都拥有 language / framework 字段。
 *
 * 仅校验 setter -> getter 往返；不校验默认值。
 * publicProjectPath 已移除，不再校验。
 */
class NodeFieldsTest {

    @Test
    void methodNode_hasLanguageAndFrameworkFields() {
        MethodNode n = new MethodNode();
        n.setLanguage("python");
        n.setFramework("fastapi");
        assertEquals("python", n.getLanguage());
        assertEquals("fastapi", n.getFramework());
    }

    @Test
    void entryPointNode_hasLanguageAndFrameworkFields() {
        EntryPointNode n = new EntryPointNode();
        n.setLanguage("python");
        n.setFramework("fastapi");
        assertEquals("python", n.getLanguage());
        assertEquals("fastapi", n.getFramework());
    }

    @Test
    void sqlNode_hasLanguageAndFrameworkFields() {
        SqlNode n = new SqlNode();
        n.setLanguage("python");
        n.setFramework("fastapi");
        assertEquals("python", n.getLanguage());
        assertEquals("fastapi", n.getFramework());
    }

    @Test
    void serviceNode_hasLanguageAndFrameworkFields() {
        ServiceNode n = new ServiceNode();
        n.setLanguage("python");
        n.setFramework("fastapi");
        assertEquals("python", n.getLanguage());
        assertEquals("fastapi", n.getFramework());
    }
}
