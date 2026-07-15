package com.huawei.hisi.knowledgegraph.codegraph;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * codegraph SQLite 读取器
 *
 * <p>从 codegraph CLI 产出的 {@code <projectPath>/.codegraph/codegraph.db}
 * 读取 nodes / edges / files 三张表并转为 Java POJO 列表，供后续
 * {@code CodegraphToNeo4jTransformer} 映射到 Neo4j。</p>
 *
 * <p><b>连接策略</b>：使用 sqlite-jdbc 的只读模式（{@code open_mode=1}），
 * 避免误写。codegraph 底层使用 Node 24 的 {@code node:sqlite} 以 WAL 模式
 * 写入，sqlite-jdbc 读取 WAL 数据库时 {@code -wal} 文件会被透明处理。</p>
 *
 * <p><b>JSON 字段不解析</b>：{@code decorators} / {@code type_parameters} /
 * {@code metadata} / {@code errors} 在本层保留为 String，由 Step 3 转换器按需解析。</p>
 */
@Service
@Slf4j
public class CodegraphSqliteReader {

    /** nodes 表字段列表（按 SELECT 顺序） */
    private static final String NODE_COLUMNS = "id, kind, name, qualified_name, file_path, language, "
            + "start_line, end_line, start_column, end_column, docstring, signature, visibility, "
            + "is_exported, is_async, is_static, is_abstract, decorators, type_parameters, "
            + "return_type, updated_at";

    /** edges 表字段列表（按 SELECT 顺序） */
    private static final String EDGE_COLUMNS = "id, source, target, kind, metadata, line, col, provenance";

    /** files 表字段列表（按 SELECT 顺序） */
    private static final String FILE_COLUMNS = "path, content_hash, language, size, modified_at, "
            + "indexed_at, node_count, errors";

    /**
     * 读取 codegraph SQLite 数据库全部三张表。
     *
     * @param dbPath codegraph.db 绝对路径
     * @return 三张表的 POJO 列表
     * @throws IllegalArgumentException 路径为空
     * @throws IOException              文件不存在或读取失败
     */
    public CodegraphDb readAll(String dbPath) throws IOException {
        if (dbPath == null || dbPath.isBlank()) {
            throw new IllegalArgumentException("dbPath 不能为空");
        }
        File dbFile = new File(dbPath);
        if (!Files.isRegularFile(dbFile.toPath())) {
            throw new IOException("codegraph db 文件不存在: " + dbPath);
        }

        String url = "jdbc:sqlite:" + dbFile.getAbsolutePath() + "?open_mode=1";
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new IOException("sqlite-jdbc 驱动未找到", e);
        }

        List<CodegraphNode> nodes = new ArrayList<>();
        List<CodegraphEdge> edges = new ArrayList<>();
        List<CodegraphFile> files = new ArrayList<>();

        try (Connection conn = DriverManager.getConnection(url)) {
            int skipped;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + NODE_COLUMNS + " FROM nodes")) {
                skipped = 0;
                while (rs.next()) {
                    try {
                        nodes.add(mapNode(rs));
                    } catch (SQLException e) {
                        skipped++;
                        log.warn("nodes 行映射失败 id={}", rs.getString("id"), e);
                    }
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + EDGE_COLUMNS + " FROM edges")) {
                skipped = 0;
                while (rs.next()) {
                    try {
                        edges.add(mapEdge(rs));
                    } catch (SQLException e) {
                        skipped++;
                        log.warn("edges 行映射失败 id={}", rs.getLong("id"), e);
                    }
                }
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT " + FILE_COLUMNS + " FROM files")) {
                skipped = 0;
                while (rs.next()) {
                    try {
                        files.add(mapFile(rs));
                    } catch (SQLException e) {
                        skipped++;
                        log.warn("files 行映射失败 path={}", rs.getString("path"), e);
                    }
                }
            }
        } catch (SQLException e) {
            throw new IOException("读取 codegraph db 失败: " + dbPath, e);
        }

        log.info("codegraph db 读取完成 nodes={}, edges={}, files={}", nodes.size(), edges.size(), files.size());
        return new CodegraphDb(nodes, edges, files);
    }

    private CodegraphNode mapNode(ResultSet rs) throws SQLException {
        return new CodegraphNode(
                rs.getString("id"),
                rs.getString("kind"),
                rs.getString("name"),
                rs.getString("qualified_name"),
                rs.getString("file_path"),
                rs.getString("language"),
                rs.getInt("start_line"),
                rs.getInt("end_line"),
                rs.getInt("start_column"),
                rs.getInt("end_column"),
                rs.getString("docstring"),
                rs.getString("signature"),
                rs.getString("visibility"),
                rs.getInt("is_exported") == 1,
                rs.getInt("is_async") == 1,
                rs.getInt("is_static") == 1,
                rs.getInt("is_abstract") == 1,
                rs.getString("decorators"),
                rs.getString("type_parameters"),
                rs.getString("return_type"),
                rs.getLong("updated_at")
        );
    }

    private CodegraphEdge mapEdge(ResultSet rs) throws SQLException {
        // 顺序：先读取可空 Integer 列并立即检查 wasNull()，避免后续 getXxx 覆盖标记
        int lineRaw = rs.getInt("line");
        boolean lineNull = rs.wasNull();
        int colRaw = rs.getInt("col");
        boolean colNull = rs.wasNull();
        return new CodegraphEdge(
                rs.getLong("id"),
                rs.getString("source"),
                rs.getString("target"),
                rs.getString("kind"),
                rs.getString("metadata"),
                lineNull ? null : lineRaw,
                colNull ? null : colRaw,
                rs.getString("provenance")
        );
    }

    private CodegraphFile mapFile(ResultSet rs) throws SQLException {
        int nodeCountRaw = rs.getInt("node_count");
        boolean nodeCountNull = rs.wasNull();
        return new CodegraphFile(
                rs.getString("path"),
                rs.getString("content_hash"),
                rs.getString("language"),
                rs.getInt("size"),
                rs.getLong("modified_at"),
                rs.getLong("indexed_at"),
                nodeCountNull ? null : nodeCountRaw,
                rs.getString("errors")
        );
    }

    // ===== POJO 记录类型 =====

    /**
     * nodes 表一行记录。
     *
     * <p>布尔字段（is_exported / is_async / is_static / is_abstract）
     * 由 SQLite 的 0/1 转换而来。JSON 字段（decorators / type_parameters）
     * 保留为原始 String，由后续转换器解析。</p>
     */
    public record CodegraphNode(
            String id,
            String kind,
            String name,
            String qualifiedName,
            String filePath,
            String language,
            int startLine,
            int endLine,
            int startColumn,
            int endColumn,
            String docstring,
            String signature,
            String visibility,
            boolean isExported,
            boolean isAsync,
            boolean isStatic,
            boolean isAbstract,
            String decorators,
            String typeParameters,
            String returnType,
            long updatedAt
    ) {
    }

    /**
     * edges 表一行记录。line / col / provenance 可为 null。
     */
    public record CodegraphEdge(
            long id,
            String source,
            String target,
            String kind,
            String metadata,
            Integer line,
            Integer col,
            String provenance
    ) {
    }

    /**
     * files 表一行记录。nodeCount / errors 可为 null。
     */
    public record CodegraphFile(
            String path,
            String contentHash,
            String language,
            int size,
            long modifiedAt,
            long indexedAt,
            Integer nodeCount,
            String errors
    ) {
    }

    /**
     * codegraph 数据库完整快照。
     */
    public record CodegraphDb(
            List<CodegraphNode> nodes,
            List<CodegraphEdge> edges,
            List<CodegraphFile> files
    ) {
    }
}
