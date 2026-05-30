package com.huawei.hisi.knowledgegraph.python;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.Async_funcdefContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.Atom_exprContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.ClassdefContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.DecoratedContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.DecoratorContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.DecoratorsContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.File_inputContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.FuncdefContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.Import_fromContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.Import_nameContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.NameContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser.TrailerContext;
import com.huawei.hisi.knowledgegraph.python.parser.Python3ParserBaseVisitor;

/**
 * ANTLR visitor that walks a parsed Python source file and assembles a
 * {@link PyModule} containing imports, classes, top-level functions, and call
 * sites.
 *
 * <p>This visitor is intentionally lenient: it captures structural facts that
 * are useful for downstream knowledge-graph construction without attempting
 * full semantic resolution (callee resolution, base-class resolution, etc.
 * are deferred to a later phase).
 */
public class PythonAstVisitor extends Python3ParserBaseVisitor<Void> {

    private enum ScopeKind { CLASS, FUNCTION }

    private final List<PyImport> imports = new ArrayList<>();
    private final List<PyClass> classes = new ArrayList<>();
    private final List<PyFunction> topLevelFunctions = new ArrayList<>();
    private final List<PyCall> calls = new ArrayList<>();

    private final Deque<ClassFrame> classFrames = new ArrayDeque<>();
    private final Deque<ScopeKind> scopeStack = new ArrayDeque<>();
    private final Deque<String> functionQualNameStack = new ArrayDeque<>();

    private List<String> pendingDecorators = Collections.emptyList();

    /**
     * Public entry point. Visits the {@code file_input} root and returns a fully
     * assembled {@link PyModule}.
     *
     * @param ctx        the {@code file_input} parse tree
     * @param filePath   absolute path of the source file
     * @param modulePath dotted module path (e.g. {@code app.api.users})
     * @return assembled module
     */
    public PyModule visit(File_inputContext ctx, String filePath, String modulePath) {
        super.visit(ctx);
        return PyModule.builder()
                .filePath(filePath)
                .modulePath(modulePath)
                .imports(imports)
                .classes(classes)
                .topLevelFunctions(topLevelFunctions)
                .calls(calls)
                .build();
    }

    @Override
    public Void visitDecorated(DecoratedContext ctx) {
        List<String> decs = ctx.decorators() == null
                ? Collections.<String>emptyList()
                : collectDecorators(ctx.decorators());
        List<String> previous = pendingDecorators;
        pendingDecorators = decs;
        try {
            if (ctx.classdef() != null) {
                visit(ctx.classdef());
            } else if (ctx.funcdef() != null) {
                visit(ctx.funcdef());
            } else if (ctx.async_funcdef() != null) {
                visit(ctx.async_funcdef());
            }
        } finally {
            pendingDecorators = previous;
        }
        return null;
    }

    @Override
    public Void visitClassdef(ClassdefContext ctx) {
        String name = ctx.name() != null ? ctx.name().getText() : "<anonymous>";
        List<String> bases = new ArrayList<>();
        if (ctx.arglist() != null) {
            ctx.arglist().argument().forEach(a -> bases.add(a.getText()));
        }
        List<String> decs = consumePendingDecorators();

        ClassFrame frame = new ClassFrame(
                name,
                bases,
                decs,
                ctx.getStart().getLine(),
                ctx.getStop() != null ? ctx.getStop().getLine() : ctx.getStart().getLine());

        classFrames.push(frame);
        scopeStack.push(ScopeKind.CLASS);
        try {
            visitChildren(ctx);
        } finally {
            classFrames.pop();
            scopeStack.pop();
            classes.add(PyClass.builder()
                    .name(frame.name)
                    .baseClasses(frame.baseClasses)
                    .decorators(frame.decorators)
                    .methods(frame.methods)
                    .lineStart(frame.lineStart)
                    .lineEnd(frame.lineEnd)
                    .build());
        }
        return null;
    }

    @Override
    public Void visitFuncdef(FuncdefContext ctx) {
        String name = ctx.name() != null ? ctx.name().getText() : "<anonymous>";
        boolean isMethod = !scopeStack.isEmpty() && scopeStack.peek() == ScopeKind.CLASS;
        String enclosingClass = isMethod ? classFrames.peek().name : null;
        String qualName = isMethod ? enclosingClass + "." + name : name;

        List<String> params = new ArrayList<>();
        if (ctx.parameters() != null && ctx.parameters().typedargslist() != null) {
            ctx.parameters().typedargslist().typedelem().forEach(elem -> {
                if (elem.tfpdef() != null && elem.tfpdef().name() != null) {
                    params.add(elem.tfpdef().name().getText());
                }
            });
        }

        List<String> decs = consumePendingDecorators();

        int lineStart = ctx.getStart().getLine();
        int lineEnd = ctx.getStop() != null ? ctx.getStop().getLine() : lineStart;

        PyFunction fn = PyFunction.builder()
                .name(name)
                .qualName(qualName)
                .paramNames(params)
                .decorators(decs)
                .lineStart(lineStart)
                .lineEnd(lineEnd)
                .isMethod(isMethod)
                .enclosingClass(enclosingClass)
                .build();

        if (isMethod) {
            classFrames.peek().methods.add(fn);
        } else {
            topLevelFunctions.add(fn);
        }

        scopeStack.push(ScopeKind.FUNCTION);
        functionQualNameStack.push(qualName);
        try {
            visitChildren(ctx);
        } finally {
            scopeStack.pop();
            functionQualNameStack.pop();
        }
        return null;
    }

    @Override
    public Void visitAsync_funcdef(Async_funcdefContext ctx) {
        if (ctx.funcdef() == null) {
            return null;
        }
        // Delegate directly to visitFuncdef - it already handles both top-level and method cases
        return visitFuncdef(ctx.funcdef());
    }

    @Override
    public Void visitImport_name(Import_nameContext ctx) {
        if (ctx.dotted_as_names() == null) {
            return null;
        }
        int line = ctx.getStart().getLine();
        ctx.dotted_as_names().dotted_as_name().forEach(dan -> {
            String moduleName = dan.dotted_name() != null ? dan.dotted_name().getText() : "";
            String alias = dan.name() != null ? dan.name().getText() : null;
            imports.add(PyImport.builder()
                    .moduleName(moduleName)
                    .symbol(null)
                    .alias(alias)
                    .fromImport(false)
                    .lineNumber(line)
                    .build());
        });
        return null;
    }

    @Override
    public Void visitImport_from(Import_fromContext ctx) {
        String moduleName = ctx.dotted_name() != null ? ctx.dotted_name().getText() : "";
        int line = ctx.getStart().getLine();
        int relativeLevel = countLeadingDots(ctx);
        if (ctx.import_as_names() != null) {
            ctx.import_as_names().import_as_name().forEach(ian -> {
                List<NameContext> names = ian.name();
                if (names == null || names.isEmpty()) {
                    return;
                }
                String symbol = names.get(0).getText();
                String alias = names.size() > 1 ? names.get(1).getText() : null;
                imports.add(PyImport.builder()
                        .moduleName(moduleName)
                        .symbol(symbol)
                        .alias(alias)
                        .fromImport(true)
                        .lineNumber(line)
                        .relativeLevel(relativeLevel)
                        .build());
            });
        } else {
            // 'from X import *'
            imports.add(PyImport.builder()
                    .moduleName(moduleName)
                    .symbol("*")
                    .alias(null)
                    .fromImport(true)
                    .lineNumber(line)
                    .relativeLevel(relativeLevel)
                    .build());
        }
        return null;
    }

    /**
     * Count the leading {@code .} / {@code ...} tokens in a {@code from ...} import.
     * Each {@code .} contributes 1; each {@code ...} (ELLIPSIS) contributes 3.
     */
    private static int countLeadingDots(Import_fromContext ctx) {
        int count = 0;
        for (int i = 0; i < ctx.getChildCount(); i++) {
            String text = ctx.getChild(i).getText();
            if (".".equals(text)) {
                count++;
            } else if ("...".equals(text)) {
                count += 3;
            } else if ("from".equals(text)) {
                continue;
            } else if ("import".equals(text)) {
                break;
            }
        }
        return count;
    }

    @Override
    public Void visitAtom_expr(Atom_exprContext ctx) {
        if (ctx.atom() != null && ctx.trailer() != null && !ctx.trailer().isEmpty()) {
            StringBuilder running = new StringBuilder(ctx.atom().getText());
            String enclosing = functionQualNameStack.isEmpty() ? "<module>" : functionQualNameStack.peek();
            for (TrailerContext trailer : ctx.trailer()) {
                if (trailer.OPEN_PAREN() != null) {
                    calls.add(PyCall.builder()
                            .calleeExpression(running.toString())
                            .lineNumber(trailer.getStart().getLine())
                            .enclosingFunction(enclosing)
                            .firstStringArg(extractFirstStringArg(trailer))
                            .secondPositionalArg(extractSecondPositionalArg(trailer))
                            .build());
                }
                running.append(trailer.getText());
            }
        }
        return visitChildren(ctx);
    }

    /**
     * Extract the first positional string-literal argument from a call trailer.
     *
     * <p>Returns the unquoted string contents, or {@code null} when the call has
     * no arguments, the first argument is not a positional argument, or the
     * first argument is not a plain string literal.
     */
    private static String extractFirstStringArg(TrailerContext trailer) {
        if (trailer.arglist() == null || trailer.arglist().argument() == null
                || trailer.arglist().argument().isEmpty()) {
            return null;
        }
        Python3Parser.ArgumentContext first = trailer.arglist().argument(0);
        // Skip keyword arguments (a=...) and *args/**kwargs forms.
        if (first.ASSIGN() != null || first.STAR() != null || first.POWER() != null) {
            return null;
        }
        if (first.test() == null || first.test().isEmpty()) {
            return null;
        }
        String text = first.test(0).getText();
        return parseStringLiteral(text);
    }

    /**
     * Extract the raw textual form of the SECOND positional argument from a call
     * trailer, skipping keyword arguments (a=...) and *args/**kwargs forms.
     *
     * <p>Used by Django URL scanning to extract the view callable from
     * {@code path('users/', views.user_list)} or
     * {@code path('users/', UserView.as_view())}. Returns the raw expression
     * text (e.g. {@code "views.user_list"} or {@code "UserView.as_view()"}),
     * NOT a string-literal value. Returns {@code null} when fewer than two
     * positional arguments exist.
     */
    private static String extractSecondPositionalArg(TrailerContext trailer) {
        if (trailer.arglist() == null || trailer.arglist().argument() == null) {
            return null;
        }
        List<Python3Parser.ArgumentContext> args = trailer.arglist().argument();
        int positionalCount = 0;
        for (Python3Parser.ArgumentContext arg : args) {
            if (arg.ASSIGN() != null || arg.STAR() != null || arg.POWER() != null) {
                // Skip kwargs / *args / **kwargs — they break positional ordering.
                continue;
            }
            positionalCount++;
            if (positionalCount == 2) {
                if (arg.test() == null || arg.test().isEmpty()) {
                    return null;
                }
                return arg.test(0).getText();
            }
        }
        return null;
    }

    /**
     * Parse a Python string-literal token, stripping prefix (r/R/b/B/u/U/f/F)
     * and surrounding quotes. Triple-quoted forms are also handled. Returns
     * {@code null} for non-string-literal text.
     */
    private static String parseStringLiteral(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int idx = 0;
        // Strip optional string prefix (single character: r, b, u, f and combinations like rb).
        while (idx < text.length() && idx < 2) {
            char c = text.charAt(idx);
            if (c == 'r' || c == 'R' || c == 'b' || c == 'B'
                    || c == 'u' || c == 'U' || c == 'f' || c == 'F') {
                idx++;
            } else {
                break;
            }
        }
        if (idx >= text.length()) {
            return null;
        }
        char quote = text.charAt(idx);
        if (quote != '"' && quote != '\'') {
            return null;
        }
        // Triple-quoted form?
        if (idx + 2 < text.length()
                && text.charAt(idx + 1) == quote
                && text.charAt(idx + 2) == quote) {
            int start = idx + 3;
            int end = text.length() - 3;
            if (end < start) {
                return null;
            }
            return text.substring(start, end);
        }
        int start = idx + 1;
        int end = text.length() - 1;
        if (end < start || text.charAt(end) != quote) {
            return null;
        }
        return text.substring(start, end);
    }

    private List<String> consumePendingDecorators() {
        List<String> decs = pendingDecorators;
        pendingDecorators = Collections.emptyList();
        return decs;
    }

    private static List<String> collectDecorators(DecoratorsContext ctx) {
        List<String> decs = new ArrayList<>();
        for (DecoratorContext d : ctx.decorator()) {
            String text = d.getText();
            if (text != null && text.startsWith("@")) {
                text = text.substring(1);
            }
            if (text != null) {
                decs.add(text.trim());
            }
        }
        return decs;
    }

    /** Mutable bookkeeping for an in-flight class definition. */
    private static final class ClassFrame {
        final String name;
        final List<String> baseClasses;
        final List<String> decorators;
        final List<PyFunction> methods = new ArrayList<>();
        final int lineStart;
        final int lineEnd;

        ClassFrame(String name,
                   List<String> baseClasses,
                   List<String> decorators,
                   int lineStart,
                   int lineEnd) {
            this.name = name;
            this.baseClasses = baseClasses;
            this.decorators = decorators;
            this.lineStart = lineStart;
            this.lineEnd = lineEnd;
        }
    }

    // Suppress unused-import warning for Python3Parser (used only via nested context types).
    @SuppressWarnings("unused")
    private static final Class<?> PARSER_REF = Python3Parser.class;
}
