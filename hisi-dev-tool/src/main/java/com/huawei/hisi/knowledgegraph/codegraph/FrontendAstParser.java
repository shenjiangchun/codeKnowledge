package com.huawei.hisi.knowledgegraph.codegraph;

import com.huawei.hisi.neo4j.model.ApiClientNode;
import com.huawei.hisi.neo4j.model.FrontendRouteNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 前端 AST 解析器（轻量正则实现）。
 *
 * <p>扫描前端源码字符串（.vue / .ts），提取两类图谱实体：</p>
 * <ul>
 *   <li>{@code ApiClient}：axios request.get/post/put/delete/patch(...) 与 fetch(...) 调用点</li>
 *   <li>{@code FrontendRoute}：vue-router 路由表中的 {@code path + component} 条目</li>
 * </ul>
 *
 * <p>说明：codegraph sidecar 不产出 HTTP 调用点语义（其节点 kind 仅
 * function/method/component/route），故 ApiClient/FrontendRoute 由本解析器
 * 直接扫描前端源码提取。前端 URL 为字符串字面量/模板（已核对 request.ts + api/*.ts），
 * 正则匹配足以覆盖；引入 @vue/compiler-sfc 到 Java 侧不可行，起 Node 子进程过重，
 * 故采用轻量正则实现（极简优先）。</p>
 */
@Slf4j
@Component
public class FrontendAstParser {

    /** request.get/post/put/delete/patch<T>('<url>'|`<url>`) 与 fetch('<url>'|`<url>`) */
    private static final Pattern REQUEST_CALL = Pattern.compile(
        "request\\.(get|post|put|delete|patch)\\s*(?:<[^>]*>)?\\s*\\(\\s*(['\"`])([^'\"`]+)\\2"
    );
    private static final Pattern FETCH_CALL = Pattern.compile(
        "fetch\\s*\\(\\s*(['\"`])([^'\"`]+)\\1"
    );

    /** vue-router 路由条目：path: '...', 相邻的 component: () => import('.../Xxx.vue') */
    private static final Pattern ROUTE_PATH = Pattern.compile(
        "path:\\s*(['\"`])([^'\"`]+)\\1"
    );
    private static final Pattern ROUTE_COMPONENT = Pattern.compile(
        "component:\\s*\\(\\)\\s*=>\\s*import\\s*\\(\\s*(['\"`])[^'\"`]*/([^/'\"]+)\\.(?:vue|tsx|ts)\\1"
    );

    /**
     * 解析结果：ApiClient 节点 + FrontendRoute 节点。
     */
    public record ParseResult(List<ApiClientNode> apiClients, List<FrontendRoute> frontendRoutes) {
    }

    /**
     * 前端路由中间结构（path + componentName，未生成节点 ID）。
     */
    public record FrontendRoute(String path, String name, String componentName) {
    }

    /**
     * 解析单文件源码。
     *
     * @param source      源码内容
     * @param projectPath 前端项目路径
     * @param sourceFile  相对/绝对源文件路径（用于节点唯一标识）
     */
    public ParseResult parseSource(String source, String projectPath, String sourceFile) {
        List<ApiClientNode> apiClients = new ArrayList<>();
        List<FrontendRoute> routes = new ArrayList<>();

        if (source == null || source.isBlank()) {
            return new ParseResult(apiClients, routes);
        }

        // 1. ApiClient：request.* 调用
        String componentName = deriveComponentName(sourceFile);
        Matcher req = REQUEST_CALL.matcher(source);
        while (req.find()) {
            String method = req.group(1).toUpperCase();
            String url = req.group(3);
            apiClients.add(ApiClientNode.builder()
                .apiClientId(ApiClientNode.generateApiClientId(projectPath, sourceFile, method, url))
                .method(method)
                .url(url)
                .sourceFile(sourceFile)
                .componentName(componentName)
                .projectPath(projectPath)
                .language("typescript")
                .build());
        }
        // 1b. ApiClient：fetch 调用（默认 GET）
        Matcher fetch = FETCH_CALL.matcher(source);
        while (fetch.find()) {
            String url = fetch.group(2);
            apiClients.add(ApiClientNode.builder()
                .apiClientId(ApiClientNode.generateApiClientId(projectPath, sourceFile, "GET", url))
                .method("GET")
                .url(url)
                .sourceFile(sourceFile)
                .componentName(componentName)
                .projectPath(projectPath)
                .language("typescript")
                .build());
        }

        // 2. FrontendRoute：vue-router 路由表（仅 router 文件）
        if (sourceFile != null && sourceFile.contains("router")) {
            Matcher pathMatcher = ROUTE_PATH.matcher(source);
            Matcher compMatcher = ROUTE_COMPONENT.matcher(source);
            List<String> paths = new ArrayList<>();
            while (pathMatcher.find()) {
                paths.add(pathMatcher.group(2));
            }
            List<String> comps = new ArrayList<>();
            while (compMatcher.find()) {
                comps.add(compMatcher.group(2));
            }
            int n = Math.min(paths.size(), comps.size());
            for (int i = 0; i < n; i++) {
                routes.add(new FrontendRoute(paths.get(i), null, comps.get(i)));
            }
        }

        return new ParseResult(apiClients, routes);
    }

    /**
     * 将解析结果转换为可落库的 FrontendRouteNode 列表。
     */
    public List<FrontendRouteNode> toFrontendRouteNodes(ParseResult result, String projectPath) {
        List<FrontendRouteNode> nodes = new ArrayList<>();
        for (FrontendRoute r : result.frontendRoutes()) {
            nodes.add(FrontendRouteNode.builder()
                .frontendRouteId(FrontendRouteNode.generateFrontendRouteId(projectPath, r.path()))
                .path(r.path())
                .name(r.name())
                .componentName(r.componentName())
                .projectPath(projectPath)
                .build());
        }
        return nodes;
    }

    /**
     * 从源文件路径推导组件名。
     * 仅当 sourceFile 是 .vue 文件（组件内直调）时返回组件名（去扩展名的文件名）；
     * api/*.ts 封装层调用返回 null（无法静态确定是哪个组件 import 了它）。
     */
    private static String deriveComponentName(String sourceFile) {
        if (sourceFile == null || !sourceFile.endsWith(".vue")) {
            return null;
        }
        String base = sourceFile;
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        int dot = base.lastIndexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        return base;
    }

    /**
     * 遍历前端项目目录，解析所有 {@code .ts}/{@code .vue} 源文件，
     * 聚合出 ApiClient 节点与 FrontendRoute 中间结构。
     *
     * @param directoryPath 前端项目根目录
     * @param projectPath   前端项目路径（用于节点唯一标识）
     * @return 聚合后的解析结果；目录不存在或遍历失败时返回空结果
     */
    public ParseResult parseDirectory(String directoryPath, String projectPath) {
        List<ApiClientNode> apiClients = new ArrayList<>();
        List<FrontendRoute> routes = new ArrayList<>();
        if (directoryPath == null || directoryPath.isBlank()) {
            return new ParseResult(apiClients, routes);
        }
        Path root = Path.of(directoryPath);
        if (!Files.isDirectory(root)) {
            log.debug("[FrontendAstParser] 前端目录不存在: {}", directoryPath);
            return new ParseResult(apiClients, routes);
        }
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    // 排除 node_modules / dist / .vite / .git 等目录，避免扫描海量第三方依赖与构建产物
                    Path rel = root.relativize(p);
                    for (Path seg : rel) {
                        String s = seg.toString();
                        if (s.equals("node_modules") || s.equals("dist")
                                || s.equals(".vite") || s.equals(".git")
                                || s.equals("build") || s.equals("out")) {
                            return false;
                        }
                    }
                    String name = p.getFileName().toString();
                    return name.endsWith(".ts") || name.endsWith(".vue");
                })
                .toList();
            for (Path file : files) {
                String source;
                try {
                    source = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    log.warn("[FrontendAstParser] 读取文件失败: {}", file, e);
                    continue;
                }
                String sourceFile = root.relativize(file).toString().replace('\\', '/');
                ParseResult r = parseSource(source, projectPath, sourceFile);
                apiClients.addAll(r.apiClients());
                routes.addAll(r.frontendRoutes());
            }
        } catch (IOException e) {
            log.warn("[FrontendAstParser] 遍历目录失败: {}", directoryPath, e);
        }
        return new ParseResult(apiClients, routes);
    }
}
