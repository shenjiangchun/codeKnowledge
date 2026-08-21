package com.huawei.hisi.knowledgegraph.codegraph;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("FrontendAstParser")
class FrontendAstParserTest {

    private final FrontendAstParser parser = new FrontendAstParser();

    @Test
    @DisplayName("提取 request.get 字符串字面量 URL 为 ApiClient")
    void parse_requestGetLiteral_extractsApiClient() {
        String source = "export const getStatus = () => request.get<string[]>('/v2/knowledge-graph/projects')\n";
        var result = parser.parseSource(source, "/proj", "api/knowledgeGraph.ts");
        assertThat(result.apiClients()).hasSize(1);
        ApiClientNode c = result.apiClients().get(0);
        assertThat(c.getMethod()).isEqualTo("GET");
        assertThat(c.getUrl()).isEqualTo("/v2/knowledge-graph/projects");
    }

    @Test
    @DisplayName("提取 request.post 模板字符串路径参数为 ApiClient")
    void parse_requestPostTemplate_extractsApiClient() {
        String source = "return request.delete(`/callchain/analysis/project/${projectName}`)\n";
        var result = parser.parseSource(source, "/proj", "api/callChain.ts");
        assertThat(result.apiClients()).hasSize(1);
        assertThat(result.apiClients().get(0).getMethod()).isEqualTo("DELETE");
        assertThat(result.apiClients().get(0).getUrl()).isEqualTo("/callchain/analysis/project/${projectName}");
    }

    @Test
    @DisplayName("提取 fetch 调用为 ApiClient")
    void parse_fetch_extractsApiClient() {
        String source = "fetch('/api/mcp/health').then(r => r.json())\n";
        var result = parser.parseSource(source, "/proj", "views/x.vue");
        assertThat(result.apiClients()).hasSize(1);
        assertThat(result.apiClients().get(0).getMethod()).isEqualTo("GET");
        assertThat(result.apiClients().get(0).getUrl()).isEqualTo("/api/mcp/health");
    }

    @Test
    @DisplayName("无 API 调用时返回空")
    void parse_noCalls_empty() {
        var result = parser.parseSource("const x = 1;\n", "/proj", "a.ts");
        assertThat(result.apiClients()).isEmpty();
        assertThat(result.frontendRoutes()).isEmpty();
    }

    @Test
    @DisplayName("提取 vue-router 路由表为 FrontendRoute")
    void parse_vueRouter_extractsFrontendRoute() {
        String source = """
            const routes = [
              { path: '/apm-debug', name: 'ApmDebug', component: () => import('@/views/apm-debug/ApmDebugView.vue') },
              { path: '/log-analysis/report/:id', name: 'ReportDetail', component: () => import('@/views/log-analysis/ReportDetail.vue') },
            ];
            """;
        var result = parser.parseSource(source, "/proj", "router/index.ts");
        assertThat(result.frontendRoutes()).hasSize(2);
        var r = result.frontendRoutes().get(0);
        assertThat(r.path()).isEqualTo("/apm-debug");
        assertThat(r.componentName()).isEqualTo("ApmDebugView");
    }

    @Test
    @DisplayName("遍历目录提取所有 .ts/.vue 文件的 ApiClient 与路由")
    void parseDirectory_extractsAll(@TempDir Path tempDir) throws Exception {
        // 构造前端目录：api/ 下一个 ts 文件 + router/ 下一个路由文件
        Files.createDirectories(tempDir.resolve("api"));
        Files.createDirectories(tempDir.resolve("router"));
        Files.writeString(tempDir.resolve("api/knowledgeGraph.ts"),
            "export const f = () => request.get('/v2/knowledge-graph/projects')\n");
        Files.writeString(tempDir.resolve("router/index.ts"),
            "const routes = [{ path: '/home', component: () => import('@/views/Home.vue') }]\n");

        var result = parser.parseDirectory(tempDir.toString(), "/fe");
        assertThat(result.apiClients()).hasSize(1);
        assertThat(result.apiClients().get(0).getUrl()).isEqualTo("/v2/knowledge-graph/projects");
        assertThat(result.frontendRoutes()).hasSize(1);
        assertThat(result.frontendRoutes().get(0).path()).isEqualTo("/home");
    }
}
