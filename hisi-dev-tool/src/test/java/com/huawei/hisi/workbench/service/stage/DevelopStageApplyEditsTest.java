package com.huawei.hisi.workbench.service.stage;

import com.huawei.hisi.workbench.service.stage.DevelopStage.EditException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevelopStageApplyEditsTest {

    @TempDir
    Path temp;

    private Path file(String name, String content) throws Exception {
        Path path = temp.resolve(name);
        Files.createDirectories(path.getParent());
        Files.writeString(path, content);
        return path;
    }

    @Test
    void replacesAUniqueFragment() throws Exception {
        Path source = file("src/main/java/com/a/UserService.java",
                "public class UserService {\n    String greet() { return \"hi\"; }\n}\n");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/UserService.java",
                "before", "return \"hi\";",
                "after", "return \"hello\";")));

        List<String> changed = DevelopStage.applyEdits(temp, plan);

        assertEquals(List.of("src/main/java/com/a/UserService.java"), changed);
        assertTrue(Files.readString(source).contains("return \"hello\";"));
        assertTrue(!Files.readString(source).contains("return \"hi\";"));
    }

    @Test
    void rejectsANonUniqueFragment() throws Exception {
        file("src/main/java/com/a/UserService.java",
                "int a() { return 1; }\nint b() { return 1; }\n");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/UserService.java",
                "before", "return 1;",
                "after", "return 2;")));

        EditException e = assertThrows(EditException.class, () -> DevelopStage.applyEdits(temp, plan));
        assertTrue(e.getMessage().contains("多次"));
    }

    @Test
    void rejectsAMissingFragment() throws Exception {
        file("src/main/java/com/a/UserService.java", "public class UserService {}");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/UserService.java",
                "before", "nothing like this",
                "after", "x")));

        EditException e = assertThrows(EditException.class, () -> DevelopStage.applyEdits(temp, plan));
        assertTrue(e.getMessage().contains("找不到"));
    }

    @Test
    void rejectsPathsOutsideTheWorktree() {
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "../outside/Evil.java",
                "before", "",
                "after", "class Evil {}")));

        EditException e = assertThrows(EditException.class, () -> DevelopStage.applyEdits(temp, plan));
        assertTrue(e.getMessage().contains("越出"));
    }

    @Test
    void createsNewFilesWithEmptyBefore() throws Exception {
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/test/java/com/a/UserServiceTest.java",
                "before", "",
                "after", "class UserServiceTest {}")));

        List<String> changed = DevelopStage.applyEdits(temp, plan);

        assertTrue(Files.exists(temp.resolve("src/test/java/com/a/UserServiceTest.java")));
        assertEquals(1, changed.size());
    }

    @Test
    void anLFFragmentMatchesACrlfFileAndTheFileStaysCrlf() throws Exception {
        Path source = file("src/main/java/com/a/UserService.java",
                "public class UserService {\r\n    int count() { return 1; }\r\n}\r\n");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/UserService.java",
                "before", "int count() { return 1; }",
                "after", "int count() {\n        return 2;\n    }")));

        DevelopStage.applyEdits(temp, plan);

        String content = Files.readString(source);
        assertTrue(content.contains("return 2;"));
        assertTrue(!content.contains("return 1;"));
        assertTrue(content.contains("\r\n"));
        assertEquals(-1, content.replace("\r\n", "").indexOf('\n'), "换行保持 CRLF，不混入裸 LF");
    }

    @Test
    void anEditAlreadyAppliedInAnEarlierRoundIsSkipped() throws Exception {
        Path source = file("src/main/java/com/a/UserService.java",
                "public class UserService {\r\n    User find() { return requireNonNull(user, \"user\"); }\r\n}\r\n");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/UserService.java",
                "before", "User find() { return user; }",
                "after", "User find() { return requireNonNull(user, \"user\"); }")));

        List<String> changed = DevelopStage.applyEdits(temp, plan);

        // 回到开发或重启后继续：模型照原计划再发一次同样的改动，不应该报「找不到 before」
        assertEquals(List.of("src/main/java/com/a/UserService.java"), changed);
        assertTrue(Files.readString(source).contains("requireNonNull(user, \"user\")"));
    }

    @Test
    void aShortAfterFragmentAloneDoesNotCountAsApplied() throws Exception {
        file("src/main/java/com/a/UserService.java", "public class UserService {\n    int x = 1;\n}\n");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/UserService.java",
                "before", "nothing like this",
                "after", "x")));

        // 一小段通用代码碰巧在文件里，不能据此认为改动已应用
        assertThrows(DevelopStage.EditException.class, () -> DevelopStage.applyEdits(temp, plan));
    }

    @Test
    void filesAreResolvedByFullPathOrByNameUnderSourceDirectories() throws Exception {
        Path real = file("module-a/src/main/java/com/a/UserController.java", "class UserController {}");
        file("target/generated-sources/UserController.java", "class Generated {}");

        assertEquals(real, DevelopStage.resolveFile(temp, "module-a/src/main/java/com/a/UserController.java"));
        assertEquals(real, DevelopStage.resolveFile(temp, "UserController.java"), "构建产物目录不参与按名查找");
        assertNull(DevelopStage.resolveFile(temp, "Missing.java"));
    }

    @Test
    void aTaskThatNeverCompilesIsUndoneSoTheWorktreeCompilesAgain() throws Exception {
        Path existing = file("src/main/java/com/a/UserService.java", "class UserService {}");
        Map<String, Object> plan = Map.of("edits", List.of(
                Map.of("file", "src/main/java/com/a/UserService.java", "before", "class UserService {}",
                        "after", "class UserService { int x = 1; }"),
                Map.of("file", "src/test/java/com/a/UserServiceTest.java", "before", "",
                        "after", "class UserServiceTest { int y = ; }")));

        // 应用前先记下每个文件原来的样子（新文件记为 null），回修失败后据此还原
        List<Path> planned = DevelopStage.plannedFiles(temp, plan);
        Map<Path, String> backups = new java.util.LinkedHashMap<>();
        for (Path path : planned) {
            backups.putIfAbsent(path, Files.isRegularFile(path) ? Files.readString(path) : null);
        }
        DevelopStage.applyEdits(temp, plan);
        assertTrue(Files.exists(temp.resolve("src/test/java/com/a/UserServiceTest.java")));
        for (Map.Entry<Path, String> backup : backups.entrySet()) {
            DevelopStage.restore(backup.getKey(), backup.getValue());
        }

        assertEquals("class UserService {}", Files.readString(existing), "改动过的文件恢复原样");
        assertTrue(Files.notExists(temp.resolve("src/test/java/com/a/UserServiceTest.java")), "新建的文件删掉");
    }

    @Test
    void plannedFilesStayInsideTheWorktree() throws Exception {
        file("pom.xml", "<project/>");
        Map<String, Object> plan = Map.of("edits", List.of(
                Map.of("file", "pom.xml", "before", "", "after", "x"),
                Map.of("file", "pom.xml", "before", "", "after", "y"),
                Map.of("file", "../outside.java", "before", "", "after", "z")));

        List<Path> planned = DevelopStage.plannedFiles(temp, plan);

        assertEquals(List.of(temp.resolve("pom.xml").toAbsolutePath().normalize()), planned, "去重，且不包含越界的路径");
        DevelopStage.restore(null, "ignored");
    }

    @Test
    void aLineRangeEditReplacesExactlyThoseLines() throws Exception {
        Path source = file("src/main/java/com/a/Big.java",
                "class Big {\r\n    int a() { return 1; }\r\n    int b() { return 2; }\r\n    int c() { return 3; }\r\n}\r\n");
        Map<String, Object> plan = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/Big.java",
                "startLine", 2, "endLine", 3, "contextLine", "    int a() { return 1; }",
                "after", "    int a() {\n        return 10;\n    }\n    int b() {\n        return 20;\n    }")));

        DevelopStage.applyEdits(temp, plan);

        String content = Files.readString(source);
        assertEquals("class Big {\r\n    int a() {\r\n        return 10;\r\n    }\r\n    int b() {\r\n        return 20;\r\n    }\r\n    int c() { return 3; }\r\n}\r\n",
                content, "区间外的行原样保留，换行保持 CRLF");
    }

    @Test
    void aMisalignedLineRangeIsRejectedBeforeItCanDamageOtherCode() throws Exception {
        Path source = file("src/main/java/com/a/Big.java", "int a = 1;\nint b = 2;\nint c = 3;\n");
        Map<String, Object> misaligned = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/Big.java", "startLine", 2, "endLine", 2,
                "contextLine", "int a = 1;", "after", "int changed = 0;")));

        EditException e = assertThrows(EditException.class, () -> DevelopStage.applyEdits(temp, misaligned));

        assertTrue(e.getMessage().contains("不一致"), e.getMessage());
        assertEquals("int a = 1;\nint b = 2;\nint c = 3;\n", Files.readString(source), "文件没有被改动");

        Map<String, Object> outOfRange = Map.of("edits", List.of(Map.of(
                "file", "src/main/java/com/a/Big.java", "startLine", 2, "endLine", 99, "after", "x")));
        EditException range = assertThrows(EditException.class, () -> DevelopStage.applyEdits(temp, outOfRange));
        assertTrue(range.getMessage().contains("超出文件范围"), range.getMessage());
    }

    @Test
    void aLargeFileIsShownAroundTheAnchorWithRealLineNumbers() {
        StringBuilder big = new StringBuilder("package com.a;\n\nimport java.util.List;\n\nclass Big {\n");
        for (int i = 0; i < 400; i++) {
            big.append("    void filler").append(i).append("() { /* 占位行，让文件足够长 */ }\n");
        }
        big.append("    int targetMethod(int x) {\n        return x + 1;\n    }\n}\n");

        String view = DevelopStage.fileView(big.toString(), "Big.targetMethod", 6000);

        assertTrue(view.contains("文件共 "), "说明只给了片段");
        assertTrue(view.contains("import java.util.List;"), "文件头（package 与 import）始终给出");
        assertTrue(view.contains("targetMethod(int x)"));
        String numberedLine = view.lines().filter(l -> l.endsWith("|     int targetMethod(int x) {")).findFirst().orElse("");
        assertEquals(406, Integer.parseInt(numberedLine.substring(0, numberedLine.indexOf('|')).trim()), "行号是文件里的真实行号");
        assertEquals(405, DevelopStage.locate(big.toString().split("\n"), "Big.targetMethod"));
        assertEquals(-1, DevelopStage.locate(big.toString().split("\n"), "Nope.nowhere"));
    }

    @Test
    void aSmallFileIsShownWholeWithLineNumbers() {
        String view = DevelopStage.fileView("class A {\n    int x = 1;\n}\n", "A.x", 8000);

        assertEquals("  1 | class A {\n  2 |     int x = 1;\n  3 | }\n  4 | \n", view, "行号右对齐到三位宽");
    }

    @Test
    void anchorsAreAcceptedInTheFormsTheTaskListActuallyUses() {
        String[] lines = ("package com.a;\npublic class SkillMarketController {\n"
                + "    public Map<String, Object> createSkill(String id) { return null; }\n"
                + "    public List<Map<String, Object>> getSkillList(String category) { return null; }\n}").split("\n", -1);
        int method = 3;

        assertEquals(method, DevelopStage.locate(lines, "SkillMarketController#getSkillList:35"));
        assertEquals(method, DevelopStage.locate(lines, "com.a.SkillMarketController#getSkillList"));
        assertEquals(method, DevelopStage.locate(lines, "SkillMarketController.getSkillList"));
        assertEquals(method, DevelopStage.locate(lines, "getSkillList"));
        assertEquals(1, DevelopStage.locate(lines, "SkillMarketController"), "只有类名时定位到类声明");
        assertEquals(1, DevelopStage.locate(lines, "SkillMarketController#missing"), "方法找不到时退到类声明，比文件开头更接近要改的地方");
        assertEquals(-1, DevelopStage.locate(lines, "Nothing#nowhere"));
    }

    @Test
    void anAnchorThatCannotBeFoundFallsBackToTheTopOfTheFileWithoutThrowing() {
        StringBuilder big = new StringBuilder("package com.a;\nclass Big {\n");
        for (int i = 0; i < 400; i++) {
            big.append("    void filler").append(i).append("() { /* 占位行，让文件足够长 */ }\n");
        }
        big.append("}\n");

        String view = DevelopStage.fileView(big.toString(), "Nothing#nowhere", 6000);

        // 真机上这里抛过 Index -1 out of bounds：窗口从第 0 行起算时取到了 lines[-1]
        assertTrue(view.contains("未能按「Nothing#nowhere」定位，给出开头部分"));
        assertTrue(view.contains("—— 相关片段（第 1–"), "定位不到时从第 1 行开始");
        assertFalse(view.contains(" -1 | "), "不会出现越界的行号");
    }

    @Test
    void theWindowStartsBeforeTheAnchorAndAlwaysContainsIt() {
        StringBuilder big = new StringBuilder("package com.a;\nclass Big {\n");
        for (int i = 0; i < 400; i++) {
            big.append("    void filler").append(i).append("() { /* 占位行，让文件足够长 */ }\n");
        }
        big.append("    int targetMethod(int x) {\n        return x + 1;\n    }\n}\n");

        String view = DevelopStage.fileView(big.toString(), "Big#targetMethod:403", 6000);

        // 第 3 行是 filler0，所以 400 个占位方法占第 3–402 行，目标方法在第 403 行，窗口从它前 14 行（第 388 行 = filler385）开始
        assertTrue(view.contains("—— 相关片段（第 388–"), "锚点前留 14 行上下文");
        assertTrue(view.contains("\n403 |     int targetMethod(int x) {"), "目标方法在窗口里，行号是文件里的真实行号");
        assertTrue(view.contains("\n388 |     void filler385() {"), "行号与内容对得上");
    }
}
