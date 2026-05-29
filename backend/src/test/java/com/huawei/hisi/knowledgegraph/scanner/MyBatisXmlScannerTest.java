package com.huawei.hisi.knowledgegraph.scanner;

import com.huawei.hisi.neo4j.model.SqlNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MyBatis XML 解析器测试
 * 重构版：测试 Neo4j 版本的扫描器
 */
class MyBatisXmlScannerTest {

    private MyBatisXmlScanner scanner;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        scanner = new MyBatisXmlScanner();
    }

    @Test
    void shouldScanMapperXmlAndExtractSqlStatements() throws Exception {
        // Given - 创建测试用的 Mapper XML 文件
        String mapperXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="selectById" parameterType="java.lang.Long" resultType="com.example.entity.User">
                    SELECT * FROM user WHERE id = #{id}
                </select>

                <insert id="insert" parameterType="com.example.entity.User">
                    INSERT INTO user (name, email) VALUES (#{name}, #{email})
                </insert>

                <update id="update" parameterType="com.example.entity.User">
                    UPDATE user SET name = #{name}, email = #{email} WHERE id = #{id}
                </update>

                <delete id="deleteById" parameterType="java.lang.Long">
                    DELETE FROM user WHERE id = #{id}
                </delete>
            </mapper>
            """;

        Path xmlFile = tempDir.resolve("UserMapper.xml");
        Files.writeString(xmlFile, mapperXml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMapperCount()).isEqualTo(1);
        assertThat(result.getSqlCount()).isEqualTo(4);
        assertThat(result.getSqlNodes()).hasSize(4);

        // Verify SQL nodes contain expected data
        List<SqlNode> sqlNodes = result.getSqlNodes();
        assertThat(sqlNodes.stream().anyMatch(n -> n.getSqlId().contains("selectById"))).isTrue();
        assertThat(sqlNodes.stream().anyMatch(n -> n.getSqlId().contains("insert"))).isTrue();
        assertThat(sqlNodes.stream().anyMatch(n -> n.getSqlId().contains("update"))).isTrue();
        assertThat(sqlNodes.stream().anyMatch(n -> n.getSqlId().contains("deleteById"))).isTrue();
    }

    @Test
    void shouldHandleEmptyProject() {
        // Given - 空目录

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMapperCount()).isEqualTo(0);
        assertThat(result.getSqlCount()).isEqualTo(0);
        assertThat(result.getSqlNodes()).isEmpty();
    }

    @Test
    void shouldIgnoreNonMapperXml() throws Exception {
        // Given - 创建非 Mapper XML 文件
        String nonMapperXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <configuration>
                <property name="key" value="value"/>
            </configuration>
            """;

        Path xmlFile = tempDir.resolve("config.xml");
        Files.writeString(xmlFile, nonMapperXml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMapperCount()).isEqualTo(0);
        assertThat(result.getSqlCount()).isEqualTo(0);
    }

    @Test
    void shouldHandleMalformedXml() throws Exception {
        // Given - 创建格式错误的 XML
        String malformedXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <mapper namespace="com.example.mapper.TestMapper">
                <select id="test"
            </mapper>
            """;

        Path xmlFile = tempDir.resolve("TestMapper.xml");
        Files.writeString(xmlFile, malformedXml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then - 应该优雅地处理错误，不应该抛出异常
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getErrors()).hasSize(1);
    }

    @Test
    void shouldParseMultipleMapperFiles() throws Exception {
        // Given - 创建多个 Mapper XML 文件
        String mapper1Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.UserMapper">
                <select id="findById" resultType="com.example.entity.User">
                    SELECT * FROM user WHERE id = #{id}
                </select>
            </mapper>
            """;

        String mapper2Xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.OrderMapper">
                <select id="findByUserId" resultType="com.example.entity.Order">
                    SELECT * FROM order WHERE user_id = #{userId}
                </select>
                <insert id="insert">
                    INSERT INTO order (user_id, amount) VALUES (#{userId}, #{amount})
                </insert>
            </mapper>
            """;

        Path resourcesDir = tempDir.resolve("resources");
        Files.createDirectories(resourcesDir);
        Files.writeString(resourcesDir.resolve("UserMapper.xml"), mapper1Xml);
        Files.writeString(resourcesDir.resolve("OrderMapper.xml"), mapper2Xml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMapperCount()).isEqualTo(2);
        assertThat(result.getSqlCount()).isEqualTo(3);
        assertThat(result.getMapperInterfaces()).containsExactlyInAnyOrder(
                "com.example.mapper.UserMapper", "com.example.mapper.OrderMapper");
    }

    @Test
    void shouldExtractSqlAttributes() throws Exception {
        // Given
        String mapperXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.TestMapper">
                <select id="selectTest" parameterType="java.lang.String" resultType="com.example.entity.Test">
                    SELECT * FROM test WHERE name = #{name}
                </select>
                <select id="selectWithResultMap" resultMap="testResultMap">
                    SELECT * FROM test
                </select>
            </mapper>
            """;

        Path xmlFile = tempDir.resolve("TestMapper.xml");
        Files.writeString(xmlFile, mapperXml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSqlCount()).isEqualTo(2);

        // Verify statement types
        List<SqlNode> sqlNodes = result.getSqlNodes();
        assertThat(sqlNodes.stream().allMatch(n -> "SELECT".equals(n.getStatementType()))).isTrue();
    }

    @Test
    void shouldHandleMapperWithoutNamespace() throws Exception {
        // Given - Mapper XML 没有命名空间
        String mapperXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper>
                <select id="selectTest">
                    SELECT * FROM test
                </select>
            </mapper>
            """;

        Path xmlFile = tempDir.resolve("NoNamespaceMapper.xml");
        Files.writeString(xmlFile, mapperXml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then - 没有 namespace 的 mapper 应该被忽略
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getMapperCount()).isEqualTo(0);
    }

    @Test
    void shouldGenerateCorrectNodeId() throws Exception {
        // Given
        String mapperXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE mapper PUBLIC "-//mybatis.org//DTD Mapper 3.0//EN"
                "http://mybatis.org/dtd/mybatis-3-mapper.dtd">
            <mapper namespace="com.example.mapper.TestMapper">
                <select id="findTest">
                    SELECT * FROM test
                </select>
            </mapper>
            """;

        Path xmlFile = tempDir.resolve("TestMapper.xml");
        Files.writeString(xmlFile, mapperXml);

        // When
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanProjectForNeo4j(tempDir.toString());

        // Then - nodeId 应该是 projectPath:sqlId 格式
        assertThat(result.getSqlNodes()).hasSize(1);
        SqlNode node = result.getSqlNodes().get(0);
        assertThat(node.getNodeId()).isEqualTo(tempDir.toString() + ":com.example.mapper.TestMapper.findTest");
        assertThat(node.getSqlId()).isEqualTo("com.example.mapper.TestMapper.findTest");
        assertThat(node.getProjectPath()).isEqualTo(tempDir.toString());
    }
}
