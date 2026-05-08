# 调用链追踪增强功能设计文档

## 概述

本文档描述了调用链追踪系统的增强功能设计，包括：
- 桥接表与调用链生成器的深度集成
- 调用类型分类追踪
- 项目目录运行时配置
- 用户引导页面与 Git 操作支持

## 设计决策

### 桥接表集成方式
**选择方案 C**：在调用链生成初始化阶段完成桥接表构建，后续生成时遇到非直接调用方法通过桥接表查找下游方法并继续生成。

### 调用类型分类
**选择方案 A**：详细分类
- `DIRECT` - 直接方法调用
- `MQ_SEND` - MQ 消息发送
- `MQ_RECEIVE` - MQ 消息接收
- `FEIGN` - Feign 客户端调用
- `HTTP` - HTTP 客户端调用
- `MYBATIS` - MyBatis Mapper 调用
- `JPA` - JPA Repository 调用
- `AOP` - AOP 切面调用

### PROJECT_DIR 配置
**选择方案 A**：单一目录配置，存储在数据库 `app_config` 表中，运行时可修改。

### 用户流程
**选择方案 A**：严格引导流程，用户必须先配置 PROJECT_DIR 并选择项目后才能进入调用链分析和日志分析页面。

---

## Phase 1: 数据库升级与桥接集成

### 1.1 数据库表结构变更

#### method_call_graph5 表增强

```sql
ALTER TABLE hiapm_test.method_call_graph5
ADD COLUMN IF NOT EXISTS call_type VARCHAR(20) DEFAULT 'DIRECT';

ALTER TABLE hiapm_test.method_call_graph5
ADD COLUMN IF NOT EXISTS project_dir VARCHAR(512);

CREATE INDEX IF NOT EXISTS idx_mcg_call_type ON hiapm_test.method_call_graph5(call_type);
CREATE INDEX IF NOT EXISTS idx_mcg_project_dir ON hiapm_test.method_call_graph5(project_dir);
```

#### 桥接表增加 project_dir 字段

所有桥接表 (`mq_call_bridge`, `http_call_bridge`, `proxy_metadata`, `service_topology`) 增加 `project_dir` 字段用于数据隔离。

#### app_config 配置表

```sql
CREATE TABLE IF NOT EXISTS app_config (
    key             VARCHAR(100) PRIMARY KEY,
    value           VARCHAR(1000) NOT NULL,
    description     VARCHAR(500),
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_by      VARCHAR(100) DEFAULT 'system'
);
```

默认配置项：
- `PROJECT_DIR` - 项目代码存放目录

### 1.2 桥接表集成流程

```
chainGenerator() 初始化
    ├── 调用 ChainAnalysisCoordinator.buildAllBridges()
    │   ├── MQEndpointScanner.scan() → mq_call_bridge
    │   ├── FeignClientScanner.scan() → http_call_bridge
    │   ├── HttpCallScanner.scan() → http_call_bridge
    │   └── ProxyClassScanner.scan() → proxy_metadata
    │
    ├── 构建内存索引
    │   ├── mqTopicIndex: Map<Topic, List<MQEndpoint>>
    │   ├── feignUriIndex: Map<ServiceName+URI, List<FeignClientInfo>>
    │   └── proxyIndex: Map<InterfaceName, ProxyMetadata>
    │
    └── 开始常规调用链遍历
```

---

## Phase 2: PROJECT_DIR 运行时配置

### 2.1 后端 API

#### 获取配置
```
GET /api/config?key=PROJECT_DIR
Response: {
  "key": "PROJECT_DIR",
  "value": "/path/to/projects",
  "description": "项目代码存放目录"
}
```

#### 更新配置
```
PUT /api/config
Body: {
  "key": "PROJECT_DIR",
  "value": "/new/path/to/projects"
}
Response: {
  "success": true,
  "message": "配置更新成功"
}
```

### 2.2 配置服务实现

```java
@Service
public class AppConfigServiceImpl implements AppConfigService {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private volatile String projectDir;

    @PostConstruct
    public void init() {
        // 启动时从数据库加载
        loadProjectDir();
    }

    public synchronized void updateProjectDir(String newPath) {
        // 验证路径有效性
        // 更新数据库
        // 更新内存缓存
        // 触发项目列表刷新
    }
}
```

### 2.3 数据源配置联动

修改 `DataSourceConfig`，将静态 `PROJECT_DIR` 改为从 `AppConfigService` 动态获取。

---

## Phase 3: 前端引导页面与 Git 操作

### 3.1 用户引导流程

```
用户访问系统
    │
    ├── 检查 PROJECT_DIR 是否配置
    │   ├── 未配置 → 显示配置引导页
    │   └── 已配置 → 继续
    │
    ├── 扫描项目列表
    │   ├── 无项目 → 显示 Git 克隆引导
    │   └── 有项目 → 显示项目列表
    │
    ├── 用户选择项目
    │
    └── 解锁功能菜单
        ├── 调用链分析 ✓
        ├── 日志分析 ✓
        └── 运维监控 ✗ (移除)
```

### 3.2 项目管理页面功能

#### 项目列表展示
- 项目名称
- 路径
- Git 状态（有/无 .git 目录）
- 最后分析时间
- 操作按钮

#### Git 操作（仅 .git 项目显示）

| 操作 | API | 说明 |
|------|-----|------|
| 状态 | `GET /api/git/status?path={path}` | 当前分支、修改状态 |
| 切换分支 | `POST /api/git/checkout` | 切换到指定分支 |
| 拉取 | `POST /api/git/pull` | 拉取最新代码 |
| 日志 | `GET /api/git/logs?path={path}&limit=10` | 最近提交记录 |

### 3.3 前端组件设计

```vue
<!-- ProjectManagement.vue -->
<template>
  <div class="project-management">
    <!-- 配置区域 -->
    <ProjectDirConfig :current-dir="projectDir" @update="handleDirUpdate" />

    <!-- 项目列表 -->
    <ProjectList :projects="projects" @select="handleProjectSelect">
      <template #actions="{ project }">
        <GitOperations v-if="project.hasGit" :project="project" />
      </template>
    </ProjectList>
  </div>
</template>
```

### 3.4 菜单状态控制

```typescript
// store/modules/app.ts
const menuState = {
  projectDirConfigured: false,
  projectSelected: false,
  availableMenus: {
    'call-chain': computed(() => state.projectDirConfigured && state.projectSelected),
    'log-analysis': computed(() => state.projectDirConfigured && state.projectSelected),
    'ops-monitor': false, // 永久禁用
    'project-management': true // 始终可用
  }
}
```

---

## Phase 4: 调用链生成器深度改造

### 4.1 核心改造点

#### HisiURIMethodChainToDBServiceImpl 改造

```java
@Service
public class HisiURIMethodChainToDBServiceImpl {

    @Autowired
    private ChainAnalysisCoordinator coordinator;

    @Autowired
    private AppConfigService appConfigService;

    // 替换静态缓存
    // private static Map<String, ClassOrInterfaceDeclaration> BEAN_MAP = null;
    // 改为：
    // 使用 GlobalAnalysisCache 或直接从 coordinator 获取

    public void chainGenerator(String projectPath) {
        String currentProjectDir = appConfigService.getProjectDir();

        // 1. 初始化：构建桥接表
        coordinator.buildAllBridges(projectPath);

        // 2. 构建 URI 映射
        buildURIMap(projectPath);

        // 3. 遍历每个 URI
        for (String uri : URI_MAP.keySet()) {
            buildCallChainWithBridges(uri, currentProjectDir);
        }
    }

    private void buildCallChainWithBridges(String uri, String projectDir) {
        // DFS 遍历中遇到非直接调用时的处理
        // ...
    }
}
```

### 4.2 调用链生成中的桥接处理

```java
private void processMethodCall(MethodCallExpr call, int depth, String projectDir) {
    String methodName = call.getNameAsString();

    // 检查是否为 MQ 发送
    if (isMQSendCall(call)) {
        String topic = extractTopic(call);
        List<MQEndpoint> consumers = mqBridgeIndex.get(topic);

        for (MQEndpoint consumer : consumers) {
            // 记录 MQ_SEND 类型边
            saveCallEdge(parent, consumer.getMethod(), "MQ_SEND", projectDir);

            // 继续追踪消费端
            dfs(consumer.getMethod(), depth + 1, "MQ_RECEIVE", projectDir);
        }
        return;
    }

    // 检查是否为 Feign 调用
    if (isFeignCall(call)) {
        FeignClientInfo feign = extractFeignInfo(call);
        List<ControllerEndpoint> targets = feignBridgeIndex.get(feign.getUri());

        for (ControllerEndpoint target : targets) {
            saveCallEdge(parent, target.getMethod(), "FEIGN", projectDir);
            dfs(target.getMethod(), depth + 1, "DIRECT", projectDir);
        }
        return;
    }

    // 检查是否为代理调用
    if (isProxyCall(call)) {
        ProxyMetadata proxy = proxyIndex.get(call.getScope().toString());
        saveCallEdge(parent, proxy.getInterfaceName(), proxy.getProxyType(), projectDir);
        return; // 代理调用通常到此为止
    }

    // 默认直接调用处理
    processDirectCall(call, depth, projectDir);
}
```

### 4.3 数据隔离

所有写入操作都带上 `projectDir` 参数：

```sql
INSERT INTO method_call_graph5
(root_uri, parent_method, package, method_body, child_method, depth, call_type, project_dir)
VALUES (?, ?, ?, ?, ?, ?, ?, ?)
```

查询时也需过滤：

```sql
SELECT * FROM method_call_graph5
WHERE project_dir = ?
AND root_uri = ?
```

---

## 实现优先级

| 阶段 | 优先级 | 预估工时 | 风险 |
|------|--------|----------|------|
| Phase 1 | P0 | 2h | 低 |
| Phase 2 | P0 | 4h | 中 |
| Phase 3 | P1 | 8h | 中 |
| Phase 4 | P1 | 16h | 高 |

**建议实施顺序**：
1. Phase 1 + Phase 2 并行开发（基础设施）
2. Phase 3 前端开发（用户可见价值）
3. Phase 4 深度改造（核心功能增强）

---

## 文件变更清单

### 新增文件
```
src/main/java/com/huawei/hisi/
├── controller/ConfigController.java
├── service/AppConfigService.java
├── service/AppConfigServiceImpl.java
├── repository/AppConfigRepository.java
├── model/AppConfig.java

src/main/resources/db/migration/
└── V7__enhance_call_chain_tables.sql (已创建)
```

### 修改文件
```
src/main/java/com/huawei/hisi/
├── config/DataSourceConfig.java
├── service/HisiURIMethodChainToDBServiceImpl.java
├── service/CodeHubFetchServiceImpl.java (已完成)

db/init.sql (已更新)
```

### 前端文件
```
hisi-dev-tool-frontend/src/
├── views/ProjectManagement.vue (增强)
├── components/ProjectDirConfig.vue (新增)
├── components/GitOperations.vue (新增)
├── api/git.ts (新增)
├── api/config.ts (新增)
├── store/modules/app.ts (修改)
└── router/index.ts (修改菜单权限)
```

---

## 验收标准

### Phase 1
- [ ] 数据库表结构更新成功
- [ ] 迁移脚本可重复执行
- [ ] 索引创建正确

### Phase 2
- [ ] 前端可查看当前 PROJECT_DIR
- [ ] 前端可修改 PROJECT_DIR
- [ ] 修改后立即生效，无需重启

### Phase 3
- [ ] 未配置 PROJECT_DIR 时显示引导页
- [ ] 项目列表正确显示
- [ ] Git 操作功能正常
- [ ] 功能菜单根据状态正确启用/禁用

### Phase 4
- [ ] 调用链包含 MQ 跨服务调用
- [ ] 调用链包含 Feign 跨服务调用
- [ ] 代理调用正确标记类型
- [ ] 切换项目目录后数据隔离正确