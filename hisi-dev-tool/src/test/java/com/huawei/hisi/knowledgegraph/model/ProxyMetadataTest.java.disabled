package com.huawei.hisi.knowledgegraph.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ProxyMetadata 模型单元测试
 */
class ProxyMetadataTest {

    @Test
    @DisplayName("测试 Builder 模式创建对象")
    void testBuilder() {
        // When
        ProxyMetadata metadata = ProxyMetadata.builder()
                .id(1L)
                .interfaceName("com.example.mapper.UserMapper")
                .interfaceType("MYBATIS")
                .implementation("com.example.mapper.UserMapperImpl")
                .proxyType("MYBATIS_MAPPER")
                .methodName("selectById")
                .methodSignature("User selectById(Long id)")
                .sqlStatement("SELECT * FROM users WHERE id = ?")
                .entityType("User")
                .projectPath("/projects/order-service")
                .metadata("{\"key\":\"value\"}")
                .build();

        // Then
        assertEquals(1L, metadata.getId());
        assertEquals("com.example.mapper.UserMapper", metadata.getInterfaceName());
        assertEquals("MYBATIS", metadata.getInterfaceType());
        assertEquals("com.example.mapper.UserMapperImpl", metadata.getImplementation());
        assertEquals("MYBATIS_MAPPER", metadata.getProxyType());
        assertEquals("selectById", metadata.getMethodName());
        assertEquals("User selectById(Long id)", metadata.getMethodSignature());
        assertEquals("SELECT * FROM users WHERE id = ?", metadata.getSqlStatement());
        assertEquals("User", metadata.getEntityType());
        assertEquals("/projects/order-service", metadata.getProjectPath());
    }

    @Test
    @DisplayName("测试无参构造函数和 Setter")
    void testNoArgsConstructorAndSetter() {
        // Given
        ProxyMetadata metadata = new ProxyMetadata();

        // When
        metadata.setId(1L);
        metadata.setInterfaceName("com.example.mapper.UserMapper");
        metadata.setInterfaceType("MYBATIS");
        metadata.setMethodName("selectById");

        // Then
        assertEquals(1L, metadata.getId());
        assertEquals("com.example.mapper.UserMapper", metadata.getInterfaceName());
        assertEquals("MYBATIS", metadata.getInterfaceType());
        assertEquals("selectById", metadata.getMethodName());
    }

    @Test
    @DisplayName("测试全参构造函数")
    void testAllArgsConstructor() {
        // When
        ProxyMetadata metadata = new ProxyMetadata(
                1L,
                "com.example.mapper.UserMapper",
                "MYBATIS",
                "com.example.mapper.UserMapperImpl",
                "MYBATIS_MAPPER",
                "selectById",
                "User selectById(Long id)",
                "SELECT * FROM users WHERE id = ?",
                "User",
                "/projects/order-service",
                "{\"key\":\"value\"}"
        );

        // Then
        assertEquals(1L, metadata.getId());
        assertEquals("MYBATIS", metadata.getInterfaceType());
        assertEquals("selectById", metadata.getMethodName());
    }

    @Test
    @DisplayName("测试接口类型常量")
    void testInterfaceTypeConstants() {
        assertEquals("MYBATIS", ProxyMetadata.INTERFACE_TYPE_MYBATIS);
        assertEquals("JPA", ProxyMetadata.INTERFACE_TYPE_JPA);
        assertEquals("AOP", ProxyMetadata.INTERFACE_TYPE_AOP);
        assertEquals("JDK_PROXY", ProxyMetadata.INTERFACE_TYPE_JDK_PROXY);
        assertEquals("CGLIB", ProxyMetadata.INTERFACE_TYPE_CGLIB);
    }

    @Test
    @DisplayName("测试代理类型常量")
    void testProxyTypeConstants() {
        assertEquals("MYBATIS_MAPPER", ProxyMetadata.PROXY_TYPE_MYBATIS_MAPPER);
        assertEquals("JPA_REPOSITORY", ProxyMetadata.PROXY_TYPE_JPA_REPOSITORY);
        assertEquals("ASPECT", ProxyMetadata.PROXY_TYPE_ASPECT);
        assertEquals("JDK_DYNAMIC", ProxyMetadata.PROXY_TYPE_JDK_DYNAMIC);
        assertEquals("CGLIB", ProxyMetadata.PROXY_TYPE_CGLIB);
    }

    @Test
    @DisplayName("测试 equals 和 hashCode")
    void testEqualsAndHashCode() {
        // Given
        ProxyMetadata metadata1 = ProxyMetadata.builder()
                .id(1L)
                .interfaceName("com.example.mapper.UserMapper")
                .build();

        ProxyMetadata metadata2 = ProxyMetadata.builder()
                .id(1L)
                .interfaceName("com.example.mapper.UserMapper")
                .build();

        ProxyMetadata metadata3 = ProxyMetadata.builder()
                .id(2L)
                .interfaceName("com.example.mapper.UserMapper")
                .build();

        // Then
        assertEquals(metadata1, metadata2);
        assertEquals(metadata1.hashCode(), metadata2.hashCode());
        assertNotEquals(metadata1, metadata3);
    }

    @Test
    @DisplayName("测试 toString")
    void testToString() {
        // Given
        ProxyMetadata metadata = ProxyMetadata.builder()
                .id(1L)
                .interfaceName("com.example.mapper.UserMapper")
                .interfaceType("MYBATIS")
                .build();

        // When
        String result = metadata.toString();

        // Then
        assertTrue(result.contains("com.example.mapper.UserMapper"));
        assertTrue(result.contains("MYBATIS"));
    }
}
