package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.neo4j.model.QueryType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 查询类型检测器
 * 基于多规则评分机制识别查询类型，支持 9 种查询类型
 *
 * 每种类型有多个规则打分，取得分最高的类型，避免单一正则误判
 */
@Component
public class QueryTypeDetector {

    private static final Logger log = LoggerFactory.getLogger(QueryTypeDetector.class);

    /**
     * 评分结果最小分数阈值
     * 所有类型的最高得分都低于此阈值时，使用 NATURAL_LANGUAGE 兜底
     */
    private static final int MIN_SCORE_THRESHOLD = 4;

    /**
     * HTTP 方法前缀
     */
    private static final Pattern HTTP_METHOD_PREFIX = Pattern.compile(
            "^(GET|POST|PUT|DELETE|PATCH|HEAD|OPTIONS)\\s+/.*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 路径开头（HTTP URI 中特征）
     */
    private static final Pattern PATH_PREFIX = Pattern.compile("^/\\S+");

    /**
     * SQL 关键字开头（带空格）
     */
    private static final Pattern SQL_KEYWORD_PREFIX = Pattern.compile(
            "^(SELECT|INSERT|UPDATE|DELETE|WITH|MERGE)\\s+.*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * SQL 关键字开头（不跟空格，如 selectById）
     */
    private static final Pattern SQL_KEYWORD_NO_SPACE = Pattern.compile(
            "^(select|insert|update|delete|get|find|save|query|count|exists)\\p{Upper}.*",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 全限定名模式：包名.类名.方法名（至少3个点分隔组件+类名+方法名）
     */
    private static final Pattern FULL_QUALIFIED_NAME_STRONG = Pattern.compile(
            "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){2,}\\.[A-Z][a-zA-Z0-9]*\\.[a-z][a-zA-Z0-9]*$"
    );

    /**
     * 包名.类名.方法名（中等特征）
     */
    private static final Pattern FULL_QUALIFIED_NAME_MEDIUM = Pattern.compile(
            "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+\\.[A-Z][a-zA-Z0-9]*\\.[a-z][a-zA-Z0-9]*$"
    );

    /**
     * Python 全限定名：全小写蛇形 + 至少 3 段（module.submodule.function）
     * 例如：app.api.users.get_user, services.auth.login
     */
    private static final Pattern PYTHON_FQN_PATTERN = Pattern.compile(
            "^[a-z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*){2,}$"
    );

    /**
     * 全限定类名（只到类，不含方法）：包名.类名
     * 例如 com.huawei.hisi.agent.controller.DiagnosisController
     * 强特征：至少 3 层包 + 类名
     */
    private static final Pattern FULL_QUALIFIED_CLASS_NAME_STRONG = Pattern.compile(
            "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*){2,}\\.[A-Z][a-zA-Z0-9]*$"
    );

    /**
     * 全限定类名（中等）：包名.类名（≥1 层包）
     */
    private static final Pattern FULL_QUALIFIED_CLASS_NAME_MEDIUM = Pattern.compile(
            "^[a-z][a-z0-9]*(\\.[a-z][a-z0-9]*)+\\.[A-Z][a-zA-Z0-9]*$"
    );

    /**
     * 注解格式 @Xxx (Java) 或装饰器 @app.get / @router.post("/x") / @shared_task / @login_required (Python)
     * 支持：大写或小写开头、点分隔多段、可选括号参数。
     */
    private static final Pattern ANNOTATION_PATTERN = Pattern.compile(
            "^@[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*(\\(.*\\))?$"
    );

    /**
     * 异常类型模式：大写字母开头，以 Exception 或 Error 结尾
     */
    private static final Pattern EXCEPTION_TYPE_PATTERN = Pattern.compile(
            "^[A-Z][a-zA-Z0-9]*(Exception|Error|Fault|Failure)$"
    );

    /**
     * 类名模式：大写字母开头含驼峰
     */
    private static final Pattern CLASS_NAME_PATTERN = Pattern.compile(
            "^[A-Z][a-zA-Z0-9]*$"
    );

    /**
     * ClassName.methodName 模式
     */
    private static final Pattern CLASS_DOT_METHOD_PATTERN = Pattern.compile(
            "^[A-Z][a-zA-Z0-9]*\\.[a-z][a-zA-Z0-9]*$"
    );

    /**
     * 方法名模式：小写开头驼峰
     */
    private static final Pattern METHOD_NAME_PATTERN = Pattern.compile(
            "^[a-z][a-zA-Z0-9]*$"
    );

    /**
     * 中文检测
     */
    private static final Pattern CHINESE_PATTERN = Pattern.compile(
            "[\\u4e00-\\u9fff]"
    );

    /**
     * Java 代码关键字
     */
    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "return", "if", "else", "for", "while", "switch", "case",
            "try", "catch", "finally", "throw", "throws", "new", "null",
            "true", "false", "void", "int", "long", "double", "float",
            "boolean", "String", "List", "Map", "Set", "import", "class",
            "interface", "extends", "implements", "public", "private",
            "protected", "static", "final", "abstract", "synchronized"
    );

    /**
     * 检测查询类型
     * 使用多规则评分机制，每种类型有多个规则打分，取得分最高的类型
     *
     * @param query 用户查询文本
     * @return 检测到的查询类型
     */
    public QueryType detect(String query) {
        if (query == null || query.trim().isEmpty()) {
            return QueryType.NATURAL_LANGUAGE;
        }

        String trimmedQuery = query.trim();
        Map<QueryType, Integer> scores = new EnumMap<>(QueryType.class);

        // 计算每种类型的得分
        scores.put(QueryType.HTTP_URI, scoreHttpUri(trimmedQuery));
        scores.put(QueryType.SQL_SNIPPET, scoreSqlSnippet(trimmedQuery));
        scores.put(QueryType.FULL_QUALIFIED_NAME, scoreFullQualifiedName(trimmedQuery));
        scores.put(QueryType.FULL_QUALIFIED_CLASS_NAME, scoreFullQualifiedClassName(trimmedQuery));
        scores.put(QueryType.ANNOTATION, scoreAnnotation(trimmedQuery));
        scores.put(QueryType.EXCEPTION_TYPE, scoreExceptionType(trimmedQuery));
        scores.put(QueryType.CLASS_NAME, scoreClassName(trimmedQuery));
        scores.put(QueryType.CODE_SNIPPET, scoreCodeSnippet(trimmedQuery));
        scores.put(QueryType.METHOD_NAME, scoreMethodName(trimmedQuery));
        scores.put(QueryType.NATURAL_LANGUAGE, scoreNaturalLanguage(trimmedQuery));

        // 找到最高得分的类型
        QueryType bestType = QueryType.NATURAL_LANGUAGE;
        int bestScore = 0;

        for (Map.Entry<QueryType, Integer> entry : scores.entrySet()) {
            if (entry.getValue() > bestScore) {
                bestScore = entry.getValue();
                bestType = entry.getKey();
            }
        }

        // 如果最高得分低于阈值，使用 NATURAL_LANGUAGE 兜底
        if (bestScore < MIN_SCORE_THRESHOLD) {
            bestType = QueryType.NATURAL_LANGUAGE;
        }

        log.debug("[QueryTypeDetector] query='{}', scores={}, bestType={}, bestScore={}",
                truncate(trimmedQuery, 30), scores, bestType, bestScore);

        return bestType;
    }

    /**
     * HTTP_URI 评分
     * - 强特征(20分): 以 HTTP方法+空格+/开头
     * - 中特征(10分): 以/开头含路径
     */
    private int scoreHttpUri(String query) {
        int score = 0;

        if (HTTP_METHOD_PREFIX.matcher(query).matches()) {
            score += 20;
        } else if (PATH_PREFIX.matcher(query).matches() && query.contains("/")) {
            score += 10;
        }

        return score;
    }

    /**
     * SQL_SNIPPET 评分
     * - 强特征(15分): 以SQL关键字+空格开头
     * - 排除: selectById这种不跟空格的方法名(给METHOD_NAME 10分)
     */
    private int scoreSqlSnippet(String query) {
        int score = 0;

        if (SQL_KEYWORD_PREFIX.matcher(query).matches()) {
            score += 15;
        }

        return score;
    }

    /**
     * FULL_QUALIFIED_NAME 评分
     * - 强特征(20分): 含3+点分隔组件+类名+方法名（Java 风格）
     * - 中特征(12分): 包名.类名.方法名
     * - Python 风格(18分): module.submodule.function 全小写蛇形（≥3 段）
     */
    private int scoreFullQualifiedName(String query) {
        int score = 0;

        if (FULL_QUALIFIED_NAME_STRONG.matcher(query).matches()) {
            score += 20;
        } else if (FULL_QUALIFIED_NAME_MEDIUM.matcher(query).matches()) {
            score += 12;
        } else if (PYTHON_FQN_PATTERN.matcher(query).matches()) {
            // Python FQN: app.api.users.get_user
            score += 18;
        }

        return score;
    }

    /**
     * FULL_QUALIFIED_CLASS_NAME 评分
     * - 强特征(22分): 包名≥3层+类名（末段大写）。比 FQN 方法名(20) 略高
     *   — 两个模式互斥（末段大小写不同），但用 22 保证 tie-break。
     * - 中特征(13分): 包名≥1层+类名
     */
    private int scoreFullQualifiedClassName(String query) {
        int score = 0;

        if (FULL_QUALIFIED_CLASS_NAME_STRONG.matcher(query).matches()) {
            score += 22;
        } else if (FULL_QUALIFIED_CLASS_NAME_MEDIUM.matcher(query).matches()) {
            score += 13;
        }

        return score;
    }

    /**
     * ANNOTATION 评分
     * - 强特征(20分): @Xxx格式
     */
    private int scoreAnnotation(String query) {
        int score = 0;

        if (ANNOTATION_PATTERN.matcher(query).matches()) {
            score += 20;
        }

        return score;
    }

    /**
     * EXCEPTION_TYPE 评分
     * - 强特征(15分): 以大写字母开头，含Exception/Error结尾
     */
    private int scoreExceptionType(String query) {
        int score = 0;

        if (EXCEPTION_TYPE_PATTERN.matcher(query).matches()) {
            score += 15;
        }

        return score;
    }

    /**
     * CLASS_NAME 评分
     * - 特征(12分): 以大写字母开头含驼峰
     * - 排除异常(-5分): 以Exception/Error结尾时不认为是类名
     */
    private int scoreClassName(String query) {
        int score = 0;

        if (CLASS_NAME_PATTERN.matcher(query).matches()) {
            score += 12;
            // 排除异常类型
            if (query.endsWith("Exception") || query.endsWith("Error")
                    || query.endsWith("Fault") || query.endsWith("Failure")) {
                score -= 5;
            }
        }

        return score;
    }

    /**
     * CODE_SNIPPET 评分
     * - 多代码特征累加:
     *   括号+3分，花括号+2分，分号+2分，关键字+3分，箭头+2分
     *   总分>=4时生效
     */
    private int scoreCodeSnippet(String query) {
        int score = 0;

        // 包含括号
        if (query.contains("(") || query.contains(")")) {
            score += 3;
        }

        // 包含花括号
        if (query.contains("{") || query.contains("}")) {
            score += 2;
        }

        // 包含分号
        if (query.contains(";")) {
            score += 2;
        }

        // 包含Java关键字
        String[] tokens = query.split("[\\s(){};.,<>=+\\-*/&|!\\[\\]]+");
        for (String token : tokens) {
            if (JAVA_KEYWORDS.contains(token)) {
                score += 3;
                break; // 只加一次
            }
        }

        // 包含箭头 (lambda)
        if (query.contains("->")) {
            score += 2;
        }

        // 包含赋值操作
        if (query.contains("=") && !query.contains("==")) {
            score += 1;
        }

        // 包含点号调用
        if (query.contains(".")) {
            score += 1;
        }

        // 总分低于4时认为不是代码片段
        if (score < 4) {
            return 0;
        }

        return score;
    }

    /**
     * METHOD_NAME 评分
     * - 特征(10分): 小写开头驼峰
     * - ClassName.methodName(12分)
     * - 排除: selectById这种SQL关键字开头但不跟空格的方法名，给 METHOD_NAME 10分
     */
    private int scoreMethodName(String query) {
        int score = 0;

        if (CLASS_DOT_METHOD_PATTERN.matcher(query).matches()) {
            score += 12;
        } else if (METHOD_NAME_PATTERN.matcher(query).matches()) {
            score += 10;
            // SQL关键字开头但不跟空格（如 selectById），给 METHOD_NAME 10分而不是 SQL
        } else if (SQL_KEYWORD_NO_SPACE.matcher(query).matches()) {
            score += 10;
        }

        return score;
    }

    /**
     * NATURAL_LANGUAGE 评分 (兜底)
     * - 特征(8分): 含中文或空格分隔3+词
     */
    private int scoreNaturalLanguage(String query) {
        int score = 0;

        // 含中文
        if (CHINESE_PATTERN.matcher(query).find()) {
            score += 8;
        }

        // 空格分隔3+词
        String[] words = query.split("\\s+");
        if (words.length >= 3) {
            score += 8;
        }

        return score;
    }

    /**
     * 截断文本用于日志输出
     */
    private static String truncate(String text, int maxLength) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength) + "...";
    }
}