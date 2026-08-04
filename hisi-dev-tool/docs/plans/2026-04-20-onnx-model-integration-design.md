# ONNX模型集成设计文档

## 概述

本文档记录了知识图谱模块中ONNX模型的集成方案，包括嵌入模型和文本生成模型的使用方式。

---

## 1. 模型概览

### 1.1 当前使用的模型

| 模型类型 | 模型名称 | 文件位置 | 维度/参数 |
|---------|---------|---------|----------|
| **嵌入模型** | paraphrase-multilingual-MiniLM-L12-v2 | `models/embedding/paraphrase-multilingual-MiniLM-L12-v2/` | 384维 |
| **文本生成** | Phi-3-mini-4k-instruct (INT4) | `models/text-generation/phi-3-mini-4k-int4/` | 3.8B参数 |

### 1.2 模型文件结构

```
models/
├── embedding/paraphrase-multilingual-MiniLM-L12-v2/
│   ├── onnx/
│   │   └── model.onnx          # 主模型文件
│   └── tokenizer.json          # Tokenizer文件
│
└── text-generation/phi-3-mini-4k-int4/
    └── cpu_and_mobile/cpu-int4-rtn-block-32/
        ├── phi3-mini-4k-instruct-cpu-int4-rtn-block-32.onnx      # 模型结构
        ├── phi3-mini-4k-instruct-cpu-int4-rtn-block-32.onnx.data # 模型权重 (~2.6GB)
        └── tokenizer.json                                        # Tokenizer文件
```

---

## 2. 嵌入模型 (MiniLM)

### 2.1 模型输入/输出

**输入:**
- `input_ids`: [batch_size, seq_len] - Token IDs
- `attention_mask`: [batch_size, seq_len] - 注意力掩码
- `token_type_ids`: [batch_size, seq_len] - Token类型IDs

**输出:**
- `last_hidden_state`: [batch_size, seq_len, 384] - 隐藏状态

### 2.2 推理流程

```java
// 1. 加载Tokenizer和模型
HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
OrtSession session = ortEnv.createSession(modelPath);

// 2. Tokenize
var encoding = tokenizer.encode(text);
long[] inputIds = encoding.getIds();

// 3. 创建输入张量
OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnv, new long[][]{inputIds});
OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnv, new long[][]{createAttentionMask(inputIds.length)});
OnnxTensor tokenTypeTensor = OnnxTensor.createTensor(ortEnv, new long[][]{new long[inputIds.length]});

// 4. 运行推理
Result result = session.run(Map.of(
    "input_ids", inputTensor,
    "attention_mask", attentionTensor,
    "token_type_ids", tokenTypeTensor
));

// 5. Mean Pooling + Normalize
float[][][] output = (float[][][]) result.get(0).getValue();
float[] embedding = meanPooling(output[0]);
normalize(embedding);
```

### 2.3 验证结果

- ✅ 模型加载成功
- ✅ 推理正常工作
- ✅ 输出维度正确 (384)
- ✅ 单次推理耗时约10-20ms

---

## 3. 文本生成模型 (Phi-3)

### 3.1 关键发现：KV Cache

Phi-3 ONNX模型**必须**提供KV Cache输入。模型包含：

**输入 (共66个):**
- `input_ids`: [batch_size, seq_len]
- `attention_mask`: [batch_size, seq_len]
- `past_key_values.0.key` ~ `past_key_values.31.key`: 32个Key Cache
- `past_key_values.0.value` ~ `past_key_values.31.value`: 32个Value Cache

**输出 (共65个):**
- `logits`: [batch_size, seq_len, vocab_size]
- `present.0.key` ~ `present.31.key`: 新的Key Cache
- `present.0.value` ~ `present.31.value`: 新的Value Cache

### 3.2 KV Cache形状

```
Key/Value Cache: [batch_size, num_heads, past_seq_len, head_dim]
```

对于Phi-3-mini-4k:
- `num_heads` = 32
- `head_dim` = 96 (3072 / 32)
- `past_seq_len`: 已处理的token数量

### 3.3 初始化KV Cache

首次推理时，需要创建零张量：

```java
int batchSize = 1;
int numHeads = 32;
int headDim = 96;
int pastSeqLen = 1;  // 使用1，因为ONNX不支持0维度

for (int layer = 0; layer < 32; layer++) {
    float[][][][] keyCache = new float[batchSize][numHeads][pastSeqLen][headDim];
    float[][][][] valueCache = new float[batchSize][numHeads][pastSeqLen][headDim];

    inputs.put("past_key_values." + layer + ".key",
               OnnxTensor.createTensor(ortEnv, keyCache));
    inputs.put("past_key_values." + layer + ".value",
               OnnxTensor.createTensor(ortEnv, valueCache));
}
```

### 3.4 验证结果

- ✅ 模型加载成功 (~8秒)
- ✅ KV Cache初始化成功 (64个张量)
- ✅ 推理成功 (~3.7秒首次，后续更快)
- ✅ 输出形状正确: [1, seq_len, 32064]

---

## 4. 实现建议

### 4.1 嵌入服务 (已验证可用)

当前的 `LocalEmbeddingService.java` 实现正确，无需修改。

### 4.2 文本生成服务 (需要修改)

当前的 `LocalTextGenerationService.java` 需要更新以正确处理KV Cache：

1. **初始化时创建空KV Cache**
2. **自回归生成循环**
3. **管理KV Cache的生命周期**

### 4.3 性能优化

1. **KV Cache复用**: 在自回归循环中复用输出的KV Cache
2. **批处理**: 多个方法描述可以批量生成
3. **模型预热**: 应用启动时预加载模型

---

## 5. 替代模型讨论

### 5.1 已测试的模型

| 模型 | ONNX可用 | 问题 |
|------|---------|------|
| Nomic Embed Text v2-MoE | ❌ | 需要自定义Python代码，无法直接转ONNX |
| DeepSeek-Coder-1.3B-Instruct | ❌ | PyTorch格式，需要转换 |

### 5.2 建议

保持使用现有的ONNX模型：
- **嵌入**: paraphrase-multilingual-MiniLM-L12-v2 (稳定可用)
- **文本生成**: Phi-3-mini-4k-instruct (需要正确处理KV Cache)

---

## 6. 下一步行动

1. [x] 验证嵌入模型可用
2. [x] 验证文本生成模型可用（含KV Cache）
3. [ ] 更新 `LocalTextGenerationService.java` 以正确处理KV Cache
4. [ ] 实现完整的自回归生成循环
5. [ ] 添加单元测试
6. [ ] 清理已下载但不使用的模型文件

---

## 附录：测试代码

测试代码位于：
- `src/test/java/com/huawei/hisi/neo4j/service/OnnxModelConnectivityTest.java`
- `src/test/java/com/huawei/hisi/neo4j/service/Phi3OnnxInferenceDemo.java`
