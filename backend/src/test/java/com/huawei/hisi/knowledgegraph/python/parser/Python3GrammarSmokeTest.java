package com.huawei.hisi.knowledgegraph.python.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test verifying the ANTLR4 Python3 grammar wires up correctly during the
 * Maven build and can parse a trivial Python source snippet end-to-end.
 */
class Python3GrammarSmokeTest {

    @Test
    @DisplayName("Python3 grammar parses a simple function definition")
    void grammar_parsesSimpleFunction() {
        String src = "def foo(x):\n    return x + 1\n";
        CharStream input = CharStreams.fromString(src);
        Python3Lexer lexer = new Python3Lexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        Python3Parser parser = new Python3Parser(tokens);

        ParseTree tree = parser.file_input();

        assertNotNull(tree, "parse tree should not be null");
        assertTrue(parser.getNumberOfSyntaxErrors() == 0,
                "expected zero syntax errors, got " + parser.getNumberOfSyntaxErrors());
    }
}
