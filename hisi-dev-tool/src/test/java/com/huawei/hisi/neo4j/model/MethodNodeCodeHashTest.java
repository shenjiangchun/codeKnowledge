package com.huawei.hisi.neo4j.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试 MethodNode.computeCodeHash 的代码指纹计算。
 * 判据：SHA-256(className.methodName(signature)\ncomment\nmethodBody)，64 位小写 hex。
 */
class MethodNodeCodeHashTest {

    @Test
    @DisplayName("codeHash 是 64 位小写十六进制字符串")
    void shouldReturn64CharLowercaseHex() {
        String hash = MethodNode.computeCodeHash("com.example.Foo", "bar", "int", "注释", "return 1;");
        assertThat(hash).hasSize(64);
        assertThat(hash).matches("[0-9a-f]{64}");
    }

    @Test
    @DisplayName("相同输入产生相同 codeHash（确定性）")
    void shouldBeDeterministic() {
        String a = MethodNode.computeCodeHash("C", "m", "s", "c", "b");
        String b = MethodNode.computeCodeHash("C", "m", "s", "c", "b");
        assertThat(a).isEqualTo(b);
    }

    @Test
    @DisplayName("comment 参与 hash：注释不同则 hash 不同")
    void shouldDifferWhenCommentDiffers() {
        String withComment = MethodNode.computeCodeHash("C", "m", "s", "aaa", "body");
        String withoutComment = MethodNode.computeCodeHash("C", "m", "s", "bbb", "body");
        assertThat(withComment).isNotEqualTo(withoutComment);
    }

    @Test
    @DisplayName("methodBody 参与 hash：方法体不同则 hash 不同")
    void shouldDifferWhenBodyDiffers() {
        String a = MethodNode.computeCodeHash("C", "m", "s", "c", "x = 1;");
        String b = MethodNode.computeCodeHash("C", "m", "s", "c", "x = 2;");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("comment 为 null 时按空串参与（不抛异常）")
    void shouldTreatNullCommentAsEmpty() {
        String withNull = MethodNode.computeCodeHash("C", "m", "s", null, "body");
        String withEmpty = MethodNode.computeCodeHash("C", "m", "s", "", "body");
        assertThat(withNull).isEqualTo(withEmpty);
    }

    @Test
    @DisplayName("所有入参为 null 时不抛异常，返回有效 hash")
    void shouldHandleAllNullInputs() {
        String hash = MethodNode.computeCodeHash(null, null, null, null, null);
        assertThat(hash).hasSize(64).matches("[0-9a-f]{64}");
    }
}
