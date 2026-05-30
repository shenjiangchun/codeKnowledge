package com.huawei.hisi.service.semantic;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.BinaryExpr;
import com.github.javaparser.ast.expr.ConditionalExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.CatchClause;
import com.github.javaparser.ast.stmt.ForStmt;
import com.github.javaparser.ast.stmt.IfStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.github.javaparser.ast.stmt.SwitchEntry;
import com.github.javaparser.ast.stmt.SwitchStmt;
import com.github.javaparser.ast.stmt.WhileStmt;
import com.github.javaparser.ast.visitor.VoidVisitorAdapter;
import com.huawei.hisi.service.semantic.model.MethodCategory;
import com.huawei.hisi.service.semantic.model.MethodSemantic;
import com.huawei.hisi.service.semantic.model.MethodStructure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 代码语义解析引擎
 *
 * 负责使用JavaParser解析Java代码，提取方法结构信息，
 * 并调用LLM进行意图标注，构建完整的MethodSemantic对象
 */
@Slf4j
@Service
public class CodeSemanticParser {

    private final JavaParser javaParser = new JavaParser();

    /**
     * 解析单个方法的语义信息
     *
     * @param className  类名
     * @param methodName 方法名
     * @param sourceCode 方法源代码
     * @return 方法语义对象
     */
    public MethodSemantic parseMethodSemantic(String className, String methodName, String sourceCode) {
        long startTime = System.currentTimeMillis();
        log.info("开始解析方法语义: {}.{}", className, methodName);

        try {
            // 1. 静态解析（JavaParser AST）
            MethodStructure structure = parseMethodStructure(className, methodName, sourceCode);

            // 2. 构建语义对象（IntentAnnotator removed - using basic semantic)
            MethodSemantic semantic = buildBasicSemanticFromStructure(className, methodName,
                    structure, sourceCode);

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("方法语义解析完成: {}.{}, 耗时={}ms", className, methodName, elapsed);

            return semantic;

        } catch (Exception e) {
            log.error("方法语义解析失败: {}.{}, error={}", className, methodName, e.getMessage(), e);
            // 返回基础语义对象（不包含LLM标注）
            return buildBasicSemantic(className, methodName, sourceCode);
        }
    }

    /**
     * 解析整个Java文件的语义信息
     *
     * @param sourceCode 文件源代码
     * @return 所有方法的语义信息列表
     */
    public List<MethodSemantic> parseFileSemantics(String sourceCode) {
        long startTime = System.currentTimeMillis();
        List<MethodSemantic> semantics = new ArrayList<>();

        try {
            CompilationUnit cu = javaParser.parse(sourceCode).getResult().orElse(null);
            if (cu == null) {
                log.warn("解析文件失败");
                return semantics;
            }

            // 获取类名
            String className = cu.findFirst(ClassOrInterfaceDeclaration.class)
                    .map(c -> c.getNameAsString())
                    .orElse("UnknownClass");

            // 解析所有方法
            List<MethodDeclaration> methods = cu.findAll(MethodDeclaration.class);
            for (MethodDeclaration method : methods) {
                String methodName = method.getNameAsString();
                String methodCode = method.toString();

                MethodSemantic semantic = parseMethodSemantic(className, methodName, methodCode);
                semantics.add(semantic);
            }

            long elapsed = System.currentTimeMillis() - startTime;
            log.info("文件语义解析完成: 类={}, 方法数={}, 耗时={}ms", className, semantics.size(), elapsed);

        } catch (Exception e) {
            log.error("文件语义解析失败: {}", e.getMessage(), e);
        }

        return semantics;
    }

    /**
     * 解析方法结构信息（AST静态解析）
     */
    private MethodStructure parseMethodStructure(String className, String methodName, String sourceCode) {
        try {
            // 尝试解析为方法声明
            Optional<MethodDeclaration> methodDeclOpt = tryParseMethod(sourceCode);

            if (methodDeclOpt.isPresent()) {
                return extractStructureFromMethod(methodDeclOpt.get());
            }

            // 尝试解析为完整类，然后查找方法
            CompilationUnit cu = javaParser.parse(sourceCode).getResult().orElse(null);
            if (cu != null) {
                Optional<MethodDeclaration> foundMethod = cu.findFirst(MethodDeclaration.class,
                        m -> m.getNameAsString().equals(methodName));
                if (foundMethod.isPresent()) {
                    return extractStructureFromMethod(foundMethod.get());
                }
            }

            // 无法解析，返回默认结构
            return buildDefaultStructure(methodName, sourceCode);

        } catch (Exception e) {
            log.warn("结构解析失败: {}, error={}", methodName, e.getMessage());
            return buildDefaultStructure(methodName, sourceCode);
        }
    }

    /**
     * 尝试将源代码解析为方法声明
     */
    private Optional<MethodDeclaration> tryParseMethod(String sourceCode) {
        try {
            var result = javaParser.parseMethodDeclaration(sourceCode);
            if (result.isSuccessful() && result.getResult().isPresent()) {
                return result.getResult();
            }
        } catch (Exception e) {
            log.debug("无法直接解析为方法声明: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * 从MethodDeclaration提取结构信息
     */
    private MethodStructure extractStructureFromMethod(MethodDeclaration method) {
        // 提取签名
        String signature = buildSignature(method);

        // 提取参数
        List<MethodSemantic.ParameterInfo> parameters = new ArrayList<>();
        method.getParameters().forEach(param -> {
            MethodSemantic.ParameterInfo paramInfo = MethodSemantic.ParameterInfo.builder()
                    .name(param.getNameAsString())
                    .type(param.getTypeAsString())
                    .isGeneric(param.getType().isTypeParameter())
                    .build();
            parameters.add(paramInfo);
        });

        // 提取抛出异常
        List<String> exceptions = new ArrayList<>();
        method.getThrownExceptions().forEach(ex -> exceptions.add(ex.toString()));

        // 计算圈复杂度
        int complexity = calculateCyclomaticComplexity(method);

        // 提取方法调用
        List<MethodStructure.MethodCallInfo> methodCalls = new ArrayList<>();
        extractMethodCalls(method, methodCalls);

        // 提取注解
        List<String> annotations = new ArrayList<>();
        method.getAnnotations().forEach(ann -> annotations.add(ann.getNameAsString()));

        return MethodStructure.builder()
                .methodName(method.getNameAsString())
                .signature(signature)
                .returnType(method.getTypeAsString())
                .parameters(parameters)
                .thrownExceptions(exceptions)
                .cyclomaticComplexity(complexity)
                .bodyLineCount(getBodyLineCount(method))
                .methodCalls(methodCalls)
                .isStatic(method.isStatic())
                .isPublic(method.isPublic())
                .isConstructor(method.isConstructorDeclaration())
                .annotations(annotations)
                .build();
    }

    /**
     * 构建方法签名
     */
    private String buildSignature(MethodDeclaration method) {
        StringBuilder sb = new StringBuilder();
        sb.append(method.getNameAsString());
        sb.append("(");

        List<String> paramTypes = new ArrayList<>();
        method.getParameters().forEach(param -> paramTypes.add(param.getTypeAsString()));
        sb.append(String.join(", ", paramTypes));

        sb.append(")");

        if (!method.getType().isVoidType()) {
            sb.append(" : ").append(method.getTypeAsString());
        }

        return sb.toString();
    }

    /**
     * 计算圈复杂度
     *
     * 基于McCabe圈复杂度计算方法，统计以下控制流结构：
     * - if语句
     * - for循环
     * - while循环
     * - switch语句及case分支
     * - catch块
     * - 逻辑运算符 (&&, ||)
     * - 三元运算符
     *
     * @param method 方法声明
     * @return 圈复杂度值
     */
    private int calculateCyclomaticComplexity(MethodDeclaration method) {
        int complexity = 1; // 基础复杂度

        // 统计if语句
        complexity += method.findAll(IfStmt.class).size();

        // 统计for循环
        complexity += method.findAll(ForStmt.class).size();

        // 统计while循环
        complexity += method.findAll(WhileStmt.class).size();

        // 统计switch语句及case分支
        for (SwitchStmt switchStmt : method.findAll(SwitchStmt.class)) {
            // 每个switch语句本身增加1（switch入口）
            complexity += 1;
            // 每个case分支增加1（不包括default）
            for (SwitchEntry entry : switchStmt.getEntries()) {
                if (!entry.getLabels().isEmpty()) {
                    complexity += 1;
                }
            }
        }

        // 统计catch块
        complexity += method.findAll(CatchClause.class).size();

        // 统计逻辑运算符 (&&, ||)
        for (BinaryExpr binaryExpr : method.findAll(BinaryExpr.class)) {
            BinaryExpr.Operator op = binaryExpr.getOperator();
            if (op == BinaryExpr.Operator.AND || op == BinaryExpr.Operator.OR) {
                complexity += 1;
            }
        }

        // 统计三元运算符
        complexity += method.findAll(ConditionalExpr.class).size();

        return complexity;
    }

    /**
     * 提取方法内的方法调用
     */
    private void extractMethodCalls(MethodDeclaration method, List<MethodStructure.MethodCallInfo> methodCalls) {
        method.findAll(MethodCallExpr.class).forEach(call -> {
            String targetClass = "Unknown";
            try {
                // 尝试解析方法调用获取类名
                var resolved = call.resolve();
                targetClass = resolved.declaringType().getQualifiedName();
            } catch (Exception e) {
                // 解析失败时尝试从scope推断
                if (call.getScope().isPresent()) {
                    targetClass = call.getScope().get().toString();
                }
            }
            String targetMethod = call.getNameAsString();
            int lineNum = call.getRange().map(r -> r.begin.line).orElse(0);

            MethodStructure.MethodCallInfo callInfo = MethodStructure.MethodCallInfo.builder()
                    .targetClassName(targetClass)
                    .targetMethodName(targetMethod)
                    .lineNumber(lineNum)
                    .build();
            methodCalls.add(callInfo);
        });
    }

    /**
     * 获取方法体行数
     */
    private int getBodyLineCount(MethodDeclaration method) {
        Optional<BlockStmt> body = method.getBody();
        if (body.isPresent()) {
            return body.get().getRange().map(r -> r.end.line - r.begin.line + 1).orElse(0);
        }
        return 0;
    }

    /**
     * 构建默认结构信息（解析失败时使用）
     */
    private MethodStructure buildDefaultStructure(String methodName, String sourceCode) {
        return MethodStructure.builder()
                .methodName(methodName)
                .signature(methodName + "()")
                .returnType("void")
                .parameters(new ArrayList<>())
                .thrownExceptions(new ArrayList<>())
                .cyclomaticComplexity(1)
                .bodyLineCount(sourceCode.split("\n").length)
                .methodCalls(new ArrayList<>())
                .isStatic(false)
                .isPublic(true)
                .isConstructor(false)
                .annotations(new ArrayList<>())
                .build();
    }

    /**
     * 从结构信息构建基础语义对象（不含LLM标注）
     */
    private MethodSemantic buildBasicSemanticFromStructure(String className, String methodName,
                                               MethodStructure structure, String sourceCode) {
        List<String> calledMethods = new ArrayList<>();
        structure.getMethodCalls().forEach(call -> {
            calledMethods.add(call.getTargetClassName() + "#" + call.getTargetMethodName());
        });

        return MethodSemantic.builder()
                .className(className)
                .methodName(methodName)
                .signature(structure.getSignature())
                .parameters(structure.getParameters())
                .returnType(structure.getReturnType())
                .intent("")
                .exceptions(structure.getThrownExceptions())
                .complexity(structure.getCyclomaticComplexity())
                .keywords(new ArrayList<>())
                .category(MethodCategory.OTHER)
                .sourceCode(sourceCode)
                .calledMethods(calledMethods)
                .callingMethods(new ArrayList<>())
                .build();
    }

    /**
     * 构建基础语义对象（解析失败时使用）
     */
    private MethodSemantic buildBasicSemantic(String className, String methodName, String sourceCode) {
        return MethodSemantic.builder()
                .className(className)
                .methodName(methodName)
                .signature(methodName + "()")
                .parameters(new ArrayList<>())
                .returnType("void")
                .intent("解析失败")
                .exceptions(new ArrayList<>())
                .complexity(1)
                .keywords(new ArrayList<>())
                .category(MethodCategory.OTHER)
                .sourceCode(sourceCode)
                .calledMethods(new ArrayList<>())
                .callingMethods(new ArrayList<>())
                .build();
    }

    /**
     * 批量解析方法语义
     *
     * @param methods 方法信息列表（className, methodName, sourceCode）
     * @return 语义对象列表
     */
    public List<MethodSemantic> batchParse(List<MethodInput> methods) {
        List<MethodSemantic> results = new ArrayList<>();
        for (MethodInput input : methods) {
            MethodSemantic semantic = parseMethodSemantic(
                    input.getClassName(),
                    input.getMethodName(),
                    input.getSourceCode()
            );
            results.add(semantic);
        }
        return results;
    }

    /**
     * 方法输入信息
     */
    public static class MethodInput {
        private final String className;
        private final String methodName;
        private final String sourceCode;

        public MethodInput(String className, String methodName, String sourceCode) {
            this.className = className;
            this.methodName = methodName;
            this.sourceCode = sourceCode;
        }

        public String getClassName() { return className; }
        public String getMethodName() { return methodName; }
        public String getSourceCode() { return sourceCode; }
    }
}