package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.huawei.hisi.knowledgegraph.python.PythonKnowledgeGraphBuilder;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves the textual view expression appearing as the second positional
 * argument of a Django {@code path(...)} / {@code re_path(...)} / {@code url(...)}
 * call into a concrete {@link PyFunction} living in (potentially) another
 * module, and computes its {@code methodNodeId}.
 *
 * <p>Examples handled:
 * <pre>
 *   urlpatterns = [
 *       path('users/', views.user_list),               # FBV via module ref
 *       path('users/&lt;id&gt;', UserDetail.as_view()),       # CBV via .as_view()
 *       path('home/',  home_view),                     # FBV imported by name
 *   ]
 * </pre>
 *
 * <p>Resolution returns {@link Optional#empty()} when the symbol cannot be
 * resolved to a concrete callable; callers should log a warning and leave
 * {@code methodNodeId = null}.
 */
@Slf4j
class DjangoViewResolver {

    private static final List<String> CBV_HTTP_METHODS = List.of(
            "get", "post", "put", "delete", "patch", "head", "options");

    /** DRF ViewSet base class names (short names). */
    private static final Set<String> VIEWSET_BASES = Set.of(
            "ViewSet", "ModelViewSet", "ReadOnlyModelViewSet", "GenericViewSet");

    /** DRF ViewSet list-level actions: method name -> HTTP method. */
    private static final Map<String, String> DRF_LIST_ACTIONS = new HashMap<>();
    static {
        DRF_LIST_ACTIONS.put("list", "GET");
        DRF_LIST_ACTIONS.put("create", "POST");
    }

    /** DRF ViewSet detail-level actions: method name -> HTTP method. */
    private static final Map<String, String> DRF_DETAIL_ACTIONS = new HashMap<>();
    static {
        DRF_DETAIL_ACTIONS.put("retrieve", "GET");
        DRF_DETAIL_ACTIONS.put("update", "PUT");
        DRF_DETAIL_ACTIONS.put("partial_update", "PATCH");
        DRF_DETAIL_ACTIONS.put("destroy", "DELETE");
    }

    /** Combined DRF action map (list + detail). */
    private static final Map<String, String> DRF_ALL_ACTIONS = new HashMap<>();
    static {
        DRF_ALL_ACTIONS.putAll(DRF_LIST_ACTIONS);
        DRF_ALL_ACTIONS.putAll(DRF_DETAIL_ACTIONS);
    }

    /** Result of resolving a view expression to a concrete callable. */
    static final class ResolvedView {
        final String modulePath;
        final String qualName;
        final List<String> paramNames;
        final boolean isClassBased;

        ResolvedView(String modulePath, String qualName, List<String> paramNames, boolean isClassBased) {
            this.modulePath = modulePath;
            this.qualName = qualName;
            this.paramNames = paramNames;
            this.isClassBased = isClassBased;
        }

        String computeNodeId() {
            return PythonKnowledgeGraphBuilder.computeMethodNodeId(modulePath, qualName, paramNames);
        }
    }

    /**
     * Resolve {@code viewExpression} to a concrete callable.
     *
     * @param viewExpression raw textual form of the second positional arg from
     *                       a {@code path(...)} call (e.g. {@code "views.user_list"})
     * @param currentModule  the module containing the {@code path(...)} call site
     * @param modulesByPath  global map of {@code modulePath -> PyModule}
     */
    Optional<ResolvedView> resolve(String viewExpression,
                                   PyModule currentModule,
                                   Map<String, PyModule> modulesByPath) {
        if (viewExpression == null || viewExpression.isEmpty()
                || currentModule == null || modulesByPath == null) {
            return Optional.empty();
        }

        // 1) Strip trailing .as_view() / .as_view (Django CBV markers)
        String expr = stripAsView(viewExpression);

        // 2) Split into root and remainder
        int firstDot = expr.indexOf('.');
        String root = firstDot < 0 ? expr : expr.substring(0, firstDot);
        String remainder = firstDot < 0 ? "" : expr.substring(firstDot + 1);

        // 3) Find an import binding for `root`
        PyImport binding = findBindingFor(root, currentModule, modulesByPath);
        if (binding == null) {
            log.debug("[DjangoViewResolver] No import binding for root '{}' in {}",
                    root, currentModule.getModulePath());
            return Optional.empty();
        }

        // 4) Compute (containingModulePath, callableSymbol) based on import kind.
        String targetModulePath;
        String callableSymbol;

        if (!binding.isFromImport()) {
            // `import a.b.c` or `import a.b.c as alias`
            // - aliased: root=alias binds to module `a.b.c`. remainder must be a callable inside.
            // - non-aliased: root='a' (top package). User wrote `a.b.c.func` — must traverse.
            String moduleName = binding.getModuleName();
            String alias = binding.getAlias();
            if (alias != null) {
                targetModulePath = moduleName;
            } else {
                // root == top package. The user-written remainder includes the rest of
                // the module path plus the callable, e.g. moduleName='a.b.c', remainder='b.c.func'
                // We try to consume as much of remainder as matches additional module segments.
                targetModulePath = moduleName;
                String expectedSuffix = moduleName.contains(".")
                        ? moduleName.substring(moduleName.indexOf('.') + 1)
                        : "";
                if (!expectedSuffix.isEmpty()) {
                    if (remainder.startsWith(expectedSuffix + ".")) {
                        remainder = remainder.substring(expectedSuffix.length() + 1);
                    } else if (remainder.equals(expectedSuffix)) {
                        remainder = "";
                    }
                }
            }
            if (remainder.isEmpty()) {
                // No callable specified — invalid for our use case
                return Optional.empty();
            }
            int dot = remainder.indexOf('.');
            callableSymbol = dot < 0 ? remainder : remainder.substring(0, dot);
        } else {
            // `from X import root[as alias]`  (or `from . import root`)
            // The imported name `root` could be either:
            //   (a) a callable/class inside module X  (e.g. from .views import user_list)
            //   (b) a sub-module of X                  (e.g. from . import views)
            //
            // Differentiator: try (b) first — does `X.root` exist in modulesByPath?
            String containingModule = absolutize(binding.getModuleName(),
                    binding.getRelativeLevel(), currentModule);
            if (containingModule == null) {
                return Optional.empty();
            }
            String originalSymbol = binding.getSymbol();
            String submoduleCandidate = containingModule.isEmpty()
                    ? originalSymbol
                    : containingModule + "." + originalSymbol;

            if (modulesByPath.containsKey(submoduleCandidate)) {
                // (b) module ref: root points at submodule, callable is in `remainder`
                targetModulePath = submoduleCandidate;
                if (remainder.isEmpty()) {
                    return Optional.empty();
                }
                int dot = remainder.indexOf('.');
                callableSymbol = dot < 0 ? remainder : remainder.substring(0, dot);
            } else {
                // (a) symbol ref: root IS the callable name (in module `containingModule`)
                targetModulePath = containingModule;
                callableSymbol = originalSymbol;
                // remainder, if present, is method access (e.g. `.as_view` already stripped,
                // or attribute on instance) — irrelevant for our lookup.
            }
        }

        PyModule targetModule = modulesByPath.get(targetModulePath);
        if (targetModule == null) {
            log.debug("[DjangoViewResolver] Module '{}' not in graph (target of '{}')",
                    targetModulePath, viewExpression);
            return Optional.empty();
        }
        return findCallableInModule(targetModule, callableSymbol);
    }

    List<ResolvedView> resolveAll(String viewExpression,
                                  PyModule currentModule,
                                  Map<String, PyModule> modulesByPath) {
        Optional<ResolvedView> single = resolve(viewExpression, currentModule, modulesByPath);
        if (single.isEmpty()) {
            return List.of();
        }
        if (!single.get().isClassBased) {
            return List.of(single.get());
        }
        return resolveAllInternal(viewExpression, currentModule, modulesByPath);
    }

    private List<ResolvedView> resolveAllInternal(String viewExpression,
                                                   PyModule currentModule,
                                                   Map<String, PyModule> modulesByPath) {
        String expr = stripAsView(viewExpression);
        int firstDot = expr.indexOf('.');
        String root = firstDot < 0 ? expr : expr.substring(0, firstDot);
        String remainder = firstDot < 0 ? "" : expr.substring(firstDot + 1);
        PyImport binding = findBindingFor(root, currentModule, modulesByPath);
        if (binding == null) {
            return List.of();
        }

        String targetModulePath;
        String callableSymbol;

        if (!binding.isFromImport()) {
            String moduleName = binding.getModuleName();
            String alias = binding.getAlias();
            if (alias != null) {
                targetModulePath = moduleName;
            } else {
                targetModulePath = moduleName;
                String expectedSuffix = moduleName.contains(".")
                        ? moduleName.substring(moduleName.indexOf('.') + 1) : "";
                if (!expectedSuffix.isEmpty()) {
                    if (remainder.startsWith(expectedSuffix + ".")) {
                        remainder = remainder.substring(expectedSuffix.length() + 1);
                    } else if (remainder.equals(expectedSuffix)) {
                        remainder = "";
                    }
                }
            }
            if (remainder.isEmpty()) {
                return List.of();
            }
            int dot = remainder.indexOf('.');
            callableSymbol = dot < 0 ? remainder : remainder.substring(0, dot);
        } else {
            String containingModule = absolutize(binding.getModuleName(),
                    binding.getRelativeLevel(), currentModule);
            if (containingModule == null) {
                return List.of();
            }
            String originalSymbol = binding.getSymbol();
            String submoduleCandidate = containingModule.isEmpty()
                    ? originalSymbol : containingModule + "." + originalSymbol;
            if (modulesByPath.containsKey(submoduleCandidate)) {
                targetModulePath = submoduleCandidate;
                if (remainder.isEmpty()) {
                    return List.of();
                }
                int dot = remainder.indexOf('.');
                callableSymbol = dot < 0 ? remainder : remainder.substring(0, dot);
            } else {
                targetModulePath = containingModule;
                callableSymbol = originalSymbol;
            }
        }

        PyModule targetModule = modulesByPath.get(targetModulePath);
        if (targetModule == null) {
            return List.of();
        }
        return findAllCallablesInModule(targetModule, callableSymbol);
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private static String stripAsView(String expr) {
        if (expr.endsWith(".as_view()")) {
            return expr.substring(0, expr.length() - ".as_view()".length());
        }
        if (expr.endsWith(".as_view")) {
            return expr.substring(0, expr.length() - ".as_view".length());
        }
        return expr;
    }

    /**
     * Find the import binding (if any) that introduces {@code root} into the
     * current module's namespace. Returns {@code null} if no such import exists.
     *
     * <p>For wildcard ({@code from X import *}) imports, attempts to resolve
     * {@code root} by searching the target module's classes and top-level functions.
     */
    private PyImport findBindingFor(String root, PyModule currentModule, Map<String, PyModule> modulesByPath) {
        for (PyImport imp : currentModule.getImports()) {
            if (imp.isFromImport()) {
                if ("*".equals(imp.getSymbol())) {
                    // Wildcard import: try to find the symbol in the target module
                    String containingModule = absolutize(imp.getModuleName(),
                            imp.getRelativeLevel(), currentModule);
                    if (containingModule != null) {
                        PyModule targetModule = modulesByPath.get(containingModule);
                        if (targetModule != null) {
                            for (PyClass cls : targetModule.getClasses()) {
                                if (cls.getName().equals(root)) {
                                    return PyImport.builder()
                                            .moduleName(imp.getModuleName())
                                            .symbol(root)
                                            .alias(null)
                                            .fromImport(true)
                                            .lineNumber(imp.getLineNumber())
                                            .relativeLevel(imp.getRelativeLevel())
                                            .build();
                                }
                            }
                            for (PyFunction fn : targetModule.getTopLevelFunctions()) {
                                if (fn.getName().equals(root)) {
                                    return PyImport.builder()
                                            .moduleName(imp.getModuleName())
                                            .symbol(root)
                                            .alias(null)
                                            .fromImport(true)
                                            .lineNumber(imp.getLineNumber())
                                            .relativeLevel(imp.getRelativeLevel())
                                            .build();
                                }
                            }
                        }
                    }
                    continue;
                }
                String effective = imp.getAlias() != null ? imp.getAlias() : imp.getSymbol();
                if (root.equals(effective)) {
                    return imp;
                }
            } else {
                String moduleName = imp.getModuleName();
                String alias = imp.getAlias();
                if (alias != null) {
                    if (root.equals(alias)) {
                        return imp;
                    }
                } else if (moduleName != null && !moduleName.isEmpty()) {
                    String topPackage = moduleName.contains(".")
                            ? moduleName.substring(0, moduleName.indexOf('.'))
                            : moduleName;
                    if (root.equals(topPackage)) {
                        return imp;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Convert a (possibly relative) import module name into an absolute dotted
     * path, using {@code currentModule.modulePath} as the anchor.
     *
     * <p>{@code relativeLevel == 0} → already absolute.<br>
     * {@code relativeLevel == 1} → relative to current package (drop trailing module name).<br>
     * {@code relativeLevel == 2} → one more level up; etc.
     */
    private static String absolutize(String moduleName, int relativeLevel, PyModule currentModule) {
        if (relativeLevel <= 0) {
            return moduleName == null ? "" : moduleName;
        }
        String current = currentModule.getModulePath();
        if (current == null) {
            return null;
        }
        String[] parts = current.split("\\.");
        int keep = parts.length - relativeLevel;
        if (keep < 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < keep; i++) {
            if (i > 0) {
                sb.append('.');
            }
            sb.append(parts[i]);
        }
        if (moduleName != null && !moduleName.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('.');
            }
            sb.append(moduleName);
        }
        return sb.toString();
    }

    private Optional<ResolvedView> findCallableInModule(PyModule module, String symbol) {
        List<ResolvedView> all = findAllCallablesInModule(module, symbol);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    List<ResolvedView> findAllCallablesInModule(PyModule module, String symbol) {
        for (PyFunction fn : module.getTopLevelFunctions()) {
            if (fn.getName().equals(symbol)) {
                return List.of(new ResolvedView(
                        module.getModulePath(),
                        fn.getQualName(),
                        fn.getParamNames(),
                        false));
            }
        }
        for (PyClass clazz : module.getClasses()) {
            if (!clazz.getName().equals(symbol)) {
                continue;
            }
            List<PyFunction> methods = pickCbvMethods(clazz);
            return methods.stream()
                    .map(m -> new ResolvedView(
                            module.getModulePath(),
                            m.getQualName(),
                            m.getParamNames(),
                            true))
                    .toList();
        }
        return List.of();
    }

    private static List<PyFunction> pickCbvMethods(PyClass clazz) {
        // DRF ViewSet: use DRF action names instead of HTTP method names
        if (isViewSetClass(clazz)) {
            return pickViewSetMethods(clazz);
        }
        // Standard Django CBV: use HTTP method names
        List<PyFunction> httpMethods = new ArrayList<>();
        for (String preferred : CBV_HTTP_METHODS) {
            for (PyFunction m : clazz.getMethods()) {
                if (m.getName().equals(preferred)) {
                    httpMethods.add(m);
                }
            }
        }
        if (!httpMethods.isEmpty()) {
            return httpMethods;
        }
        for (PyFunction m : clazz.getMethods()) {
            if ("dispatch".equals(m.getName())) {
                return List.of(m);
            }
        }
        if (!clazz.getMethods().isEmpty()) {
            return List.of(clazz.getMethods().get(0));
        }
        return List.of();
    }

    /** Check if a class inherits from a DRF ViewSet base class. */
    static boolean isViewSetClass(PyClass clazz) {
        for (String base : clazz.getBaseClasses()) {
            String simple = base.contains(".") ? base.substring(base.lastIndexOf('.') + 1) : base;
            if (VIEWSET_BASES.contains(simple)) {
                return true;
            }
        }
        return false;
    }

    /** Pick ViewSet action methods (list/create/retrieve/update/partial_update/destroy). */
    private static List<PyFunction> pickViewSetMethods(PyClass clazz) {
        List<PyFunction> actions = new ArrayList<>();
        for (String actionName : DRF_ALL_ACTIONS.keySet()) {
            for (PyFunction m : clazz.getMethods()) {
                if (m.getName().equals(actionName)) {
                    actions.add(m);
                }
            }
        }
        return actions;
    }

    /** Get the HTTP method for a DRF action name, or null if not a standard action. */
    static String getDrfHttpMethod(String actionName) {
        return DRF_ALL_ACTIONS.get(actionName);
    }

    /** Get whether an action is a list-level action (no URL pk suffix). */
    static boolean isDrfListAction(String actionName) {
        return DRF_LIST_ACTIONS.containsKey(actionName);
    }
}
