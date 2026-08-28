package com.huawei.hisi.knowledgegraph.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证类描述生成方法声明了 Neo4j 事务管理器。
 *
 * <p>背景：ClassDescriptionService.generateClassDescriptions 直接调用
 * classNodeRepository.save()，若缺少 @Transactional(transactionManager =
 * "neo4jTransactionManager")，Spring Data Neo4j 会去找默认的 JDBC
 * transactionManager bean 而抛 "No bean named 'transactionManager'"，
 * 导致所有类描述生成静默失败（被上层 catch 吞掉）。
 */
class ClassDescriptionServiceTest {

    @Test
    @DisplayName("generateClassDescriptions 必须绑定 neo4jTransactionManager")
    void generateClassDescriptions_shouldDeclareNeo4jTransactionManager() throws NoSuchMethodException {
        Method method = ClassDescriptionService.class.getMethod("generateClassDescriptions", String.class);

        Transactional tx = method.getAnnotation(Transactional.class);

        assertThat(tx)
            .as("generateClassDescriptions 必须标注 @Transactional 以绑定 Neo4j 事务管理器")
            .isNotNull();
        assertThat(tx.transactionManager())
            .as("必须显式指定 neo4jTransactionManager，避免回落到 JDBC 的默认 transactionManager")
            .isEqualTo("neo4jTransactionManager");
    }
}
