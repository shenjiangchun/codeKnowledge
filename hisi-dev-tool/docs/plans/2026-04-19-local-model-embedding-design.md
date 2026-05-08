# 本地模型嵌入方案设计

## 概述

将知识图谱模块的自然语言描述生成和向量生成功能从远程 API 迁移到本地嵌入模型，实现：
1. **向量生成**: ONNX Runtime + sentence-transformers 模型
2. **描述生成**: ONNX Runtime + Phi-3-mini 量化模型

---

## 1. 当前架构问题

### 1.1 向量生成

```
当前实现: LocalEmbeddingService.generateEmbedding()
           └── 伪随机确定性向量（基于文本哈希）
           └── 无实际语义意义
           └── 无法用于真正的语义搜索
```

### 1.2 描述生成

```
当前实现: LLMDescriptionService.generateDescription()
           └── 调用远程 LLM API (腾讯云 glm-5)
           └── 每次调用耗时 10-15 秒
           └── 4138 个方法需要约 12-17 小时
           └── 依赖网络和 API 可用性
```

---

## 2. 目标架构

```
┌─────────────────────────────────────────────────────────────────┐
│                    Spring Boot 应用 (JVM)                       │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌─────────────────────┐    ┌─────────────────────────────┐    │
│  │ LocalEmbeddingService│    │ LocalTextGenerationService │    │
│  │ (ONNX Runtime)       │    │ (ONNX Runtime)             │    │
│  │                     │    │                            │    │
│  │ 模型: paraphrase-   │    │ 模型: Phi-3-mini-4k-q4    │    │
│  │ multilingual-MiniLM │    │ 大小: ~2GB                 │    │
│  │ 大小: ~400MB        │    │ 推理: ~500ms               │    │
│  │ 推理: ~10ms         │    │                            │    │
│  └─────────────────────┘    └─────────────────────────────┘    │
│           │                           │                        │
│           ▼                           ▼                        │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │              VectorGenerationService                    │   │
│  │                                                        │   │
│  │  方法节点 → 本地LLM生成描述 → 本地向量 → 存储到 Neo4j   │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

---

## 3. 技术选型

### 3.1 向量模型

| 属性 | 值 |
|------|-----|
| 模型名称 | `sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2` |
| 维度 | 384 |
| 模型大小 | ~400MB |
| 推理速度 | ~10ms (CPU) |
| 支持语言 | 中英文 |

### 3.2 文本生成模型

| 属性 | 值 |
|------|-----|
| 模型名称 | `Phi-3-mini-4k-instruct` (INT4 量化) |
| 参数量 | 3.8B |
| 量化后大小 | ~2GB |
| 推理速度 | ~500ms (CPU) |
| 上下文长度 | 4096 |
| 支持语言 | 中英文 |

### 3.3 推理引擎

**ONNX Runtime**
- 跨平台
- CPU 优化（支持 AVX2/AVX512）
- 内存占用低
- 支持 INT4/INT8 量化

---

## 4. 模型文件

### 4.1 下载地址

**Embedding 模型:**
```
https://huggingface.co/sentence-transformers/paraphrase-multilingual-MiniLM-L12-v2/tree/main
需要文件:
- model.onnx (或 pytorch_model.bin 转换)
- tokenizer.json
- vocab.txt
```

**Phi-3 模型 (INT4 量化):**
```
https://huggingface.co/microsoft/Phi-3-mini-4k-instruct-onnx/tree/main/cpu_and_mobile/cpu-int4-rtn-block-32
需要文件:
- phi-3-mini-4k-instruct-cpu-int4-rtn-block-32.onnx
- tokenizer.json
- tokenizer.model
```

### 4.2 存放目录

```
hisi-dev-tool/
├── models/
│   ├── embedding/
│   │   └── paraphrase-multilingual-MiniLM-L12-v2/
│   │       ├── model.onnx
│   │       └── tokenizer.json
│   └── text-generation/
│       └── phi-3-mini-4k-int4/
│           ├── model.onnx
│           └── tokenizer.json
└── src/main/resources/
    └── application.yml
```

---

## 5. 代码实现

### 5.1 LocalEmbeddingService (重写)

```java
@Slf4j
@Service
public class LocalEmbeddingService {

    private static final int EMBEDDING_DIMENSION = 384;

    private OrtEnvironment ortEnv;
    private OrtSession embeddingSession;
    private HuggingFaceTokenizer tokenizer;

    @PostConstruct
    public void init() {
        // 加载 ONNX 模型
        ortEnv = OrtEnvironment.getEnvironment();
        Path modelPath = Paths.get("models/embedding/.../model.onnx");
        embeddingSession = ortEnv.createSession(modelPath.toString());

        // 加载 tokenizer
        tokenizer = HuggingFaceTokenizer.newInstance("...");
    }

    public float[] generateEmbedding(String text) {
        // 1. Tokenize
        long[] inputIds = tokenize(text);

        // 2. Create tensors
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, inputIds);

        // 3. Run inference
        OrtSession.Result result = embeddingSession.run(Map.of("input_ids", inputTensor));

        // 4. Mean pooling + normalize
        float[] embedding = meanPoolAndNormalize(result);

        return embedding;
    }
}
```

### 5.2 LocalTextGenerationService (新增)

```java
@Slf4j
@Service
public class LocalTextGenerationService {

    private OrtSession phi3Session;
    private Tokenizer tokenizer;

    private static final String SYSTEM_PROMPT = """
        你是一个代码文档助手。请用一句话描述以下Java方法的功能，不超过50字。
        """;

    public String generateDescription(String className, String methodName,
                                       String signature, String comment) {
        // 1. 构建 prompt
        String prompt = buildPrompt(className, methodName, signature, comment);

        // 2. Tokenize
        long[] inputIds = tokenizer.encode(prompt);

        // 3. 自回归生成
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < MAX_NEW_TOKENS; i++) {
            float[] logits = runInference(inputIds);
            int nextToken = sample(logits);
            if (isEndToken(nextToken)) break;
            output.append(tokenizer.decode(nextToken));
            inputIds = append(inputIds, nextToken);
        }

        return output.toString();
    }
}
```

### 5.3 LLMDescriptionService (修改)

```java
@Service
public class LLMDescriptionService {

    private final LocalTextGenerationService localService;
    private final LLMConfig llmConfig;

    @Value("${llm.use-local:true}")
    private boolean useLocal;

    public String generateDescription(MethodNode node) {
        if (useLocal && localService.isAvailable()) {
            // 优先使用本地模型
            return localService.generateDescription(
                node.getClassName(),
                node.getMethodName(),
                node.getSignature(),
                node.getComment()
            );
        } else {
            // 回退到远程 API
            return callRemoteLLM(node);
        }
    }
}
```

---

## 6. 配置文件

### application.yml 新增配置

```yaml
# 本地模型配置
local-models:
  embedding:
    enabled: true
    model-path: models/embedding/paraphrase-multilingual-MiniLM-L12-v2/model.onnx
    tokenizer-path: models/embedding/paraphrase-multilingual-MiniLM-L12-v2/tokenizer.json

  text-generation:
    enabled: true
    model-path: models/text-generation/phi-3-mini-4k-int4/model.onnx
    tokenizer-path: models/text-generation/phi-3-mini-4k-int4/tokenizer.json
    max-new-tokens: 100
    temperature: 0.7

# LLM 配置更新
llm:
  use-local: true  # 优先使用本地模型
  fallback-to-remote: true  # 本地模型不可用时回退到远程
```

---

## 7. 性能预期

| 操作 | 当前耗时 | 优化后耗时 | 提升倍数 |
|------|---------|-----------|---------|
| 单个方法描述生成 | 10-15秒 | 0.5秒 | 20-30x |
| 单个向量生成 | 0 (伪随机) | 10ms | N/A |
| 4138个方法总耗时 | 12-17小时 | ~35分钟 | ~20x |

---

## 8. 内存需求

| 组件 | 内存占用 |
|------|---------|
| JVM 基础 | ~512MB |
| Embedding 模型 | ~500MB |
| Phi-3 模型 (INT4) | ~2.5GB |
| **总计** | **~3.5GB** |

---

## 9. 部署步骤

### 9.1 下载模型文件

```bash
# 创建目录
mkdir -p models/embedding models/text-generation

# 下载 Embedding 模型
# (从 HuggingFace 下载或使用 Python 脚本转换)

# 下载 Phi-3 INT4 模型
# (从 HuggingFace ONNX 仓库下载)
```

### 9.2 配置 application.yml

```yaml
local-models:
  embedding:
    model-path: models/embedding/.../model.onnx
  text-generation:
    model-path: models/text-generation/.../model.onnx
```

### 9.3 启动应用

```bash
mvn spring-boot:run
```

---

## 10. 验收标准

- [ ] Embedding 模型正确加载
- [ ] 向量维度正确 (384)
- [ ] 相似文本向量余弦相似度 > 0.8
- [ ] Phi-3 模型正确加载
- [ ] 描述生成时间 < 1秒/方法
- [ ] 描述质量符合预期（语义连贯、50字以内）
- [ ] 内存占用 < 4GB
- [ ] 本地模型不可用时正确回退到远程 API

---

## 11. 风险与缓解

| 风险 | 影响 | 缓解措施 |
|------|------|---------|
| 模型加载失败 | 服务不可用 | 回退到远程 API |
| 内存不足 | OOM 错误 | 按需加载模型，支持卸载 |
| CPU 推理太慢 | 体验差 | 支持批处理、缓存 |
| 模型文件损坏 | 加载失败 | 启动时验证文件完整性 |

---

## 12. 后续优化

1. **模型预热**: 应用启动时预加载模型
2. **批量推理**: 多个方法合并为一个 batch
3. **描述缓存**: 相同签名的方法复用描述
4. **GPU 支持**: 检测 GPU 并自动切换
