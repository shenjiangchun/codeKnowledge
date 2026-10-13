package com.huawei.hisi.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.neo4j.repository.config.EnableNeo4jRepositories;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防止 SDN repository 自动事务断链的回归测试。
 *
 * <p>背景：SDN 的 {@code TransactionalRepositoryProxyPostProcessor} 会给每个 repository
 * 方法自动加 {@code @Transactional}，其事务管理器由 {@code @EnableNeo4jRepositories.transactionManagerRef}
 * 决定。若该值缺省，SDN 按默认 bean 名 {@code transactionManager} 查找，而本项目事务管理器
 * 叫 {@code neo4jTransactionManager}，导致所有 SDN 裸写操作（saveAll 等）抛
 * "No bean named 'transactionManager'"。
 *
 * <p>本测试锁定：{@code transactionManagerRef} 指向的 bean 名必须与实际 {@code @Bean} 名一致。
 */
class Neo4jConfigTest {

    @Test
    void transactionManagerRef_matchesNeo4jTransactionManagerBeanName() {
        EnableNeo4jRepositories annotation = Neo4jConfig.class.getAnnotation(EnableNeo4jRepositories.class);
        assertThat(annotation).as("@EnableNeo4jRepositories 必须存在").isNotNull();

        String ref = annotation.transactionManagerRef();
        assertThat(ref)
            .as("transactionManagerRef 必须显式指定，否则 SDN 按默认名 transactionManager 查找失败")
            .isNotBlank();

        Method tmMethod = findMethod(Neo4jConfig.class, "neo4jTransactionManager");
        assertThat(tmMethod).as("neo4jTransactionManager 方法必须存在").isNotNull();

        Bean bean = tmMethod.getAnnotation(Bean.class);
        String beanName = beanNameOf(bean);
        assertThat(ref)
            .as("transactionManagerRef 必须与实际 @Bean 名一致，避免悬空引用")
            .isEqualTo(beanName);
    }

    @Test
    void neo4jTransactionManager_isPrimary() throws NoSuchMethodException {
        Method tmMethod = Neo4jConfig.class.getDeclaredMethod(
            "neo4jTransactionManager",
            org.neo4j.driver.Driver.class,
            org.springframework.data.neo4j.core.DatabaseSelectionProvider.class);
        assertThat(tmMethod.getAnnotation(Primary.class))
            .as("neo4jTransactionManager 应为 @Primary，供按类型注入时唯一命中")
            .isNotNull();
    }

    private static Method findMethod(Class<?> clazz, String name) {
        for (Method m : clazz.getDeclaredMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private static String beanNameOf(Bean bean) {
        if (bean == null) {
            return null;
        }
        String[] names = bean.name();
        if (names != null && names.length > 0 && !names[0].isBlank()) {
            return names[0];
        }
        String[] value = bean.value();
        if (value != null && value.length > 0 && !value[0].isBlank()) {
            return value[0];
        }
        return null;
    }
}
