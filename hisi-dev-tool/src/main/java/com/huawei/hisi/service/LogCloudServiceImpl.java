package com.huawei.hisi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.huawei.hisi.config.LogCloudConfig;
import com.huawei.hisi.model.LogEntry;
import com.huawei.hisi.model.LogQueryDto;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 日志云服务实现
 * 使用 HTTP API 直接调用日志云服务
 */
@Slf4j
@Service
public class LogCloudServiceImpl implements LogCloudService {

    private final LogCloudConfig logCloudConfig;
    private final ObjectMapper objectMapper;
    private final OkHttpClient httpClient;

    /**
     * 构建 OkHttpClient
     */
    @Autowired
    public LogCloudServiceImpl(LogCloudConfig logCloudConfig, ObjectMapper objectMapper) {
        this.logCloudConfig = logCloudConfig;
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(logCloudConfig.getApi().getConnectTimeout(), TimeUnit.MILLISECONDS)
                .readTimeout(logCloudConfig.getApi().getTimeout(), TimeUnit.MILLISECONDS)
                .writeTimeout(logCloudConfig.getApi().getTimeout(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void login() {
        // HTTP API 方式无需登录，直接使用 AppKey 认证
        log.info("日志云 API 认证准备完成，AppKey: {}",
                logCloudConfig.getApi().getHeaderAppkey().replaceAll("(.*)(.{4})", "$1****"));
    }

    @Override
    public List<LogEntry> queryLogs(LogQueryDto query) {
        try {
            // 构建 DSL 查询语句
            String dslQuery = buildDslQuery(query);
            log.debug("DSL 查询语句：{}", dslQuery);

            // 构建请求体
            Map<String, Object> requestBody = new HashMap<>();
            ArrayNode appIdArray = objectMapper.createArrayNode();
            appIdArray.add(logCloudConfig.getAppId());
            ObjectNode parameters = objectMapper.createObjectNode();
            parameters.put("qsl", dslQuery);
            requestBody.put("appId", appIdArray);
            requestBody.put("parameters", parameters);
            requestBody.put("region", "cn-south-1");
            requestBody.put("logModelCode", "app");

            // 发送 POST 请求
            String response = sendApiRequest(requestBody);

            // 解析响应
            List<LogEntry> logs = parseResponse(response, query);
            log.info("查询日志成功，共 {} 条", logs.size());
            return logs;

        } catch (IOException e) {
            log.error("查询日志 API 调用失败", e);
            throw new RuntimeException("查询日志失败：" + e.getMessage(), e);
        } catch (Exception e) {
            log.error("查询日志处理失败", e);
            throw new RuntimeException("查询日志处理失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void logout() {
        // HTTP API 方式无需退出登录
        log.info("日志云 API 会话清理完成");
    }

    /**
     * 构建 DSL 查询语句
     */
    private String buildDslQuery(LogQueryDto query) {
        // 如果提供了自定义 DSL，直接使用
        if (query.getDslQuery() != null && !query.getDslQuery().isEmpty()) {
            log.info("使用自定义 DSL 查询");
            return query.getDslQuery();
        }
        Map<String, Object> dsl = new LinkedHashMap<>();

        // 查询时间范围
        Map<String, Object> rangeFilter = new LinkedHashMap<>();
        rangeFilter.put("gte", formatTimestamp(query.getStartTime()));
        rangeFilter.put("lte", formatTimestamp(query.getEndTime()));

        List<Map<String, Object>> filters = new ArrayList<>();
        Map<String, Object> filterObj = new LinkedHashMap<>();
        filterObj.put("@timestamp", rangeFilter);
        filters.add(filterObj);

        // 错误日志过滤
        if (query.isErrorOnly() || "ERROR".equalsIgnoreCase(query.getLogLevel())) {
            Map<String, Object> levelFilter = new LinkedHashMap<>();
            List<String> errorLevels = new ArrayList<>();
            if (query.getLogLevel() != null) {
                errorLevels.add(query.getLogLevel());
            } else {
                errorLevels.add("ERROR");
                errorLevels.add("FATAL");
            }
            levelFilter.put("level", new LinkedHashMap<String, Object>() {{
                put("terms", errorLevels);
            }});
            filters.add(levelFilter);
        }

        // 关键词过滤
        if (query.getKeyword() != null && !query.getKeyword().isEmpty()) {
            Map<String, Object> keywordFilter = new LinkedHashMap<>();
            keywordFilter.put("message", new LinkedHashMap<String, Object>() {{
                put("match", query.getKeyword());
            }});
            filters.add(keywordFilter);
        }

        // 内容包含过滤
        if (query.getContentContains() != null && !query.getContentContains().isEmpty()) {
            Map<String, Object> contentFilter = new LinkedHashMap<>();
            contentFilter.put("content", new LinkedHashMap<String, Object>() {{
                put("wildcard", new LinkedHashMap<String, Object>() {{
                    put("value", "*" + query.getContentContains() + "*");
                }});
            }});
            filters.add(contentFilter);
        }

        // TraceID 过滤
        if (query.getTraceId() != null && !query.getTraceId().isEmpty()) {
            Map<String, Object> traceFilter = new LinkedHashMap<>();
            traceFilter.put("traceId", new LinkedHashMap<String, Object>() {{
                put("match", query.getTraceId());
            }});
            filters.add(traceFilter);
        }

        // 构建 DSL
        Map<String, Object> queryObj = new LinkedHashMap<>();
        queryObj.put("filter", new LinkedHashMap<String, Object>() {{
            put("and", filters);
        }});

        // 排序
        Map<String, Object> sortObj = new LinkedHashMap<>();
        sortObj.put(query.getSortBy() != null ? query.getSortBy() : "@timestamp",
                query.getSortOrder() != null ? query.getSortOrder() : "desc");
        queryObj.put("sort", sortObj);

        // 大小限制
        queryObj.put("size", query.getSize() != null ? query.getSize() : 100);

        dsl.put("query", queryObj);

        try {
            return objectMapper.writeValueAsString(dsl);
        } catch (Exception e) {
            log.error("DSL 序列化失败", e);
            throw new RuntimeException("构建 DSL 查询失败", e);
        }
    }

    /**
     * 发送 API 请求
     */
    private String sendApiRequest(Map<String, Object> requestBody) throws IOException {
        LogCloudConfig.ApiConfig apiConfig = logCloudConfig.getApi();
        String url = apiConfig.getBaseUrl() + apiConfig.getQueryPath();

        // 构建 JSON 请求体
        String jsonBody = objectMapper.writeValueAsString(requestBody);
        log.debug("API 请求 URL: {}", url);
        log.debug("API 请求体：{}", jsonBody);

        // 创建请求
        RequestBody body = RequestBody.create(
                jsonBody,
                MediaType.get("application/json; charset=UTF-8")
        );

        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .addHeader("Content-Type", "application/json; charset=UTF-8")
                .addHeader("X-HW-ID", apiConfig.getHeaderXhwId())
                .addHeader("x-hw-appkey", apiConfig.getHeaderAppkey())
                .build();

        // 发送请求
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "Unknown error";
                log.error("API 请求失败，状态码：{}, 响应：{}", response.code(), errorBody);
                throw new IOException("API 请求失败，状态码：" + response.code());
            }

            String responseBody = response.body() != null ? response.body().string() : "";
            log.debug("API 响应：{}", responseBody.substring(0, Math.min(500, responseBody.length())));
            return responseBody;
        }
    }

    /**
     * 解析 API 响应
     */
    private List<LogEntry> parseResponse(String response, LogQueryDto query) throws Exception {
        List<LogEntry> logs = new ArrayList<>();

        try {
            JsonNode rootNode = objectMapper.readTree(response);

            // 解析 data.data.query_result.data.rows
            JsonNode rowsNode = rootNode.path("data").path("data")
                    .path("query_result").path("data").path("rows");

            if (!rowsNode.isArray()) {
                log.warn("响应格式不正确，未找到 rows 数组");
                return logs;
            }

            // 处理每一行日志
            Set<String> seenMessages = new HashSet<>();
            for (JsonNode row : rowsNode) {
                try {
                    LogEntry entry = parseLogEntry(row, query);

                    // 去重
                    if (entry != null && entry.getMessage() != null) {
                        if (!seenMessages.contains(entry.getMessage())) {
                            seenMessages.add(entry.getMessage());
                            logs.add(entry);
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析单条日志失败", e);
                }
            }

        } catch (Exception e) {
            log.error("解析 API 响应失败", e);
            throw e;
        }

        return logs;
    }

    /**
     * 解析单条日志条目
     */
    private LogEntry parseLogEntry(JsonNode row, LogQueryDto query) {
        LogEntry entry = new LogEntry();

        // 获取原始字段映射
        Map<String, Object> rawFields = new HashMap<>();
        Iterator<String> fieldNames = row.fieldNames();
        while (fieldNames.hasNext()) {
            String fieldName = fieldNames.next();
            try {
                JsonNode field = row.get(fieldName);
                if (field != null) {
                    rawFields.put(fieldName, field.asText());
                }
            } catch (Exception e) {
                // 忽略无法转换为文本的字段
            }
        }
        entry.setRawFields(rawFields);

        // 日志 ID - 支持多种字段名
        String id = getFieldValue(row, "_id", "id", "logId");
        if (id != null) {
            try {
                // 尝试解析为数字，失败则使用哈希值
                entry.setId((long) Math.abs(id.hashCode()));
            } catch (Exception e) {
                entry.setId((long) id.hashCode());
            }
        }

        // 时间戳 - 支持多种字段名
        String timestamp = getFieldValue(row, "@timestamp", "timestamp", "logtime", "timestamp_topic");
        if (timestamp != null) {
            entry.setTimestamp(timestamp);
        }

        // 日志级别 - 支持多种字段名 (loglevel, level, log_level)
        String level = getFieldValue(row, "loglevel", "level", "log_level", "severity");
        if (level != null) {
            entry.setLevel(level.toUpperCase());
        }

        // 日志消息/内容 - 支持多种字段名
        String message = getFieldValue(row, "message", "content", "log", "msg");
        if (message != null) {
            entry.setMessage(message);
            entry.setRawContent(message);
        }

        // 提取错误信息
        if (message != null && !message.isEmpty()) {
            extractErrorInformation(entry, message);
        }

        // Trace ID - 支持多种字段名和格式
        String traceId = getFieldValue(row, "traceId", "trace_id", "traceid", "requestId", "request_id");
        if (traceId == null) {
            // 尝试从 threadid 中提取 (格式: http-nio-8003-exec-102 - RFrAY0W6MZ)
            String threadId = getFieldValue(row, "threadid", "thread_id", "thread");
            if (threadId != null && threadId.contains(" - ")) {
                String[] parts = threadId.split(" - ");
                if (parts.length > 1) {
                    traceId = parts[1].trim();
                }
            }
        }
        if (traceId != null) {
            entry.setTraceId(traceId);
        }

        // 服务名称 - 支持多种字段名，组合 appname + subappname
        String appName = getFieldValue(row, "appname", "appName", "app_name", "application", "service", "serviceName", "service_name");
        String subAppName = getFieldValue(row, "subappname", "subAppName", "subapp", "sub_app");
        if (appName != null) {
            String serviceName = appName;
            if (subAppName != null && !subAppName.isEmpty() && !subAppName.equals(appName)) {
                serviceName = appName + "/" + subAppName;
            }
            entry.setServiceName(serviceName);
        }

        // Pod 名称 - 支持多种字段名
        String podName = getFieldValue(row, "podName", "pod_name", "pod", "pod_name");
        if (podName == null) {
            // 尝试从 hostname 推断 pod 名称
            String hostname = getFieldValue(row, "hostname");
            if (hostname != null && hostname.contains("-")) {
                // Kubernetes pod 名称通常包含多个连字符和数字后缀
                podName = hostname;
            }
        }
        if (podName != null) {
            entry.setPodName(podName);
        }

        // 主机名
        String hostname = getFieldValue(row, "hostname", "host", "host_name");
        if (hostname != null) {
            entry.setHostname(hostname);
        }

        // 容器名称 - 支持多种字段名
        String containerName = getFieldValue(row, "containerName", "container_name", "container");
        if (containerName != null) {
            entry.setContainerName(containerName);
        }

        // 命名空间 - 支持多种字段名
        String namespace = getFieldValue(row, "namespace", "name_space", "ns");
        if (namespace != null) {
            entry.setNamespace(namespace);
        }

        // 日志源/文件 - 支持多种字段名
        String logSource = getFieldValue(row, "source", "logSource", "log_source", "file", "log_file", "source_file");
        if (logSource != null) {
            entry.setLogSource(logSource);
        }

        // 环境
        String env = getFieldValue(row, "env", "environment");
        if (env != null && entry.getNamespace() == null) {
            entry.setNamespace(env);
        }

        return entry;
    }

    /**
     * 从 JSON 节点中获取字段值，支持多个可能的字段名
     */
    private String getFieldValue(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode fieldNode = node.path(fieldName);
            if (fieldNode != null && !fieldNode.isMissingNode() && !fieldNode.isNull()) {
                String value = fieldNode.asText();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * 提取错误信息
     */
    private void extractErrorInformation(LogEntry entry, String message) {
        // 判断是否包含堆栈信息
        boolean hasStackTrace = message.contains("Exception") ||
                                message.contains("Error") ||
                                message.contains("at ") ||
                                message.contains("Caused by:");
        entry.setHasStackTrace(hasStackTrace);

        // 尝试解析标准日志格式，提取更多信息
        // 格式示例: 2026-03-20 08:45:49.823 [http-nio-8003-exec-102 - RFrAY0W6MZ] ERROR c.h.p.service.impl.PbiProjectOrgService : queryPbiOrgById query org by pbi error
        parseStandardLogFormat(entry, message);

        // 提取堆栈信息
        if (hasStackTrace) {
            StringBuilder stackTrace = new StringBuilder();
            String[] lines = message.split("\n");
            boolean inStack = false;

            for (String line : lines) {
                String trimmedLine = line.trim();
                if (trimmedLine.contains("Exception") || trimmedLine.contains("Error") ||
                    trimmedLine.startsWith("at ") || trimmedLine.startsWith("Caused by:")) {
                    inStack = true;
                }
                if (inStack) {
                    stackTrace.append(line).append("\n");
                }
            }

            if (stackTrace.length() > 0) {
                entry.setStackTrace(stackTrace.toString().trim());
            }
        }

        // 提取错误类型
        extractErrorType(entry, message);

        // 计算行数
        entry.setLineCount(message.split("\n").length);
    }

    /**
     * 解析标准日志格式
     * 格式: TIMESTAMP [THREAD] LEVEL LOGGER : MESSAGE
     */
    private void parseStandardLogFormat(LogEntry entry, String message) {
        // 正则匹配标准日志格式
        // 示例: 2026-03-20 08:45:49.823 [http-nio-8003-exec-102 - RFrAY0W6MZ] ERROR c.h.p.service.impl.PbiProjectOrgService : queryPbiOrgById...
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
            "^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d+)\\s*\\[([^\\]]+)\\]\\s*(\\w+)\\s+([\\w.]+)\\s*:\\s*(.*)$",
            java.util.regex.Pattern.DOTALL
        );

        java.util.regex.Matcher matcher = pattern.matcher(message);
        if (matcher.find()) {
            // 提取日志级别（如果还没设置）
            if (entry.getLevel() == null || entry.getLevel().isEmpty()) {
                entry.setLevel(matcher.group(3).toUpperCase());
            }

            // 提取线程信息
            String threadInfo = matcher.group(2);
            // 如果 traceId 没设置，尝试从线程信息提取
            if (entry.getTraceId() == null && threadInfo.contains(" - ")) {
                String[] parts = threadInfo.split(" - ");
                if (parts.length > 1) {
                    entry.setTraceId(parts[1].trim());
                }
            }

            // 提取 logger 名称（通常是类名）
            String logger = matcher.group(4);
            if (entry.getServiceName() == null && logger != null && !logger.isEmpty()) {
                // 将缩写类名转为完整名称，例如 c.h.p.service -> com.huawei.project.service
                String fullClassName = expandAbbreviatedClassName(logger);
                entry.setServiceName(fullClassName);
            }
        }
    }

    /**
     * 展开缩写的类名
     * 例如: c.h.p.service.impl.PbiProjectOrgService -> com.huawei.project.service.impl.PbiProjectOrgService
     */
    private String expandAbbreviatedClassName(String abbreviated) {
        if (abbreviated == null || !abbreviated.contains(".")) {
            return abbreviated;
        }

        String[] parts = abbreviated.split("\\.");
        StringBuilder result = new StringBuilder();

        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i > 0) {
                result.append(".");
            }

            // 如果是单字母缩写，尝试展开常见前缀
            if (part.length() == 1 || (part.length() == 2 && part.endsWith("x"))) {
                switch (part) {
                    case "c":
                        result.append("com");
                        break;
                    case "o":
                        result.append("org");
                        break;
                    case "n":
                        result.append("net");
                        break;
                    case "i":
                        result.append("io");
                        break;
                    case "j":
                        result.append("java");
                        break;
                    case "h":
                        result.append("huawei");
                        break;
                    case "p":
                        result.append("project");
                        break;
                    default:
                        result.append(part);
                }
            } else {
                result.append(part);
            }
        }

        return result.toString();
    }

    /**
     * 提取错误类型
     */
    private void extractErrorType(LogEntry entry, String message) {
        // 常见异常类型模式
        String[] exceptionPatterns = {
            "([\\w.]+Exception)",
            "([\\w.]+Error)",
            "([\\w.]+Fault)",
            "([\\w.]+Failure)"
        };

        for (String pattern : exceptionPatterns) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile(pattern);
            java.util.regex.Matcher m = p.matcher(message);
            if (m.find()) {
                String exceptionClass = m.group(1);
                // 提取简短类名
                int lastDot = exceptionClass.lastIndexOf('.');
                if (lastDot >= 0) {
                    entry.setErrorType(exceptionClass.substring(lastDot + 1));
                } else {
                    entry.setErrorType(exceptionClass);
                }
                return;
            }
        }

        // 检查是否有 ERROR 关键字
        if (message.contains("ERROR") || message.contains("error")) {
            entry.setErrorType("ERROR");
        }
    }

    /**
     * 格式化时间戳为 API 需要的格式
     */
    private String formatTimestamp(java.time.LocalDateTime time) {
        if (time == null) {
            // 默认返回 1 小时前的时间
            return java.time.LocalDateTime.now()
                    .minusHours(1)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}