package com.huawei.hisi.neo4j.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession.Result;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Phi-3 ONNX 模型推理演示
 * 展示如何正确处理KV Cache进行文本生成
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class Phi3OnnxInferenceDemo {

    private static final String MODEL_DIR = System.getProperty("user.dir", ".") +
            "/models/text-generation/phi-3-mini-4k-int4/cpu_and_mobile/cpu-int4-rtn-block-32";

    // Phi-3 特殊token
    private static final int EOS_TOKEN_ID = 32007; // <|end|>
    private static final int PAD_TOKEN_ID = 32000;

    // 模型配置 - 从config.json获取
    private static final int NUM_LAYERS = 32;
    private static final int NUM_KEY_VALUE_HEADS = 32;
    private static final int HEAD_DIM = 96;  // hidden_size(3072) / num_heads(32)
    private static final int MAX_NEW_TOKENS = 30;

    private OrtEnvironment ortEnvironment;

    @BeforeAll
    void setUp() {
        ortEnvironment = OrtEnvironment.getEnvironment();
        System.out.println("========================================");
        System.out.println("Phi-3 ONNX 推理测试（含KV Cache）");
        System.out.println("========================================");
        System.out.println("模型目录: " + MODEL_DIR);
    }

    @Test
    void testPhi3ModelFilesExist() {
        System.out.println("\n--- 测试模型文件存在性 ---");
        Path modelPath = Paths.get(MODEL_DIR, "phi3-mini-4k-instruct-cpu-int4-rtn-block-32.onnx");
        Path tokenizerPath = Paths.get(MODEL_DIR, "tokenizer.json");

        System.out.println("模型文件: " + modelPath);
        System.out.println("  存在: " + Files.exists(modelPath));
        System.out.println("Tokenizer文件: " + tokenizerPath);
        System.out.println("  存在: " + Files.exists(tokenizerPath));

        assertTrue(Files.exists(modelPath), "模型文件不存在");
        assertTrue(Files.exists(tokenizerPath), "Tokenizer文件不存在");
    }

    @Test
    void testPhi3InferenceWithKVCACHE() throws Exception {
        System.out.println("\n--- 测试Phi-3推理（含KV Cache） ---");

        // 1. 加载Tokenizer
        System.out.println("\n步骤1: 加载Tokenizer...");
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(MODEL_DIR, "tokenizer.json"));
        System.out.println("✓ Tokenizer加载成功");

        // 2. 加载ONNX模型
        System.out.println("\n步骤2: 加载ONNX模型...");
        long startTime = System.currentTimeMillis();
        OrtSession session = ortEnvironment.createSession(Paths.get(MODEL_DIR, "phi3-mini-4k-instruct-cpu-int4-rtn-block-32.onnx").toString());
        System.out.println("✓ 模型加载成功 (耗时: " + (System.currentTimeMillis() - startTime) + "ms)");

        // 打印输入输出信息
        System.out.println("\n模型输入:");
        List<String> inputNames = new ArrayList<>(session.getInputNames());
        System.out.println("  总数: " + inputNames.size());
        System.out.println("  前5个: " + inputNames.subList(0, Math.min(5, inputNames.size())));
        System.out.println("  ...");

        System.out.println("\n模型输出:");
        List<String> outputNames = new ArrayList<>(session.getOutputNames());
        System.out.println("  总数: " + outputNames.size());
        System.out.println("  前5个: " + outputNames.subList(0, Math.min(5, outputNames.size())));
        System.out.println("  ...");

        // 3. 构建prompt
        System.out.println("\n步骤3: 构建Prompt...");
        String prompt = buildPrompt("UserService", "getUserById", "public User getUserById(Long id)", "根据ID获取用户信息");
        System.out.println("Prompt: " + prompt.replace("\n", "\\n"));

        // 4. Tokenize
        System.out.println("\n步骤4: Tokenize...");
        var encoding = tokenizer.encode(prompt);
        long[] inputIds = encoding.getIds();
        System.out.println("Token数量: " + inputIds.length);

        // 5. 初始化KV Cache (首次推理需要空cache)
        System.out.println("\n步骤5: 准备初始输入（包含空KV Cache）...");
        Map<String, OnnxTensor> inputs = createInitialInputs(inputIds);
        System.out.println("输入张量数量: " + inputs.size());

        // 6. 运行第一次推理
        System.out.println("\n步骤6: 运行推理...");
        startTime = System.currentTimeMillis();
        Result result = session.run(inputs);
        System.out.println("✓ 推理完成 (耗时: " + (System.currentTimeMillis() - startTime) + "ms)");

        // 7. 获取logits并预测下一个token
        System.out.println("\n步骤7: 解析输出...");
        float[][][] logits = (float[][][]) result.get(0).getValue();
        System.out.println("Logits形状: [1, " + logits[0].length + ", " + logits[0][0].length + "]");

        // 找到下一个token
        int nextToken = argmax(logits[0][logits[0].length - 1]);
        System.out.println("预测的下一个Token ID: " + nextToken);

        // 解码
        String decoded = tokenizer.decode(new long[]{nextToken});
        System.out.println("解码结果: '" + decoded + "'");

        // 检查是否是结束token
        if (nextToken == EOS_TOKEN_ID || nextToken == PAD_TOKEN_ID) {
            System.out.println("检测到结束Token");
        }

        // 8. 清理
        System.out.println("\n步骤8: 清理资源...");
        inputs.values().forEach(OnnxTensor::close);
        result.close();
        session.close();
        System.out.println("✓ 清理完成");

        // 验证
        assertTrue(logits.length > 0, "应该有logits输出");
        assertTrue(nextToken >= 0, "Token ID应该非负");

        System.out.println("\n✓ Phi-3推理测试完成!");
        System.out.println("\n注意: KV Cache的正确使用需要:");
        System.out.println("  1. 首次推理: 提供空KV Cache (零张量)");
        System.out.println("  2. 后续推理: 使用上一步输出的present.*.key/value");
        System.out.println("  3. 自回归循环: 每次只输入新生成的token");
    }

    /**
     * 创建初始输入（包含空的KV Cache）
     * Phi-3模型要求提供所有64个KV Cache输入（32层 × 2）
     */
    private Map<String, OnnxTensor> createInitialInputs(long[] inputIds) throws Exception {
        Map<String, OnnxTensor> inputs = new HashMap<>();

        // 基本输入
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{inputIds});
        OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{createAttentionMask(inputIds.length)});

        inputs.put("input_ids", inputTensor);
        inputs.put("attention_mask", attentionTensor);

        // 创建空的KV Cache
        // 形状: [batch_size, num_heads, past_seq_len, head_dim]
        // 对于首次推理，past_seq_len = 0，但ONNX不支持0维度
        // 所以我们创建形状为 [1, num_heads, 1, head_dim] 的零张量
        // 然后通过position_ids或attention_mask来处理

        int batchSize = 1;
        int pastSeqLen = 1;  // 使用1而不是0，因为ONNX不支持0维度

        // 为每一层创建空的KV Cache
        for (int layer = 0; layer < NUM_LAYERS; layer++) {
            // Key cache: [batch, num_heads, past_seq_len, head_dim]
            float[][][][] keyCache = new float[batchSize][NUM_KEY_VALUE_HEADS][pastSeqLen][HEAD_DIM];
            float[][][][] valueCache = new float[batchSize][NUM_KEY_VALUE_HEADS][pastSeqLen][HEAD_DIM];

            // 创建零张量
            OnnxTensor keyTensor = OnnxTensor.createTensor(ortEnvironment, keyCache);
            OnnxTensor valueTensor = OnnxTensor.createTensor(ortEnvironment, valueCache);

            inputs.put("past_key_values." + layer + ".key", keyTensor);
            inputs.put("past_key_values." + layer + ".value", valueTensor);
        }

        System.out.println("  已创建 " + (NUM_LAYERS * 2) + " 个KV Cache张量");
        System.out.println("  每个张量形状: [1, " + NUM_KEY_VALUE_HEADS + ", " + pastSeqLen + ", " + HEAD_DIM + "]");

        return inputs;
    }

    /**
     * 构建Phi-3格式的Prompt
     */
    private String buildPrompt(String className, String methodName, String signature, String comment) {
        String commentStr = (comment == null || comment.isEmpty()) ? "无" : comment;
        return "<|user|>\n" +
               "请用一句简洁的中文描述以下Java方法的功能（不超过30字）。\n" +
               "类名：" + className + "\n" +
               "方法名：" + methodName + "\n" +
               "签名：" + signature + "\n" +
               "注释：" + commentStr + "\n" +
               "<|end|>\n" +
               "<|assistant|>\n";
    }

    /**
     * 创建attention mask
     */
    private long[] createAttentionMask(int length) {
        long[] mask = new long[length];
        for (int i = 0; i < length; i++) {
            mask[i] = 1L;
        }
        return mask;
    }

    /**
     * 找到数组中最大值的索引
     */
    private int argmax(float[] array) {
        int maxIdx = 0;
        float maxVal = array[0];
        for (int i = 1; i < array.length; i++) {
            if (array[i] > maxVal) {
                maxVal = array[i];
                maxIdx = i;
            }
        }
        return maxIdx;
    }
}
