package com.huawei.hisi.neo4j.service;

import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 分词路由组件
 * 判断每个分词应走语义向量（descriptionEmbedding）还是代码向量（codeEmbedding）
 */
@Component
public class TokenRouter {

    public enum RouteType { DESCRIPTION, CODE }

    public record TokenRoute(String token, RouteType route) {}

    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\u4e00-\\u9fa5]+");

    /**
     * 代码特征词模式：
     * - @Xxx 注解
     * - XxxException 异常类
     * - 驼峰标识符
     * - 点分隔的FQN
     * - SQL关键字
     */
    private static final Pattern CODE_PATTERN = Pattern.compile(
        "^(@\\w+|\\w+Exception|[A-Z][a-z]+[A-Z][a-zA-Z]*|\\w+\\.\\w+|sql|query|insert|update|delete|select|from|where)$"
    );

    /**
     * 代码关键词集合
     */
    private static final Set<String> CODE_KEYWORDS = Set.of(
        "sql", "query", "insert", "update", "delete", "select", "from", "where",
        "exception", "error", "fault", "failure", "annotation", "override",
        "async", "sync", "thread", "lock", "queue", "cache", "pool"
    );

    /**
     * 分词 + 路由判断
     *
     * @param query 用户查询文本
     * @return 分词路由结果列表
     */
    public List<TokenRoute> tokenizeAndRoute(String query) {
        if (query == null || query.isBlank()) return List.of();

        List<TokenRoute> result = new ArrayList<>();
        String[] tokens = query.split("[\\s(){};.,<>=+\\-*/&|!\\[\\]+]+");

        for (String token : tokens) {
            if (token.isBlank()) continue;

            RouteType route = guessRouteType(token.trim());
            result.add(new TokenRoute(token.trim(), route));
        }

        // 若全部分词都是 CODE 类型且有多个分词，则整体走 DESCRIPTION
        // （避免纯业务词被代码向量噪声干扰，如"用户管理"分词后被误判为代码）
        if (result.stream().allMatch(t -> t.route() == RouteType.CODE)
            && result.size() > 1) {
            return result.stream()
                .map(t -> new TokenRoute(t.token(), RouteType.DESCRIPTION))
                .toList();
        }

        return result;
    }

    /**
     * 判断单个 token 的路由类型
     */
    private RouteType guessRouteType(String token) {
        // 规则优先级：

        // 1. 中文 → 语义向量
        if (CHINESE_PATTERN.matcher(token).find()) {
            return RouteType.DESCRIPTION;
        }

        // 2. 代码特征词 → 代码向量
        if (CODE_PATTERN.matcher(token).matches() || CODE_KEYWORDS.contains(token.toLowerCase())) {
            return RouteType.CODE;
        }

        // 3. 默认语义向量
        return RouteType.DESCRIPTION;
    }
}