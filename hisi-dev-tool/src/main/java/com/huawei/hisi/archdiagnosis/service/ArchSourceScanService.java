package com.huawei.hisi.archdiagnosis.service;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.ImportDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.ConstructorDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.Type;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 源码注入点扫描服务（诊断侧纯读，无 LLM）：直接扫描本地 .java 源码，
 * 识别 {@code @Autowired/@Resource/@Inject} 字段注入与构造器注入的声明类型，
 * 判定是接口（interface）/ 实现类（class）/ 项目外类型（external）。
 *
 * <p>图谱构建将接口注入层淡化为直达实现类的 CALLS 边，丢失「接口注入 vs 直接依赖
 * 实现类」的语义；本服务从源码声明类型恢复该信息，供 AI 深度分析灰度验证
 * 「直接依赖实现类」类误报。</p>
 */
@Slf4j
@Service
public class ArchSourceScanService {

    private static final Set<String> INJECT_ANNOTATIONS = Set.of("Autowired", "Resource", "Inject");

    /** 单次扫描文件数上限（防大项目扫爆上下文），超出置 truncated=true。 */
    private static final int MAX_FILES = 10000;

    /** 全项目注入统计摘要（接口注入数 / 实现类注入数 / 外部类型注入数 + Top 直接依赖实现类的类）。 */
    public Map<String, Object> injectionSummary(List<String> projectPaths) {
        ScanResult scan = scan(projectPaths);

        int interfaceCount = 0;
        int classCount = 0;
        int externalCount = 0;
        List<Map<String, Object>> implInjected = new ArrayList<>();
        for (ClassInjection ci : scan.classes) {
            interfaceCount += ci.interfaceCount;
            classCount += ci.classCount;
            externalCount += ci.externalCount;
            if (!ci.implTypes.isEmpty()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("className", ci.className);
                row.put("packageName", ci.packageName);
                row.put("implInjectionCount", ci.implTypes.size());
                row.put("implTypes", ci.implTypes);
                implInjected.add(row);
            }
        }
        implInjected.sort((a, b) -> Integer.compare(
                (int) b.get("implInjectionCount"), (int) a.get("implInjectionCount")));
        if (implInjected.size() > 10) {
            implInjected = new ArrayList<>(implInjected.subList(0, 10));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("scannedFileCount", scan.scannedFileCount);
        result.put("typeCount", scan.typeIndex.size());
        result.put("classesWithInjection", scan.classes.size());
        result.put("interfaceInjectionCount", interfaceCount);
        result.put("classInjectionCount", classCount);
        result.put("externalInjectionCount", externalCount);
        result.put("topImplInjectedClasses", implInjected);
        result.put("truncated", scan.truncated);
        return result;
    }

    /** 按 className 或 packageFilter（包名前缀）下钻返回注入点明细。 */
    public Map<String, Object> injectionSites(List<String> projectPaths, String className, String packageFilter) {
        ScanResult scan = scan(projectPaths);

        List<Map<String, Object>> items = new ArrayList<>();
        for (ClassInjection ci : scan.classes) {
            if (className != null && !className.equals(ci.className)) {
                continue;
            }
            if (packageFilter != null && !packageFilter.isBlank()
                    && !ci.packageName.startsWith(packageFilter)) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("className", ci.className);
            item.put("packageName", ci.packageName);
            item.put("filePath", ci.filePath);
            item.put("injections", ci.injections);
            item.put("interfaceInjectionCount", ci.interfaceCount);
            item.put("classInjectionCount", ci.classCount);
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", items.size());
        result.put("truncated", scan.truncated);
        return result;
    }

    // ── 扫描：两遍（先建类型索引，再收注入点，因注入点解析需全局类型索引） ──

    private ScanResult scan(List<String> projectPaths) {
        Map<String, Boolean> typeIndex = new HashMap<>();
        List<CuFile> files = new ArrayList<>();
        int scannedFileCount = 0;
        boolean truncated = false;

        for (String path : projectPaths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            Path root = Path.of(path);
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root, 20)) {
                for (Path file : (Iterable<Path>) walk::iterator) {
                    if (!isJavaSource(file)) {
                        continue;
                    }
                    if (scannedFileCount >= MAX_FILES) {
                        truncated = true;
                        break;
                    }
                    try {
                        CompilationUnit cu = StaticJavaParser.parse(file);
                        scannedFileCount++;
                        collectTypes(cu, typeIndex);
                        files.add(new CuFile(file, cu));
                    } catch (Exception e) {
                        log.debug("[ArchSourceScan] parse failed {}: {}", file, e.getMessage());
                    }
                }
            } catch (IOException e) {
                log.warn("[ArchSourceScan] walk failed {}: {}", path, e.getMessage());
            }
        }

        List<ClassInjection> classes = new ArrayList<>();
        for (CuFile cf : files) {
            collectInjections(cf.cu, cf.path, typeIndex, classes);
        }
        return new ScanResult(scannedFileCount, typeIndex, classes, truncated);
    }

    private void collectTypes(CompilationUnit cu, Map<String, Boolean> typeIndex) {
        String pkg = packageOf(cu);
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (td instanceof ClassOrInterfaceDeclaration cid) {
                typeIndex.putIfAbsent(fqn(pkg, cid.getNameAsString()), cid.isInterface());
            }
        }
    }

    private void collectInjections(CompilationUnit cu, Path file,
                                   Map<String, Boolean> typeIndex, List<ClassInjection> out) {
        String pkg = packageOf(cu);
        Map<String, String> imports = importMap(cu);

        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof ClassOrInterfaceDeclaration cid) || cid.isInterface()) {
                continue;
            }
            String className = fqn(pkg, cid.getNameAsString());
            if (isTestClass(className)) {
                continue;
            }

            List<Map<String, Object>> injections = new ArrayList<>();

            for (FieldDeclaration field : cid.getFields()) {
                boolean injected = field.getAnnotations().stream()
                        .anyMatch(a -> INJECT_ANNOTATIONS.contains(a.getNameAsString()));
                if (!injected) {
                    continue;
                }
                for (VariableDeclarator v : field.getVariables()) {
                    Map<String, Object> inj = resolveType(v.getType(), v.getNameAsString(),
                            "field", imports, pkg, typeIndex);
                    if (inj != null) {
                        injections.add(inj);
                    }
                }
            }

            List<ConstructorDeclaration> ctors = cid.getConstructors();
            for (ConstructorDeclaration ctor : ctors) {
                boolean annotated = ctor.getAnnotations().stream()
                        .anyMatch(a -> INJECT_ANNOTATIONS.contains(a.getNameAsString()));
                boolean singleArgCtor = ctors.size() == 1 && !ctor.getParameters().isEmpty();
                if (!annotated && !singleArgCtor) {
                    continue;
                }
                for (Parameter p : ctor.getParameters()) {
                    Map<String, Object> inj = resolveType(p.getType(), p.getNameAsString(),
                            "constructor", imports, pkg, typeIndex);
                    if (inj != null) {
                        injections.add(inj);
                    }
                }
            }

            if (injections.isEmpty()) {
                continue;
            }

            int interfaceCount = 0;
            int classCount = 0;
            int externalCount = 0;
            List<String> implTypes = new ArrayList<>();
            for (Map<String, Object> inj : injections) {
                switch ((String) inj.get("kind")) {
                    case "interface" -> interfaceCount++;
                    case "class" -> {
                        classCount++;
                        implTypes.add((String) inj.get("fqn"));
                    }
                    default -> externalCount++;
                }
            }

            ClassInjection ci = new ClassInjection();
            ci.className = className;
            ci.packageName = pkg;
            ci.filePath = file.toString();
            ci.injections = injections;
            ci.interfaceCount = interfaceCount;
            ci.classCount = classCount;
            ci.externalCount = externalCount;
            ci.implTypes = implTypes;
            out.add(ci);
        }
    }

    /** 解析注入点声明类型 → {via, name, declaredType, fqn, kind}；primitive/array 等非引用类型返回 null。 */
    private Map<String, Object> resolveType(Type type, String varName, String via,
                                           Map<String, String> imports, String pkg,
                                           Map<String, Boolean> typeIndex) {
        if (!(type instanceof ClassOrInterfaceType cit)) {
            return null;
        }
        String simpleName = cit.getNameAsString();
        String resolvedFqn = resolveFqn(simpleName, imports, pkg, typeIndex);
        String kind;
        if (resolvedFqn == null) {
            kind = "external";
        } else {
            Boolean isInterface = typeIndex.get(resolvedFqn);
            kind = isInterface == null ? "external" : (isInterface ? "interface" : "class");
        }

        Map<String, Object> inj = new LinkedHashMap<>();
        inj.put("via", via);
        inj.put("name", varName);
        inj.put("declaredType", simpleName);
        inj.put("fqn", resolvedFqn);
        inj.put("kind", kind);
        return inj;
    }

    /** 简单名 → 全限定名：先查显式 import，再试同包类型，未命中返回 null（外部类型）。 */
    private String resolveFqn(String simpleName, Map<String, String> imports,
                              String pkg, Map<String, Boolean> typeIndex) {
        if (imports.containsKey(simpleName)) {
            return imports.get(simpleName);
        }
        String samePkg = fqn(pkg, simpleName);
        if (typeIndex.containsKey(samePkg)) {
            return samePkg;
        }
        return null;
    }

    private Map<String, String> importMap(CompilationUnit cu) {
        Map<String, String> map = new HashMap<>();
        for (ImportDeclaration imp : cu.getImports()) {
            if (!imp.isAsterisk()) {
                String fqn = imp.getNameAsString();
                map.put(fqn.substring(fqn.lastIndexOf('.') + 1), fqn);
            }
        }
        return map;
    }

    private static String packageOf(CompilationUnit cu) {
        return cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");
    }

    private static String fqn(String pkg, String simpleName) {
        return pkg.isEmpty() ? simpleName : pkg + "." + simpleName;
    }

    private static boolean isTestClass(String className) {
        return className.endsWith("Test") || className.endsWith("Tests");
    }

    private static boolean isJavaSource(Path path) {
        if (!path.toString().endsWith(".java")) {
            return false;
        }
        String fileName = path.getFileName().toString();
        if (fileName.endsWith("Test.java") || fileName.endsWith("Tests.java")) {
            return false;
        }
        String p = path.toString().replace('\\', '/');
        return !p.contains("/target/") && !p.contains("/build/") && !p.contains("/node_modules/")
                && !p.contains("/src/test/") && !p.contains("/.git/");
    }

    // ── 内部类型 ──

    private record ScanResult(int scannedFileCount, Map<String, Boolean> typeIndex,
                              List<ClassInjection> classes, boolean truncated) {}

    private record CuFile(Path path, CompilationUnit cu) {}

    private static final class ClassInjection {
        String className;
        String packageName;
        String filePath;
        List<Map<String, Object>> injections;
        int interfaceCount;
        int classCount;
        int externalCount;
        List<String> implTypes;
    }
}
