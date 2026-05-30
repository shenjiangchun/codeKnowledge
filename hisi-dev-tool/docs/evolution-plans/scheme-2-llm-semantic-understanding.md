# 方案2: LLM原生代码语义理解

## 依赖层级声明

```
依赖层级图：
┌─────────────────────────────────────────────────────────┐
│                    应用层（本方案）                       │
│         LLM原生代码语义理解服务                          │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    基础设施层                            │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ LLMService      │  │ ClaudeSdkService │              │
│  │ (OkHttp+OpenAI) │  │ (Claude CLI)     │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    数据层                                │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ 代码库(Git)     │  │ 向量数据库       │              │
│  │ JavaParser      │  │ (pgvector)       │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘

前置依赖：
- LLMService 基础调用能力（已有）
- JavaParser 代码解析能力（已有）
- Git 仓库管理能力（已有）

可独立开发：
- 语义理解引擎核心逻辑
- 向量嵌入与检索服务
- 知识图谱构建模块

解耦点：
- 通过 Agent 接口与多 Agent 系统解耦
- 通过 Repository 接口与数据层解耦
- 可独立部署为微服务
```

---

## 一、目标与价值

### 1.1 核心目标

**将代码理解从"文本匹配"升级为"语义理解"**

| 当前状态 | 目标状态 |
|---------|---------|
| 堆栈行号 → 提取代码片段 → 文本分析 | 堆栈位置 → 语义图谱查询 → 深度理解 |
| 静态代码片段 | 代码语义上下文 |
| 孤立的方法分析 | 方法间的语义关联 |
| 无法理解代码意图 | 自动推断代码意图 |

### 1.2 价值主张

```
对开发者的价值：
├── 更精准的根因定位：理解代码意图，而非机械匹配
├── 更全面的上下文：自动关联相关代码，无需手动搜索
├── 更深入的分析：识别潜在的逻辑错误，而非仅语法错误
└── 更智能的建议：基于语义理解给出修复建议

对团队的价值：
├── 知识沉淀：代码语义图谱可复用
├── 新人培训：通过语义理解快速了解代码
└── 代码审查：自动识别潜在问题
```

### 1.3 成功指标

| 指标 | 基线 | 目标 |
|------|------|------|
| 根因定位准确率 | 70% | 85%+ |
| 分析上下文覆盖率 | 40% | 90%+ |
| 平均分析时间 | 30秒 | 15秒 |
| 用户满意度 | N/A | 4.0/5.0+ |

---

## 二、技术方案

### 2.1 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                   Code Semantic Engine                       │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Semantic Analysis Layer                 │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ Intent      │  │ Relation    │  │ Pattern     │  │   │
│  │  │ Analyzer    │  │ Extractor   │  │ Recognizer  │  │   │
│  │  │ (意图分析)  │  │ (关系提取)  │  │ (模式识别)  │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Knowledge Graph Layer                   │   │
│  │                                                     │   │
│  │     ┌───────────────────────────────────────┐       │   │
│  │     │         Code Knowledge Graph          │       │   │
│  │     │  ┌─────┐    ┌─────┐    ┌─────┐       │       │   │
│  │     │  │Class│───▶│Method│───▶│Field│       │       │   │
│  │     │  └─────┘    └─────┘    └─────┘       │       │   │
│  │     │      │          │          │         │       │   │
│  │     │      ▼          ▼          ▼         │       │   │
│  │     │  ┌──────────────────────────┐        │       │   │
│  │     │  │   Semantic Embeddings    │        │       │   │
│  │     │  │   (pgvector存储)          │        │       │   │
│  │     │  └──────────────────────────┘        │       │   │
│  │     └───────────────────────────────────────┘       │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Index & Retrieval Layer                 │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ Code        │  │ Vector      │  │ Hybrid      │  │   │
│  │  │ Indexer     │  │ Retriever   │  │ Search      │  │   │
│  │  │ (代码索引)  │  │ (向量检索)  │  │ (混合搜索)  │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件设计

#### 2.2.1 语义分析层

```java
/**
 * 意图分析器 - 理解代码的意图
 */
@Service
public class IntentAnalyzer {

    private final LLMService llmService;

    /**
     * 分析方法意图
     * @param methodSource 方法源码
     * @return 方法意图描述
     */
    public MethodIntent analyzeIntent(String methodSource) {
        String prompt = """
            分析以下方法的意图，输出JSON格式：
            {
              "purpose": "方法用途（一句话）",
              "inputs": ["输入参数含义"],
              "outputs": "返回值含义",
              "sideEffects": ["副作用"],
              "errorConditions": ["可能的错误场景"]
            }

            方法源码：
            %s
            """.formatted(methodSource);

        String result = llmService.generateText(prompt);
        return parseIntent(result);
    }
}

/**
 * 关系提取器 - 提取代码间的关系
 */
@Service
public class RelationExtractor {

    /**
     * 提取方法调用关系
     */
    public List<MethodRelation> extractRelations(ClassInfo classInfo) {
        // 使用JavaParser分析调用关系
        // 结合LLM理解调用语义
    }

    /**
     * 提取异常传播路径
     */
    public List<ExceptionPath> extractExceptionPaths(MethodInfo method) {
        // 分析方法可能抛出的异常
        // 追踪异常传播链
    }
}

/**
 * 模式识别器 - 识别代码模式
 */
@Service
public class PatternRecognizer {

    // 常见错误模式
    private static final Map<String, ErrorPattern> ERROR_PATTERNS = Map.of(
        "NPE_RISK", new ErrorPattern("空指针风险", "变量使用前未校验null"),
        "RESOURCE_LEAK", new ErrorPattern("资源泄漏", "未正确关闭资源"),
        "CONCURRENCY_ISSUE", new ErrorPattern("并发问题", "线程安全问题")
    );

    /**
     * 识别代码中的潜在问题模式
     */
    public List<PatternMatch> recognizePatterns(String code) {
        // 结合规则匹配和LLM分析
    }
}
```

#### 2.2.2 知识图谱层

```java
/**
 * 代码知识图谱
 */
@Service
public class CodeKnowledgeGraph {

    private final GraphRepository graphRepository;
    private final VectorRepository vectorRepository;

    /**
     * 代码节点类型
     */
    public enum NodeType {
        CLASS, METHOD, FIELD, EXCEPTION, ANNOTATION
    }

    /**
     * 关系类型
     */
    public enum RelationType {
        CALLS, IMPLEMENTS, EXTENDS, THROWS, USES, DEPENDS_ON
    }

    /**
     * 构建代码知识图谱
     */
    public void buildGraph(Project project) {
        // 1. 解析代码结构
        List<ClassInfo> classes = parseClasses(project);

        // 2. 提取节点和关系
        for (ClassInfo clazz : classes) {
            // 创建类节点
            Node classNode = createNode(clazz);

            // 创建方法节点
            for (MethodInfo method : clazz.getMethods()) {
                Node methodNode = createNode(method);

                // 生成方法语义嵌入
                float[] embedding = generateEmbedding(method);
                vectorRepository.store(methodNode.getId(), embedding);

                // 提取方法关系
                List<Relation> relations = extractRelations(method);
                graphRepository.createRelations(relations);
            }
        }

        // 3. 构建异常传播图
        buildExceptionPropagationGraph();
    }

    /**
     * 生成代码语义嵌入
     */
    private float[] generateEmbedding(MethodInfo method) {
        // 结合方法签名、注释、实现生成语义向量
        String semanticText = """
            类: %s
            方法: %s
            参数: %s
            返回值: %s
            注释: %s
            实现: %s
            """.formatted(
                method.getClassName(),
                method.getName(),
                method.getParameters(),
                method.getReturnType(),
                method.getComment(),
                method.getBody()
            );

        return embeddingService.embed(semanticText);
    }
}
```

#### 2.2.3 混合检索层

```java
/**
 * 混合搜索服务 - 结合关键词和语义检索
 */
@Service
public class HybridSearchService {

    private final VectorRepository vectorRepository;
    private final CodeIndexer codeIndexer;
    private final LLMService llmService;

    /**
     * 语义搜索相关代码
     * @param query 自然语言查询
     * @param topK 返回数量
     * @return 相关代码片段
     */
    public List<CodeSnippet> semanticSearch(String query, int topK) {
        // 1. 生成查询向量
        float[] queryVector = embeddingService.embed(query);

        // 2. 向量相似度检索
        List<VectorMatch> vectorMatches = vectorRepository.search(queryVector, topK * 2);

        // 3. 关键词检索（精确匹配）
        List<CodeMatch> keywordMatches = codeIndexer.search(extractKeywords(query));

        // 4. 融合排序
        return mergeAndRank(vectorMatches, keywordMatches, topK);
    }

    /**
     * 根据堆栈位置查找相关上下文
     */
    public CodeContext findContextByStackTrace(String stackTrace) {
        // 1. 解析堆栈，提取类名和方法名
        List<StackFrame> frames = parseStackTrace(stackTrace);

        // 2. 查询知识图谱，获取方法节点
        List<MethodNode> methods = frames.stream()
            .map(f -> graphRepository.findMethod(f.getClassName(), f.getMethodName()))
            .filter(Optional::isPresent)
            .map(Optional::get)
            .toList();

        // 3. 扩展上下文：获取调用者和被调用者
        List<MethodNode> callers = methods.stream()
            .flatMap(m -> graphRepository.findCallers(m.getId()).stream())
            .distinct()
            .toList();

        List<MethodNode> callees = methods.stream()
            .flatMap(m -> graphRepository.findCallees(m.getId()).stream())
            .distinct()
            .toList();

        // 4. 构建上下文
        return new CodeContext(methods, callers, callees);
    }
}
```

### 2.3 数据模型设计

```sql
-- 代码节点表
CREATE TABLE code_nodes (
    id UUID PRIMARY KEY,
    type VARCHAR(20) NOT NULL,  -- CLASS/METHOD/FIELD
    name VARCHAR(255) NOT NULL,
    full_name VARCHAR(500),     -- 全限定名
    source_code TEXT,
    intent TEXT,                -- LLM分析的意图
    embedding VECTOR(1536),     -- 语义向量
    metadata JSONB,
    created_at TIMESTAMP,
    updated_at TIMESTAMP
);

-- 代码关系表
CREATE TABLE code_relations (
    id UUID PRIMARY KEY,
    source_id UUID REFERENCES code_nodes(id),
    target_id UUID REFERENCES code_nodes(id),
    type VARCHAR(50) NOT NULL,  -- CALLS/IMPLEMENTS/EXTENDS/THROWS
    weight FLOAT DEFAULT 1.0,
    metadata JSONB,
    created_at TIMESTAMP
);

-- 异常传播路径表
CREATE TABLE exception_paths (
    id UUID PRIMARY KEY,
    exception_type VARCHAR(255),
    source_method_id UUID REFERENCES code_nodes(id),
    propagation_path JSONB,     -- 传播路径
    likelihood FLOAT,           -- 可能性评分
    created_at TIMESTAMP
);

-- 向量索引
CREATE INDEX idx_code_nodes_embedding ON code_nodes USING ivfflat (embedding vector_cosine_ops);
```

---

## 三、实施步骤

### 3.1 版本迭代计划

```
┌─────────────────────────────────────────────────────────────┐
│                    v1.0 基础语义理解                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 1-2                                              │
│ 目标：建立基础语义理解能力                                   │
│                                                             │
│ 功能：                                                      │
│ ├── 方法意图自动分析                                        │
│ ├── 代码语义向量化                                          │
│ ├── 基于向量的相似代码检索                                   │
│ └── 简单的调用关系图谱                                       │
│                                                             │
│ 交付物：                                                    │
│ ├── IntentAnalyzer 服务                                     │
│ ├── 向量嵌入Pipeline                                        │
│ └── 基础API接口                                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v2.0 知识图谱构建                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 3-4                                              │
│ 目标：构建完整的代码知识图谱                                 │
│                                                             │
│ 功能：                                                      │
│ ├── 完整的类/方法/字段节点                                   │
│ ├── 调用关系、继承关系、依赖关系                             │
│ ├── 异常传播路径分析                                         │
│ └── 知识图谱可视化                                           │
│                                                             │
│ 交付物：                                                    │
│ ├── CodeKnowledgeGraph 服务                                 │
│ ├── 图数据库集成                                            │
│ └── 图谱可视化前端                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v3.0 深度语义分析                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 5-6                                              │
│ 目标：深度语义理解和智能推理                                  │
│                                                             │
│ 功能：                                                      │
│ ├── 代码模式识别（NPE风险、资源泄漏等）                       │
│ ├── 语义变更检测                                             │
│ ├── 智能代码补全建议                                         │
│ └── 跨文件语义关联                                           │
│                                                             │
│ 交付物：                                                    │
│ ├── PatternRecognizer 服务                                  │
│ ├── 变更影响分析API                                         │
│ └── IDE插件集成                                             │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v4.0 持续学习优化                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 7-8                                              │
│ 目标：建立持续学习和优化机制                                  │
│                                                             │
│ 功能：                                                      │
│ ├── 基于反馈的语义模型优化                                   │
│ ├── 历史案例学习                                             │
│ ├── 团队知识沉淀                                             │
│ └── 多项目语义迁移                                           │
│                                                             │
│ 交付物：                                                    │
│ ├── 反馈学习系统                                            │
│ ├── 知识迁移工具                                            │
│ └── 运维监控面板                                            │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 详细任务分解

#### v1.0 任务清单

| 任务 | 描述 | 工时 | 依赖 |
|------|------|------|------|
| T1.1 | 设计语义数据模型 | 4h | 无 |
| T1.2 | 实现IntentAnalyzer | 8h | T1.1 |
| T1.3 | 集成pgvector向量存储 | 4h | T1.1 |
| T1.4 | 实现向量嵌入Pipeline | 8h | T1.3 |
| T1.5 | 实现相似代码检索API | 4h | T1.4 |
| T1.6 | 单元测试和集成测试 | 8h | T1.2-T1.5 |

---

## 四、验收标准

### 4.1 功能验收标准

| 功能 | 验收标准 | 测试方法 |
|------|---------|---------|
| 方法意图分析 | 准确率≥85% | 100个方法样本人工评测 |
| 相似代码检索 | Top-5召回率≥80% | 50个查询测试 |
| 调用关系图谱 | 覆盖率≥95% | 与静态分析结果对比 |
| 异常传播分析 | 准确识别率≥90% | 已知异常案例验证 |

### 4.2 性能验收标准

| 指标 | 标准 | 测试方法 |
|------|------|---------|
| 意图分析延迟 | <3s/方法 | 性能测试 |
| 向量检索延迟 | <100ms | 性能测试 |
| 图谱构建速度 | <1min/1000行代码 | 性能测试 |
| 内存占用 | <2GB/项目 | 资源监控 |

### 4.3 质量验收标准

| 指标 | 标准 |
|------|------|
| 代码覆盖率 | ≥80% |
| API文档完整性 | 100% |
| 无阻塞性Bug | 0个 |
| 用户满意度 | ≥4.0/5.0 |

---

## 五、依赖关系图

```
                    ┌─────────────────┐
                    │  用户/前端      │
                    └────────┬────────┘
                             │
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    本方案：代码语义理解                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  SemanticAnalysisController                          │   │
│  │  - POST /api/semantic/analyze                        │   │
│  │  - GET /api/semantic/search                          │   │
│  │  - GET /api/semantic/graph                           │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│           ┌───────────────┼───────────────┐                │
│           ▼               ▼               ▼                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Intent      │  │ Knowledge   │  │ Hybrid      │         │
│  │ Analyzer    │  │ Graph       │  │ Search      │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    基础设施层                                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ LLMService  │  │ PostgreSQL  │  │ JavaParser  │         │
│  │ (已有)      │  │ + pgvector  │  │ (已有)      │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                    外部依赖                                  │
│  ┌─────────────┐  ┌─────────────┐                          │
│  │ 外部LLM API │  │ 代码仓库    │                          │
│  │ (Qwen/Claude)│  │ (Git)       │                          │
│  └─────────────┘  └─────────────┘                          │
└─────────────────────────────────────────────────────────────┘

与其他方案的关系：
┌─────────────┐     提供语义理解能力      ┌─────────────┐
│  方案1      │ ◀─────────────────────── │  本方案     │
│  多Agent协作 │                          │  代码语义   │
└─────────────┘                          └─────────────┘
       │                                       │
       │         提供诊断入口                   │
       └───────────────────────────────────────▶
                                           ┌─────────────┐
                                           │  方案3      │
                                           │  自然语言   │
                                           └─────────────┘
```

---

## 六、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| LLM输出不稳定 | 中 | 高 | 结构化Prompt + 结果验证 |
| 向量检索精度不足 | 中 | 中 | 混合检索策略 |
| 图谱构建耗时 | 低 | 中 | 增量构建 + 缓存 |
| 内存占用过高 | 中 | 中 | 分页加载 + 稀疏存储 |

---

文档版本：v1.0
创建时间：2026-04-04
作者：llm-expert-2
状态：待评审