package com.huawei.hisi.knowledgegraph.scanner;

import com.huawei.hisi.neo4j.model.SqlNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MyBatis XML 解析器
 * 扫描并解析 Mapper XML 文件，提取 SQL 语句信息
 * 重构版：返回 Neo4j SqlNode 列表，不再直接保存到 PostgreSQL
 */
@Component
public class MyBatisXmlScanner {

    private static final Logger log = LoggerFactory.getLogger(MyBatisXmlScanner.class);

    // SQL 语句类型
    private static final String[] STATEMENT_TYPES = {"select", "insert", "update", "delete"};

    /**
     * 扫描结果（Neo4j 版本）
     */
    public static class Neo4jScanResult {
        private final boolean success;
        private final int mapperCount;
        private final int sqlCount;
        private final List<SqlNode> sqlNodes;
        private final Set<String> mapperInterfaces;
        private final List<String> errors;

        public Neo4jScanResult(boolean success, int mapperCount, int sqlCount,
                               List<SqlNode> sqlNodes, Set<String> mapperInterfaces, List<String> errors) {
            this.success = success;
            this.mapperCount = mapperCount;
            this.sqlCount = sqlCount;
            this.sqlNodes = sqlNodes;
            this.mapperInterfaces = mapperInterfaces;
            this.errors = errors;
        }

        public boolean isSuccess() {
            return success;
        }

        public int getMapperCount() {
            return mapperCount;
        }

        public int getSqlCount() {
            return sqlCount;
        }

        public List<SqlNode> getSqlNodes() {
            return sqlNodes;
        }

        public Set<String> getMapperInterfaces() {
            return mapperInterfaces;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    /**
     * 扫描项目目录，解析所有 Mapper XML 文件，返回 Neo4j SqlNode 列表
     * 新版本：不保存到数据库，由调用方处理存储
     *
     * @param projectPath 项目路径
     * @return 扫描结果，包含 SqlNode 列表
     * @throws RuntimeException 如果解析过程中出现严重错误
     */
    public Neo4jScanResult scanProjectForNeo4j(String projectPath) {
        return scanProjectForNeo4j(projectPath, null);
    }

    /**
     * 扫描项目目录，支持自定义屏蔽目录
     */
    public Neo4jScanResult scanProjectForNeo4j(String projectPath, List<String> excludePaths) {
        List<SqlNode> allSqlNodes = new ArrayList<>();
        Set<String> mapperInterfaces = new HashSet<>();
        List<String> errors = new ArrayList<>();
        int mapperCount = 0;

        try {
            // 查找所有 XML 文件
            List<Path> xmlFiles = findXmlFiles(projectPath, excludePaths);
            log.info("发现 {} 个 XML 文件待扫描", xmlFiles.size());

            for (Path xmlFile : xmlFiles) {
                try {
                    ParsedMapperNeo4j mapper = parseMapperXmlForNeo4j(xmlFile, projectPath);
                    if (mapper != null) {
                        allSqlNodes.addAll(mapper.sqlNodes);
                        mapperInterfaces.add(mapper.namespace);
                        mapperCount++;
                    }
                    // 如果 mapper 为 null，说明不是 MyBatis Mapper XML，跳过即可
                } catch (Exception e) {
                    // 对于解析错误，记录但继续处理其他文件
                    // 只在确实是 Mapper XML（有 mapper 根节点）但解析失败时才报错
                    String error = "解析 XML 文件失败: " + xmlFile + " - " + e.getMessage();
                    log.warn(error);  // 改为 warn 级别，不中断流程
                    errors.add(error);
                }
            }

            log.info("MyBatis 扫描完成: {} 个 Mapper, {} 个 SQL 语句", mapperCount, allSqlNodes.size());
            return new Neo4jScanResult(true, mapperCount, allSqlNodes.size(), allSqlNodes, mapperInterfaces, errors);

        } catch (RuntimeException e) {
            // 重新抛出运行时异常
            throw e;
        } catch (Exception e) {
            log.error("扫描项目失败: {}", projectPath, e);
            throw new RuntimeException("MyBatis XML 扫描失败: " + e.getMessage(), e);
        }
    }

    /**
     * Scan a single XML file and return SqlNodes (single-file entry point for incremental refresh).
     *
     * @param filePath path to a single XML file
     * @return scan result for the single file, or empty result if not a MyBatis mapper
     */
    public Neo4jScanResult scanFile(String filePath) {
        List<SqlNode> sqlNodes = new ArrayList<>();
        Set<String> mapperInterfaces = new HashSet<>();
        List<String> errors = new ArrayList<>();

        try {
            Path xmlPath = Path.of(filePath);
            // Use filePath's parent as a fallback projectPath
            String projectPath = xmlPath.getParent() != null ? xmlPath.getParent().toString() : filePath;
            ParsedMapperNeo4j mapper = parseMapperXmlForNeo4j(xmlPath, projectPath);
            if (mapper != null) {
                sqlNodes.addAll(mapper.sqlNodes);
                mapperInterfaces.add(mapper.namespace);
            }
        } catch (Exception e) {
            String error = "解析 XML 文件失败: " + filePath + " - " + e.getMessage();
            log.warn(error);
            errors.add(error);
        }

        return new Neo4jScanResult(
                errors.isEmpty(), errors.isEmpty() ? 1 : 0, sqlNodes.size(),
                sqlNodes, mapperInterfaces, errors);
    }

    /**
     * 查找项目中的所有 XML 文件
     * 排除构建产物目录（target, build, node_modules 等）
     */
    private List<Path> findXmlFiles(String projectPath) throws Exception {
        return findXmlFiles(projectPath, null);
    }

    private List<Path> findXmlFiles(String projectPath, List<String> excludePaths) throws Exception {
        Path rootPath = Path.of(projectPath);
        if (!Files.exists(rootPath)) {
            return List.of();
        }

        // 默认屏蔽目录（含 .worktrees）+ 用户自定义
        Set<String> excludedDirs = new HashSet<>(
                com.huawei.hisi.service.CodeAnalysisCoreService.EXCLUDED_SCAN_DIRS);
        excludedDirs.add("bin");
        if (excludePaths != null) {
            for (String s : excludePaths) {
                if (s != null && !s.isBlank()) {
                    excludedDirs.add(s.trim().replace('\\', '/'));
                }
            }
        }

        try (Stream<Path> stream = Files.walk(rootPath)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.toString().endsWith(".xml"))
                    .filter(p -> {
                        // 检查路径中是否包含排除的目录
                        String pathStr = p.toString().replace('\\', '/');
                        for (String excluded : excludedDirs) {
                            // 支持 "src/test/" 这种带斜杠的复合片段以及单段 "target"
                            String seg = excluded.endsWith("/") ? excluded.substring(0, excluded.length() - 1) : excluded;
                            if (pathStr.contains("/" + seg + "/") || pathStr.endsWith("/" + seg)) {
                                return false;
                            }
                        }
                        return true;
                    })
                    .collect(Collectors.toList());
        }
    }

    /**
     * 解析 Mapper XML 文件，返回 Neo4j SqlNode 列表
     */
    private ParsedMapperNeo4j parseMapperXmlForNeo4j(Path xmlFile, String projectPath) throws Exception {
        File file = xmlFile.toFile();

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        // 安全配置：禁用外部实体解析
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        // 设置不验证 DTD
        factory.setValidating(false);

        DocumentBuilder builder = factory.newDocumentBuilder();
        // 设置 EntityResolver 来忽略 DTD 引用
        builder.setEntityResolver((publicId, systemId) -> {
            // 返回空的输入流，忽略 DTD
            return new org.xml.sax.InputSource(new java.io.StringReader(""));
        });

        Document document = builder.parse(file);

        Element root = document.getDocumentElement();

        // 检查是否为 Mapper XML
        if (!"mapper".equals(root.getNodeName())) {
            return null;
        }

        String namespace = root.getAttribute("namespace");
        if (namespace == null || namespace.isEmpty()) {
            log.warn("Mapper XML 缺少 namespace: {}", xmlFile);
            return null;
        }

        String xmlFilePath = xmlFile.toString();

        // 解析 SQL 语句
        List<SqlNode> sqlNodes = new ArrayList<>();
        for (String statementType : STATEMENT_TYPES) {
            NodeList statements = root.getElementsByTagName(statementType);
            for (int i = 0; i < statements.getLength(); i++) {
                Element statement = (Element) statements.item(i);
                SqlNode sqlNode = parseStatementForNeo4j(statement, namespace, statementType, xmlFilePath, projectPath);
                if (sqlNode != null) {
                    sqlNodes.add(sqlNode);
                }
            }
        }

        return new ParsedMapperNeo4j(namespace, xmlFilePath, sqlNodes);
    }

    /**
     * 解析单个 SQL 语句，返回 Neo4j SqlNode
     */
    private SqlNode parseStatementForNeo4j(Element statement, String namespace,
                                            String statementType, String xmlFilePath, String projectPath) {
        String id = statement.getAttribute("id");
        if (id == null || id.isEmpty()) {
            log.warn("SQL 语句缺少 id 属性: namespace={}, type={}", namespace, statementType);
            return null;
        }

        String sqlId = namespace + "." + id;
        String sqlContent = statement.getTextContent().trim();
        String nodeId = SqlNode.generateNodeId(projectPath, sqlId);

        return SqlNode.builder()
                .nodeId(nodeId)
                .sqlId(sqlId)
                .statementType(statementType.toUpperCase())
                .sqlStatement(sqlContent)
                .parameterType(statement.getAttribute("parameterType"))
                .resultType(statement.getAttribute("resultType"))
                .resultMap(statement.getAttribute("resultMap"))
                .mapperInterface(namespace)
                .methodName(id)
                .xmlFilePath(xmlFilePath)
                .projectPath(projectPath)
                .build();
    }

    /**
     * 内部类：解析结果封装（Neo4j 版本）
     */
    private static class ParsedMapperNeo4j {
        final String namespace;
        final String xmlFilePath;
        final List<SqlNode> sqlNodes;

        ParsedMapperNeo4j(String namespace, String xmlFilePath, List<SqlNode> sqlNodes) {
            this.namespace = namespace;
            this.xmlFilePath = xmlFilePath;
            this.sqlNodes = sqlNodes;
        }
    }
}
