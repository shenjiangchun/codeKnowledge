package com.huawei.hisi.knowledgegraph.python;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class JoinImplicitLineContinuationsTest {

    @Test
    void bracketAcrossLine_joinedIntoOneLine() {
        // The exact pattern from common_tools.py line 176-177
        String input = "    result[\n        \"key\"] = f\"value={x.get('name')}\"\n";
        String result = PythonKnowledgeGraphBuilder.joinImplicitLineContinuations(input);
        assertThat(result).isEqualTo("    result[ \"key\"] = f\"value={x.get('name')}\"\n");
    }

    @Test
    void noOpenBrackets_newlinesPreserved() {
        String input = "x = 1\ny = 2\n";
        String result = PythonKnowledgeGraphBuilder.joinImplicitLineContinuations(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    void parenthesesAcrossMultipleLines() {
        String input = "func(\n    arg1,\n    arg2\n)\n";
        String result = PythonKnowledgeGraphBuilder.joinImplicitLineContinuations(input);
        assertThat(result).isEqualTo("func( arg1, arg2 )\n");
    }

    @Test
    void stringLiteralBracketsNotCounted() {
        String input = "x = \"hello[world]\"\ny = 1\n";
        String result = PythonKnowledgeGraphBuilder.joinImplicitLineContinuations(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    void nestedBrackets() {
        String input = "result[\n    dict[\n        \"key\"\n    ]\n]\n";
        String result = PythonKnowledgeGraphBuilder.joinImplicitLineContinuations(input);
        assertThat(result).isEqualTo("result[ dict[ \"key\" ] ]\n");
    }
}
