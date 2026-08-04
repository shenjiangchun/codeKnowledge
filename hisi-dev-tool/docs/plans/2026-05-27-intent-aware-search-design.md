# 意图感知多通道检索方案 (Intent-Aware Multi-Channel Search)

## 1. 背景与问题

当前混合检索在"需求状态反标"场景下表现差：

- **语义鸿沟**：需求文档用"回卷"、"每10分钟"，代码用 `syncReqStatus`、`@Scheduled`
- **关键词噪声**：引入关键词补充后，"同步"、"状态"等高频词在 description 中匹配到大量无关方法（如 `syncDifferencesTemplate`、`syncJdcData`），且因多路命中 RRF 分数反超真正相关的向量结果
- **权重扁平**：所有检索通道等权 RRF，图扩展的弱关联结果与精准向量结果同权竞争

## 2. 方案核心思想

**分词时做意图类型标注 → 检索时走专用通道 → 命中加权**

子查询不再是纯字符串，而是 `(query, intentType)` 对。不同意图类型走不同的专用检索通道，专用通道命中权重远高于通用向量检索。

## 3. 意图类型定义

| IntentType | 触发词/模式 | 专用检索通道 | 匹配方式 |
|---|---|---|---|
| `SCHEDULE` | 定时/周期/cron/schedule/每隔/每N分钟 | `@Scheduled` + methodName 含 Schedule/Cron/Task | ANNOTATION 检索 `@Scheduled` + methodName 模糊 |
| `HTTP` | 接口/API/URI/请求/HTTP/REST/Controller | entryPoint entryKey 匹配 | HTTP_URI 检索 |
| `SQL` | SQL/数据库/查询/Mapper/MyBatis/表/字段 | sqlEmbedding 向量 + Mapper 接口 | SQL_SNIPPET 检索 |
| `EXCEPTION` | 异常/报错/失败/捕获/抛出/try-catch | thrownExceptions/caughtExceptions | EXCEPTION_TYPE 检索 |
| `LISTENER` | 回调/事件/监听/触发/订阅 | @EventListener/@Async/@KafkaListener 等 | ANNOTATION 检索 |
| `GENERAL` | 无特殊模式（默认） | descriptionEmbedding 向量检索 | NATURAL_LANGUAGE 检索 |

## 4. 数据模型变更

### 4.1 SubQuery（新增）

```java
public record SubQuery(String query, IntentType intentType) {}
```

### 4.2 IntentType（新增枚举）

```java
public enum IntentType {
    SCHEDULE, HTTP, SQL, EXCEPTION, LISTENER, GENERAL
}
```

### 4.3 分词返回值变更

`QueryDecomposer.decompose()` 返回 `List<SubQuery>` 而非 `List<String>`。

## 5. LLM Prompt 设计

```
你是一个代码搜索查询分解器。将用户的需求描述拆分为独立的语义搜索子查询。

每个子查询必须标注意图类型：
- SCHEDULE: 定时任务、周期性执行、cron、每隔N分钟
- HTTP: HTTP接口、API端点、REST请求、Controller
- SQL: SQL操作、数据库查询、Mapper、MyBatis
- EXCEPTION: 异常处理、错误捕获、try-catch
- LISTENER: 事件回调、消息监听、订阅触发
- GENERAL: 通用功能点（无特殊类型）

核心原则：
1. 每个功能点生成需求侧术语 + 代码侧术语变体的双重子查询
2. 对定时/周期性任务，标注 SCHEDULE 类型，同时生成包含 schedule/cron/定时 的子查询
3. 对涉及API/接口的功能，标注 HTTP 类型
4. 对涉及数据库操作的功能，标注 SQL 类型

返回 JSON: {"queries": [{"query": "子查询文本", "type": "SCHEDULE"}, ...]}

示例：
输入："上游需求下发后进展情况无法编辑，当前需求状态的变更逻辑，总体原则有子项看子项的进度卷积，每10分钟刷新一次，下游状态回卷到上游"
输出：{"queries": [
  {"query": "需求下发后进展情况编辑", "type": "GENERAL"},
  {"query": "需求状态变更逻辑", "type": "GENERAL"},
  {"query": "require status change update", "type": "GENERAL"},
  {"query": "子项进度卷积规则", "type": "GENERAL"},
  {"query": "下游状态回卷机制", "type": "GENERAL"},
  {"query": "需求反标 syncReqStatus", "type": "SCHEDULE"},
  {"query": "需求状态定时同步 schedule cron", "type": "SCHEDULE"},
  {"query": "需求基线与状态流转", "type": "GENERAL"},
  {"query": "关联TP的EDA验证进度", "type": "GENERAL"},
  {"query": "已发行状态替换代码引用", "type": "GENERAL"}
]}
```

## 6. 检索路由与加权

### 6.1 通道权重 (Weighted RRF)

```java
// score(d) = Σ w_channel / (K + rank_channel(d))
private static final Map<IntentType, Double> CHANNEL_WEIGHTS = Map.of(
    IntentType.SCHEDULE,   3.0,  // 专用注解匹配，精准度高
    IntentType.HTTP,       3.0,  // 入口点匹配，精准度高
    IntentType.SQL,        2.5,  // SQL向量+Mapper，精准度中高
    IntentType.EXCEPTION,  2.5,  // 异常类型匹配，精准度中高
    IntentType.LISTENER,   2.5,  // 注解匹配，精准度中高
    IntentType.GENERAL,    1.0   // 通用向量检索，基准权重
);
```

### 6.2 信号源权重（NATURAL_LANGUAGE 内部）

当 GENERAL 子查询走 `hybridSearch()` 内部的向量+图扩展 RRF 时：

| 信号源 | 权重 | 理由 |
|---|---|---|
| descriptionEmbedding 向量检索 | 1.0 | 基准，语义相关性 |
| codeEmbedding 向量检索 | 0.7 | 代码级语义，略低于描述级 |
| 图扩展 (callers/callees) | 0.3 | 关联性弱，仅作补充 |
| methodName 关键词补充 | 追加到末尾 | 不参与 RRF，只做兜底补充 |

### 6.3 检索路由逻辑

```java
for (SubQuery sq : subQueries) {
    SearchResult sr = switch (sq.intentType()) {
        case SCHEDULE   -> searchScheduleChannel(sq.query(), projectPaths, limit);
        case HTTP       -> searchHttpChannel(sq.query(), projectPaths, limit);
        case SQL        -> searchSqlChannel(sq.query(), projectPaths, limit);
        case EXCEPTION  -> searchExceptionChannel(sq.query(), projectPaths, limit);
        case LISTENER   -> searchListenerChannel(sq.query(), projectPaths, limit);
        case GENERAL    -> hybridSearchService.hybridSearch(sq.query(), ...);
    };
    // 加权 RRF 融合
    double weight = CHANNEL_WEIGHTS.get(sq.intentType());
    for (int rank = 0; rank < sr.getResults().size(); rank++) {
        String nodeId = sr.getResults().get(rank).getNodeId();
        rrfScores.merge(nodeId, weight / (RRF_K + rank + 1), Double::sum);
    }
}
```

### 6.4 专用通道实现

**SCHEDULE 通道**：
```java
private SearchResult searchScheduleChannel(String query, List<String> projectPaths, int limit) {
    // 1. @Scheduled 注解匹配 → 获取所有定时方法
    // 2. 在定时方法中做 descriptionEmbedding 向量筛选（缩小范围后精准匹配）
    // 3. methodName 含 Schedule/Cron/Task/Sync 的模糊匹配
    // 4. RRF 融合注解命中 + 向量筛选 + 方法名匹配
}
```

**HTTP 通道**：
```java
private SearchResult searchHttpChannel(String query, List<String> projectPaths, int limit) {
    // 1. entryPoint 匹配（@RequestMapping/@GetMapping/@PostMapping 等）
    // 2. 在入口方法中做 descriptionEmbedding 向量筛选
    // 3. RRF 融合
}
```

**SQL 通道**：复用现有 `searchBySqlSnippetWithScores()`

**EXCEPTION 通道**：复用现有 `searchByExceptionType()`

**LISTENER 通道**：
```java
private SearchResult searchListenerChannel(String query, List<String> projectPaths, int limit) {
    // 1. @EventListener/@Async/@KafkaListener/@RabbitListener 注解匹配
    // 2. 在监听方法中做 descriptionEmbedding 向量筛选
    // 3. RRF 融合
}
```

## 7. 兼容性

- **向后兼容**：`SubQuery` 的 `intentType` 默认为 `GENERAL`，不标注类型的子查询走原有路径
- **单路降级**：只有一个子查询时退化为普通 `hybridSearch()`
- **前端不变**：前端只消费 `SearchResult`，不感知 `IntentType`
- **RAM 需求分析大师**：`InvolvedRingResolver` 和 `MultiQuerySearcher` 也改为使用 `SubQuery`，享受意图感知检索

## 8. 关键风险与缓解

| 风险 | 缓解 |
|---|---|
| LLM 类型标注不准 | GENERAL 兜底，不会漏掉；误标注只是走了专用通道但权重合理 |
| SCHEDULE 通道 @Scheduled 匹配范围太广 | 先注解匹配缩小范围，再做向量筛选，避免噪声 |
| 权重设定不当 | 初始值保守，后续可配置化 |
| LLM 返回格式不稳定 | 保留纯字符串解析兜底，兼容老格式 |
| 关键词补充噪声重现 | 关键词补充只追加到末尾、只匹配 methodName、不参与 RRF |

## 9. 涉及文件

| 文件 | 改动 |
|---|---|
| `SubQuery.java` | 新增 record |
| `IntentType.java` | 新增枚举 |
| `QueryDecomposer.java` | 返回 `List<SubQuery>`，prompt 增加类型标注指令 |
| `MultiQueryHybridSearchService.java` | 按意图类型路由检索，加权 RRF |
| `HybridSearchService.java` | 新增专用通道方法，内部向量+图扩展加权 RRF |
| `MultiQuerySearcher.java` | 适配 `SubQuery`，加权 RRF |
| `InvolvedRingResolver.java` | 适配 `SubQuery` |
| `SearchResult.java` | 可选：新增 `intentTypes` 字段供前端展示 |
| `application.yml` | 可选：通道权重可配置化 |
