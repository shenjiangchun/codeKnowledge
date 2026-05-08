package com.huawei.hisi.knowledgegraph.python.model;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a Python import statement.
 *
 * <p>Examples:
 * <ul>
 *   <li>{@code import foo.bar} — moduleName="foo.bar", symbol=null, alias=null, fromImport=false</li>
 *   <li>{@code import foo.bar as fb} — moduleName="foo.bar", symbol=null, alias="fb", fromImport=false</li>
 *   <li>{@code from foo.bar import baz} — moduleName="foo.bar", symbol="baz", alias=null, fromImport=true</li>
 *   <li>{@code from foo.bar import baz as qux} — moduleName="foo.bar", symbol="baz", alias="qux", fromImport=true</li>
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
}
