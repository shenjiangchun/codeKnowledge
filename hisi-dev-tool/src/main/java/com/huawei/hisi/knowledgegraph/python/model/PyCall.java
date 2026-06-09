package com.huawei.hisi.knowledgegraph.python.model;

import lombok.Builder;
import lombok.Value;

/**
 * Represents a call site (function/method invocation) within a Python source file.
 *
 * <p>{@code calleeExpression} is the raw textual expression that precedes the
 * argument list, e.g. {@code self.foo}, {@code requests.get}, {@code users.create}.
 * Resolution to a target function is intentionally deferred to a later phase.
 *
 * <p>{@code firstStringArg} captures the first positional string-literal argument,
 * if any, with surrounding quotes stripped. This is used by downstream scanners
 * (e.g. {@code PythonHttpCallScanner}) to extract URL targets without re-parsing
 * the source. Null when the first argument is missing or is not a string literal.
 *
 * <p>{@code secondPositionalArg} captures the raw textual form of the SECOND
 * positional argument (NOT kwargs). Used to extract the view callable from
 * Django {@code path('users/', views.user_list)} URL registrations, where the
 * first positional arg is the URL pattern and the second is the view function
 * / class-based-view expression (possibly {@code .as_view()}). Null when no
 * second positional arg exists.
 */
@Value
@Builder
public class PyCall {
    String calleeExpression;
    int lineNumber;
    String enclosingFunction;
    String firstStringArg;
    String secondPositionalArg;
    boolean inMainBlock;
}
