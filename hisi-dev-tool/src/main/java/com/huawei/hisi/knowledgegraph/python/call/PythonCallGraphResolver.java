package com.huawei.hisi.knowledgegraph.python.call;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves Python {@link PyCall} sites into call edges suitable for persistence
 * via {@code Neo4jStorageService.saveCallRelations(...)}.
 *
 * <p>Resolution rules (see plan §3.5):
 * <ul>
 *   <li>{@code self.foo()} inside a method of class {@code C} → {@code C.foo} in the same module.</li>
 *   <li>Plain {@code foo()} → top-level function {@code foo} in the current module.</li>
 *   <li>{@code from x.y import z} + {@code z()} or {@code z.attr()} → resolved against the imported module.</li>
 *   <li>{@code import x.y as alias} + {@code alias.func()} → resolved against the aliased module.</li>
 *   <li>Anything else → emitted as an unresolved edge (with a synthetic callee id) so downstream tools
 *       can still see the call site.</li>
 * </ul>
 *
 * <p>All returned lists are immutable.
 */
@Component
public class PythonCallGraphResolver {

    private static final Logger log = LoggerFactory.getLogger(PythonCallGraphResolver.class);

    private static final String CALL_TYPE_DIRECT = "DIRECT";
    private static final String CALL_TYPE_SELF = "SELF";
    private static final String CALL_TYPE_SUPER = "SUPER";
    private static final String CALL_TYPE_IMPORT = "IMPORT";
    private static final String CALL_TYPE_UNRESOLVED = "UNRESOLVED";

    /** Common Python builtins — skip these, they have no KG node. "super" is excluded; handled separately. */
    private static final Set<String> PYTHON_BUILTINS;
    static {
        Set<String> builtins = new HashSet<>(Set.of(
                "print", "len", "range", "enumerate", "zip", "map", "filter", "sorted",
                "reversed", "list", "dict", "set", "tuple", "str", "int", "float", "bool",
                "type", "isinstance", "issubclass", "hasattr", "getattr", "setattr", "delattr",
                "property", "classmethod", "staticmethod", "abs", "min", "max", "sum",
                "any", "all", "round", "repr", "hash", "id", "input", "open", "iter", "next",
                "callable", "vars", "dir", "globals", "locals", "exec", "eval", "compile",
                "format", "chr", "ord", "hex", "oct", "bin", "pow", "divmod",
                "object", "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
                "AttributeError", "RuntimeError", "StopIteration", "NotImplementedError",
                "OSError", "IOError", "FileNotFoundError", "ImportError", "ModuleNotFoundError"));
        PYTHON_BUILTINS = Collections.unmodifiableSet(builtins);
    }

    // Cross-module class indices (populated by indexModules)
    Map<String, PyModule> moduleByPath;
    Map<String, PyClass> classByQualifiedName;  // "module.path.ClassName" -> PyClass
    Map<String, List<PyClass>> classesBySimpleName;  // "ClassName" -> [PyClass, ...]

    /**
     * Resolve calls for a single module. Cross-module resolution uses {@code allModules}
     * to look up the target module by its dotted module path.
     *
     * @param module      the parsed PyModule
     * @param projectPath root project path (currently unused but accepted for symmetry)
     * @param allModules  all parsed modules in the project (for cross-module resolution)
     * @return immutable list of call relation maps
     */
    public List<Map<String, Object>> resolveModule(PyModule module,
                                                   String projectPath,
                                                   List<PyModule> allModules) {
        if (module == null || module.getCalls().isEmpty()) {
            return Collections.emptyList();
        }

        ResolutionContext ctx = new ResolutionContext(
                module,
                indexModules(allModules),
                indexImports(module.getImports()),
                indexTopLevelFunctions(module.getTopLevelFunctions()),
                indexClasses(module.getClasses()));

        // Diagnostic: dump resolution context for first module with calls
        if (!module.getCalls().isEmpty()) {
            log.debug("[CallGraph] Module {} has {} calls, {} imports, {} top-level funcs, {} classes",
                    module.getModulePath(), module.getCalls().size(),
                    ctx.importsBySymbol().size(), ctx.topLevelByName().size(), ctx.classesByName().size());
            // Dump first 5 call expressions
            int i = 0;
            for (PyCall call : module.getCalls()) {
                if (i++ >= 5) break;
                log.debug("[CallGraph]   call: expression='{}' enclosing='{}' line={}",
                        call.getCalleeExpression(), call.getEnclosingFunction(), call.getLineNumber());
            }
        }

        List<Map<String, Object>> edges = new ArrayList<>();

        for (PyCall call : module.getCalls()) {
            String callerNodeId = computeCallerNodeId(module, call);
            if (callerNodeId == null) {
                continue;
            }

            Map<String, Object> edge = resolveCall(call, callerNodeId, ctx);
            if (edge != null) {
                edges.add(edge);
            }
        }

        return Collections.unmodifiableList(edges);
    }

    /**
     * Bundle of per-module lookup tables, threaded through resolution strategies.
     */
    private record ResolutionContext(PyModule module,
                                     Map<String, PyModule> moduleIndex,
                                     Map<String, PyImport> importsBySymbol,
                                     Map<String, List<PyFunction>> topLevelByName,
                                     Map<String, List<PyClass>> classesByName) {
    }

    /**
     * Resolve calls across all modules in a project.
     */
    public List<Map<String, Object>> resolveProject(List<PyModule> allModules, String projectPath) {
        if (allModules == null || allModules.isEmpty()) {
            return Collections.emptyList();
        }
        List<Map<String, Object>> all = new ArrayList<>();
        for (PyModule module : allModules) {
            all.addAll(resolveModule(module, projectPath, allModules));
        }
        return Collections.unmodifiableList(all);
    }

    // ---------------------------------------------------------------------
    // Resolution dispatch
    // ---------------------------------------------------------------------

    /**
     * Normalize constructor-chain expressions.
     * {@code ClassName().method} → {@code ClassName.method}
     * {@code module.ClassName().method} → {@code module.ClassName.method}
     */
    private String normalizeExpression(String expression) {
        // Strip constructor parens in chain: Foo().bar → Foo.bar, Foo(arg).bar → Foo.bar
        // Uses iterative paren-depth tracking to handle nested parens correctly.
        StringBuilder result = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < expression.length(); i++) {
            char c = expression.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')' && depth > 0) {
                depth--;
                if (i + 1 < expression.length() && expression.charAt(i + 1) == '.') {
                    result.append('.');
                    i++;
                }
            } else if (depth == 0) {
                result.append(c);
            }
        }
        return result.toString();
    }

    private Map<String, Object> resolveCall(PyCall call, String callerNodeId, ResolutionContext ctx) {
        String rawExpression = call.getCalleeExpression();
        if (rawExpression == null || rawExpression.isEmpty()) {
            return null;
        }

        // Normalize constructor chains: ClassName().method → ClassName.method
        String expression = normalizeExpression(rawExpression);
        String[] parts = expression.split("\\.");
        String head = parts[0];

        // super().method() → resolve to parent class method
        if ("super".equals(head)) {
            Map<String, Object> superResult = resolveSuperCall(head, parts, callerNodeId, call, ctx);
            if (superResult != null) return superResult;
            return null; // Can't resolve super call — skip silently
        }

        // Skip Python builtins — no KG node exists for these
        if (PYTHON_BUILTINS.contains(head)) {
            return null;
        }

        // self.method() → method on the call's enclosing class
        if ("self".equals(head) && parts.length >= 2) {
            Map<String, Object> e = resolveSelfCall(call, callerNodeId, parts[1], ctx);
            if (e != null) return e;
            // self.field.method() or unresolvable self call — skip
            return null;
        }

        // Single-part call: foo()
        if (parts.length == 1) {
            Map<String, Object> direct = resolveDirectCall(call, callerNodeId, head, ctx);
            if (direct != null) {
                return direct;
            }
            Map<String, Object> imported = resolveImportCall(call, callerNodeId, parts, ctx);
            if (imported != null) {
                return imported;
            }
            Map<String, Object> wildcard = resolveWildcardImport(call, callerNodeId, head, ctx);
            if (wildcard != null) {
                return wildcard;
            }
            log.debug("[CallGraph] Unresolved call '{}' in {} at line {}",
                    head, call.getEnclosingFunction(), call.getLineNumber());
            return unresolvedEdge(callerNodeId, head, call.getLineNumber());
        }

        // Multi-part call: module.func() or Class.method()
        Map<String, Object> imported = resolveImportCall(call, callerNodeId, parts, ctx);
        if (imported != null) {
            return imported;
        }
        Map<String, Object> localClass = resolveLocalClassCall(call, callerNodeId, parts, ctx);
        if (localClass != null) {
            return localClass;
        }
        log.debug("[CallGraph] Unresolved call '{}' in {} at line {}",
                expression, call.getEnclosingFunction(), call.getLineNumber());
        return unresolvedEdge(callerNodeId, expression, call.getLineNumber());
    }

    /** {@code self.method()} → method on the call's enclosing class. */
    private Map<String, Object> resolveSelfCall(PyCall call, String callerNodeId,
                                                String methodName, ResolutionContext ctx) {
        String enclosingClass = enclosingClassOf(call, ctx.classesByName());
        if (enclosingClass == null) {
            return null;
        }
        List<PyClass> candidates = ctx.classesByName().get(enclosingClass);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        PyClass owner = candidates.get(0);
        PyFunction method = findMethod(owner, methodName);
        if (method == null) {
            return null;
        }
        String calleeId = methodNodeId(ctx.module().getModulePath(), owner.getName(), method);
        return edge(callerNodeId, calleeId, CALL_TYPE_SELF, call.getLineNumber(), false);
    }

    /**
     * {@code super().method()} → resolve to the parent class's method.
     *
     * <p>After normalization, {@code super().method} becomes {@code super.method}
     * with parts = ["super", "method"]. We find the enclosing class, resolve its
     * base classes via {@link #resolveBaseClass}, then look up the method in the
     * parent class.
     */
    private Map<String, Object> resolveSuperCall(String head, String[] parts,
                                                  String callerNodeId, PyCall call,
                                                  ResolutionContext ctx) {
        if (parts.length < 2) return null;
        String methodName = parts[1];

        // Find the enclosing class
        String enclosingClassName = enclosingClassOf(call, ctx.classesByName());
        if (enclosingClassName == null) return null;

        List<PyClass> enclosingCandidates = ctx.classesByName().get(enclosingClassName);
        if (enclosingCandidates == null || enclosingCandidates.isEmpty()) return null;
        PyClass enclosingClass = enclosingCandidates.get(0);

        if (enclosingClass.getBaseClasses() == null || enclosingClass.getBaseClasses().isEmpty()) {
            return null;
        }

        // Try each base class
        for (String base : enclosingClass.getBaseClasses()) {
            String parentFqn = resolveBaseClass(base, ctx.module());
            if (parentFqn == null) continue;

            PyClass parentClass = findClassByFqn(parentFqn);
            if (parentClass == null) continue;

            PyFunction method = findMethodInClassHierarchy(parentClass, methodName);
            if (method != null) {
                // Extract the module path from the parent FQN (everything before the last dot)
                String parentModulePath = parentFqn.contains(".")
                        ? parentFqn.substring(0, parentFqn.lastIndexOf('.'))
                        : ctx.module().getModulePath();
                String parentSimpleName = parentFqn.contains(".")
                        ? parentFqn.substring(parentFqn.lastIndexOf('.') + 1)
                        : parentFqn;
                String calleeId = methodNodeId(parentModulePath, parentSimpleName, method);
                return edge(callerNodeId, calleeId, CALL_TYPE_SUPER, call.getLineNumber(), false);
            }
        }
        return null;
    }

    /**
     * Find a method in a class, searching parent classes if not found locally.
     */
    private PyFunction findMethodInClassHierarchy(PyClass cls, String methodName) {
        // Search locally first
        PyFunction local = findMethod(cls, methodName);
        if (local != null) return local;

        // Search parent classes
        if (cls.getBaseClasses() == null || cls.getBaseClasses().isEmpty()) return null;

        for (String base : cls.getBaseClasses()) {
            // Try to resolve the base class to a known class
            // We need a module context — look it up from classByQualifiedName
            String fqn = findFqnForClass(cls);
            if (fqn == null) continue;
            String modulePath = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : null;
            if (modulePath == null) continue;
            PyModule ownerModule = moduleByPath != null ? moduleByPath.get(modulePath) : null;
            if (ownerModule == null) continue;

            String parentFqn = resolveBaseClass(base, ownerModule);
            if (parentFqn == null) continue;
            PyClass parentClass = findClassByFqn(parentFqn);
            if (parentClass == null) continue;

            PyFunction inherited = findMethodInClassHierarchy(parentClass, methodName);
            if (inherited != null) return inherited;
        }
        return null;
    }

    /**
     * Find the FQN for a PyClass by looking it up in the classByQualifiedName index.
     */
    private String findFqnForClass(PyClass cls) {
        if (classByQualifiedName == null) return null;
        for (var entry : classByQualifiedName.entrySet()) {
            if (entry.getValue() == cls) return entry.getKey();
        }
        return null;
    }

    /** Plain {@code foo()} → top-level function in the current module. */
    private Map<String, Object> resolveDirectCall(PyCall call, String callerNodeId,
                                                  String name, ResolutionContext ctx) {
        List<PyFunction> candidates = ctx.topLevelByName().get(name);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // TODO: Handle multiple candidates (overloads) - for now pick first
        PyFunction local = candidates.get(0);
        String calleeId = topLevelNodeId(ctx.module().getModulePath(), local);
        return edge(callerNodeId, calleeId, CALL_TYPE_DIRECT, call.getLineNumber(), false);
    }

    /**
     * Cross-module / imported-symbol resolution:
     * <ul>
     *   <li>{@code foo()} where {@code foo} is {@code from x import foo} → top-level in {@code x}</li>
     *   <li>{@code alias.func()} where {@code import x.y as alias} → top-level in {@code x.y}</li>
     *   <li>{@code Cls.method()} where {@code from x import Cls} → method on {@code Cls} in {@code x}</li>
     * </ul>
     */
    private Map<String, Object> resolveImportCall(PyCall call, String callerNodeId,
                                                  String[] parts, ResolutionContext ctx) {
        String head = parts[0];
        PyImport imp = ctx.importsBySymbol().get(head);
        if (imp == null) {
            return null;
        }
        // Absolutize relative imports: from .services → api.services
        String absModuleName = absolutizeModuleName(
                imp.getModuleName(), imp.getRelativeLevel(), ctx.module());
        PyModule target = ctx.moduleIndex().get(absModuleName);
        if (target == null && imp.isFromImport() && imp.getSymbol() != null) {
            String submoduleCandidate = absModuleName.isEmpty()
                    ? imp.getSymbol()
                    : absModuleName + "." + imp.getSymbol();
            target = ctx.moduleIndex().get(submoduleCandidate);
            if (target != null) {
                absModuleName = submoduleCandidate;
                if (parts.length >= 2) {
                    List<PyFunction> funcs = findTopLevel(target, parts[1]);
                    if (!funcs.isEmpty()) {
                        PyFunction func = funcs.get(0);  // TODO: Handle multiple candidates
                        return edge(callerNodeId, topLevelNodeId(target.getModulePath(), func),
                                CALL_TYPE_IMPORT, call.getLineNumber(), false);
                    }
                    List<PyClass> clses = findClass(target, parts[1]);
                    if (!clses.isEmpty() && parts.length >= 3) {
                        PyClass cls = clses.get(0);  // TODO: Handle multiple candidates
                        PyFunction method = findMethod(cls, parts[2]);
                        return method == null ? null : edge(
                                callerNodeId, methodNodeId(target.getModulePath(), cls.getName(), method),
                                CALL_TYPE_IMPORT, call.getLineNumber(), false);
                    }
                }
                return null;
            }
        }
        if (target == null) {
            return null;
        }

        if (parts.length == 1) {
            if (!imp.isFromImport() || imp.getSymbol() == null) {
                return null;
            }
            List<PyFunction> funcs = findTopLevel(target, imp.getSymbol());
            if (funcs.isEmpty()) {
                return null;
            }
            PyFunction func = funcs.get(0);  // TODO: Handle multiple candidates
            return edge(callerNodeId, topLevelNodeId(target.getModulePath(), func),
                    CALL_TYPE_IMPORT, call.getLineNumber(), false);
        }

        if (!imp.isFromImport()) {
            List<PyFunction> funcs = findTopLevel(target, parts[1]);
            if (funcs.isEmpty()) {
                return null;
            }
            PyFunction func = funcs.get(0);  // TODO: Handle multiple candidates
            return edge(callerNodeId, topLevelNodeId(target.getModulePath(), func),
                    CALL_TYPE_IMPORT, call.getLineNumber(), false);
        }

        if (imp.getSymbol() == null) {
            return null;
        }
        List<PyClass> clses = findClass(target, imp.getSymbol());
        if (clses.isEmpty()) {
            return null;
        }
        PyClass cls = clses.get(0);  // TODO: Handle multiple candidates
        PyFunction method = findMethod(cls, parts[1]);
        return method == null ? null : edge(
                callerNodeId, methodNodeId(target.getModulePath(), cls.getName(), method),
                CALL_TYPE_IMPORT, call.getLineNumber(), false);
    }

    /** {@code LocalClass.method()} where {@code LocalClass} is defined in the current module. */
    private Map<String, Object> resolveLocalClassCall(PyCall call, String callerNodeId,
                                                      String[] parts, ResolutionContext ctx) {
        List<PyClass> candidates = ctx.classesByName().get(parts[0]);
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        // TODO: Handle multiple candidates - for now pick first
        PyClass local = candidates.get(0);
        PyFunction method = findMethod(local, parts[1]);
        if (method == null) {
            return null;
        }
        String calleeId = methodNodeId(ctx.module().getModulePath(), local.getName(), method);
        return edge(callerNodeId, calleeId, CALL_TYPE_DIRECT, call.getLineNumber(), false);
    }

    // ---------------------------------------------------------------------
    // Caller node id
    // ---------------------------------------------------------------------

    private String computeCallerNodeId(PyModule module, PyCall call) {
        String enclosing = call.getEnclosingFunction();
        if (enclosing == null || enclosing.isEmpty()) {
            return null;
        }
        // enclosing is qualName: either "name" (top-level) or "ClassName.method".
        int dot = enclosing.indexOf('.');
        if (dot >= 0) {
            String className = enclosing.substring(0, dot);
            String methodName = enclosing.substring(dot + 1);
            for (PyClass cls : module.getClasses()) {
                if (cls.getName().equals(className)) {
                    PyFunction m = findMethod(cls, methodName);
                    if (m != null) {
                        return methodNodeId(module.getModulePath(), className, m);
                    }
                }
            }
            return null;
        }
        for (PyFunction f : module.getTopLevelFunctions()) {
            if (f.getName().equals(enclosing)) {
                return topLevelNodeId(module.getModulePath(), f);
            }
        }
        // Module-level calls inside if __name__ == "__main__": block
        if ("<module>".equals(enclosing) && call.isInMainBlock()) {
            return mainBlockNodeId(module.getModulePath());
        }
        return null;
    }

    private String enclosingClassOf(PyCall call, Map<String, List<PyClass>> classesByName) {
        String enclosing = call.getEnclosingFunction();
        if (enclosing == null) {
            return null;
        }
        int dot = enclosing.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String className = enclosing.substring(0, dot);
        return classesByName.containsKey(className) ? className : null;
    }

    // ---------------------------------------------------------------------
    // Wildcard import fallback
    // ---------------------------------------------------------------------

    private Map<String, Object> resolveWildcardImport(PyCall call, String callerNodeId,
                                                       String funcName, ResolutionContext ctx) {
        if (ctx.module().getImports() == null) {
            return null;
        }
        for (PyImport imp : ctx.module().getImports()) {
            if (!"*".equals(imp.getSymbol()) || !imp.isFromImport()) {
                continue;
            }
            String absModule = absolutizeModuleName(
                    imp.getModuleName(), imp.getRelativeLevel(), ctx.module());
            PyModule source = ctx.moduleIndex().get(absModule);
            if (source == null) {
                continue;
            }
            List<PyFunction> funcs = findTopLevel(source, funcName);
            if (!funcs.isEmpty()) {
                PyFunction func = funcs.get(0);
                return edge(callerNodeId, topLevelNodeId(source.getModulePath(), func),
                        CALL_TYPE_IMPORT, call.getLineNumber(), false);
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Node id helpers (mirror PythonKnowledgeGraphBuilder)
    // ---------------------------------------------------------------------

    private String topLevelNodeId(String modulePath, PyFunction func) {
        String signature = func.getQualName() + "(" + String.join(",", func.getParamNames()) + ")";
        return PythonKnowledgeGraphBuilder.toNodeId(modulePath + "::" + signature);
    }

    private String methodNodeId(String modulePath, String className, PyFunction method) {
        String signature = className + "." + method.getName()
                + "(" + String.join(",", method.getParamNames()) + ")";
        return PythonKnowledgeGraphBuilder.toNodeId(modulePath + "::" + signature);
    }

    /** Pseudo node ID for the {@code if __name__ == "__main__":} block. */
    private String mainBlockNodeId(String modulePath) {
        return PythonKnowledgeGraphBuilder.toNodeId(modulePath + "::__main__()");
    }

    // ---------------------------------------------------------------------
    // Indexing
    // ---------------------------------------------------------------------

    /**
     * Convert a (possibly relative) import module name into an absolute dotted path.
     * Uses {@code currentModule.modulePath} as the anchor.
     *
     * <p>{@code relativeLevel == 0} → already absolute.
     * {@code relativeLevel == 1} → relative to current package.
     * {@code relativeLevel == 2} → one more level up; etc.
     */
    private String absolutizeModuleName(String moduleName, int relativeLevel, PyModule currentModule) {
        if (relativeLevel <= 0) {
            return moduleName == null ? "" : moduleName;
        }
        String current = currentModule.getModulePath();
        if (current == null) {
            return moduleName == null ? "" : moduleName;
        }
        String[] parts = current.split("\\.");
        int keep = parts.length - relativeLevel;
        if (keep < 0) {
            return moduleName == null ? "" : moduleName;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) sb.append('.');
            sb.append(parts[i]);
        }
        if (moduleName != null && !moduleName.isEmpty()) {
            if (sb.length() > 0) sb.append('.');
            sb.append(moduleName);
        }
        return sb.toString();
    }

    private Map<String, PyModule> indexModules(List<PyModule> modules) {
        if (modules == null || modules.isEmpty()) {
            moduleByPath = Collections.emptyMap();
            classByQualifiedName = Collections.emptyMap();
            classesBySimpleName = Collections.emptyMap();
            return Collections.emptyMap();
        }
        Map<String, PyModule> out = new HashMap<>();
        for (PyModule m : modules) {
            if (m != null && m.getModulePath() != null) {
                out.put(m.getModulePath(), m);
            }
        }
        // Store module index for cross-module class resolution
        moduleByPath = out;
        // Populate cross-module class indices
        classByQualifiedName = new HashMap<>();
        classesBySimpleName = new HashMap<>();
        for (var entry : out.entrySet()) {
            String modulePath = entry.getKey();
            for (PyClass cls : entry.getValue().getClasses()) {
                String fqn = modulePath + "." + cls.getName();
                classByQualifiedName.put(fqn, cls);
                classesBySimpleName.computeIfAbsent(cls.getName(), k -> new ArrayList<>()).add(cls);
            }
        }
        return out;
    }

    private Map<String, PyImport> indexImports(List<PyImport> imports) {
        if (imports == null || imports.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, PyImport> out = new HashMap<>();
        for (PyImport imp : imports) {
            String key = importLocalName(imp);
            if (key != null) {
                out.put(key, imp);
            }
        }
        return out;
    }

    private String importLocalName(PyImport imp) {
        if (imp.getAlias() != null && !imp.getAlias().isEmpty()) {
            return imp.getAlias();
        }
        if (imp.isFromImport()) {
            return imp.getSymbol();
        }
        // `import a.b.c` introduces `a` as a local name in Python.
        String mod = imp.getModuleName();
        if (mod == null || mod.isEmpty()) {
            return null;
        }
        int firstDot = mod.indexOf('.');
        return firstDot < 0 ? mod : mod.substring(0, firstDot);
    }

    private Map<String, List<PyFunction>> indexTopLevelFunctions(List<PyFunction> functions) {
        if (functions == null || functions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<PyFunction>> out = new HashMap<>();
        for (PyFunction f : functions) {
            out.computeIfAbsent(f.getName(), k -> new ArrayList<>()).add(f);
        }
        return out;
    }

    private Map<String, List<PyClass>> indexClasses(List<PyClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, List<PyClass>> out = new HashMap<>();
        for (PyClass c : classes) {
            out.computeIfAbsent(c.getName(), k -> new ArrayList<>()).add(c);
        }
        return out;
    }

    private PyFunction findMethod(PyClass cls, String name) {
        for (PyFunction m : cls.getMethods()) {
            if (m.getName().equals(name)) {
                return m;
            }
        }
        return null;
    }

    private List<PyFunction> findTopLevel(PyModule module, String name) {
        List<PyFunction> result = new ArrayList<>();
        for (PyFunction f : module.getTopLevelFunctions()) {
            if (f.getName().equals(name)) {
                result.add(f);
            }
        }
        return result;
    }

    private List<PyClass> findClass(PyModule module, String name) {
        List<PyClass> result = new ArrayList<>();
        for (PyClass c : module.getClasses()) {
            if (c.getName().equals(name)) {
                result.add(c);
            }
        }
        return result;
    }

    // ---------------------------------------------------------------------
    // Cross-module class resolution
    // ---------------------------------------------------------------------

    /**
     * Resolve a raw base class reference to a fully qualified class name
     * (e.g., "BaseView" -> "myapp.views.BaseView").
     * Returns null if the base class cannot be resolved.
     */
    public String resolveBaseClass(String baseClassText, PyModule currentModule) {
        if (baseClassText == null || baseClassText.isBlank()) return null;

        // Skip 'object' — every Python class inherits from it
        if ("object".equals(baseClassText)) return null;

        // 1. Direct match in current module's classes
        for (PyClass cls : currentModule.getClasses()) {
            if (cls.getName().equals(baseClassText)) {
                return currentModule.getModulePath() + "." + cls.getName();
            }
        }

        // 2. Try to resolve through imports
        String head = baseClassText.contains(".")
                ? baseClassText.substring(0, baseClassText.indexOf("."))
                : baseClassText;
        String tail = baseClassText.contains(".")
                ? baseClassText.substring(baseClassText.indexOf(".") + 1)
                : null;

        for (PyImport imp : currentModule.getImports()) {
            String localName = importLocalName(imp);
            if (!head.equals(localName)) continue;

            String targetModulePath = absolutizeModuleName(
                    imp.getModuleName(), imp.getRelativeLevel(), currentModule);

            if (tail == null) {
                // Simple name: "from myapp.views import BaseView"
                if (imp.getSymbol() != null && !"*".equals(imp.getSymbol())) {
                    String fqn = targetModulePath + "." + imp.getSymbol();
                    if (classByQualifiedName.containsKey(fqn)) return fqn;
                } else if (imp.getSymbol() == null) {
                    // import myapp.views -> head="myapp", class is in myapp.views
                    PyModule targetModule = moduleByPath != null ? moduleByPath.get(targetModulePath) : null;
                    if (targetModule != null) {
                        for (PyClass cls : targetModule.getClasses()) {
                            if (cls.getName().equals(baseClassText)) {
                                return targetModulePath + "." + cls.getName();
                            }
                        }
                    }
                }
            } else {
                // Dotted name: "serializers.ModelSerializer"
                // head="serializers", tail="ModelSerializer"
                PyModule targetModule = moduleByPath != null ? moduleByPath.get(targetModulePath) : null;
                if (targetModule != null) {
                    for (PyClass cls : targetModule.getClasses()) {
                        if (cls.getName().equals(tail)) {
                            return targetModulePath + "." + cls.getName();
                        }
                    }
                }
                // Try nested module: targetModulePath + "." + head as module, tail as class
                String nestedModulePath = targetModulePath + "." + head;
                PyModule nestedModule = moduleByPath != null ? moduleByPath.get(nestedModulePath) : null;
                if (nestedModule != null) {
                    for (PyClass cls : nestedModule.getClasses()) {
                        if (cls.getName().equals(tail)) {
                            return nestedModulePath + "." + cls.getName();
                        }
                    }
                }
            }
        }

        // 3. Global search by simple name (last resort — may be ambiguous)
        List<PyClass> candidates = classesBySimpleName != null ? classesBySimpleName.get(baseClassText) : null;
        if (candidates != null && candidates.size() == 1) {
            PyClass cls = candidates.get(0);
            for (var entry : moduleByPath.entrySet()) {
                if (entry.getValue().getClasses().contains(cls)) {
                    return entry.getKey() + "." + cls.getName();
                }
            }
        }

        return null;
    }

    /**
     * Find a PyClass by its fully qualified name (modulePath.ClassName).
     */
    private PyClass findClassByFqn(String fqn) {
        return classByQualifiedName != null ? classByQualifiedName.get(fqn) : null;
    }

    // ---------------------------------------------------------------------
    // Edge construction
    // ---------------------------------------------------------------------

    private Map<String, Object> edge(String callerId, String calleeId,
                                     String callType, int line, boolean unresolved) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("callerId", callerId);
        m.put("calleeId", calleeId);
        m.put("callType", callType);
        m.put("callLine", line);
        if (unresolved) {
            m.put("unresolved", true);
        }
        return Collections.unmodifiableMap(m);
    }

    private Map<String, Object> unresolvedEdge(String callerId, String expression, int line) {
        String calleeId = "unresolved:" + PythonKnowledgeGraphBuilder.toNodeId(expression);
        return edge(callerId, calleeId, CALL_TYPE_UNRESOLVED, line, true);
    }
}
