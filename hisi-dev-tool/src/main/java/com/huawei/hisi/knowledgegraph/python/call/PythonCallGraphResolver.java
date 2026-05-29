package com.huawei.hisi.knowledgegraph.python.call;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
    private static final String CALL_TYPE_IMPORT = "IMPORT";
    private static final String CALL_TYPE_UNRESOLVED = "UNRESOLVED";

    /** Common Python builtins — skip these, they have no KG node. */
    private static final Set<String> PYTHON_BUILTINS = Set.of(
            "print", "len", "range", "enumerate", "zip", "map", "filter", "sorted",
            "reversed", "list", "dict", "set", "tuple", "str", "int", "float", "bool",
            "type", "isinstance", "issubclass", "hasattr", "getattr", "setattr", "delattr",
            "super", "property", "classmethod", "staticmethod", "abs", "min", "max", "sum",
            "any", "all", "round", "repr", "hash", "id", "input", "open", "iter", "next",
            "callable", "vars", "dir", "globals", "locals", "exec", "eval", "compile",
            "format", "chr", "ord", "hex", "oct", "bin", "pow", "divmod",
            "object", "Exception", "ValueError", "TypeError", "KeyError", "IndexError",
            "AttributeError", "RuntimeError", "StopIteration", "NotImplementedError",
            "OSError", "IOError", "FileNotFoundError", "ImportError", "ModuleNotFoundError");

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
                                     Map<String, PyFunction> topLevelByName,
                                     Map<String, PyClass> classesByName) {
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
        return expression.replaceAll("\\([^)]*\\)\\.", ".");
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
            log.trace("[CallGraph] Skipping unresolvable call '{}' in {}",
                    head, call.getEnclosingFunction());
            return null;
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
        // Can't resolve — skip
        log.trace("[CallGraph] Skipping unresolvable call '{}' in {}",
                expression, call.getEnclosingFunction());
        return null;
    }

    /** {@code self.method()} → method on the call's enclosing class. */
    private Map<String, Object> resolveSelfCall(PyCall call, String callerNodeId,
                                                String methodName, ResolutionContext ctx) {
        String enclosingClass = enclosingClassOf(call, ctx.classesByName());
        if (enclosingClass == null) {
            return null;
        }
        PyClass owner = ctx.classesByName().get(enclosingClass);
        if (owner == null) {
            return null;
        }
        PyFunction method = findMethod(owner, methodName);
        if (method == null) {
            return null;
        }
        String calleeId = methodNodeId(ctx.module().getModulePath(), owner.getName(), method);
        return edge(callerNodeId, calleeId, CALL_TYPE_SELF, call.getLineNumber(), false);
    }

    /** Plain {@code foo()} → top-level function in the current module. */
    private Map<String, Object> resolveDirectCall(PyCall call, String callerNodeId,
                                                  String name, ResolutionContext ctx) {
        PyFunction local = ctx.topLevelByName().get(name);
        if (local == null) {
            return null;
        }
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
                    PyFunction func = findTopLevel(target, parts[1]);
                    if (func != null) {
                        return edge(callerNodeId, topLevelNodeId(target.getModulePath(), func),
                                CALL_TYPE_IMPORT, call.getLineNumber(), false);
                    }
                    PyClass cls = findClass(target, parts[1]);
                    if (cls != null && parts.length >= 3) {
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
            PyFunction func = findTopLevel(target, imp.getSymbol());
            return func == null ? null : edge(
                    callerNodeId, topLevelNodeId(target.getModulePath(), func),
                    CALL_TYPE_IMPORT, call.getLineNumber(), false);
        }

        if (!imp.isFromImport()) {
            PyFunction func = findTopLevel(target, parts[1]);
            return func == null ? null : edge(
                    callerNodeId, topLevelNodeId(target.getModulePath(), func),
                    CALL_TYPE_IMPORT, call.getLineNumber(), false);
        }

        if (imp.getSymbol() == null) {
            return null;
        }
        PyClass cls = findClass(target, imp.getSymbol());
        if (cls == null) {
            return null;
        }
        PyFunction method = findMethod(cls, parts[1]);
        return method == null ? null : edge(
                callerNodeId, methodNodeId(target.getModulePath(), cls.getName(), method),
                CALL_TYPE_IMPORT, call.getLineNumber(), false);
    }

    /** {@code LocalClass.method()} where {@code LocalClass} is defined in the current module. */
    private Map<String, Object> resolveLocalClassCall(PyCall call, String callerNodeId,
                                                      String[] parts, ResolutionContext ctx) {
        PyClass local = ctx.classesByName().get(parts[0]);
        if (local == null) {
            return null;
        }
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
        return null;
    }

    private String enclosingClassOf(PyCall call, Map<String, PyClass> classesByName) {
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
            PyFunction func = findTopLevel(source, funcName);
            if (func != null) {
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
            return Collections.emptyMap();
        }
        Map<String, PyModule> out = new HashMap<>();
        for (PyModule m : modules) {
            if (m != null && m.getModulePath() != null) {
                out.put(m.getModulePath(), m);
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

    private Map<String, PyFunction> indexTopLevelFunctions(List<PyFunction> functions) {
        if (functions == null || functions.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, PyFunction> out = new HashMap<>();
        for (PyFunction f : functions) {
            out.put(f.getName(), f);
        }
        return out;
    }

    private Map<String, PyClass> indexClasses(List<PyClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, PyClass> out = new HashMap<>();
        for (PyClass c : classes) {
            out.put(c.getName(), c);
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

    private PyFunction findTopLevel(PyModule module, String name) {
        for (PyFunction f : module.getTopLevelFunctions()) {
            if (f.getName().equals(name)) {
                return f;
            }
        }
        return null;
    }

    private PyClass findClass(PyModule module, String name) {
        for (PyClass c : module.getClasses()) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
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
