package com.huawei.hisi.knowledgegraph.python.model;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a Python import statement.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code import foo.bar} — moduleName="foo.bar", symbol=null, alias=null, fromImport=false, relativeLevel=0</li>
 *   <li>{@code import foo.bar as fb} — moduleName="foo.bar", symbol=null, alias="fb", fromImport=false, relativeLevel=0</li>
 *   <li>{@code from foo.bar import baz} — moduleName="foo.bar", symbol="baz", alias=null, fromImport=true, relativeLevel=0</li>
 *   <li>{@code from foo.bar import baz as qux} — moduleName="foo.bar", symbol="baz", alias="qux", fromImport=true, relativeLevel=0</li>
 *   <li>{@code from .views import user_list} — moduleName="views", symbol="user_list", fromImport=true, relativeLevel=1</li>
 *   <li>{@code from ..pkg import x} — moduleName="pkg", symbol="x", fromImport=true, relativeLevel=2</li>
 *   <li>{@code from . import views} — moduleName="", symbol="views", fromImport=true, relativeLevel=1</li>
 * </ul>
 */
@Value
@Builder
public class PyImport {
    String moduleName;
    String symbol;
    String alias;
    boolean fromImport;
    int lineNumber;
    /**
     * Number of leading dots in a {@code from ... import ...} statement.
     * 0 for absolute imports; 1 for {@code from .x}; 2 for {@code from ..x}; etc.
     */
    int relativeLevel;
}
