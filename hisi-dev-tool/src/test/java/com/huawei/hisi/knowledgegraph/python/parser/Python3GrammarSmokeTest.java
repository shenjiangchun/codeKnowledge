package com.huawei.hisi.knowledgegraph.python.parser;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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

    private void assertParses(String src) {
        CharStream input = CharStreams.fromString(src);
        Python3Lexer lexer = new Python3Lexer(input);
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        Python3Parser parser = new Python3Parser(tokens);
        ParseTree tree = parser.file_input();
        assertNotNull(tree);
        assertThat(parser.getNumberOfSyntaxErrors())
                .as("syntax errors for:\n%s", src)
                .isZero();
    }

    static Stream<Arguments> python312Features() {
        return Stream.of(
                // 3.8: walrus operator
                Arguments.of("walrus operator",
                        "if (n := len(data)) > 10:\n    print(n)\n"),
                // 3.8: positional-only params
                Arguments.of("positional-only params",
                        "def foo(a, b, /, c, d):\n    return a + b + c + d\n"),
                // 3.10: parenthesized with
                Arguments.of("parenthesized with",
                        "with (\n    open('a') as f1,\n    open('b') as f2,\n):\n    pass\n"),
                // 3.10: match/case
                Arguments.of("match/case",
                        "match command:\n    case 'quit':\n        quit_game()\n    case 'go' | 'move':\n        do_move()\n"),
                // 3.11: except*
                Arguments.of("except star",
                        "try:\n    pass\nexcept* ValueError as eg:\n    print(eg)\nexcept* TypeError:\n    pass\n"),
                // 3.12: type statement
                Arguments.of("type statement",
                        "type Point = tuple[int, int]\n"),
                // 3.12: type statement with params
                Arguments.of("type statement with params",
                        "type Vector[T] = list[T]\n"),
                // 3.12: generic function
                Arguments.of("generic function",
                        "def first[T](items: list[T]) -> T:\n    return items[0]\n"),
                // 3.12: generic class
                Arguments.of("generic class",
                        "class Stack[T]:\n    def push(self, item: T) -> None:\n        pass\n"),
                // soft keywords as identifiers
                Arguments.of("soft keywords as identifiers",
                        "type = 'hello'\nmatch = 42\ncase = True\n"),
                // combined: decorated generic
                Arguments.of("decorated generic class",
                        "@dataclass\nclass Pair[T, U]:\n    first: T\n    second: U\n")
        );
    }

    @ParameterizedTest(name = "Python 3.8-3.12: {0}")
    @MethodSource("python312Features")
    @DisplayName("Python 3.8-3.12 syntax features parse without errors")
    void grammar_parsesModernPython(String featureName, String src) {
        assertParses(src);
    }
}
