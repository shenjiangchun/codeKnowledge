package com.huawei.hisi.knowledgegraph.python;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.neo4j.model.DataModelNode;
import com.huawei.hisi.neo4j.model.MethodNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PythonDataModelScanner {

    private static final Set<String> PYDANTIC_BASES = Set.of(
        "BaseModel", "pydantic.BaseModel"
    );

    private static final Set<String> DJANGO_MODEL_BASES = Set.of(
        "Model", "models.Model", "django.db.models.Model"
    );

    private static final Set<String> SQLALCHEMY_BASES = Set.of(
        "Base", "db.Model", "DeclarativeBase"
    );

    private static final Set<String> DATACLASS_DECORATORS = Set.of(
        "dataclass", "dataclasses.dataclass"
    );

    public List<DataModelNode> scanDataModels(List<PyModule> modules, String projectPath) {
        List<DataModelNode> result = new ArrayList<>();

        for (PyModule module : modules) {
            String modulePath = module.getModulePath();

            for (PyClass pyClass : module.getClasses()) {
                String modelType = detectModelType(pyClass);
                if (modelType == null) continue;

                String className = modulePath + "." + pyClass.getName();

                List<String> annotations = new ArrayList<>(pyClass.getDecorators());
                List<String> fields = pyClass.getMethods().stream()
                    .filter(m -> m.getName().equals("__init__"))
                    .flatMap(m -> m.getParamNames().stream())
                    .filter(p -> !p.equals("self"))
                    .collect(Collectors.toList());

                // For Pydantic/dataclass, class-level attributes are also fields
                // but PyClass doesn't expose them directly; __init__ params are a proxy

                DataModelNode node = DataModelNode.builder()
                    .nodeId(DataModelNode.generateNodeId(projectPath, className))
                    .className(className)
                    .modelType(modelType)
                    .filePath(module.getFilePath())
                    .startLine(pyClass.getLineStart())
                    .endLine(pyClass.getLineEnd())
                    .projectPath(projectPath)
                    .language("python")
                    .annotations(annotations)
                    .fields(fields)
                    .build();

                result.add(node);
                log.debug("[Python DataModel] Detected {}: {} (bases={}, decorators={})",
                    modelType, className, pyClass.getBaseClasses(), pyClass.getDecorators());
            }
        }

        return result;
    }

    public List<Map<String, Object>> scanUsesModelRelations(
            List<PyModule> modules, List<MethodNode> methodNodes,
            String projectPath, Set<String> dataModelClassNames) {

        List<Map<String, Object>> relations = new ArrayList<>();

        // Build simple-name → FQN lookup from known data models
        Map<String, String> simpleNameToFqn = new HashMap<>();
        for (String fqn : dataModelClassNames) {
            simpleNameToFqn.put(fqn, fqn);
            int lastDot = fqn.lastIndexOf('.');
            if (lastDot > 0) {
                simpleNameToFqn.putIfAbsent(fqn.substring(lastDot + 1), fqn);
            }
        }

        // Build methodNode lookup by (modulePath + "::" + signature) → nodeId
        Map<String, String> methodNodeIdMap = new HashMap<>();
        for (MethodNode node : methodNodes) {
            methodNodeIdMap.put(node.getNodeId(), node.getNodeId());
        }

        for (PyModule module : modules) {
            String modulePath = module.getModulePath();

            // Build per-module import-aware name lookup
            Map<String, String> moduleNameLookup = buildModuleNameLookup(module, simpleNameToFqn);

            // Scan class methods
            for (PyClass pyClass : module.getClasses()) {
                for (PyFunction method : pyClass.getMethods()) {
                    String signature = pyClass.getName() + "." + method.getName()
                        + "(" + String.join(",", method.getParamNames()) + ")";
                    String nodeId = PythonKnowledgeGraphBuilder.toNodeId(modulePath + "::" + signature);

                    Set<String> usedModels = findUsedModels(
                        module, method, moduleNameLookup, dataModelClassNames);

                    for (String modelFqn : usedModels) {
                        relations.add(Map.of(
                            "methodNodeId", nodeId,
                            "dataModelNodeId", DataModelNode.generateNodeId(projectPath, modelFqn),
                            "usageType", "BODY_REFERENCE"
                        ));
                    }
                }
            }

            // Scan top-level functions
            for (PyFunction func : module.getTopLevelFunctions()) {
                String signature = func.getQualName() + "(" + String.join(",", func.getParamNames()) + ")";
                String nodeId = PythonKnowledgeGraphBuilder.toNodeId(modulePath + "::" + signature);

                Set<String> usedModels = findUsedModels(
                    module, func, moduleNameLookup, dataModelClassNames);

                for (String modelFqn : usedModels) {
                    relations.add(Map.of(
                        "methodNodeId", nodeId,
                        "dataModelNodeId", DataModelNode.generateNodeId(projectPath, modelFqn),
                        "usageType", "BODY_REFERENCE"
                    ));
                }
            }
        }

        return relations;
    }

    private String detectModelType(PyClass pyClass) {
        // Check decorators first
        for (String decorator : pyClass.getDecorators()) {
            if (DATACLASS_DECORATORS.contains(decorator)) {
                return DataModelNode.TYPE_DATACLASS;
            }
        }

        // Check base classes
        for (String base : pyClass.getBaseClasses()) {
            if (PYDANTIC_BASES.contains(base)) return DataModelNode.TYPE_PYDANTIC;
            if (DJANGO_MODEL_BASES.contains(base)) return DataModelNode.TYPE_DJANGO_MODEL;
            if (SQLALCHEMY_BASES.contains(base)) return DataModelNode.TYPE_SQLALCHEMY;
        }

        return null;
    }

    private Map<String, String> buildModuleNameLookup(
            PyModule module, Map<String, String> simpleNameToFqn) {
        Map<String, String> lookup = new HashMap<>(simpleNameToFqn);

        // Add import aliases — if the module imports a data model class,
        // map the imported name to its FQN
        module.getImports().forEach(imp -> {
            // from x.y import ClassName → symbol = "ClassName"
            String importedName = imp.getSymbol() != null ? imp.getSymbol() : imp.getModuleName();
            if (importedName != null) {
                String effectiveName = imp.getAlias() != null ? imp.getAlias() : importedName;
                String fqn = simpleNameToFqn.get(importedName);
                if (fqn != null) {
                    lookup.put(effectiveName, fqn);
                }
            }
        });

        return lookup;
    }

    private Set<String> findUsedModels(
            PyModule module, PyFunction function,
            Map<String, String> nameToFqn, Set<String> dataModelClassNames) {
        Set<String> used = new LinkedHashSet<>();

        // Check calls within this function's scope
        for (PyCall call : module.getCalls()) {
            if (!matchesFunction(call, function)) continue;

            String callee = call.getCalleeExpression();
            if (callee == null) continue;

            // Direct instantiation: ModelName(...)
            String fqn = nameToFqn.get(callee);
            if (fqn != null && dataModelClassNames.contains(fqn)) {
                used.add(fqn);
                continue;
            }

            // Method call on model: ModelName.objects.filter(...)
            int dotIdx = callee.indexOf('.');
            if (dotIdx > 0) {
                String prefix = callee.substring(0, dotIdx);
                fqn = nameToFqn.get(prefix);
                if (fqn != null && dataModelClassNames.contains(fqn)) {
                    used.add(fqn);
                }
            }
        }

        return used;
    }

    private boolean matchesFunction(PyCall call, PyFunction function) {
        if (call.getEnclosingFunction() == null) return false;
        return call.getEnclosingFunction().equals(function.getQualName())
            || call.getEnclosingFunction().equals(function.getName());
    }
}
