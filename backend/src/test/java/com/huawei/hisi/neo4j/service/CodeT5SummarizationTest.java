package com.huawei.hisi.neo4j.service;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtSession.Result;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
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
 * CodeT5 ONNX 模型推理测试
 *
 * CodeT5 是 Salesforce 开发的代码理解与生成模型，基于 T5 架构。
 * 专门针对代码任务进行了预训练，可用于：
 * - 代码摘要生成
 * - 代码翻译
 * - 代码修复
 *
 * 模型架构：编码器-解码器（T5ForConditionalGeneration）
 *
 * 推理流程：
 * 1. 使用 encoder_model.onnx 编码输入代码
 * 2. 使用 decoder_model.onnx（初始）或 decoder_with_past_model.onnx（后续）解码
 * 3. 自回归生成摘要文本
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled("Requires local ONNX model files which are not committed to source control")
public class CodeT5SummarizationTest {

    private static final String MODEL_DIR = System.getProperty("user.dir", ".") +
            "/models/codet5-base";

    // CodeT5/T5 特殊token
    private static final int PAD_TOKEN_ID = 0;      // <pad>
    private static final int EOS_TOKEN_ID = 1;      // </s>
    private static final int DECODER_START_ID = 0;  // decoder start token
    private static final int MAX_NEW_TOKENS = 50;

    private OrtEnvironment ortEnvironment;

    @BeforeAll
    void setUp() {
        ortEnvironment = OrtEnvironment.getEnvironment();
        System.out.println("========================================");
        System.out.println("CodeT5-base ONNX 推理测试");
        System.out.println("========================================");
        System.out.println("模型目录: " + MODEL_DIR);
    }

    @Test
    void testModelFilesExist() throws Exception {
        System.out.println("\n--- 测试模型文件存在性 ---");

        Path encoderPath = Paths.get(MODEL_DIR, "onnx", "encoder_model.onnx");
        Path decoderPath = Paths.get(MODEL_DIR, "onnx", "decoder_model.onnx");
        Path decoderWithPastPath = Paths.get(MODEL_DIR, "onnx", "decoder_with_past_model.onnx");
        Path tokenizerPath = Paths.get(MODEL_DIR, "tokenizer.json");
        Path configPath = Paths.get(MODEL_DIR, "config.json");

        System.out.println("Encoder模型: " + encoderPath);
        System.out.println("  存在: " + Files.exists(encoderPath));
        System.out.println("  大小: " + (Files.exists(encoderPath) ? Files.size(encoderPath) / 1024 / 1024 + " MB" : "N/A"));

        System.out.println("Decoder模型: " + decoderPath);
        System.out.println("  存在: " + Files.exists(decoderPath));
        System.out.println("  大小: " + (Files.exists(decoderPath) ? Files.size(decoderPath) / 1024 / 1024 + " MB" : "N/A"));

        System.out.println("Decoder with Past模型: " + decoderWithPastPath);
        System.out.println("  存在: " + Files.exists(decoderWithPastPath));
        System.out.println("  大小: " + (Files.exists(decoderWithPastPath) ? Files.size(decoderWithPastPath) / 1024 / 1024 + " MB" : "N/A"));

        System.out.println("Tokenizer文件: " + tokenizerPath);
        System.out.println("  存在: " + Files.exists(tokenizerPath));

        assertTrue(Files.exists(encoderPath), "Encoder模型不存在");
        assertTrue(Files.exists(decoderPath), "Decoder模型不存在");
        assertTrue(Files.exists(tokenizerPath), "Tokenizer文件不存在");

        System.out.println("模型文件检查通过");
    }

    @Test
    void testEncoderModelLoadAndInference() throws Exception {
        System.out.println("\n--- 测试Encoder模型加载和推理 ---");

        Path encoderPath = Paths.get(MODEL_DIR, "onnx", "encoder_model.onnx");
        Path tokenizerDir = Paths.get(MODEL_DIR);

        // 1. 加载Tokenizer - 使用目录路径
        System.out.println("步骤1: 加载Tokenizer...");
        HuggingFaceTokenizer tokenizer;
        try {
            // 尝试使用目录加载
            tokenizer = HuggingFaceTokenizer.newInstance(tokenizerDir);
        } catch (Exception e) {
            System.out.println("使用目录加载失败，尝试使用tokenizer.json: " + e.getMessage());
            // 尝试使用tokenizer.json文件
            tokenizer = HuggingFaceTokenizer.newInstance(Paths.get(MODEL_DIR, "tokenizer.json"));
        }
        System.out.println("Tokenizer加载成功");

        // 2. 加载Encoder模型
        System.out.println("步骤2: 加载Encoder ONNX模型...");
        long startTime = System.currentTimeMillis();
        OrtSession encoderSession = ortEnvironment.createSession(encoderPath.toString());
        long loadTime = System.currentTimeMillis() - startTime;
        System.out.println("Encoder模型加载成功 (耗时: " + loadTime + "ms)");

        // 打印模型信息
        System.out.println("\nEncoder输入:");
        List<String> encoderInputs = new ArrayList<>(encoderSession.getInputNames());
        for (String name : encoderInputs) {
            System.out.println("  - " + name);
        }

        System.out.println("\nEncoder输出:");
        List<String> encoderOutputs = new ArrayList<>(encoderSession.getOutputNames());
        for (String name : encoderOutputs) {
            System.out.println("  - " + name);
        }

        // 3. 测试编码
        System.out.println("\n步骤3: 测试编码...");
        String javaCode = "public User getUserById(Long id) { return userRepository.findById(id); }";
        System.out.println("输入代码: " + javaCode);

        var encoding = tokenizer.encode(javaCode);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        System.out.println("Token数量: " + inputIds.length);
        System.out.println("Token IDs: " + java.util.Arrays.toString(java.util.Arrays.copyOf(inputIds, Math.min(10, inputIds.length))) + "...");

        // 创建输入张量
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{inputIds});
        OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{attentionMask});

        // 运行Encoder
        startTime = System.currentTimeMillis();
        Result encoderResult = encoderSession.run(Map.of(
            "input_ids", inputTensor,
            "attention_mask", attentionTensor
        ));
        long encodeTime = System.currentTimeMillis() - startTime;
        System.out.println("Encoder推理完成 (耗时: " + encodeTime + "ms)");

        // 获取encoder输出 (last_hidden_state)
        float[][][] encoderOutput = (float[][][]) encoderResult.get(0).getValue();
        System.out.println("Encoder输出形状: [1, " + encoderOutput[0].length + ", " + encoderOutput[0][0].length + "]");

        // 验证
        assertTrue(encoderOutput.length == 1, "Batch size应为1");
        assertTrue(encoderOutput[0].length == inputIds.length, "序列长度应与输入相同");
        assertTrue(encoderOutput[0][0].length == 768, "隐藏维度应为768");

        // 清理
        inputTensor.close();
        attentionTensor.close();
        encoderResult.close();
        encoderSession.close();

        System.out.println("Encoder模型测试通过");
    }

    @Test
    void testDecoderModelLoadAndInference() throws Exception {
        System.out.println("\n--- 测试Decoder模型加载和推理 ---");

        Path encoderPath = Paths.get(MODEL_DIR, "onnx", "encoder_model.onnx");
        Path decoderPath = Paths.get(MODEL_DIR, "onnx", "decoder_model.onnx");
        Path tokenizerPath = Paths.get(MODEL_DIR, "tokenizer.json");

        // 1. 加载Tokenizer
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
        System.out.println("Tokenizer加载成功");

        // 2. 加载模型
        System.out.println("加载Encoder模型...");
        long startTime = System.currentTimeMillis();
        OrtSession encoderSession = ortEnvironment.createSession(encoderPath.toString());
        System.out.println("Encoder加载耗时: " + (System.currentTimeMillis() - startTime) + "ms");

        System.out.println("加载Decoder模型...");
        startTime = System.currentTimeMillis();
        OrtSession decoderSession = ortEnvironment.createSession(decoderPath.toString());
        System.out.println("Decoder加载耗时: " + (System.currentTimeMillis() - startTime) + "ms");

        // 打印Decoder模型信息
        System.out.println("\nDecoder输入:");
        for (String name : decoderSession.getInputNames()) {
            System.out.println("  - " + name);
        }

        System.out.println("\nDecoder输出:");
        for (String name : decoderSession.getOutputNames()) {
            System.out.println("  - " + name);
        }

        // 3. 编码输入
        String javaCode = "public User getUserById(Long id) { return userRepository.findById(id); }";
        System.out.println("\n输入代码: " + javaCode);

        var encoding = tokenizer.encode(javaCode);
        long[] inputIds = encoding.getIds();
        long[] attentionMask = encoding.getAttentionMask();

        // Encoder推理
        OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{inputIds});
        OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{attentionMask});

        Result encoderResult = encoderSession.run(Map.of(
            "input_ids", inputTensor,
            "attention_mask", attentionTensor
        ));

        // 获取encoder_hidden_states
        float[][][] encoderHiddenStates = (float[][][]) encoderResult.get(0).getValue();
        System.out.println("Encoder输出形状: [1, " + encoderHiddenStates[0].length + ", " + encoderHiddenStates[0][0].length + "]");

        // 4. Decoder推理
        // 初始decoder输入: [PAD] token (ID=0)
        long[] decoderInputIds = new long[]{DECODER_START_ID};
        OnnxTensor decoderInputTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{decoderInputIds});

        // encoder_attention_mask
        OnnxTensor encoderAttentionMask = OnnxTensor.createTensor(ortEnvironment, new long[][]{attentionMask});

        // 将encoder输出转换为张量
        OnnxTensor encoderHiddenTensor = OnnxTensor.createTensor(ortEnvironment, encoderHiddenStates);

        System.out.println("\n运行Decoder推理...");
        startTime = System.currentTimeMillis();

        Map<String, OnnxTensor> decoderInputs = new HashMap<>();
        decoderInputs.put("input_ids", decoderInputTensor);
        decoderInputs.put("encoder_attention_mask", encoderAttentionMask);
        decoderInputs.put("encoder_hidden_states", encoderHiddenTensor);

        Result decoderResult = decoderSession.run(decoderInputs);

        System.out.println("Decoder推理耗时: " + (System.currentTimeMillis() - startTime) + "ms");

        // 获取logits
        float[][][] logits = (float[][][]) decoderResult.get(0).getValue();
        System.out.println("Decoder logits形状: [1, " + logits[0].length + ", " + logits[0][0].length + "]");

        // 预测下一个token
        int nextToken = argmax(logits[0][0]);
        String decoded = tokenizer.decode(new long[]{nextToken});
        System.out.println("预测的第一个token ID: " + nextToken);
        System.out.println("解码结果: '" + decoded + "'");

        // 清理
        inputTensor.close();
        attentionTensor.close();
        encoderHiddenTensor.close();
        decoderInputTensor.close();
        encoderAttentionMask.close();
        encoderResult.close();
        decoderResult.close();
        encoderSession.close();
        decoderSession.close();

        System.out.println("Decoder模型测试通过");
    }

    @Test
    void testFullSummarization() throws Exception {
        System.out.println("\n--- 测试完整摘要生成 ---");

        Path encoderPath = Paths.get(MODEL_DIR, "onnx", "encoder_model.onnx");
        Path decoderPath = Paths.get(MODEL_DIR, "onnx", "decoder_model.onnx");
        Path tokenizerPath = Paths.get(MODEL_DIR, "tokenizer.json");

        // 加载Tokenizer
        HuggingFaceTokenizer tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);

        // 加载模型
        System.out.println("加载模型...");
        long startTime = System.currentTimeMillis();
        OrtSession encoderSession = ortEnvironment.createSession(encoderPath.toString());
        OrtSession decoderSession = ortEnvironment.createSession(decoderPath.toString());
        long loadTime = System.currentTimeMillis() - startTime;
        System.out.println("模型加载耗时: " + loadTime + "ms");

        // 测试用例
        List<String[]> testCases = List.of(
            new String[]{"getUserById", "public User getUserById(Long id)", "根据ID获取用户"},
            new String[]{"saveUser", "public void saveUser(User user)", "保存用户信息"},
            new String[]{"calculateTotal", "public double calculateTotal(List<Item> items)", "计算总价"},
            new String[]{"validateInput", "public boolean validateInput(String input)", "验证输入"}
        );

        System.out.println("\n开始生成摘要...\n");

        for (String[] testCase : testCases) {
            String methodName = testCase[0];
            String signature = testCase[1];
            String expected = testCase[2];

            System.out.println("方法: " + methodName);
            System.out.println("签名: " + signature);

            // 构建输入 - CodeT5通常使用特定的任务前缀
            String input = "Summarize: " + signature;

            // 编码
            var encoding = tokenizer.encode(input);
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();

            // 限制长度
            if (inputIds.length > 512) {
                inputIds = java.util.Arrays.copyOf(inputIds, 512);
                attentionMask = java.util.Arrays.copyOf(attentionMask, 512);
            }

            // Encoder推理
            OnnxTensor inputTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{inputIds});
            OnnxTensor attentionTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{attentionMask});

            startTime = System.currentTimeMillis();
            Result encoderResult = encoderSession.run(Map.of(
                "input_ids", inputTensor,
                "attention_mask", attentionTensor
            ));
            float[][][] encoderHiddenStates = (float[][][]) encoderResult.get(0).getValue();

            // 自回归解码
            List<Long> generatedTokens = new ArrayList<>();
            generatedTokens.add((long) DECODER_START_ID);

            for (int i = 0; i < MAX_NEW_TOKENS; i++) {
                // 准备decoder输入
                long[] decoderInputIds = generatedTokens.stream().mapToLong(Long::longValue).toArray();
                OnnxTensor decoderInputTensor = OnnxTensor.createTensor(ortEnvironment, new long[][]{decoderInputIds});
                OnnxTensor encoderHiddenTensor = OnnxTensor.createTensor(ortEnvironment, encoderHiddenStates);
                OnnxTensor encoderAttentionMask = OnnxTensor.createTensor(ortEnvironment, new long[][]{attentionMask});

                Map<String, OnnxTensor> decoderInputs = new HashMap<>();
                decoderInputs.put("input_ids", decoderInputTensor);
                decoderInputs.put("encoder_attention_mask", encoderAttentionMask);
                decoderInputs.put("encoder_hidden_states", encoderHiddenTensor);

                Result decoderResult = decoderSession.run(decoderInputs);
                float[][][] logits = (float[][][]) decoderResult.get(0).getValue();

                // 获取最后一个token的预测
                int nextToken = argmax(logits[0][logits[0].length - 1]);

                // 检查是否结束
                if (nextToken == EOS_TOKEN_ID || nextToken == PAD_TOKEN_ID) {
                    decoderInputTensor.close();
                    encoderHiddenTensor.close();
                    encoderAttentionMask.close();
                    decoderResult.close();
                    break;
                }

                generatedTokens.add((long) nextToken);

                decoderInputTensor.close();
                encoderHiddenTensor.close();
                encoderAttentionMask.close();
                decoderResult.close();
            }

            long inferenceTime = System.currentTimeMillis() - startTime;

            // 解码生成的tokens
            long[] finalTokens = generatedTokens.subList(1, generatedTokens.size()).stream().mapToLong(Long::longValue).toArray();
            String summary = tokenizer.decode(finalTokens).trim();

            System.out.println("生成摘要: " + summary);
            System.out.println("期望结果: " + expected);
            System.out.println("推理耗时: " + inferenceTime + "ms");
            System.out.println();

            // 清理
            inputTensor.close();
            attentionTensor.close();
            encoderResult.close();
        }

        encoderSession.close();
        decoderSession.close();

        System.out.println("完整摘要生成测试完成");
    }

    @Test
    void testMemoryUsage() throws Exception {
        System.out.println("\n--- 测试内存占用 ---");

        Runtime runtime = Runtime.getRuntime();

        // 记录初始内存
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("初始内存使用: " + (initialMemory / 1024 / 1024) + " MB");

        // 加载模型
        Path encoderPath = Paths.get(MODEL_DIR, "onnx", "encoder_model.onnx");
        Path decoderPath = Paths.get(MODEL_DIR, "onnx", "decoder_model.onnx");

        long startTime = System.currentTimeMillis();
        OrtSession encoderSession = ortEnvironment.createSession(encoderPath.toString());
        OrtSession decoderSession = ortEnvironment.createSession(decoderPath.toString());
        long loadTime = System.currentTimeMillis() - startTime;

        // 记录加载后内存
        long afterLoadMemory = runtime.totalMemory() - runtime.freeMemory();
        System.out.println("模型加载后内存: " + (afterLoadMemory / 1024 / 1024) + " MB");
        System.out.println("内存增量: " + ((afterLoadMemory - initialMemory) / 1024 / 1024) + " MB");
        System.out.println("加载耗时: " + loadTime + "ms");

        // 模型文件大小
        long encoderSize = Files.size(encoderPath) / 1024 / 1024;
        long decoderSize = Files.size(decoderPath) / 1024 / 1024;
        System.out.println("模型文件大小: Encoder=" + encoderSize + "MB, Decoder=" + decoderSize + "MB, Total=" + (encoderSize + decoderSize) + "MB");

        encoderSession.close();
        decoderSession.close();

        System.out.println("内存测试完成");
    }

    @Test
    void testComparisonWithPhi3() throws Exception {
        System.out.println("\n--- CodeT5 vs Phi-3 对比测试 ---");

        // CodeT5 信息
        Path codet5EncoderPath = Paths.get(MODEL_DIR, "onnx", "encoder_model.onnx");
        Path codet5DecoderPath = Paths.get(MODEL_DIR, "onnx", "decoder_model.onnx");

        // Phi-3 信息
        String phi3Dir = System.getProperty("user.dir", ".") + "/models/text-generation/phi-3-mini-4k-int4/cpu_and_mobile/cpu-int4-rtn-block-32";
        Path phi3Path = Paths.get(phi3Dir, "phi3-mini-4k-instruct-cpu-int4-rtn-block-32.onnx");

        System.out.println("\n=== 模型文件大小对比 ===");

        if (Files.exists(codet5EncoderPath) && Files.exists(codet5DecoderPath)) {
            long codet5Size = Files.size(codet5EncoderPath) + Files.size(codet5DecoderPath);
            System.out.println("CodeT5-base: " + (codet5Size / 1024 / 1024) + " MB");
        }

        if (Files.exists(phi3Path)) {
            long phi3Size = Files.size(phi3Path);
            System.out.println("Phi-3-mini (int4): " + (phi3Size / 1024 / 1024) + " MB");
        }

        System.out.println("\n=== 加载速度对比 ===");

        // 测试CodeT5加载
        if (Files.exists(codet5EncoderPath) && Files.exists(codet5DecoderPath)) {
            long startTime = System.currentTimeMillis();
            OrtSession encoderSession = ortEnvironment.createSession(codet5EncoderPath.toString());
            OrtSession decoderSession = ortEnvironment.createSession(codet5DecoderPath.toString());
            long codet5LoadTime = System.currentTimeMillis() - startTime;
            System.out.println("CodeT5-base 加载耗时: " + codet5LoadTime + "ms");
            encoderSession.close();
            decoderSession.close();
        }

        // 测试Phi-3加载
        if (Files.exists(phi3Path)) {
            long startTime = System.currentTimeMillis();
            OrtSession phi3Session = ortEnvironment.createSession(phi3Path.toString());
            long phi3LoadTime = System.currentTimeMillis() - startTime;
            System.out.println("Phi-3-mini 加载耗时: " + phi3LoadTime + "ms");
            phi3Session.close();
        }

        System.out.println("\n=== 架构特点对比 ===");
        System.out.println("CodeT5-base:");
        System.out.println("  - 架构: 编码器-解码器 (T5)");
        System.out.println("  - 参数量: ~220M");
        System.out.println("  - 专长: 代码理解和生成任务");
        System.out.println("  - 输入: 代码片段");
        System.out.println("  - 输出: 直接生成摘要文本");

        System.out.println("\nPhi-3-mini:");
        System.out.println("  - 架构: 解码器-only (Transformer)");
        System.out.println("  - 参数量: ~3.8B (int4量化后更小)");
        System.out.println("  - 专长: 通用文本生成");
        System.out.println("  - 输入: 提示词 + 代码");
        System.out.println("  - 输出: 对话式回复");

        System.out.println("\n对比测试完成");
    }

    // 辅助方法
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
