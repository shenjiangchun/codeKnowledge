package com.huawei.hisi.knowledgegraph.python;

import com.huawei.hisi.knowledgegraph.python.model.PyCall;
import com.huawei.hisi.knowledgegraph.python.model.PyClass;
import com.huawei.hisi.knowledgegraph.python.model.PyFunction;
import com.huawei.hisi.knowledgegraph.python.model.PyImport;
import com.huawei.hisi.knowledgegraph.python.model.PyModule;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PythonAstVisitorTest {

    private PyModule parse(String src) {
        Python3Lexer lexer = new Python3Lexer(CharStreams.fromString(src));
        Python3Parser parser = new Python3Parser(new CommonTokenStream(lexer));
        return new PythonAstVisitor().visit(parser.file_input(), "/tmp/x.py", "x");
    }

    @Test
    @DisplayName("top-level function with 2 params")
    void topLevelFunction() {
        PyModule mod = parse("def greet(name, greeting):\n    pass\n");

        assertEquals(1, mod.getTopLevelFunctions().size());
        PyFunction fn = mod.getTopLevelFunctions().get(0);
        assertEquals("greet", fn.getName());
        assertEquals("greet", fn.getQualName());
        assertEquals(2, fn.getParamNames().size());
        assertTrue(fn.getParamNames().contains("name"));
        assertTrue(fn.getParamNames().contains("greeting"));
        assertFalse(fn.isMethod());
        assertNull(fn.getEnclosingClass());
    }

    @Test
    @DisplayName("class with 2 methods")
    void classWithMethods() {
        String src = String.join("\n",
                "class Dog:",
                "    def bark(self):",
                "        pass",
                "    def fetch(self, item):",
                "        pass",
                "");

        PyModule mod = parse(src);

        assertEquals(1, mod.getClasses().size());
        PyClass cls = mod.getClasses().get(0);
        assertEquals("Dog", cls.getName());
        assertEquals(2, cls.getMethods().size());

        PyFunction bark = cls.getMethods().get(0);
        assertEquals("bark", bark.getName());
        assertTrue(bark.isMethod());
        assertEquals("Dog", bark.getEnclosingClass());
        assertEquals("Dog.bark", bark.getQualName());

        PyFunction fetch = cls.getMethods().get(1);
        assertEquals("fetch", fetch.getName());
        assertTrue(fetch.isMethod());
        assertEquals("Dog", fetch.getEnclosingClass());
    }

    @Test
    @DisplayName("from X.Y import Z as A")
    void fromImportWithAlias() {
        PyModule mod = parse("from foo.bar import baz as qux\n");

        assertEquals(1, mod.getImports().size());
        PyImport imp = mod.getImports().get(0);
        assertTrue(imp.isFromImport());
        assertEquals("foo.bar", imp.getModuleName());
        assertEquals("baz", imp.getSymbol());
        assertEquals("qux", imp.getAlias());
    }

    @Test
    @DisplayName("call site detection inside function body")
    void callSiteDetection() {
        String src = String.join("\n",
                "def fetch_data(url):",
                "    requests.get(url)",
                "");

        PyModule mod = parse(src);

        assertFalse(mod.getCalls().isEmpty(), "expected at least one call");
        boolean found = mod.getCalls().stream()
                .anyMatch(c -> c.getCalleeExpression().contains("requests.get")
                        && "fetch_data".equals(c.getEnclosingFunction()));
        assertTrue(found, "expected a call to requests.get in fetch_data, got: " + mod.getCalls());
    }

    @Test
    @DisplayName("decorated function includes decorator text")
    void decoratedFunction() {
        String src = String.join("\n",
                "@app.get(\"/users\")",
                "def list_users():",
                "    pass",
                "");

        PyModule mod = parse(src);

        assertEquals(1, mod.getTopLevelFunctions().size());
        PyFunction fn = mod.getTopLevelFunctions().get(0);
        assertNotNull(fn.getDecorators());
        assertFalse(fn.getDecorators().isEmpty(), "expected decorators");
        boolean hasAppGet = fn.getDecorators().stream()
                .anyMatch(d -> d.contains("app.get"));
        assertTrue(hasAppGet, "expected decorator containing 'app.get', got: " + fn.getDecorators());
    }

    @Test
    @DisplayName("call site captures first string-literal argument")
    void callSiteFirstStringArg() {
        String src = String.join("\n",
                "def fetch_data():",
                "    requests.get(\"https://example.com/api\", timeout=5)",
                "");

        PyModule mod = parse(src);

        PyCall call = mod.getCalls().stream()
                .filter(c -> c.getCalleeExpression().endsWith("requests.get"))
                .findFirst()
                .orElseThrow();
        assertEquals("https://example.com/api", call.getFirstStringArg());
    }

    @Test
    @DisplayName("call site has null firstStringArg when first arg is not a literal")
    void callSiteFirstStringArgNonLiteral() {
        String src = String.join("\n",
                "def fetch_data(url):",
                "    requests.get(url)",
                "");

        PyModule mod = parse(src);

        PyCall call = mod.getCalls().stream()
                .filter(c -> c.getCalleeExpression().endsWith("requests.get"))
                .findFirst()
                .orElseThrow();
        assertNull(call.getFirstStringArg());
    }

    @Test
    @DisplayName("call site supports single-quoted string literal")
    void callSiteSingleQuotedString() {
        String src = String.join("\n",
                "def fetch_data():",
                "    httpx.post('https://api.x/y')",
                "");

        PyModule mod = parse(src);

        PyCall call = mod.getCalls().stream()
                .filter(c -> c.getCalleeExpression().endsWith("httpx.post"))
                .findFirst()
                .orElseThrow();
        assertEquals("https://api.x/y", call.getFirstStringArg());
    }

    @Test
    @DisplayName("module-level calls are captured with <module> as enclosingFunction")
    void moduleLevelCallDetection() {
        String src = String.join("\n",
                "from django.urls import path",
                "from . import views",
                "",
                "urlpatterns = [",
                "    path(\"users/\", views.user_list),",
                "]",
                "");

        PyModule mod = parse(src);

        assertFalse(mod.getCalls().isEmpty(), "expected module-level calls to be captured");
        boolean found = mod.getCalls().stream()
                .anyMatch(c -> "path".equals(c.getCalleeExpression())
                        && "<module>".equals(c.getEnclosingFunction())
                        && "users/".equals(c.getFirstStringArg())
                        && "views.user_list".equals(c.getSecondPositionalArg()));
        assertTrue(found, "expected path('users/', views.user_list) at module level, got: " + mod.getCalls());
    }
}
