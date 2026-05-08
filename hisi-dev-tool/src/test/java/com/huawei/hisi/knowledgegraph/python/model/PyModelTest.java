package com.huawei.hisi.knowledgegraph.python.model;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PyModelTest {

    @Test
    @DisplayName("PyImport builder produces correct immutable instance")
    void pyImport_builderRoundTrip() {
        PyImport imp = PyImport.builder()
                .moduleName("foo.bar")
                .symbol("baz")
                .alias("qux")
                .fromImport(true)
                .lineNumber(5)
                .build();

        assertEquals("foo.bar", imp.getModuleName());
        assertEquals("baz", imp.getSymbol());
        assertEquals("qux", imp.getAlias());
        assertTrue(imp.isFromImport());
        assertEquals(5, imp.getLineNumber());
    }

    @Test
    @DisplayName("PyFunction builder returns correct values with unmodifiable collections")
    void pyFunction_builderRoundTrip() {
        PyFunction fn = PyFunction.builder()
                .name("do_stuff")
                .qualName("MyClass.do_stuff")
                .paramNames(List.of("self", "x"))
                .decorators(List.of("staticmethod"))
                .lineStart(10)
                .lineEnd(20)
                .isMethod(true)
                .enclosingClass("MyClass")
                .build();

        assertEquals("do_stuff", fn.getName());
        assertEquals("MyClass.do_stuff", fn.getQualName());
        assertEquals(List.of("self", "x"), fn.getParamNames());
        assertEquals(List.of("staticmethod"), fn.getDecorators());
        assertEquals(10, fn.getLineStart());
        assertEquals(20, fn.getLineEnd());
        assertTrue(fn.isMethod());
        assertEquals("MyClass", fn.getEnclosingClass());
        assertThrows(UnsupportedOperationException.class, () -> fn.getParamNames().add("y"));
        assertThrows(UnsupportedOperationException.class, () -> fn.getDecorators().add("x"));
    }

    @Test
    @DisplayName("PyFunction with null collections defaults to empty unmodifiable lists")
    void pyFunction_nullCollectionsDefaultEmpty() {
        PyFunction fn = PyFunction.builder()
                .name("bare")
                .qualName("bare")
                .lineStart(1)
                .lineEnd(1)
                .build();

        assertTrue(fn.getParamNames().isEmpty());
        assertTrue(fn.getDecorators().isEmpty());
        assertFalse(fn.isMethod());
        assertNull(fn.getEnclosingClass());
    }

    @Test
    @DisplayName("PyClass builder returns correct values with unmodifiable collections")
    void pyClass_builderRoundTrip() {
        PyFunction method = PyFunction.builder()
                .name("run")
                .qualName("Base.run")
                .lineStart(5)
                .lineEnd(8)
                .isMethod(true)
                .enclosingClass("Base")
                .build();

        PyClass cls = PyClass.builder()
                .name("Base")
                .baseClasses(List.of("object"))
                .decorators(List.of("dataclass"))
                .methods(List.of(method))
                .lineStart(3)
                .lineEnd(10)
                .build();

        assertEquals("Base", cls.getName());
        assertEquals(List.of("object"), cls.getBaseClasses());
        assertEquals(List.of("dataclass"), cls.getDecorators());
        assertEquals(1, cls.getMethods().size());
        assertThrows(UnsupportedOperationException.class, () -> cls.getBaseClasses().add("x"));
        assertThrows(UnsupportedOperationException.class, () -> cls.getMethods().add(method));
    }

    @Test
    @DisplayName("PyCall builder produces correct immutable instance")
    void pyCall_builderRoundTrip() {
        PyCall call = PyCall.builder()
                .calleeExpression("requests.get")
                .lineNumber(42)
                .enclosingFunction("fetch_data")
                .firstStringArg("http://example.com")
                .build();

        assertEquals("requests.get", call.getCalleeExpression());
        assertEquals(42, call.getLineNumber());
        assertEquals("fetch_data", call.getEnclosingFunction());
        assertEquals("http://example.com", call.getFirstStringArg());
    }

    @Test
    @DisplayName("PyCall firstStringArg defaults to null when not set")
    void pyCall_firstStringArgDefaultsToNull() {
        PyCall call = PyCall.builder()
                .calleeExpression("foo.bar")
                .lineNumber(1)
                .enclosingFunction("main")
                .build();

        assertNull(call.getFirstStringArg());
    }

    @Test
    @DisplayName("PyModule builder returns correct values with unmodifiable collections")
    void pyModule_builderRoundTrip() {
        PyImport imp = PyImport.builder().moduleName("os").lineNumber(1).build();
        PyModule mod = PyModule.builder()
                .filePath("/tmp/x.py")
                .modulePath("x")
                .imports(List.of(imp))
                .classes(List.of())
                .topLevelFunctions(List.of())
                .calls(List.of())
                .build();

        assertEquals("/tmp/x.py", mod.getFilePath());
        assertEquals("x", mod.getModulePath());
        assertEquals(1, mod.getImports().size());
        assertThrows(UnsupportedOperationException.class, () -> mod.getImports().add(imp));
        assertThrows(UnsupportedOperationException.class, () -> mod.getClasses().add(null));
    }
}
