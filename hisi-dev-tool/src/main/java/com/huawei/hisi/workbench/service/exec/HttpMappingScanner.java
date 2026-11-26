package com.huawei.hisi.workbench.service.exec;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 从 Java 源码里提取 HTTP 接口映射：类级 {@code @RequestMapping("/users")} 加方法级
 * {@code @GetMapping("/{id}")} → {@code GET /users/{id}}。
 *
 * <p>启发式扫描，用于两处：代码实证阶段把它作为证据交给模型（Spec 里的接口用例因而有真实路径可依），
 * E2E 阶段用它校验用例路径确实存在于代码——路径写错时 404 是假通过。
 * 只认字面量路径；非 Java 项目扫不到任何映射，返回空列表。
 */
public final class HttpMappingScanner {

    /** 路径可以是字面量，也可以整个省略（{@code @GetMapping} 跟在类级路径后面）。 */
    private static final Pattern MAPPING = Pattern.compile(
            "@(Request|Get|Post|Put|Delete|Patch)Mapping(?:\\s*\\(\\s*(?:value\\s*=\\s*|path\\s*=\\s*)?\\{?\\s*\"([^\"]*)\"|(?!\\s*[=(]))");
    /** 类型声明所在行：行首只有修饰符和关键字。注释里出现「xxx class Y」不算。 */
    private static final Pattern CLASS_DECL = Pattern.compile(
            "(?m)^[ \\t]*(?:(?:public|protected|private|abstract|final|static|sealed|non-sealed|strictfp)\\s+)*(class|interface|record)\\s+[A-Za-z_]");
    private static final Pattern REQUEST_METHOD = Pattern.compile("RequestMethod\\.(GET|POST|PUT|DELETE|PATCH)");

    private HttpMappingScanner() {}

    /** 扫描项目根（含一层子模块）下 src/main/java 里的所有映射。任何读取失败都只跳过该文件。 */
    public static List<String> scan(Path projectRoot) {
        List<Path> roots = new ArrayList<>();
        roots.add(projectRoot.resolve("src/main/java"));
        try (Stream<Path> modules = Files.list(projectRoot)) {
            modules.filter(Files::isDirectory)
                    .map(p -> p.resolve("src/main/java"))
                    .filter(Files::isDirectory)
                    .forEach(roots::add);
        } catch (IOException ignored) {
            // 项目根读不了：只剩默认的 src/main/java
        }
        List<String> mappings = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(root)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                    try {
                        mappings.addAll(scanFile(Files.readString(file, StandardCharsets.UTF_8)));
                    } catch (IOException ignored) {
                        // 单个文件读不了不影响其它文件
                    }
                });
            } catch (IOException ignored) {
                // 目录遍历失败视为没有映射
            }
        }
        return mappings;
    }

    /** 扫描一份源码：类声明之前的映射是类级前缀，之后的是接口。 */
    public static List<String> scanFile(String source) {
        Matcher classDecl = CLASS_DECL.matcher(source);
        int classPos = classDecl.find() ? classDecl.start(1) : -1;

        String prefix = "";
        List<String> mappings = new ArrayList<>();
        Matcher mapping = MAPPING.matcher(source);
        while (mapping.find()) {
            String path = mapping.group(2) == null ? "" : mapping.group(2).trim();
            if (classPos < 0 || mapping.start() < classPos) {
                // 类级：只取路径前缀
                prefix = path;
                continue;
            }
            String verb = "Request".equals(mapping.group(1)) ? verbOf(source, mapping.start()) : mapping.group(1).toUpperCase(Locale.ROOT);
            String full = join(prefix, path);
            String entry = verb + " " + full;
            if (!mappings.contains(entry)) {
                mappings.add(entry);
            }
        }
        return mappings;
    }

    /** {@code @RequestMapping} 没写 method 时按任意方法处理。 */
    private static String verbOf(String source, int annotationStart) {
        Matcher method = REQUEST_METHOD.matcher(source);
        return method.find(annotationStart) && method.start() - annotationStart < 200
                ? method.group(1) : "ANY";
    }

    private static String join(String prefix, String path) {
        String joined = "/" + prefix + "/" + path;
        joined = joined.replaceAll("/+", "/");
        return joined.length() > 1 && joined.endsWith("/") ? joined.substring(0, joined.length() - 1) : joined;
    }

    /** 用例路径是否对应代码里的某个映射：分段比较，路径变量只认形状不认名字（{id} 与 {userId} 等价）。 */
    public static boolean matches(List<String> mappings, String method, String path) {
        for (String mapping : mappings) {
            int split = mapping.indexOf(' ');
            String verb = mapping.substring(0, split);
            if (!"ANY".equals(verb) && !verb.equalsIgnoreCase(method)) {
                continue;
            }
            if (sameTemplate(mapping.substring(split + 1), path)) {
                return true;
            }
        }
        return false;
    }

    static boolean sameTemplate(String a, String b) {
        String[] left = a.split("/");
        String[] right = b.split("/");
        if (left.length != right.length) {
            return false;
        }
        for (int i = 0; i < left.length; i++) {
            boolean leftVar = left[i].startsWith("{") && left[i].endsWith("}");
            boolean rightVar = right[i].startsWith("{") && right[i].endsWith("}");
            if (!(leftVar && rightVar) && !left[i].equals(right[i])) {
                return false;
            }
        }
        return true;
    }
}
