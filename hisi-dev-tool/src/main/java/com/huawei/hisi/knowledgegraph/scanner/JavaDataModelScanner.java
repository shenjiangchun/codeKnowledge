package com.huawei.hisi.knowledgegraph.scanner;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ObjectCreationExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.huawei.hisi.neo4j.model.DataModelNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class JavaDataModelScanner {

    private static final Set<String> JPA_ANNOTATIONS = Set.of(
        "Entity", "Table", "MappedSuperclass", "Embeddable"
    );

    private static final Set<String> LOMBOK_DATA_ANNOTATIONS = Set.of(
        "Data", "Value"
    );

    public List<DataModelNode> scanDataModels(CompilationUnit cu, String filePath, String projectPath) {
        List<DataModelNode> result = new ArrayList<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            if (clazz.isInterface()) return;

            String className = packageName.isEmpty()
                ? clazz.getNameAsString()
                : packageName + "." + clazz.getNameAsString();

            String modelType = detectModelType(clazz);
            if (modelType == null) return;

            List<String> annotations = clazz.getAnnotations().stream()
                .map(AnnotationExpr::getNameAsString)
                .collect(Collectors.toList());

            List<String> fields = clazz.findAll(FieldDeclaration.class).stream()
                .flatMap(f -> f.getVariables().stream())
                .map(v -> v.getNameAsString())
                .collect(Collectors.toList());

            DataModelNode node = DataModelNode.builder()
                .nodeId(DataModelNode.generateNodeId(projectPath, className))
                .className(className)
                .modelType(modelType)
                .filePath(filePath)
                .startLine(clazz.getBegin().map(p -> p.line).orElse(0))
                .endLine(clazz.getEnd().map(p -> p.line).orElse(0))
                .projectPath(projectPath)
                .language("java")
                .serviceName(extractServiceName(className))
                .annotations(annotations)
                .fields(fields)
                .build();

            result.add(node);
            log.debug("[DataModel] Detected {}: {} ({})", modelType, className, annotations);
        });

        return result;
    }

    public List<Map<String, Object>> scanUsesModelRelations(
            CompilationUnit cu, String projectPath,
            Set<String> dataModelClassNames,
            Map<String, String> methodSignatureToNodeId) {

        List<Map<String, Object>> relations = new ArrayList<>();

        String packageName = cu.getPackageDeclaration()
            .map(pd -> pd.getNameAsString())
            .orElse("");

        // Build simple-name → FQN lookup for imports + same-package models
        Map<String, String> simpleNameToFqn = buildSimpleNameLookup(cu, packageName, dataModelClassNames);

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
            String className = packageName.isEmpty()
                ? clazz.getNameAsString()
                : packageName + "." + clazz.getNameAsString();

            clazz.findAll(MethodDeclaration.class).forEach(method -> {
                String methodKey = className + "." + method.getNameAsString();
                String methodNodeId = methodSignatureToNodeId.get(methodKey);
                if (methodNodeId == null) return;

                Set<String> usedModels = new LinkedHashSet<>();
                Map<String, String> modelUsageTypes = new LinkedHashMap<>();

                // Check parameter types
                for (Parameter param : method.getParameters()) {
                    collectTypeReferences(param.getType().asString(), simpleNameToFqn, dataModelClassNames)
                        .forEach(fqn -> {
                            usedModels.add(fqn);
                            modelUsageTypes.putIfAbsent(fqn, "PARAMETER");
                        });
                }

                // Check return type
                if (!method.getType().isVoidType()) {
                    collectTypeReferences(method.getType().asString(), simpleNameToFqn, dataModelClassNames)
                        .forEach(fqn -> {
                            usedModels.add(fqn);
                            modelUsageTypes.putIfAbsent(fqn, "RETURN");
                        });
                }

                // Check body references
                method.getBody().ifPresent(body -> {
                    // Object creation: new ModelClass(...)
                    body.findAll(ObjectCreationExpr.class).forEach(expr -> {
                        String typeName = expr.getType().getNameAsString();
                        String fqn = simpleNameToFqn.get(typeName);
                        if (fqn != null && dataModelClassNames.contains(fqn)) {
                            usedModels.add(fqn);
                            modelUsageTypes.putIfAbsent(fqn, "BODY_REFERENCE");
                        }
                    });

                    // Variable types and casts: ClassOrInterfaceType references
                    body.findAll(ClassOrInterfaceType.class).forEach(typeRef -> {
                        String typeName = typeRef.getNameAsString();
                        String fqn = simpleNameToFqn.get(typeName);
                        if (fqn != null && dataModelClassNames.contains(fqn)) {
                            usedModels.add(fqn);
                            modelUsageTypes.putIfAbsent(fqn, "BODY_REFERENCE");
                        }
                    });
                });

                // Create relation entries
                for (String modelFqn : usedModels) {
                    String dataModelNodeId = DataModelNode.generateNodeId(projectPath, modelFqn);
                    relations.add(Map.of(
                        "methodNodeId", methodNodeId,
                        "dataModelNodeId", dataModelNodeId,
                        "usageType", modelUsageTypes.getOrDefault(modelFqn, "BODY_REFERENCE")
                    ));
                }
            });
        });

        return relations;
    }

    private String detectModelType(ClassOrInterfaceDeclaration clazz) {
        boolean hasJpa = false;
        boolean hasLombok = false;

        for (AnnotationExpr ann : clazz.getAnnotations()) {
            String name = ann.getNameAsString();
            if (JPA_ANNOTATIONS.contains(name)) hasJpa = true;
            if (LOMBOK_DATA_ANNOTATIONS.contains(name)) hasLombok = true;
        }

        if (hasJpa) return DataModelNode.TYPE_JPA_ENTITY;
        if (hasLombok) return DataModelNode.TYPE_LOMBOK_DATA;
        return null;
    }

    private Map<String, String> buildSimpleNameLookup(
            CompilationUnit cu, String packageName, Set<String> dataModelClassNames) {
        Map<String, String> lookup = new HashMap<>();

        // From explicit imports
        cu.getImports().forEach(imp -> {
            if (!imp.isAsterisk()) {
                String fqn = imp.getNameAsString();
                String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                if (dataModelClassNames.contains(fqn)) {
                    lookup.put(simpleName, fqn);
                }
            }
        });

        // Same-package models (no import needed)
        for (String fqn : dataModelClassNames) {
            String modelPackage = fqn.contains(".")
                ? fqn.substring(0, fqn.lastIndexOf('.'))
                : "";
            if (modelPackage.equals(packageName)) {
                String simpleName = fqn.substring(fqn.lastIndexOf('.') + 1);
                lookup.putIfAbsent(simpleName, fqn);
            }
        }

        // Also map FQN → FQN for direct references
        for (String fqn : dataModelClassNames) {
            lookup.put(fqn, fqn);
        }

        return lookup;
    }

    private Set<String> collectTypeReferences(
            String typeStr, Map<String, String> simpleNameToFqn, Set<String> dataModelClassNames) {
        Set<String> found = new LinkedHashSet<>();

        // Handle generic types: List<UserDTO> → extract UserDTO
        // Handle simple types: UserDTO
        String cleaned = typeStr.replaceAll("[<>,\\[\\]\\s]", " ");
        for (String token : cleaned.split("\\s+")) {
            if (token.isEmpty()) continue;
            String fqn = simpleNameToFqn.get(token);
            if (fqn != null && dataModelClassNames.contains(fqn)) {
                found.add(fqn);
            }
        }
        return found;
    }

    private String extractServiceName(String className) {
        if (className == null || className.isEmpty()) return null;
        int lastDot = className.lastIndexOf('.');
        return lastDot > 0 ? className.substring(lastDot + 1) : className;
    }
}
