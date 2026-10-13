package com.huawei.hisi.knowledgegraph.vector;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.ComponentNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;

/**
 * 前端节点描述生成器（静态拼接，控成本，不引入 LLM 摘要）。
 *
 * <p>按设计 D3：Component → 组件名 + 描述；FrontendRoute → 路径 + 目标组件；
 * ApiClient → method + URL + 源文件 + 组件名。</p>
 */
public final class FrontendNodeDescriptionBuilder {

    private FrontendNodeDescriptionBuilder() {
    }

    public static String forComponent(ComponentNode node) {
        StringBuilder sb = new StringBuilder("组件 ").append(nullToEmpty(node.getComponentName()));
        String desc = node.getDescription();
        if (desc != null && !desc.isBlank()) {
            sb.append(": ").append(desc.trim());
        }
        return sb.toString();
    }

    public static String forRoute(FrontendRouteNode node) {
        return "前端路由 " + nullToEmpty(node.getPath())
                + " -> " + nullToEmpty(node.getComponentName());
    }

    public static String forApiClient(ApiClientNode node) {
        return nullToEmpty(node.getMethod()) + " " + nullToEmpty(node.getUrl())
                + " (源文件: " + nullToEmpty(node.getSourceFile())
                + ", 组件: " + nullToEmpty(node.getComponentName()) + ")";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
