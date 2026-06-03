package com.huawei.hisi.knowledgegraph.python;

import com.huawei.hisi.knowledgegraph.python.parser.Python3Lexer;
import com.huawei.hisi.knowledgegraph.python.parser.Python3Parser;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NormalizeFstringsTest {

    @Test
    void pep701_doubleQuoteInDoubleQuotedFstring() {
        String input = "logger.info(f\"notify_id: {msg.get(\"id\")} done\")";
        String result = PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input);
        assertThat(result).isEqualTo("logger.info(f\"notify_id: {msg.get('id')} done\")");
    }

    @Test
    void normalString_unchanged() {
        String input = "x = \"hello world\"";
        assertThat(PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input)).isEqualTo(input);
    }

    @Test
    void fstringWithoutInnerQuotes_unchanged() {
        String input = "f\"value={x}\"";
        assertThat(PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input)).isEqualTo(input);
    }

    @Test
    void singleQuotedFstring_withInnerDoubleQuotes_unchanged() {
        // Different quote type inside is valid for the grammar — no normalization needed
        String input = "f'key={d[\"id\"]}'";
        String result = PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input);
        assertThat(result).isEqualTo(input);
    }

    @Test
    void rawFstring_alsoNormalized() {
        String input = "rf\"path={get(\"home\")}\"";
        String result = PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input);
        assertThat(result).isEqualTo("rf\"path={get('home')}\"");
    }

    @Test
    void multipleFstringsInOneLine() {
        String input = "f\"{a[\"x\"]}\" + f\"{b[\"y\"]}\"";
        String result = PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input);
        assertThat(result).isEqualTo("f\"{a['x']}\" + f\"{b['y']}\"");
    }

    @Test
    void tripleQuotedFstring() {
        String input = "f\"\"\"result={d[\"key\"]}\"\"\"";
        String result = PythonKnowledgeGraphBuilder.normalizeFstringsForParser(input);
        assertThat(result).isEqualTo("f\"\"\"result={d['key']}\"\"\"");
    }

    @Test
    void pep701_fstring_parsesAfterNormalization() {
        // The actual problematic line from notification.py
        String source = """
            def broadcast(user_id):
                logger.info(f"✅ 用户 {user_id}，notify_id: {msg.get("id")} 消息已标记为已读")
            """;
        String normalized = PythonKnowledgeGraphBuilder.normalizeFstringsForParser(source);
        var lexer = new Python3Lexer(CharStreams.fromString(normalized));
        var parser = new Python3Parser(new CommonTokenStream(lexer));
        parser.file_input();
        assertThat(parser.getNumberOfSyntaxErrors()).isZero();
    }
}
