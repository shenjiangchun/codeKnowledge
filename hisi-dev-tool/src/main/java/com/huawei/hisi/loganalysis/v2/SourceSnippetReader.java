package com.huawei.hisi.loganalysis.v2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 按堆栈里的类名与行号，从本地源码读出所在方法的一段代码。
 *
 * <p>图谱里不一定存有每个方法的源码（建图时可能没有写入方法体）。堆栈本身就带着文件名与行号，
 * 项目目录又在本地，所以直接读文件是最可靠的兜底；而且保留了行号，比图谱里的压缩方法体更方便对照。
 *
 * <p>只读项目目录之下的源码文件：类名先经过白名单校验，再拼成相对路径，不可能拼出 {@code ..}。
 */
final class SourceSnippetReader {

    /** 读到的一段源码。 */
    record Snippet(String text, String filePath) {
    }

    private static final Pattern SAFE_CLASS_NAME = Pattern.compile("[A-Za-z0-9_.$]+");

    private static final int MAX_FILE_BYTES = 2 * 1024 * 1024;
    /** 向上找方法签名的最多行数。 */
    private static final int LOOK_BACK_LINES = 80;
    /** 一段方法最多取多少行；超过时只保留目标行附近的一段。 */
    private static final int MAX_SNIPPET_LINES = 60;
    private static final int WINDOW_RADIUS = 15;
    private static final int MAX_CHARS = 1800;
    private static final int MAX_MODULE_DIRS = 300;

    /** 常见的源码根目录，依次尝试。 */
    private static final List<String> SOURCE_DIRS = List.of("src/main/java", "src");

    private SourceSnippetReader() {
    }

    /**
     * 读取指定方法在指定行附近的一段源码，带行号。
     *
     * @param lineNumber 堆栈指向的行号（从 1 开始）
     * @param marker     加在目标行行尾的说明
     * @return 找不到文件或行号越界时返回 null
     */
    static Snippet read(List<String> projectPaths, String className, String methodName,
                        int lineNumber, String marker) {
        if (projectPaths == null || lineNumber <= 0 || className == null
                || methodName == null || methodName.isBlank()) {
            return null;
        }
        // 内部类、代理类的源码在外部类的文件里
        String outer = className.split("\\$", 2)[0];
        if (!SAFE_CLASS_NAME.matcher(outer).matches() || outer.contains("..")) {
            return null;
        }
        String relative = outer.replace('.', '/') + ".java";
        for (String projectPath : projectPaths) {
            Path root;
            try {
                root = Path.of(projectPath).normalize();
            } catch (RuntimeException e) {
                continue;
            }
            Snippet snippet = readFromRoot(root, relative, methodName, lineNumber, marker);
            if (snippet != null) {
                return snippet;
            }
        }
        return null;
    }

    private static Snippet readFromRoot(Path root, String relative, String methodName,
                                        int lineNumber, String marker) {
        if (!Files.isDirectory(root)) {
            return null;
        }
        for (String dir : SOURCE_DIRS) {
            Snippet snippet = snippet(root.resolve(dir).resolve(relative), root, methodName, lineNumber, marker);
            if (snippet != null) {
                return snippet;
            }
        }
        // 多模块工程：源码在一级子模块目录下
        try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
            int seen = 0;
            for (Path module : children) {
                if (seen++ > MAX_MODULE_DIRS || !Files.isDirectory(module)) {
                    continue;
                }
                String name = module.getFileName() == null ? "" : module.getFileName().toString();
                if (name.startsWith(".") || "target".equals(name) || "node_modules".equals(name) || "build".equals(name)) {
                    continue;
                }
                for (String dir : SOURCE_DIRS) {
                    Snippet snippet = snippet(module.resolve(dir).resolve(relative), root, methodName, lineNumber, marker);
                    if (snippet != null) {
                        return snippet;
                    }
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    private static Snippet snippet(Path file, Path root, String methodName, int lineNumber, String marker) {
        if (!Files.isRegularFile(file)) {
            return null;
        }
        List<String> lines;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return null;
            }
            // 宽松解码：GBK 等编码的中文注释不会让读取失败，只会显示成替换符
            lines = List.of(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).split("\n", -1));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        int target = lineNumber - 1;
        if (target >= lines.size()) {
            return null;
        }
        int declaration = findDeclarationStart(lines, target, methodName);
        // 方法的起止行。目标行上方找不到方法签名时（方法很长，或签名的写法特殊），
        // 不把整个文件都带上：从目标行上方 LOOK_BACK_LINES 处算起，到文件末尾。
        int blockStart = declaration >= 0 ? declaration : Math.max(0, target - LOOK_BACK_LINES);
        int blockEnd = declaration >= 0
                ? Math.max(findBlockEnd(lines, declaration, target), target)
                : lines.size() - 1;

        int from = blockStart;
        int to = blockEnd;
        if (to - from + 1 > MAX_SNIPPET_LINES) {
            from = Math.max(blockStart, target - WINDOW_RADIUS);
            to = Math.min(blockEnd, target + WINDOW_RADIUS);
        }
        to = Math.min(to, lines.size() - 1);

        StringBuilder sb = new StringBuilder();
        if (from > blockStart) {
            appendLine(sb, from - 1, "…（上方省略 " + (from - blockStart) + " 行）");
        }
        for (int i = from; i <= to; i++) {
            appendLine(sb, i, lines.get(i), i == target, marker);
        }
        if (to < blockEnd) {
            appendLine(sb, to, "…（下方省略 " + (blockEnd - to) + " 行）");
        }
        String text = sb.toString();
        if (text.length() > MAX_CHARS) {
            text = text.substring(0, MAX_CHARS) + "\n…（片段过长，已截断）";
        }
        return new Snippet(text, root.relativize(file).toString().replace('\\', '/'));
    }

    /** 从目标行向上找最近的一行「方法名(」且不像注释或调用语句的行，作为方法起点；找不到返回 -1。 */
    private static int findDeclarationStart(List<String> lines, int target, String methodName) {
        String needle = methodName + "(";
        for (int i = target; i >= 0 && i > target - LOOK_BACK_LINES; i--) {
            String trimmed = lines.get(i).strip();
            if (!trimmed.contains(needle) || trimmed.startsWith("//") || trimmed.startsWith("*")
                    || trimmed.startsWith("/*") || trimmed.endsWith(";")) {
                continue;
            }
            return i;
        }
        return -1;
    }

    /** 从方法起点做花括号配对，找到方法体的结束行；配不上时取目标行之后几行。 */
    private static int findBlockEnd(List<String> lines, int start, int target) {
        int depth = 0;
        boolean opened = false;
        int limit = Math.min(lines.size() - 1, start + MAX_SNIPPET_LINES + LOOK_BACK_LINES);
        for (int i = start; i <= limit; i++) {
            for (char c : codeOnly(lines.get(i)).toCharArray()) {
                if (c == '{') {
                    depth++;
                    opened = true;
                } else if (c == '}') {
                    depth--;
                }
            }
            if (opened && depth <= 0) {
                return i;
            }
        }
        // 配不上花括号（例如签名换行、注解里带括号）：取目标行之后几行，保证目标行一定在片段里
        return Math.min(lines.size() - 1, target + 8);
    }

    /** 去掉字符串字面量与行注释里的内容，避免其中的花括号干扰配对。 */
    private static String codeOnly(String line) {
        StringBuilder sb = new StringBuilder(line.length());
        boolean inString = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inString) {
                if (c == '\\' && i + 1 < line.length()) {
                    i++;
                } else if (c == quote) {
                    inString = false;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                inString = true;
                quote = c;
            } else if (c == '/' && i + 1 < line.length() && line.charAt(i + 1) == '/') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, int lineIndex, String content, boolean target, String marker) {
        sb.append(String.format("%4d| %s", lineIndex + 1, content.stripTrailing()));
        if (target && marker != null) {
            sb.append("   // <-- ").append(marker);
        }
        sb.append('\n');
    }

    private static void appendLine(StringBuilder sb, int lineIndex, String note) {
        sb.append(String.format("%4d| %s%n", lineIndex + 1, note));
    }
}
