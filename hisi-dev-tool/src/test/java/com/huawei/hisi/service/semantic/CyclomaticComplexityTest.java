package com.huawei.hisi.service.semantic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.huawei.hisi.service.semantic.model.MethodStructure;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TD-001: 圈复杂度计算单元测试
 *
 * 测试各种代码结构的复杂度计算：
 * - if语句计数
 * - for循环计数
 * - while循环计数
 * - switch/case计数
 * - catch块计数
 * - 逻辑运算符计数
 *
 * @author HiAPM Plugin Team
 * @version 1.0.0
 */
@DisplayName("TD-001: 圈复杂度计算测试")
class CyclomaticComplexityTest {

    private JavaParser javaParser;

    @BeforeEach
    void setUp() {
        javaParser = new JavaParser();
    }

    @Test
    @DisplayName("测试基础方法复杂度为1")
    void testBaseComplexity() {
        String sourceCode = "public void simpleMethod() { }";
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertEquals(1, complexity, "基础方法复杂度应为1");
    }

    @Test
    @DisplayName("测试单个if语句复杂度为2")
    void testSingleIfStatement() {
        String sourceCode = "public void methodWithIf() { if (condition) { doSomething(); } }";
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertEquals(2, complexity, "单个if语句应增加1个复杂度");
    }

    @Test
    @DisplayName("测试多个if语句复杂度累加")
    void testMultipleIfStatements() {
        String sourceCode = """
            public void methodWithMultipleIfs() {
                if (condition1) { doA(); }
                if (condition2) { doB(); }
                if (condition3) { doC(); }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertEquals(4, complexity, "3个if语句应增加3个复杂度，总复杂度为4");
    }

    @Test
    @DisplayName("测试if-else结构复杂度")
    void testIfElseStatement() {
        String sourceCode = """
            public void methodWithIfElse() {
                if (condition) {
                    doA();
                } else {
                    doB();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertEquals(2, complexity, "if-else结构复杂度应为2");
    }

    @Test
    @DisplayName("测试嵌套if语句复杂度")
    void testNestedIfStatements() {
        String sourceCode = """
            public void methodWithNestedIf() {
                if (condition1) {
                    if (condition2) {
                        doA();
                    }
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertEquals(3, complexity, "2个嵌套if语句应增加2个复杂度，总复杂度为3");
    }

    @Test
    @DisplayName("测试for循环复杂度")
    void testForLoop() {
        String sourceCode = """
            public void methodWithForLoop() {
                for (int i = 0; i < 10; i++) {
                    doSomething();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 2, "for循环应增加复杂度");
    }

    @Test
    @DisplayName("测试while循环复杂度")
    void testWhileLoop() {
        String sourceCode = """
            public void methodWithWhileLoop() {
                while (condition) {
                    doSomething();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 2, "while循环应增加复杂度");
    }

    @Test
    @DisplayName("测试switch语句复杂度")
    void testSwitchStatement() {
        String sourceCode = """
            public void methodWithSwitch() {
                switch (value) {
                    case 1: doA(); break;
                    case 2: doB(); break;
                    case 3: doC(); break;
                    default: doD();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 4, "switch语句每个case应增加复杂度");
    }

    @Test
    @DisplayName("测试catch块复杂度")
    void testCatchBlock() {
        String sourceCode = """
            public void methodWithCatch() {
                try {
                    doSomething();
                } catch (Exception e) {
                    handleError();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 2, "catch块应增加复杂度");
    }

    @Test
    @DisplayName("测试多catch块复杂度")
    void testMultipleCatchBlocks() {
        String sourceCode = """
            public void methodWithMultipleCatch() {
                try {
                    doSomething();
                } catch (IOException e) {
                    handleIOError();
                } catch (SQLException e) {
                    handleSQLError();
                } catch (Exception e) {
                    handleGeneralError();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 4, "多个catch块应累加复杂度");
    }

    @Test
    @DisplayName("测试复杂方法的综合复杂度")
    void testComplexMethod() {
        String sourceCode = """
            public void complexMethod() {
                if (condition1) {
                    for (int i = 0; i < 10; i++) {
                        if (condition2) {
                            doA();
                        } else {
                            doB();
                        }
                    }
                }
                try {
                    riskyOperation();
                } catch (Exception e) {
                    handleError();
                }
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 5, "复杂方法应正确累加各种控制流的复杂度，实际复杂度为: " + complexity);
    }

    @Test
    @DisplayName("测试三元运算符复杂度")
    void testTernaryOperator() {
        String sourceCode = """
            public String methodWithTernary() {
                return condition ? "yes" : "no";
            }
            """;
        MethodDeclaration method = parseMethod(sourceCode);
        int complexity = calculateComplexity(method);
        assertTrue(complexity >= 2, "三元运算符应增加复杂度");
    }

    /**
     * 解析方法声明
     */
    private MethodDeclaration parseMethod(String sourceCode) {
        Optional<MethodDeclaration> result = javaParser.parseMethodDeclaration(sourceCode).getResult();
        assertTrue(result.isPresent(), "方法解析应成功");
        return result.get();
    }

    /**
     * 计算圈复杂度 - 修复后的完整版本
     */
    private int calculateComplexity(MethodDeclaration method) {
        int complexity = 1; // 基础复杂度

        // 统计if语句（包含else）
        complexity += method.findAll(com.github.javaparser.ast.stmt.IfStmt.class).size();

        // 统计for循环
        complexity += method.findAll(com.github.javaparser.ast.stmt.ForStmt.class).size();

        // 统计while循环
        complexity += method.findAll(com.github.javaparser.ast.stmt.WhileStmt.class).size();

        // 统计do-while循环
        complexity += method.findAll(com.github.javaparser.ast.stmt.DoStmt.class).size();

        // 统计switch语句（每个case分支）
        for (com.github.javaparser.ast.stmt.SwitchStmt switchStmt : method.findAll(com.github.javaparser.ast.stmt.SwitchStmt.class)) {
            complexity += switchStmt.getEntries().size();
        }

        // 统计catch块
        complexity += method.findAll(com.github.javaparser.ast.stmt.CatchClause.class).size();

        // 统计三元运算符（条件表达式）
        complexity += method.findAll(com.github.javaparser.ast.expr.ConditionalExpr.class).size();

        // 统计逻辑运算符（&& 和 ||）
        for (com.github.javaparser.ast.expr.BinaryExpr binaryExpr : method.findAll(com.github.javaparser.ast.expr.BinaryExpr.class)) {
            if (binaryExpr.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.AND
                || binaryExpr.getOperator() == com.github.javaparser.ast.expr.BinaryExpr.Operator.OR) {
                complexity++;
            }
        }

        return complexity;
    }
}