# 方案4: 智能变更影响预测

## 依赖层级声明

```
依赖层级图：
┌─────────────────────────────────────────────────────────┐
│                    应用层（本方案）                       │
│         智能变更影响预测服务                              │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    语义层                                │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ 代码语义理解    │  │ 调用链分析      │              │
│  │ (方案2)         │  │ (已有)          │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    数据层                                │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ GitService      │  │ CodeIndex       │              │
│  │ (已有)          │  │ (已有)          │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘

前置依赖：
- GitService（Git操作，已有）
- CodeIndexService（代码索引，已有）
- CallChainService（调用链分析，已有）
- 代码语义理解（方案2，可选增强）

可独立开发：
- 变更影响预测引擎
- 影响范围评估模型
- 风险评估算法
- 测试用例推荐

解耦点：
- 通过ImpactPrediction接口与上层解耦
- 可独立于方案2运行（使用基础调用链分析）
- 方案2可增强预测精度（语义级影响分析）
```

---

## 一、目标与价值

### 1.1 核心目标

**用LLM预测代码变更的潜在影响范围和风险**

| 当前状态 | 目标状态 |
|---------|---------|
| 手工分析影响范围 | LLM自动预测影响 |
| 只看直接调用方 | 语义级影响传播分析 |
| 无风险评估 | 变更风险自动评估 |
| 无测试建议 | 智能生成测试建议 |

### 1.2 价值主张

```
开发效率提升：
├── 快速评估：变更提交前快速了解影响范围
├── 风险预警：高风险变更自动标记
├── 测试指导：智能推荐需要执行的测试
└── 评审辅助：为代码评审提供影响分析

质量保障：
├── 防止遗漏：识别潜在受影响的代码
├── 减少回归：提前预警可能的回归问题
└── 精准测试：只测真正需要测的部分
```

### 1.3 成功指标

| 指标 | 基线 | 目标 |
|------|------|------|
| 影响范围预测准确率 | N/A | ≥75% |
| 风险评估准确率 | N/A | ≥80% |
| 测试推荐覆盖率 | N/A | ≥70% |
| 分析耗时 | 手工30分钟 | ≤2分钟 |

---

## 二、技术方案

### 2.1 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                   Impact Prediction Engine                   │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Change Analysis Layer                   │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ Change      │  │ Semantic    │  │ Risk        │  │   │
│  │  │ Detector    │  │ Analyzer    │  │ Classifier  │  │   │
│  │  │ (变更检测)  │  │ (语义分析)  │  │ (风险分级)  │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Impact Propagation Layer                │   │
│  │                                                     │   │
│  │     ┌───────────────────────────────────────┐       │   │
│  │     │         Impact Propagation Graph       │       │   │
│  │     │  ┌─────┐    ┌─────┐    ┌─────┐       │       │   │
│  │     │  │变更 │───▶│调用方│───▶│下游 │       │       │   │
│  │     │  │节点 │    │节点 │    │影响 │       │       │   │
│  │     │  └─────┘    └─────┘    └─────┘       │       │   │
│  │     │      │          │          │         │       │   │
│  │     │      ▼          ▼          ▼         │       │   │
│  │     │  ┌──────────────────────────┐        │       │   │
│  │     │  │   Semantic Impact Score   │        │       │   │
│  │     │  │   (语义影响评分)          │        │       │   │
│  │     │  └──────────────────────────┘        │       │   │
│  │     └───────────────────────────────────────┘       │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Recommendation Layer                    │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ Test        │  │ Monitor     │  │ Review      │  │   │
│  │  │ Recommender │  │ Advisor     │  │ Assistant   │  │   │
│  │  │ (测试推荐)  │  │ (监控建议)  │  │ (评审辅助)  │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件设计

#### 2.2.1 变更检测器

```java
/**
 * 变更检测器 - 分析Git Diff
 */
@Service
public class ChangeDetector {

    private final GitService gitService;

    /**
     * 检测变更类型
     */
    public enum ChangeType {
        METHOD_MODIFIED,     // 方法修改
        METHOD_ADDED,        // 方法新增
        METHOD_DELETED,      // 方法删除
        METHOD_SIGNATURE_CHANGE, // 签名变更
        CLASS_MODIFIED,      // 类修改
        CLASS_ADDED,         // 类新增
        CLASS_DELETED,       // 类删除
        FIELD_MODIFIED,      // 字段修改
        DEPENDENCY_CHANGE    // 依赖变更
    }

    /**
     * 检测文件变更
     */
    public List<CodeChange> detectChanges(String commitId) {
        GitDiff diff = gitService.getDiff(commitId);

        return diff.getModifiedFiles().stream()
            .map(this::analyzeFileChange)
            .flatMap(List::stream)
            .toList();
    }

    /**
     * 分析单个文件的变更
     */
    private List<CodeChange> analyzeFileChange(FileDiff fileDiff) {
        List<CodeChange> changes = new ArrayList<>();

        // 解析变更内容
        for (DiffLine line : fileDiff.getDiffLines()) {
            if (line.isAddition() || line.isDeletion()) {
                // 使用JavaParser定位变更的方法/类
                CodeLocation location = locateChange(fileDiff.getFilePath(), line);

                CodeChange change = new CodeChange(
                    location,
                    determineChangeType(line, location),
                    line.getContent(),
                    fileDiff.getFilePath()
                );
                changes.add(change);
            }
        }

        return changes;
    }

    /**
     * 确定变更类型
     */
    private ChangeType determineChangeType(DiffLine line, CodeLocation location) {
        // 通过解析前后代码对比确定变更类型
        // 如：签名是否改变、逻辑是否改变等
        return changeTypeAnalyzer.analyze(line, location);
    }
}
```

#### 2.2.2 影响传播分析器

```java
/**
 * 影响传播分析器 - 计算变更影响范围
 */
@Service
public class ImpactPropagationAnalyzer {

    private final CallChainService callChainService;
    private final CodeKnowledgeGraph knowledgeGraph; // 方案2
    private final LLMService llmService;

    /**
     * 分析变更影响
     */
    public ImpactAnalysisResult analyzeImpact(List<CodeChange> changes) {
        ImpactAnalysisResult result = new ImpactAnalysisResult();

        for (CodeChange change : changes) {
            // 1. 直接调用方分析
            List<CallerInfo> directCallers = callChainService.findCallers(
                change.getLocation()
            );

            // 2. 语义级影响分析（使用方案2能力）
            List<SemanticImpact> semanticImpacts = analyzeSemanticImpact(change);

            // 3. 业务影响预测（LLM）
            List<BusinessImpact> businessImpacts = predictBusinessImpact(change, directCallers);

            // 4. 汇总影响范围
            ImpactScope scope = new ImpactScope(
                change,
                directCallers,
                semanticImpacts,
                businessImpacts
            );
            result.addImpact(change, scope);
        }

        return result;
    }

    /**
     * 语义级影响分析
     */
    private List<SemanticImpact> analyzeSemanticImpact(CodeChange change) {
        // 利用方案2的语义理解能力
        // 分析变更对语义上下文的影响

        String prompt = """
            分析以下代码变更的语义影响：

            变更位置：%s
            变更类型：%s
            变更内容：
            %s

            请分析：
            1. 变更是否影响方法的语义（用途、行为）
            2. 可能影响哪些下游功能
            3. 是否破坏现有约定/契约

            输出JSON格式：
            {
              "semanticChangeLevel": "MAJOR|MINOR|NONE",
              "affectedFeatures": ["功能1", "功能2"],
              "contractBreaking": true/false
            }
            """.formatted(
                change.getLocation(),
                change.getType(),
                change.getContent()
            );

        String result = llmService.generateText(prompt);
        return parseSemanticImpact(result);
    }

    /**
     * 业务影响预测
     */
    private List<BusinessImpact> predictBusinessImpact(
        CodeChange change,
        List<CallerInfo> callers
    ) {
        String prompt = """
            预测以下代码变更的业务影响：

            变更：%s.%s
            调用方：
            %s

            请预测：
            1. 可能影响哪些业务功能
            2. 影响严重程度（高/中/低）
            3. 是否可能导致用户可见问题

            输出JSON格式：
            {
              "businessFeatures": [
                {"name": "功能名", "severity": "高/中/低", "visible": true/false}
              ]
            }
            """.formatted(
                change.getLocation().getClassName(),
                change.getLocation().getMethodName(),
                callers.stream().map(c -> c.getClassName() + "." + c.getMethodName())
                    .collect(Collectors.joining("\n"))
            );

        String result = llmService.generateText(prompt);
        return parseBusinessImpact(result);
    }
}
```

#### 2.2.3 风险评估器

```java
/**
 * 风险评估器 - 评估变更风险等级
 */
@Service
public class RiskClassifier {

    /**
     * 风险等级
     */
    public enum RiskLevel {
        CRITICAL,   // 关键风险 - 必须全面测试
        HIGH,       // 高风险 - 需要回归测试
        MEDIUM,     // 中风险 - 需要针对性测试
        LOW,        // 低风险 - 基础测试即可
        SAFE        // 安全变更 - 无风险
    }

    // 风险因子权重
    private static final Map<String, Double> RISK_FACTORS = Map.of(
        "callDepth", 0.3,          // 调用深度
        "callerCount", 0.25,       // 调用方数量
        "semanticChangeLevel", 0.25, // 语义变更级别
        "contractBreaking", 0.2    // 契约破坏
    );

    /**
     * 评估变更风险
     */
    public RiskAssessment assessRisk(CodeChange change, ImpactScope impact) {
        // 1. 计算各风险因子得分
        double callDepthScore = calculateCallDepthScore(impact);
        double callerCountScore = calculateCallerCountScore(impact);
        double semanticScore = calculateSemanticScore(impact);
        double contractScore = calculateContractScore(impact);

        // 2. 加权计算总风险得分
        double totalScore =
            callDepthScore * RISK_FACTORS.get("callDepth") +
            callerCountScore * RISK_FACTORS.get("callerCount") +
            semanticScore * RISK_FACTORS.get("semanticChangeLevel") +
            contractScore * RISK_FACTORS.get("contractBreaking");

        // 3. 映射到风险等级
        RiskLevel level = mapToRiskLevel(totalScore);

        return new RiskAssessment(
            change,
            level,
            totalScore,
            Map.of(
                "callDepth", callDepthScore,
                "callerCount", callerCountScore,
                "semantic", semanticScore,
                "contract", contractScore
            ),
            generateRiskReason(level, impact)
        );
    }

    /**
     * 映射风险等级
     */
    private RiskLevel mapToRiskLevel(double score) {
        if (score >= 0.8) return RiskLevel.CRITICAL;
        if (score >= 0.6) return RiskLevel.HIGH;
        if (score >= 0.4) return RiskLevel.MEDIUM;
        if (score >= 0.2) return RiskLevel.LOW;
        return RiskLevel.SAFE;
    }

    /**
     * 生成风险原因说明
     */
    private String generateRiskReason(RiskLevel level, ImpactScope impact) {
        return switch (level) {
            case CRITICAL -> "变更涉及核心方法，影响范围广，建议全面回归测试";
            case HIGH -> "变更影响多个调用方，可能影响业务功能，建议针对性测试";
            case MEDIUM -> "变更有一定影响范围，建议覆盖相关测试";
            case LOW -> "变更影响范围有限，基础测试即可";
            case SAFE -> "变更安全，无显著影响";
        };
    }
}
```

#### 2.2.4 测试推荐器

```java
/**
 * 测试推荐器 - 推荐需要执行的测试
 */
@Service
public class TestRecommender {

    private final TestCaseRepository testCaseRepo;
    private final LLMService llmService;

    /**
     * 推荐测试用例
     */
    public TestRecommendation recommendTests(
        List<CodeChange> changes,
        RiskAssessment risk
    ) {
        List<TestCase> recommendedTests = new ArrayList<>();

        for (CodeChange change : changes) {
            // 1. 查找现有相关测试
            List<TestCase> existingTests = testCaseRepo.findTestsFor(
                change.getLocation()
            );

            // 2. 基于影响范围扩展测试
            List<TestCase> impactTests = findImpactTests(change);

            // 3. LLM生成新测试建议
            List<TestSuggestion> newTests = generateTestSuggestions(change, risk);

            recommendedTests.addAll(existingTests);
            recommendedTests.addAll(impactTests);
        }

        // 4. 去重并排序（按风险优先级）
        recommendedTests = deduplicateAndSort(recommendedTests, risk);

        return new TestRecommendation(
            recommendedTests,
            generateTestPlan(recommendedTests, risk)
        );
    }

    /**
     * LLM生成测试建议
     */
    private List<TestSuggestion> generateTestSuggestions(
        CodeChange change,
        RiskAssessment risk
    ) {
        if (risk.getLevel() == RiskLevel.SAFE) {
            return List.of(); // 安全变更无需新测试
        }

        String prompt = """
            为以下代码变更生成测试建议：

            变更位置：%s
            变更内容：%s
            风险等级：%s
            影响范围：%s

            请生成：
            1. 需要新增的测试场景
            2. 需要修改的现有测试
            3. 边界测试建议

            输出JSON格式：
            {
              "newTests": [
                {"name": "测试名", "scenario": "测试场景", "priority": "高/中/低"}
              ],
              "modifiedTests": [
                {"name": "现有测试名", "modification": "修改说明"}
              ],
              "edgeCases": ["边界场景1", "边界场景2"]
            }
            """.formatted(
                change.getLocation(),
                change.getContent(),
                risk.getLevel(),
                risk.getReason()
            );

        String result = llmService.generateText(prompt);
        return parseTestSuggestions(result);
    }

    /**
     * 生成测试计划
     */
    private TestPlan generateTestPlan(List<TestCase> tests, RiskAssessment risk) {
        return new TestPlan(
            tests,
            "根据风险评估，建议按以下顺序执行测试",
            estimateTestTime(tests),
            generateTestOrder(tests, risk)
        );
    }
}
```

### 2.3 API设计

```java
/**
 * 变更影响预测API
 */
@RestController
@RequestMapping("/api/impact")
public class ImpactPredictionController {

    private final ImpactPredictionService impactService;

    /**
     * 分析提交影响
     */
    @PostMapping("/analyze/{commitId}")
    public ApiResponse<ImpactAnalysisResult> analyzeCommit(
        @PathVariable String commitId
    ) {
        return ApiResponse.success(impactService.analyzeCommit(commitId));
    }

    /**
     * 分析文件变更影响
     */
    @PostMapping("/analyze/file")
    public ApiResponse<ImpactAnalysisResult> analyzeFileChange(
        @RequestBody FileChangeRequest request
    ) {
        return ApiResponse.success(impactService.analyzeFileChange(request));
    }

    /**
     * 获取风险评估
     */
    @GetMapping("/risk/{changeId}")
    public ApiResponse<RiskAssessment> getRiskAssessment(
        @PathVariable String changeId
    ) {
        return ApiResponse.success(impactService.getRiskAssessment(changeId));
    }

    /**
     * 获取测试推荐
     */
    @GetMapping("/tests/{changeId}")
    public ApiResponse<TestRecommendation> getTestRecommendation(
        @PathVariable String changeId
    ) {
        return ApiResponse.success(impactService.getTestRecommendation(changeId));
    }

    /**
     * 预览变更影响（提交前）
     */
    @PostMapping("/preview")
    public ApiResponse<ImpactPreview> previewImpact(
        @RequestBody ImpactPreviewRequest request
    ) {
        // 用户提交前预览影响分析
        return ApiResponse.success(impactService.previewImpact(request));
    }
}
```

---

## 三、实施步骤

### 3.1 版本迭代计划

```
┌─────────────────────────────────────────────────────────────┐
│                    v4.1 基础变更检测                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 1-2                                              │
│ 目标：建立变更检测与基础影响分析                             │
│                                                             │
│ 功能：                                                      │
│ ├── Git Diff解析                                            │
│ ├── 变更类型识别                                            │
│ ├── 直接调用方分析                                          │
│ ├── 基础风险评估                                            │
│ └── 变更检测API                                             │
│                                                             │
│ 交付物：                                                    │
│ ├── ChangeDetector服务                                      │
│ ├── ImpactPropagationAnalyzer                               │
│ ├── RiskClassifier基础版                                    │
│ └─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v4.2 语义级影响分析                       │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 3-4                                              │
│ 目标：引入语义理解增强影响分析                               │
│                                                             │
│ 功能：                                                      │
│ ├── LLM语义影响分析                                         │
│ ├── 业务影响预测                                            │
│ ├── 契约破坏检测                                            │
│ │── 与方案2集成                                             │
│ └── 影响范围可视化                                          │
│                                                             │
│ 交付物：                                                    │
│ ├── SemanticImpactAnalyzer                                  │
│ ├── BusinessImpactPredictor                                 │
│ ├── 前端影响范围组件                                        │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v4.3 测试推荐系统                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 5-6                                              │
│ 目标：智能测试推荐                                          │
│                                                             │
│ 功能：                                                      │
│ ├── 现有测试关联                                            │
│ ├── 新测试建议生成                                          │
│ ├── 测试计划生成                                            │
│ ├── 提交前预览                                              │
│ └── 风险分级优化                                            │
│                                                             │
│ 交付物：                                                    │
│ ├── TestRecommender                                         │
│ ├── ImpactPreviewService                                    │
│ ├── 提交前检查插件                                          │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 详细任务分解

#### v4.1 任务清单

| 任务 | 描述 | 工时 | 依赖 |
|------|------|------|------|
| T1.1 | Git Diff解析与变更定位 | 6h | GitService |
| T1.2 | 变更类型识别算法 | 4h | T1.1 |
| T1.3 | 直接调用方分析 | 4h | CallChainService |
| T1.4 | 基础风险评估模型 | 6h | T1.2, T1.3 |
| T1.5 | API接口开发 | 4h | T1.1-T1.4 |
| T1.6 | 单元测试 | 4h | T1.1-T1.5 |

---

## 四、验收标准

### 4.1 功能验收标准

| 功能 | 验收标准 | 测试方法 |
|------|---------|---------|
| 变更检测 | 检测准确率≥95% | 100个提交样本 |
| 直接调用方分析 | 准确率≥90% | 与静态分析对比 |
| 语义影响分析 | 准确率≥75% | 50个变更样本 |
| 风险评估 | 准确率≥80% | 与实际回归对比 |
| 测试推荐 | 覆盖率≥70% | 与实际测试对比 |

### 4.2 性能验收标准

| 指标 | 标准 | 测试方法 |
|------|------|---------|
| 变更检测耗时 | <500ms | 性能测试 |
| 影响分析耗时 | <2min | 性能测试 |
| LLM分析耗时 | <10s/变更 | 性能测试 |
| 预览响应时间 | <30s | 性能测试 |

### 4.3 质量验收标准

| 指标 | 标准 |
|------|------|
| 单元测试覆盖率 | ≥80% |
| API文档完整性 | 100% |
| 历史验证准确率 | ≥75% |

---

## 五、依赖关系图

```
                    ┌─────────────────┐
                    │  用户/前端      │
                    └────────┬────────┘
                             │ REST API
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    本方案：智能变更影响预测                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ImpactPredictionController                          │   │
│  │  - POST /api/impact/analyze/{commitId}               │   │
│  │  - POST /api/impact/preview                          │   │
│  │  - GET /api/impact/tests/{changeId}                  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│           ┌───────────────┼───────────────┐                │
│           ▼               ▼               ▼                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Change      │  │ Impact      │  │ Test        │         │
│  │ Detector    │  │ Analyzer    │  │ Recommender │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    依赖层                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ GitService  │  │ CallChain   │  │ 代码语义    │         │
│  │ (已有)      │  │ Service     │  │ (方案2)     │         │
│  │             │  │ (已有)      │  │             │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
│  ┌─────────────┐  ┌─────────────┐                          │
│  │ LLMService  │  │ TestRepo    │                          │
│  │ (已有)      │  │ (新增)      │                          │
│  └─────────────┘  └─────────────┘                          │
└─────────────────────────────────────────────────────────────┘

与其他方案的关系：
┌─────────────┐     提供语义增强能力      ┌─────────────┐
│  方案2      │ ──────────────────────▶ │  本方案     │
│  LLM语义    │                          │  变更影响   │
└─────────────┘                          └─────────────┘

本方案可独立运行（使用基础能力），方案2可增强精度。

与方案1的关系：
┌─────────────┐     提供变更历史追踪      ┌─────────────┐
│  方案1      │ ──────────────────────▶ │  本方案     │
│  GitHistory │                          │  变更影响   │
│  Agent      │                          │             │
└─────────────┘                          └─────────────┘
```

---

## 六、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| LLM预测不准确 | 中 | 高 | 多因子验证 + 历史对比 |
| 语义理解偏差 | 中 | 中 | 方案2增强 + 规则补充 |
| 测试覆盖遗漏 | 中 | 中 | 多策略推荐 + 人工确认 |
| 分析耗时过长 | 低 | 中 | 异步分析 + 缓存 |

---

文档版本：v1.0
创建时间：2026-04-04
作者：hisi-evolution-v2专家组
状态：待评审