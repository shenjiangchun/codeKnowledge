# Model Configuration Restructure: Platform-based → Function-based

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restructure AI model configuration from platform-based (zhipu/siliconflow/iflytek) to function-based (embedding/text-model), with unified services and frontend presets.

**Architecture:** Two config classes (EmbeddingModelConfig, TextModelConfig) replace three platform configs. Two unified services (UnifiedEmbeddingService, UnifiedTextService) replace three duplicate services. Frontend provides preset dropdowns for quick provider selection.

**Tech Stack:** Spring Boot 3.2.0, Java 17, Vue 3 + Element Plus, SnakeYAML

---

## Task 1: New Config Classes

**Files:**
- Create: `src/main/java/com/huawei/hisi/config/EmbeddingModelConfig.java`
- Create: `src/main/java/com/huawei/hisi/config/TextModelConfig.java`

### Step 1: Create EmbeddingModelConfig

```java
@Configuration
@ConfigurationProperties(prefix = "embedding")
@Data
public class EmbeddingModelConfig {
    private String apiKey;
    private String baseUrl = "https://api.siliconflow.cn/v1";
    private String model = "Qwen/Qwen3-VL-Embedding-8B";
    private int dimension = 4096;
    private int timeout = 30000;
    private int maxRetries = 3;
    private long retryBaseDelayMs = 60000;
}
```

### Step 2: Create TextModelConfig

```java
@Configuration
@ConfigurationProperties(prefix = "text-model")
@Data
public class TextModelConfig {
    private String apiKey;
    private String baseUrl = "https://open.bigmodel.cn/api/paas/v4";
    private String model = "glm-4-flash";
    private double temperature = 0.1;
    private int maxTokens = 200;
    private int timeout = 30000;
    private int maxRetries = 3;
    private long retryBaseDelayMs = 60000;
}
```

---

## Task 2: Update application.yml

Replace zhipu/siliconflow/iflytek sections with embedding + text-model sections. Comment out unused provider info as reference.

---

## Task 3: New Unified Services

**Files:**
- Create: `src/main/java/com/huawei/hisi/service/UnifiedEmbeddingService.java`
- Create: `src/main/java/com/huawei/hisi/service/UnifiedTextService.java`

UnifiedEmbeddingService: Single service that calls OpenAI-compatible /embeddings endpoint using EmbeddingModelConfig. Includes retry logic, L2 normalization, dimension validation.

UnifiedTextService: Single service that calls OpenAI-compatible /chat/completions endpoint using TextModelConfig. Includes retry logic, prompt building for code description.

---

## Task 4: Rewire EmbeddingService Facade

Modify `EmbeddingService.java` to delegate to `UnifiedEmbeddingService` instead of three platform services.

---

## Task 5: Rewire LLMDescriptionService

Modify `LLMDescriptionService.java` to use `UnifiedTextService` instead of `ZhipuService`.

---

## Task 6: Update SettingsController + SettingsView.vue

Add model config read/write to settings. Frontend: preset dropdowns for embedding and text-model providers.

---

## Task 7: Deprecate Old Classes

Add @Deprecated to: ZhipuConfig, SiliconFlowConfig, IFlytekConfig, ZhipuService, SiliconFlowEmbeddingService, IFlytekEmbeddingService. Disable their Spring loading.

---

## Task 8: Compile & Verify

`mvn compile` + `vue-tsc --noEmit` both pass.
