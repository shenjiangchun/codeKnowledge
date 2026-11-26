package com.huawei.hisi.loganalysis.v2;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceSnippetReaderTest {

    @TempDir
    Path project;

    private Path write(String relative, String content) throws IOException {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    @Test
    void readsTheMethodAroundTheStackLineWithLineNumbers() throws IOException {
        write("src/main/java/com/example/controller/UserController.java", String.join("\n",
                "package com.example.controller;",
                "",
                "@RestController",
                "public class UserController {",
                "    private final UserService userService;",
                "",
                "    @GetMapping(\"/{id}\")",
                "    public User getUser(Long id) {",
                "        // 按主键查用户",
                "        return userService.getUserById(id);",
                "    }",
                "",
                "    public User createUser(String name) {",
                "        return userService.createUser(name);",
                "    }",
                "}",
                ""));

        SourceSnippetReader.Snippet snippet = SourceSnippetReader.read(
                List.of(project.toString()), "com.example.controller.UserController", "getUser", 10, "异常在这里抛出");

        assertNotNull(snippet);
        assertEquals("src/main/java/com/example/controller/UserController.java", snippet.filePath());
        String text = snippet.text();
        // 从方法签名取到方法体结束，不带下一个方法
        assertTrue(text.contains("   8|     public User getUser(Long id) {"), text);
        assertTrue(text.contains("  10|         return userService.getUserById(id);   // <-- 异常在这里抛出"), text);
        assertTrue(text.contains("  11|     }"), text);
        assertFalse(text.contains("createUser"), text);
    }

    @Test
    void handlesInnerClassesLambdasAndSourceUnderAModuleDirectory() throws IOException {
        write("service-a/src/main/java/com/example/Job.java", String.join("\n",
                "public class Job {",
                "    void run() {",
                "        Runnable r = () -> {",
                "            String json = \"{not a brace}\"; // }\"",
                "            step();",
                "        };",
                "        r.run();",
                "    }",
                "}",
                ""));

        // 堆栈里常见 lambda / 匿名类的写法：类名带 $Lambda / $1
        SourceSnippetReader.Snippet lambda = SourceSnippetReader.read(
                List.of(project.toString()), "com.example.Job$$Lambda$12/0x0000000800c0a840", "run", 5, null);
        SourceSnippetReader.Snippet anonymous = SourceSnippetReader.read(
                List.of(project.toString()), "com.example.Job$1", "step", 5, null);

        assertNotNull(lambda);
        assertNotNull(anonymous);
        // 字符串与注释里的花括号不应提前结束方法体
        assertTrue(lambda.text().contains("   7|         r.run();"), lambda.text());
        assertTrue(lambda.text().contains("   8|     }"), lambda.text());
    }

    @Test
    void longMethodsAreCutToAWindowAroundTheStackLine() throws IOException {
        StringBuilder sb = new StringBuilder("public class Big {\n    void big() {\n");
        for (int i = 0; i < 200; i++) {
            sb.append("        line").append(i).append("();\n");
        }
        sb.append("    }\n}\n");
        write("src/main/java/com/example/Big.java", sb.toString());

        SourceSnippetReader.Snippet snippet = SourceSnippetReader.read(
                List.of(project.toString()), "com.example.Big", "big", 120, "异常在这里抛出");

        assertNotNull(snippet);
        String text = snippet.text();
        assertTrue(text.contains("上方省略"), text);
        assertTrue(text.contains("下方省略"), text);
        assertTrue(text.contains(" 120|         line117();   // <-- 异常在这里抛出"), text);
        assertFalse(text.contains("line10()"), text);
    }

    @Test
    void returnsNullWhenTheFileOrLineDoesNotExist() throws IOException {
        write("src/main/java/com/example/Small.java", "class Small {\n}\n");
        List<String> paths = List.of(project.toString());

        assertNull(SourceSnippetReader.read(paths, "com.example.Missing", "go", 3, null));
        assertNull(SourceSnippetReader.read(paths, "com.example.Small", "go", 99, null));
        assertNull(SourceSnippetReader.read(paths, "com.example.Small", "go", 0, null));
        assertNull(SourceSnippetReader.read(List.of(), "com.example.Small", "go", 1, null));
        assertNull(SourceSnippetReader.read(List.of(project.resolve("no-such-dir").toString()),
                "com.example.Small", "go", 1, null));
    }

    @Test
    void refusesClassNamesThatCouldEscapeTheProjectDirectory() throws IOException {
        Files.createDirectories(project.resolve("secret"));
        write("secret/Secret.java", "class Secret {\n}\n");
        List<String> paths = List.of(project.toString());

        // 含路径分隔符或 .. 的类名不可能来自正常的堆栈，一律拒绝
        assertNull(SourceSnippetReader.read(paths, "com.example..secret.Secret", "x", 1, null));
        assertNull(SourceSnippetReader.read(paths, "../secret/Secret", "x", 1, null));
        assertNull(SourceSnippetReader.read(paths, "com/example/Secret", "x", 1, null));
        assertNull(SourceSnippetReader.read(paths, "C:/Windows/win.ini", "x", 1, null));
    }
}
