package com.huawei.hisi.workflow.agent;

import com.huawei.hisi.archdiagnosis.service.ArchSourceScanService;
import com.huawei.hisi.knowledgegraph.controller.KnowledgeGraphController;
import com.huawei.hisi.model.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 架构分析 ReAct 智能体只读工具注册表：18 个工具各委托
 * {@link KnowledgeGraphController} 既有只读端点，不新增端点、不改生成侧。
 *
 * <p>工具参数以 {@code args} 字典传入（LLM 从 system prompt 中的参数 schema
 * 得知键名），此处统一做类型提取与默认值处理，再由
 * {@link ArchReActAgentNode} 统一截断观察结果。</p>
 */
@Slf4j
@Component
public class ArchAgentToolRegistry {

    private final Map<String, ArchAgentTool> tools = new LinkedHashMap<>();
    private final ArchSourceScanService sourceScan;

    public ArchAgentToolRegistry(KnowledgeGraphController kg, ArchSourceScanService sourceScan) {
        this.sourceScan = sourceScan;
        registerReadOnlyTools(kg);
    }

    public ArchAgentTool get(String name) {
        return tools.get(name);
    }

    public List<ArchAgentTool> tools() {
        return List.copyOf(tools.values());
    }

    private void registerReadOnlyTools(KnowledgeGraphController kg) {
        tools.put("dashboard", ArchAgentTool.of(
                "dashboard", "项目全景：健康分、模块列表、KPI、风险列表",
                Map.of(),
                (args, paths) -> unpack(kg.getDashboard(null, paths, str(args, "language")))));

        tools.put("dsm", ArchAgentTool.of(
                "dsm", "DSM 依赖结构矩阵：N×N 模块依赖与循环依赖/分层违规标记",
                Map.of("language", "语言过滤（可选）", "level", "粒度：package（默认）| build-module"),
                (args, paths) -> unpack(kg.getDsm(null, paths, str(args, "language"),
                        strOr(args, "level", "package")))));

        tools.put("package_cycles", ArchAgentTool.of(
                "package_cycles", "包级循环依赖",
                Map.of(),
                (args, paths) -> unpack(kg.getPackageCycles(null, paths))));

        tools.put("module_cycles", ArchAgentTool.of(
                "module_cycles", "模块级循环依赖",
                Map.of(),
                (args, paths) -> unpack(kg.getModuleCycles(null, paths))));

        tools.put("class_ego_net", ArchAgentTool.of(
                "class_ego_net", "类邻域下钻：指定包内类及其依赖关系",
                Map.of("packages", "包名列表（必填）"),
                (args, paths) -> unpack(kg.getClassEgoNet(null, paths, strList(args, "packages")))));

        tools.put("callees_tree", ArchAgentTool.of(
                "callees_tree", "方法完整下游调用树（含深度与循环检测）",
                Map.of("className", "全限定类名", "methodName", "方法名", "maxDepth", "最大深度（默认10）"),
                (args, paths) -> unpack(kg.getCalleesTree(str(args, "className"), str(args, "methodName"),
                        null, paths, intOr(args, "maxDepth", 10)))));

        tools.put("downstream", ArchAgentTool.of(
                "downstream", "方法下游调用链（按 nodeId）",
                Map.of("nodeId", "方法节点ID", "maxDepth", "最大深度（默认10）"),
                (args, paths) -> unpack(kg.getDownstreamCallChain(str(args, "nodeId"),
                        null, paths, intOr(args, "maxDepth", 10)))));

        tools.put("upstream", ArchAgentTool.of(
                "upstream", "影响指定方法的上游调用链（哪些入口会调用到它）",
                Map.of("className", "全限定类名", "methodName", "方法名"),
                (args, paths) -> unpack(kg.getCallChainsAffecting(str(args, "className"),
                        str(args, "methodName"), null, paths))));

        tools.put("root_entries", ArchAgentTool.of(
                "root_entries", "方法的上游信息：根入口点 + 直接调用方",
                Map.of("className", "全限定类名", "methodName", "方法名"),
                (args, paths) -> unpack(kg.getRootEntries(str(args, "className"),
                        str(args, "methodName"), null, paths))));

        tools.put("method_detail", ArchAgentTool.of(
                "method_detail", "方法详情：签名、复杂度、方法体、异常声明",
                Map.of("nodeId", "方法节点ID"),
                (args, paths) -> unpack(kg.getMethodDetail(str(args, "nodeId"), null, paths))));

        tools.put("method_by_class", ArchAgentTool.of(
                "method_by_class", "类内所有方法列表",
                Map.of("className", "全限定类名"),
                (args, paths) -> unpack(kg.getMethodsByClass(str(args, "className"), null, paths))));

        tools.put("blast_radius", ArchAgentTool.of(
                "blast_radius", "方法爆炸半径：下游影响面、上游调用者、受影响入口点、风险摘要",
                Map.of("nodeId", "方法节点ID", "maxDepth", "遍历深度（默认5）"),
                (args, paths) -> unpack(kg.getBlastRadius(str(args, "nodeId"),
                        intOr(args, "maxDepth", 5), null, paths))));

        tools.put("implementations", ArchAgentTool.of(
                "implementations", "接口的所有实现类（实现类外泄判定）",
                Map.of("interfaceName", "接口全限定名"),
                (args, paths) -> unpack(kg.getImplementations(str(args, "interfaceName"), null, paths))));

        tools.put("interfaces", ArchAgentTool.of(
                "interfaces", "类实现的所有接口",
                Map.of("className", "全限定类名"),
                (args, paths) -> unpack(kg.getInterfaces(str(args, "className"), null, paths))));

        tools.put("class_dependencies", ArchAgentTool.of(
                "class_dependencies", "类级依赖关系（节点 + 边，可按包过滤）",
                Map.of("packages", "包名列表（可选）"),
                (args, paths) -> unpack(kg.getClassDependencies(null, paths, strList(args, "packages")))));

        tools.put("hotspots", ArchAgentTool.of(
                "hotspots", "项目热点文件列表（复杂度×变更频率×耦合度，按风险降序）",
                Map.of("limit", "返回数量（默认20）"),
                (args, paths) -> unpack(kg.getHotspots(null, paths, str(args, "language"),
                        intOr(args, "limit", 20)))));

        tools.put("service_topology", ArchAgentTool.of(
                "service_topology", "微服务拓扑：服务节点、跨服务依赖边（Feign/MQ/HTTP）",
                Map.of("language", "语言过滤（可选）"),
                (args, paths) -> unpack(kg.getServiceTopology(null, paths, str(args, "language")))));

        tools.put("domains", ArchAgentTool.of(
                "domains", "自动检测的领域/DDD 边界及其跨域交互关系",
                Map.of("language", "语言过滤（可选）"),
                (args, paths) -> unpack(kg.getDomains(null, paths, str(args, "language")))));

        tools.put("injection_scan", ArchAgentTool.of(
                "injection_scan", "本地源码依赖注入扫描：区分接口注入(interface) vs 直接依赖实现类(class) vs 外部类型(external)，用于灰度验证实现类外泄/直接依赖实现类误报",
                Map.of("className", "全限定类名（可选，返回该类的注入点明细）",
                        "packageFilter", "包名前缀（可选，返回该包下所有类的注入点明细）"),
                (args, paths) -> {
                    String className = str(args, "className");
                    String pkg = str(args, "packageFilter");
                    return (className != null || (pkg != null && !pkg.isBlank()))
                            ? sourceScan.injectionSites(paths, className, pkg)
                            : sourceScan.injectionSummary(paths);
                }));
    }

    /** 解包 {@link ApiResponse}：成功返回 data，失败返回 error 摘要（不抛异常）。 */
    private static Object unpack(ApiResponse<?> resp) {
        if (resp == null) {
            return Map.of();
        }
        if (resp.getCode() != 200) {
            return Map.of("error", resp.getMessage());
        }
        return resp.getData() != null ? resp.getData() : Map.of();
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v instanceof String s ? s : null;
    }

    private static String strOr(Map<String, Object> args, String key, String def) {
        String s = str(args, key);
        return s == null || s.isBlank() ? def : s;
    }

    private static int intOr(Map<String, Object> args, String key, int def) {
        Object v = args.get(key);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            try {
                return Integer.parseInt(s.trim());
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strList(Map<String, Object> args, String key) {
        Object v = args.get(key);
        if (v instanceof List<?> list) {
            return list.stream().filter(o -> o instanceof String).map(o -> (String) o).toList();
        }
        return null;
    }
}
