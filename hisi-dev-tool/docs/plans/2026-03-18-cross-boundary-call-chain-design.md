# 跨边界调用链追踪系统设计文档

## 概述

本文档描述了 HiSi DevTool 后端静态分析系统中跨边界调用链追踪功能的设计方案。

## 背景

### 问题陈述

当前基于 JavaParser 的静态分析系统无法追踪以下类型的调用链路：

1. **MQ 调用**：消息队列的生产者-消费者之间的调用关系
2. **跨服务调用**：Feign 客户端或 HTTP 客户端到远程服务的调用
3. **代理类调用**：MyBatis Mapper、JPA Repository、AOP 代理等动态代理类的实际执行逻辑

### 目标

构建完整的跨边界调用链追踪能力，实现：

- 自动识别 MQ 端点（Kafka、RabbitMQ、RocketMQ、JMS）
- 自动识别 Feign 客户端和 HTTP 调用端点
- 自动识别代理类（MyBatis、JPA、AOP）
- 构建跨服务的调用链路图
- 生成服务拓扑关系

## 架构设计

### 整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    ChainAnalysisCoordinator                      │
│                    (协调服务层 - 入口点)                          │
└───────────────────────────┬─────────────────────────────────────┘
                            │
        ┌───────────────────┼───────────────────┐
        │                   │                   │
        ▼                   ▼                   ▼
┌───────────────┐   ┌───────────────┐   ┌───────────────┐
│  Scanner 层   │   │  Bridge 层    │   │  Cache 层     │
│  (端点扫描)   │   │  (桥接匹配)   │   │  (全局缓存)   │
└───────────────┘   └───────────────┘   └───────────────┘
        │                   │                   │
        ▼                   ▼                   ▼
┌─────────────────────────────────────────────────────────────────┐
│                      Repository 层                               │
│           (数据持久化 - PostgreSQL/OpenGauss)                    │
└─────────────────────────────────────────────────────────────────┘
```

### 模块设计

#### 1. Scanner 层

负责扫描和识别各类端点。

| 扫描器 | 职责 | 识别目标 |
|--------|------|----------|
| MQEndpointScanner | MQ 端点扫描 | @KafkaListener, @RabbitListener, @RocketMQMessageListener, @JmsListener |
| FeignClientScanner | Feign 客户端扫描 | @FeignClient 注解及其方法 |
| HttpCallScanner | HTTP 调用扫描 | RestTemplate, WebClient 调用 |
| ProxyClassScanner | 代理类扫描 | @Mapper, JpaRepository, @Aspect |

#### 2. Bridge 层

负责连接跨边界的调用关系。

| 桥接器 | 匹配方式 | 输出 |
|--------|----------|------|
| MQChainBridge | Topic 匹配 | mq_call_bridge 表 |
| HttpChainBridge | 服务名 + URI 匹配 | http_call_bridge 表 |
| ProxyChainBridge | 接口方法映射 | proxy_metadata 表 |

#### 3. Cache 层

全局缓存管理，存储分析过程中的中间数据。

```java
public class GlobalAnalysisCache {
    private Map<String, ClassOrInterfaceDeclaration> beanMap;      // Bean 定义
    private Map<String, List<String>> extendMap;                   // 继承关系
    private Map<String, List<String>> implementationMap;           // 实现关系
    private Map<String, List<MethodInfo>> uriMap;                  // URI 映射
    private List<MQEndpoint> mqEndpoints;                          // MQ 端点
    private List<FeignClientInfo> feignClients;                    // Feign 客户端
    private List<ProxyMetadata> proxyMetadataList;                 // 代理元数据
}
```

#### 4. Coordinator 层

协调服务，统筹整个分析流程。

```java
public interface ChainAnalysisCoordinator {
    void initialize(String projectPath);
    void scanEndpoints();
    void buildBridges();
    void buildServiceTopology();
    AnalysisResult getAnalysisResult();
}
```

## 数据模型

### 数据库表设计

#### mq_call_bridge 表

存储 MQ 生产者-消费者关系。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| source_method | VARCHAR(500) | 发送方法 |
| source_class | VARCHAR(500) | 发送类 |
| topic | VARCHAR(256) | Topic 名称 |
| message_type | VARCHAR(50) | 消息类型 (KAFKA/RABBITMQ/ROCKETMQ/JMS) |
| target_method | VARCHAR(500) | 消费方法 |
| target_class | VARCHAR(500) | 消费类 |
| consumer_group | VARCHAR(256) | 消费者组 |
| package | VARCHAR(256) | 包名 |
| metadata | JSONB | 扩展元数据 |

#### http_call_bridge 表

存储 HTTP 客户端到服务端的关系。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| source_method | VARCHAR(500) | 调用方方法 |
| source_class | VARCHAR(500) | 调用方类 |
| service_name | VARCHAR(256) | 目标服务名 |
| http_method | VARCHAR(10) | HTTP 方法 |
| uri_pattern | VARCHAR(512) | URI 模式 |
| target_method | VARCHAR(500) | 目标方法 |
| target_class | VARCHAR(500) | 目标类 |
| package | VARCHAR(256) | 包名 |
| metadata | JSONB | 扩展元数据 |

#### proxy_metadata 表

存储代理类元数据。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| interface_name | VARCHAR(500) | 接口名称 |
| interface_type | VARCHAR(50) | 接口类型 (MYBATIS/JPA/AOP) |
| implementation | VARCHAR(500) | 实现类 |
| proxy_type | VARCHAR(50) | 代理类型 |
| method_name | VARCHAR(256) | 方法名 |
| method_signature | VARCHAR(1000) | 方法签名 |
| sql_statement | TEXT | SQL 语句 (MyBatis) |
| entity_type | VARCHAR(500) | 实体类型 (JPA) |
| package | VARCHAR(256) | 包名 |
| metadata | JSONB | 扩展元数据 |

#### service_topology 表

存储服务拓扑关系。

| 字段 | 类型 | 说明 |
|------|------|------|
| id | BIGSERIAL | 主键 |
| source_service | VARCHAR(256) | 源服务 |
| target_service | VARCHAR(256) | 目标服务 |
| call_type | VARCHAR(50) | 调用类型 (FEIGN/HTTP/MQ/GRPC) |
| endpoint | VARCHAR(512) | 端点 |
| package | VARCHAR(256) | 包名 |
| metadata | JSONB | 扩展元数据 |

## 处理流程

### 分析流程

```
1. 初始化 (initialize)
   │
   ├── 解析项目结构
   ├── 构建 AST 树
   └── 初始化全局缓存

2. 端点扫描 (scanEndpoints)
   │
   ├── MQ 端点扫描
   │   ├── 扫描 @KafkaListener 等消费端
   │   └── 扫描 KafkaTemplate.send() 等生产端
   │
   ├── Feign 客户端扫描
   │   ├── 扫描 @FeignClient 接口
   │   └── 解析方法 HTTP 端点
   │
   ├── HTTP 调用扫描
   │   ├── 扫描 RestTemplate 调用
   │   └── 扫描 WebClient 调用
   │
   └── 代理类扫描
       ├── MyBatis @Mapper 扫描
       ├── JPA Repository 扫描
       └── AOP @Aspect 扫描

3. 桥接构建 (buildBridges)
   │
   ├── MQ 桥接：通过 Topic 匹配生产者和消费者
   ├── HTTP 桥接：通过服务名 + URI 匹配
   └── 代理桥接：解析实际执行逻辑

4. 服务拓扑构建 (buildServiceTopology)
   │
   └── 聚合所有跨服务调用关系

5. 数据持久化
   │
   ├── 写入 mq_call_bridge
   ├── 写入 http_call_bridge
   ├── 写入 proxy_metadata
   └── 写入 service_topology
```

## API 设计

### 新增 REST 端点

```java
// 获取跨服务调用链
GET /api/callchain/cross-service?uri={uri}

// 获取服务拓扑
GET /api/callchain/topology?project={project}

// 触发全量分析
POST /api/method_chain/generate
```

### 响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "uri": "/api/users/{id}",
    "localChain": [...],
    "crossServiceCalls": [
      {
        "type": "FEIGN",
        "targetService": "user-service",
        "endpoint": "/api/users/{id}",
        "targetMethod": "UserController.getUser"
      },
      {
        "type": "MQ",
        "topic": "user-events",
        "producer": "UserService.publishEvent",
        "consumer": "NotificationService.handleUserEvent"
      }
    ]
  }
}
```

## 技术选型

| 组件 | 技术 | 说明 |
|------|------|------|
| AST 解析 | JavaParser | 解析 Java 源代码 |
| 类型解析 | JavaSymbolSolver | 解析类型引用 |
| 数据库 | OpenGauss (PostgreSQL) | 存储分析结果 |
| 缓存 | 内存 Map | 分析过程缓存 |
| JSON 处理 | Jackson | JSON 序列化 |

## 扩展性设计

### 添加新的 MQ 类型

1. 在 `MQEndpointScanner` 中添加新注解识别
2. 在 `MQEndpoint` 中添加新类型枚举
3. 无需修改桥接逻辑

### 添加新的 HTTP 客户端

1. 在 `HttpCallScanner` 中添加新客户端识别
2. 实现 URL 提取逻辑
3. 自动纳入桥接匹配

### 添加新的代理类型

1. 在 `ProxyClassScanner` 中添加新代理识别
2. 在 `ProxyMetadata` 中添加新类型
3. 实现对应的桥接逻辑

## 限制与约束

1. **静态分析限制**：无法追踪动态生成的代码
2. **反射调用限制**：无法追踪通过反射调用的方法
3. **配置限制**：需要在代码中有明确的注解声明
4. **多语言限制**：仅支持 Java 项目

## 未来规划

1. **动态代理增强**：支持运行时代理分析
2. **配置文件解析**：支持 application.yml 中的配置解析
3. **多语言支持**：支持 Python、Go 等语言
4. **可视化界面**：前端展示调用链路图

## 附录

### 文件清单

```
src/main/java/com/huawei/hisi/
├── cache/
│   └── GlobalAnalysisCache.java
├── scanner/
│   ├── EndpointScanner.java
│   ├── MQEndpointScanner.java
│   ├── FeignClientScanner.java
│   ├── HttpCallScanner.java
│   └── ProxyClassScanner.java
├── bridge/
│   ├── ChainBridge.java
│   ├── MQChainBridge.java
│   ├── HttpChainBridge.java
│   └── ProxyChainBridge.java
├── model/
│   ├── MQEndpoint.java
│   ├── FeignClientInfo.java
│   ├── HttpCallInfo.java
│   ├── ProxyMetadata.java
│   └── ScanResult.java
├── service/
│   ├── ChainAnalysisCoordinator.java
│   └── ChainAnalysisCoordinatorImpl.java
└── repository/
    ├── MQBridgeRepository.java
    ├── HttpBridgeRepository.java
    └── ProxyMetadataRepository.java

db/init.sql  # 数据库初始化脚本
```

### 版本历史

| 版本 | 日期 | 作者 | 说明 |
|------|------|------|------|
| 1.0.0 | 2026-03-18 | HiAPM Team | 初始版本 |