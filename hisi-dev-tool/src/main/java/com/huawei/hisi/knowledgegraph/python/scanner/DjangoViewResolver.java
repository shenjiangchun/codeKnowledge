package com.huawei.hisi.knowledgegraph.python.scanner;

import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    private static final List<String> CBV_REPRESENTATIVE_METHODS = List.of(
            "get", "post", "put", "delete", "patch", "head", "options", "dispatch");

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
        PyImport binding = findBindingFor(root, currentModule);
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
     */
    private static PyImport findBindingFor(String root, PyModule currentModule) {
        for (PyImport imp : currentModule.getImports()) {
            if (imp.isFromImport()) {
                if ("*".equals(imp.getSymbol())) {
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
        for (PyFunction fn : module.getTopLevelFunctions()) {
            if (fn.getName().equals(symbol)) {
                return Optional.of(new ResolvedView(
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
            PyFunction representative = pickCbvRepresentativeMethod(clazz);
            if (representative == null) {
                return Optional.empty();
            }
            return Optional.of(new ResolvedView(
                    module.getModulePath(),
                    representative.getQualName(),
                    representative.getParamNames(),
                    true));
        }
        return Optional.empty();
    }

    private static PyFunction pickCbvRepresentativeMethod(PyClass clazz) {
        for (String preferred : CBV_REPRESENTATIVE_METHODS) {
            for (PyFunction m : clazz.getMethods()) {
                if (m.getName().equals(preferred)) {
                    return m;
                }
            }
        }
        if (!clazz.getMethods().isEmpty()) {
            return clazz.getMethods().get(0);
        }
        return null;
    }
}
