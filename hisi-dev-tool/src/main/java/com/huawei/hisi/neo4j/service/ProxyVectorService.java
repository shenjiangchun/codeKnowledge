package com.huawei.hisi.neo4j.service;

import com.huawei.hisi.knowledgegraph.util.CommentExtractor;
import com.huawei.hisi.neo4j.model.MethodNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 代理向量服务
 * 用于生成方法的代理向量输入文本，用于后续的嵌入向量生成
 */
@Service
public class ProxyVectorService {

    /**
     * 代理向量输入的最大长度
     */
    private static final int MAX_INPUT_LENGTH = 500;

    /**
     * 生成代理向量输入文本
     * 格式: {className} {methodName} {signature} {commentSummary} {serviceName}
     *
     * @param node 方法节点
     * @return 代理向量输入文本
     */
    public String generateProxyVectorInput(MethodNode node) {
        if (node == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();

        // 1. 提取简化的类名（不含包名）
        String className = simplifyClassName(node.getClassName());
        if (className != null && !className.isEmpty()) {
            sb.append(className).append(" ");
        }

        // 2. 方法名
        String methodName = node.getMethodName();
        if (methodName != null && !methodName.isEmpty()) {
            sb.append(methodName).append(" ");
        }

        // 3. 简化的方法签名
        String signature = simplifySignature(node.getSignature());
        if (signature != null && !signature.isEmpty()) {
            sb.append(signature).append(" ");
        }

        // 4. 注释摘要（优先使用显式注释，否则使用方法名推断）
        String comment = node.getComment();
        if (comment == null || comment.isEmpty()) {
            comment = CommentExtractor.inferFromMethodName(methodName);
        }
        if (comment != null && !comment.isEmpty()) {
            // 截断过长的注释
            if (comment.length() > 100) {
                comment = comment.substring(0, 100);
            }
            sb.append(comment).append(" ");
        }

        // 5. 服务名
        String serviceName = node.getServiceName();
        if (serviceName != null && !serviceName.isEmpty()) {
            sb.append(serviceName);
        }

        String result = sb.toString().trim();

        // 限制总长度
        if (result.length() > MAX_INPUT_LENGTH) {
            result = result.substring(0, MAX_INPUT_LENGTH);
        }

        return result;
    }

    /**
     * 简化方法签名
     * 移除全限定类名的包名前缀，保留简化类型名
     *
     * @param signature 原始方法签名
     * @return 简化后的签名
     */
    public String simplifySignature(String signature) {
        if (signature == null || signature.isEmpty()) {
            return "";
        }

        // 移除常见的java包名前缀
        String simplified = signature;

        // 替换 java.lang.* 和 java.util.* 等常见包名
        simplified = simplified.replaceAll("java\\.lang\\.", "");
        simplified = simplified.replaceAll("java\\.util\\.", "");
        simplified = simplified.replaceAll("java\\.io\\.", "");
        simplified = simplified.replaceAll("java\\.time\\.", "");
        simplified = simplified.replaceAll("java\\.math\\.", "");
        simplified = simplified.replaceAll("java\\.net\\.", "");
        simplified = simplified.replaceAll("java\\.nio\\.", "");
        simplified = simplified.replaceAll("java\\.sql\\.", "");

        // 替换 org.springframework.* 等框架包名
        simplified = simplified.replaceAll("org\\.springframework\\.[a-z]+\\.", "");

        // 替换其他常见的全限定类名模式
        // 例如: com.example.model.User -> User
        simplified = simplified.replaceAll("([a-z]+\\.)+([A-Z][a-zA-Z0-9]*)", "$2");

        return simplified;
    }

    /**
     * 简化类名，移除包名前缀
     *
     * @param fullClassName 全限定类名
     * @return 简化的类名
     */
    private String simplifyClassName(String fullClassName) {
        if (fullClassName == null || fullClassName.isEmpty()) {
            return "";
        }

        int lastDot = fullClassName.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < fullClassName.length() - 1) {
            return fullClassName.substring(lastDot + 1);
        }
        return fullClassName;
    }

    /**
     * 批量生成代理向量输入
     *
     * @param nodes 方法节点列表
     * @return 代理向量输入文本列表
     */
    public List<String> batchGenerateProxyVectorInputs(List<MethodNode> nodes) {
        if (nodes == null || nodes.isEmpty()) {
            return Collections.emptyList();
        }

        List<String> results = new ArrayList<>(nodes.size());
        for (MethodNode node : nodes) {
            results.add(generateProxyVectorInput(node));
        }
        return results;
    }
}
