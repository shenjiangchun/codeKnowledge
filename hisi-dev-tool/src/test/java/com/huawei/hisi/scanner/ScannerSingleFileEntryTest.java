package com.huawei.hisi.scanner;

import com.huawei.hisi.knowledgegraph.scanner.MyBatisXmlScanner;
import com.huawei.hisi.model.ScanResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that each scanner's scanFile(String) single-file entry point
 * is callable and returns a valid result.
 */
class ScannerSingleFileEntryTest {

    @TempDir
    Path tempDir;

    private Path writeJavaFile(String content) throws IOException {
        Path file = tempDir.resolve("Test.java");
        Files.writeString(file, content);
        return file;
    }

    private Path writeXmlFile(String content) throws IOException {
        Path file = tempDir.resolve("TestMapper.xml");
        Files.writeString(file, content);
        return file;
    }

    // --- FeignClientScanner ---

    @Test
    @DisplayName("FeignClientScanner.scanFile(String) returns result for a Java file")
    void feignClientScanner_scanFile_returnsResult() throws IOException {
        Path javaFile = writeJavaFile("""
                package com.example;
                public interface MyClient {
                    String hello();
                }
                """);

        FeignClientScanner scanner = new FeignClientScanner();
        ScanResult<?> result = scanner.scanFile(javaFile.toString());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
    }

    // --- MQEndpointScanner ---

    @Test
    @DisplayName("MQEndpointScanner.scanFile(String) returns result for a Java file")
    void mqEndpointScanner_scanFile_returnsResult() throws IOException {
        Path javaFile = writeJavaFile("""
                package com.example;
                public class MyListener {
                    public void onMessage(String msg) {}
                }
                """);

        MQEndpointScanner scanner = new MQEndpointScanner();
        ScanResult<?> result = scanner.scanFile(javaFile.toString());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
    }

    // --- HttpCallScanner ---

    @Test
    @DisplayName("HttpCallScanner.scanFile(String) returns result for a Java file")
    void httpCallScanner_scanFile_returnsResult() throws IOException {
        Path javaFile = writeJavaFile("""
                package com.example;
                public class MyService {
                    public void call() {}
                }
                """);

        HttpCallScanner scanner = new HttpCallScanner();
        ScanResult<?> result = scanner.scanFile(javaFile.toString());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
    }

    // --- ProxyClassScanner ---

    @Test
    @DisplayName("ProxyClassScanner.scanFile(String) returns result for a Java file")
    void proxyClassScanner_scanFile_returnsResult() throws IOException {
        Path javaFile = writeJavaFile("""
                package com.example;
                public interface MyMapper {
                    void findAll();
                }
                """);

        ProxyClassScanner scanner = new ProxyClassScanner();
        ScanResult<?> result = scanner.scanFile(javaFile.toString());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
    }

    // --- MyBatisXmlScanner ---

    @Test
    @DisplayName("MyBatisXmlScanner.scanFile(String) returns result for an XML file")
    void myBatisXmlScanner_scanFile_returnsResult() throws IOException {
        Path xmlFile = writeXmlFile("""
                <?xml version="1.0" encoding="UTF-8"?>
                <mapper namespace="com.example.dao.UserMapper">
                    <select id="findById" resultType="com.example.model.User">
                        SELECT * FROM users WHERE id = #{id}
                    </select>
                </mapper>
                """);

        MyBatisXmlScanner scanner = new MyBatisXmlScanner();
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanFile(xmlFile.toString());

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSqlNodes()).isNotEmpty();
        assertThat(result.getSqlNodes().get(0).getSqlId()).isEqualTo("com.example.dao.UserMapper.findById");
    }

    // --- MyBatisXmlScanner with non-mapper XML ---

    @Test
    @DisplayName("MyBatisXmlScanner.scanFile(String) returns empty for non-mapper XML")
    void myBatisXmlScanner_scanFile_nonMapper_returnsEmpty() throws IOException {
        Path xmlFile = writeXmlFile("""
                <?xml version="1.0" encoding="UTF-8"?>
                <beans>
                    <bean id="foo" class="com.example.Foo"/>
                </beans>
                """);

        MyBatisXmlScanner scanner = new MyBatisXmlScanner();
        MyBatisXmlScanner.Neo4jScanResult result = scanner.scanFile(xmlFile.toString());

        assertThat(result).isNotNull();
        assertThat(result.getSqlNodes()).isEmpty();
    }
}
