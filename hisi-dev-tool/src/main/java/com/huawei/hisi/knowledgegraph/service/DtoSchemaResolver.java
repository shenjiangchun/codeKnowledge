package com.huawei.hisi.knowledgegraph.service;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.huawei.hisi.service.CodeAnalysisCoreService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 解析 Java DTO 类的字段 schema（字段名、类型、Bean Validation 约束）。
 *
 * <p>用于 APM 调试页面的 RequestBody 动态表单：根据方法 @RequestBody 参数类型，
 * 反射出该 DTO 的字段列表与校验注解，前端据此渲染表单并生成合法 JSON。
 *
 * <p>非框架反射 —— 走 JavaParser 静态解析项目源码，避免类加载冲突。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DtoSchemaResolver {

    private final CodeAnalysisCoreService coreService;

    /** 简单名 → FQN 索引缓存，按 projectPath 分桶。 */
    private final Map<String, Map<String, String>> simpleNameIndexCache = new ConcurrentHashMap<>();

    /** FQN → DtoSchema 缓存，按 projectPath 分桶。 */
    private final Map<String, Map<String, DtoSchema>> schemaCache = new ConcurrentHashMap<>();

    /**
     * 解析 DTO schema。className 可传简单名或全限定名。
     *
     * @return 解析失败返回 null（找不到源码或非 POJO 类型）
     */
    /** Public entry: resolve top-level schema (depth 0 / fresh visited set). */
    public DtoSchema resolve(String className, String projectPath) {
        return resolve(className, projectPath, new java.util.HashSet<>(), 0);
    }

    /** Maximum recursion depth for nested DTO expansion. */
    private static final int MAX_NESTED_DEPTH = 4;

    /**
     * Internal recursive resolver. {@code visited} prevents infinite loops on
     * cyclic types (A -> B -> A). {@code depth} caps the expansion footprint
     * so a deep object graph does not explode the response payload.
     */
    private DtoSchema resolve(String className, String projectPath,
                              java.util.Set<String> visited, int depth) {
        if (className == null || className.isBlank() || projectPath == null) {
            return null;
        }
        String fqn = resolveFqn(className, projectPath);
        if (fqn == null) {
            log.debug("[DtoSchema] FQN not found for {} in {}", className, projectPath);
            return null;
        }
        if (visited.contains(fqn) || depth >= MAX_NESTED_DEPTH) {
            return null;
        }
        var bucket = schemaCache.computeIfAbsent(projectPath, k -> new ConcurrentHashMap<>());
        // Cache is only safe for top-level (depth 0) — nested cycles depend on visited set.
        if (depth == 0) {
            DtoSchema cached = bucket.get(fqn);
            if (cached != null) {
                return cached;
            }
        }
        java.util.Set<String> nextVisited = new java.util.HashSet<>(visited);
        nextVisited.add(fqn);
        DtoSchema schema = parseSchema(fqn, projectPath, nextVisited, depth);
        if (schema != null && depth == 0) {
            bucket.put(fqn, schema);
        }
        return schema;
    }

    /** 清掉某项目的 schema 缓存（重新生成 KG 时调用）。 */
    public void invalidate(String projectPath) {
        simpleNameIndexCache.remove(projectPath);
        schemaCache.remove(projectPath);
    }

    // ===================================================================
    // 简单名 → FQN 索引
    // ===================================================================
    private String resolveFqn(String className, String projectPath) {
        // already FQN
        if (className.contains(".")) {
            return className;
        }
        Map<String, String> index = simpleNameIndexCache.computeIfAbsent(
                projectPath, k -> buildSimpleNameIndex(projectPath));
        return index.get(className);
    }

    private Map<String, String> buildSimpleNameIndex(String projectPath) {
        Map<String, String> map = new HashMap<>();
        try {
            List<File> javaFiles = coreService.findJavaFiles(projectPath);
            JavaParser parser = new JavaParser();
            for (File f : javaFiles) {
                try {
                    ParseResult<CompilationUnit> pr = parser.parse(f);
                    if (!pr.isSuccessful() || pr.getResult().isEmpty()) {
                        continue;
                    }
                    CompilationUnit cu = pr.getResult().get();
                    String pkg = cu.getPackageDeclaration()
                            .map(p -> p.getNameAsString())
                            .orElse("");
                    cu.getTypes().forEach(td -> {
                        String simple = td.getNameAsString();
                        String fqn = pkg.isEmpty() ? simple : pkg + "." + simple;
                        map.putIfAbsent(simple, fqn);
                        // Index nested types (inner classes / enums) as Outer.Inner
                        indexNestedTypes(td, fqn, map);
                    });
                } catch (Exception ex) {
                    // skip unparseable file
                }
            }
        } catch (Exception e) {
            log.warn("[DtoSchema] Failed to build simple-name index for {}: {}", projectPath, e.getMessage());
        }
        return map;
    }

    @SuppressWarnings("rawtypes")
    private void indexNestedTypes(TypeDeclaration<?> td, String enclosingFqn, Map<String, String> map) {
        td.getMembers().forEach(member -> {
            if (member instanceof TypeDeclaration<?> nested) {
                String simple = nested.getNameAsString();
                String nestedFqn = enclosingFqn + "." + simple;
                map.putIfAbsent(simple, nestedFqn);
                indexNestedTypes(nested, nestedFqn, map);
            }
        });
    }

    // ===================================================================
    // 真正的 schema 解析
    // ===================================================================
    private DtoSchema parseSchema(String fqn, String projectPath,
                                  java.util.Set<String> visited, int depth) {
        try {
            List<File> javaFiles = coreService.findJavaFiles(projectPath);
            JavaParser parser = new JavaParser();

            // 先精确匹配 —— FQN 的最后一段当文件名（处理内部类:Outer.Inner → Outer.java）
            String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
            String outerSimpleName = resolveOuterSimpleName(fqn);
            String fileSimpleName = outerSimpleName != null ? outerSimpleName : simpleName;
            for (File f : javaFiles) {
                String norm = f.getPath().replace('\\', '/');
                if (!norm.endsWith("/" + fileSimpleName + ".java")) {
                    continue;
                }
                ParseResult<CompilationUnit> pr = parser.parse(f);
                if (!pr.isSuccessful() || pr.getResult().isEmpty()) {
                    continue;
                }
                CompilationUnit cu = pr.getResult().get();
                String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");
                String expectedFqnPrefix = pkg.isEmpty() ? fileSimpleName : pkg + "." + fileSimpleName;
                if (!fqn.equals(expectedFqnPrefix)
                        && !fqn.startsWith(expectedFqnPrefix + ".")) {
                    continue;
                }
                DtoSchema schema = extractSchema(cu, simpleName, fqn, projectPath, visited, depth);
                if (schema != null) {
                    return schema;
                }
            }
            log.debug("[DtoSchema] No matching source file found for FQN {}", fqn);
        } catch (Exception e) {
            log.warn("[DtoSchema] Failed to parse schema for {} in {}: {}",
                    fqn, projectPath, e.getMessage());
        }
        return null;
    }

    /**
     * For FQN like {@code com.foo.Outer.Inner}, returns {@code "Outer"} so we
     * locate {@code Outer.java}. Returns null if FQN does not look nested.
     */
    private String resolveOuterSimpleName(String fqn) {
        int lastDot = fqn.lastIndexOf('.');
        if (lastDot <= 0) {
            return null;
        }
        String parent = fqn.substring(0, lastDot);
        int prevDot = parent.lastIndexOf('.');
        String parentSimple = prevDot < 0 ? parent : parent.substring(prevDot + 1);
        // Heuristic: PascalCase parent => looks like a class name (inner class case)
        if (!parentSimple.isEmpty() && Character.isUpperCase(parentSimple.charAt(0))) {
            return parentSimple;
        }
        return null;
    }

    private DtoSchema extractSchema(CompilationUnit cu, String simpleName, String fqn,
                                    String projectPath,
                                    java.util.Set<String> visited, int depth) {
        // record
        Optional<RecordDeclaration> rd = cu.findFirst(RecordDeclaration.class,
                r -> r.getNameAsString().equals(simpleName));
        if (rd.isPresent()) {
            List<DtoField> fields = new ArrayList<>();
            for (var p : rd.get().getParameters()) {
                DtoField f = new DtoField();
                f.name = p.getNameAsString();
                f.type = p.getTypeAsString();
                applyAnnotations(p.getAnnotations(), f);
                fields.add(f);
            }
            DtoSchema schema = new DtoSchema(fqn, simpleName, "record", fields);
            expandNestedFields(schema, projectPath, visited, depth);
            return schema;
        }
        // enum
        Optional<EnumDeclaration> ed = cu.findFirst(EnumDeclaration.class,
                e -> e.getNameAsString().equals(simpleName));
        if (ed.isPresent()) {
            List<DtoField> constants = new ArrayList<>();
            for (EnumConstantDeclaration c : ed.get().getEntries()) {
                DtoField f = new DtoField();
                f.name = c.getNameAsString();
                f.type = simpleName;
                constants.add(f);
            }
            return new DtoSchema(fqn, simpleName, "enum", constants);
        }
        // class (with inheritance support)
        Optional<ClassOrInterfaceDeclaration> cd = cu.findFirst(ClassOrInterfaceDeclaration.class,
                c -> c.getNameAsString().equals(simpleName) && !c.isInterface());
        if (cd.isPresent()) {
            List<DtoField> fields = new ArrayList<>();
            collectClassFields(cd.get(), fields, projectPath, new java.util.HashSet<>());
            DtoSchema schema = new DtoSchema(fqn, simpleName, "class", fields);
            expandNestedFields(schema, projectPath, visited, depth);
            return schema;
        }
        return null;
    }

    /**
     * After top-level fields are collected, walk each field's declared type and
     * recursively attach nested DtoSchema when the type looks like a project DTO.
     * Collections are unwrapped to their element type.
     */
    private void expandNestedFields(DtoSchema schema, String projectPath,
                                    java.util.Set<String> visited, int depth) {
        if (schema == null || schema.getFields() == null) return;
        for (DtoField f : schema.getFields()) {
            String rawType = f.getType();
            if (rawType == null || rawType.isBlank()) continue;
            String elementType = unwrapCollection(rawType);
            if (elementType != null) {
                f.setCollection(true);
                f.setItemType(elementType);
                if (!isPrimitiveLike(elementType)) {
                    DtoSchema nested = resolve(elementType, projectPath, visited, depth + 1);
                    if (nested != null) {
                        f.setItemSchema(nested);
                    }
                }
                continue;
            }
            // single object — also unwrap Optional<X>
            String inner = unwrapOptional(rawType);
            String candidate = inner != null ? inner : rawType;
            if (!isPrimitiveLike(candidate) && !isFrameworkBase(candidate)) {
                DtoSchema nested = resolve(candidate, projectPath, visited, depth + 1);
                if (nested != null) {
                    f.setNested(nested);
                }
            }
        }
    }

    /**
     * Returns element type of {@code List<X>}/{@code Set<X>}/{@code Collection<X>}/{@code X[]},
     * else {@code null}.
     */
    private String unwrapCollection(String type) {
        if (type.endsWith("[]")) {
            return type.substring(0, type.length() - 2).trim();
        }
        for (String prefix : COLLECTION_PREFIXES) {
            if (type.startsWith(prefix) && type.endsWith(">")) {
                return type.substring(prefix.length(), type.length() - 1).trim();
            }
        }
        return null;
    }

    /** Returns inner type of {@code Optional<X>}, else {@code null}. */
    private String unwrapOptional(String type) {
        if (type.startsWith("Optional<") && type.endsWith(">")) {
            return type.substring("Optional<".length(), type.length() - 1).trim();
        }
        return null;
    }

    private static final List<String> COLLECTION_PREFIXES = List.of(
            "List<", "Set<", "Collection<", "Iterable<", "ArrayList<", "LinkedList<", "HashSet<"
    );

    private static final Set<String> PRIMITIVE_LIKE = Set.of(
            "string", "integer", "int", "long", "short", "byte",
            "double", "float", "bigdecimal", "biginteger",
            "boolean", "char", "character",
            "date", "localdate", "localdatetime", "instant",
            "zoneddatetime", "offsetdatetime", "timestamp",
            "uuid", "object", "void"
    );

    private boolean isPrimitiveLike(String type) {
        if (type == null) return true;
        String lower = type.toLowerCase().trim();
        if (lower.startsWith("map<")) return true; // can't render Map schema-aware
        // also covers fully-qualified java.lang.String etc.
        String simple = lower.contains(".") ? lower.substring(lower.lastIndexOf('.') + 1) : lower;
        return PRIMITIVE_LIKE.contains(simple);
    }

    /**
     * Walks the inheritance chain (extends) collecting non-static fields.
     * Parent fields appear first (so subclass overrides — by name dedup — win).
     */
    private void collectClassFields(ClassOrInterfaceDeclaration cd,
                                    List<DtoField> out,
                                    String projectPath,
                                    java.util.Set<String> visited) {
        String key = cd.getFullyQualifiedName().orElse(cd.getNameAsString());
        if (!visited.add(key)) {
            return;
        }
        // Recurse into parents first
        for (ClassOrInterfaceType parent : cd.getExtendedTypes()) {
            String parentName = parent.getNameAsString();
            // Skip well-known JDK / framework superclasses
            if (isFrameworkBase(parentName)) {
                continue;
            }
            DtoSchema parentSchema = resolve(parentName, projectPath);
            if (parentSchema != null && parentSchema.getFields() != null) {
                for (DtoField pf : parentSchema.getFields()) {
                    out.add(pf);
                }
            }
        }
        // Own fields (override parent by name)
        for (FieldDeclaration field : cd.getFields()) {
            if (field.isStatic()) continue;
            for (VariableDeclarator v : field.getVariables()) {
                DtoField f = new DtoField();
                f.name = v.getNameAsString();
                f.type = v.getTypeAsString();
                applyAnnotations(field.getAnnotations(), f);
                // dedup by name — remove earlier parent entry
                out.removeIf(existing -> existing.name.equals(f.name));
                out.add(f);
            }
        }
    }

    private static final java.util.Set<String> FRAMEWORK_BASES = java.util.Set.of(
            "Object", "Serializable", "Cloneable", "Comparable",
            "AbstractMap", "HashMap", "LinkedHashMap",
            "RuntimeException", "Exception", "Throwable",
            "Record"
    );

    private boolean isFrameworkBase(String simpleParentName) {
        // strip generics like "BaseDto<Foo>"
        int lt = simpleParentName.indexOf('<');
        String n = lt > 0 ? simpleParentName.substring(0, lt) : simpleParentName;
        return FRAMEWORK_BASES.contains(n);
    }

    private static final Set<String> VALIDATION_ANNOS = Set.of(
            "NotNull", "NotBlank", "NotEmpty", "Email", "Size",
            "Min", "Max", "Pattern", "Positive", "PositiveOrZero",
            "Negative", "NegativeOrZero", "Past", "Future"
    );

    private void applyAnnotations(Iterable<AnnotationExpr> annotations, DtoField f) {
        for (AnnotationExpr ann : annotations) {
            String name = ann.getNameAsString();
            // strip prefix like "jakarta.validation.constraints.NotNull"
            String simple = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1) : name;
            if (simple.equals("NotNull") || simple.equals("NotBlank") || simple.equals("NotEmpty")) {
                f.required = true;
            }
            if (VALIDATION_ANNOS.contains(simple)) {
                f.constraints.add(buildConstraint(simple, ann));
            }
            // Jackson serialization name overrides
            if (simple.equals("JsonProperty") || simple.equals("JsonAlias")) {
                String jsonName = extractStringValue(ann);
                if (jsonName != null && !jsonName.isBlank()) {
                    f.jsonName = jsonName;
                }
            }
        }
    }

    private String extractStringValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr single) {
            var v = single.getMemberValue();
            if (v instanceof StringLiteralExpr sle) {
                return sle.asString();
            }
        }
        if (ann instanceof NormalAnnotationExpr norm) {
            for (MemberValuePair p : norm.getPairs()) {
                if (p.getNameAsString().equals("value") && p.getValue() instanceof StringLiteralExpr sle) {
                    return sle.asString();
                }
            }
        }
        return null;
    }

    private String buildConstraint(String name, AnnotationExpr ann) {
        if (ann instanceof NormalAnnotationExpr norm) {
            StringBuilder sb = new StringBuilder(name).append("(");
            boolean first = true;
            for (MemberValuePair p : norm.getPairs()) {
                if (p.getNameAsString().equals("message")) continue;
                if (!first) sb.append(",");
                sb.append(p.getNameAsString()).append("=").append(p.getValue().toString());
                first = false;
            }
            sb.append(")");
            return sb.toString();
        }
        if (ann instanceof SingleMemberAnnotationExpr single) {
            var val = single.getMemberValue();
            if (val instanceof StringLiteralExpr sle) {
                return name + "(\"" + sle.asString() + "\")";
            }
            return name + "(" + val.toString() + ")";
        }
        return name;
    }

    // ===================================================================
    // DTOs returned to controller / serialized to JSON
    // ===================================================================
    @lombok.AllArgsConstructor
    @lombok.Data
    public static class DtoSchema {
        private String fqn;
        private String simpleName;
        private String kind; // "class" | "record"
        private List<DtoField> fields;
    }

    @lombok.Data
    public static class DtoField {
        private String name;
        private String type;
        private String jsonName;
        private boolean required = false;
        private List<String> constraints = new ArrayList<>();
        /** Nested schema when this field's type is itself a project DTO. */
        private DtoSchema nested;
        /**
         * Element schema when this field is a collection (List/Set/Collection/array)
         * of a project DTO. {@code itemType} holds the raw element type string.
         */
        private DtoSchema itemSchema;
        private String itemType;
        /** True when this field is a collection (any-element-type). */
        private boolean isCollection;
    }
}
