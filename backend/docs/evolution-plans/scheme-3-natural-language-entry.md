# 方案3: 自然语言驱动诊断入口

## 依赖层级声明

```
依赖层级图：
┌─────────────────────────────────────────────────────────┐
│                    交互层（本方案）                       │
│         自然语言诊断入口 + 实时交互界面                   │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    Agent协作层                           │
│  ┌─────────────────────────────────────────────────┐   │
│  │  Orchestrator + Diagnosis/Analysis/QA Agents    │   │
│  │  (方案1：多Agent协作诊断系统)                    │   │
│  └─────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────┘
                          │
                          ▼
┌─────────────────────────────────────────────────────────┐
│                    能力层                                │
│  ┌─────────────────┐  ┌─────────────────┐              │
│  │ 代码语义理解    │  │ LLM服务         │              │
│  │ (方案2)         │  │ (已有)          │              │
│  └─────────────────┘  └─────────────────┘              │
└─────────────────────────────────────────────────────────┘

前置依赖：
- 多Agent协作系统（方案1）
- LLM基础服务（已有）
- WebSocket通信能力（已有）

可独立开发：
- 自然语言意图识别模块
- 多轮对话管理模块
- 前端交互组件
- 思考链可视化组件

解耦点：
- 通过标准Agent接口与后端解耦
- 通过WebSocket协议与通信层解耦
- 前端组件可独立部署
```

---

## 一、目标与价值

### 1.1 核心目标

**将诊断入口从"表单提交"升级为"自然语言对话"**

| 当前状态 | 目标状态 |
|---------|---------|
| 选择日志类型 → 填写表单 → 提交 | 直接说"帮我分析这个NPE" |
| 固定的分析流程 | 灵活的对话式探索 |
| 无法中途干预 | 随时追问、调整方向 |
| 黑盒分析过程 | 透明的思考过程展示 |

### 1.2 价值主张

```
用户体验革命：
├── 零学习成本：自然语言交互，无需记忆命令
├── 实时反馈：看到AI的思考过程，建立信任
├── 灵活追问：不满意可追问，调整分析方向
└── 上下文记忆：多轮对话，持续深入分析

效率提升：
├── 快速入口：无需填写复杂表单
├── 智能引导：AI主动询问关键信息
└── 结果精准：通过对话逐步收敛问题
```

### 1.3 成功指标

| 指标 | 基线 | 目标 |
|------|------|------|
| 用户首次成功率 | 60% | 85%+ |
| 平均对话轮数 | N/A | 3-5轮 |
| 用户满意度 | N/A | 4.5/5.0+ |
| 平均问题解决时间 | 10分钟 | 5分钟 |

---

## 二、技术方案

### 2.1 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                   Natural Language Interface                 │
│                                                             │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Frontend Interaction Layer              │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ Chat        │  │ Thinking    │  │ Context     │  │   │
│  │  │ Panel       │  │ Chain View  │  │ Sidebar     │  │   │
│  │  │ (对话面板)  │  │ (思考链)    │  │ (上下文)    │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │ WebSocket                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Dialogue Management Layer               │   │
│  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │
│  │  │ Intent      │  │ Context     │  │ Response    │  │   │
│  │  │ Recognizer  │  │ Manager     │  │ Generator   │  │   │
│  │  │ (意图识别)  │  │ (上下文管理)│  │ (响应生成)  │  │   │
│  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              Agent Orchestration Layer               │   │
│  │  ┌───────────────────────────────────────────────┐  │   │
│  │  │            Orchestrator Agent                  │  │   │
│  │  │  Task Decomposition │ Progress Push │ Result  │  │   │
│  │  └───────────────────────────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

### 2.2 核心组件设计

#### 2.2.1 意图识别器

```java
/**
 * 自然语言意图识别器
 */
@Service
public class IntentRecognizer {

    private final LLMService llmService;

    // 支持的意图类型
    public enum UserIntent {
        DIAGNOSE_ERROR,      // 诊断错误
        ANALYZE_IMPACT,      // 分析影响
        EXPLAIN_CODE,        // 解释代码
        SUGGEST_FIX,         // 建议修复
        COMPARE_CHANGE,      // 对比变更
        GUIDE_DEBUG,         // 引导调试
        CLARIFY_QUESTION,    // 澄清问题
        UNKNOWN              // 未知意图
    }

    /**
     * 识别用户意图
     */
    public IntentResult recognize(String userInput, DialogueContext context) {
        String prompt = """
            分析用户输入的意图，输出JSON格式：
            {
              "intent": "DIAGNOSE_ERROR|ANALYZE_IMPACT|EXPLAIN_CODE|SUGGEST_FIX|COMPARE_CHANGE|GUIDE_DEBUG|CLARIFY_QUESTION",
              "entities": {
                "errorType": "错误类型（如有）",
                "className": "类名（如有）",
                "methodName": "方法名（如有）",
                "file": "文件名（如有）"
              },
              "confidence": 0.0-1.0,
              "needClarification": true/false,
              "clarificationQuestion": "需要澄清的问题（如needClarification为true）"
            }

            对话历史：
            %s

            用户输入：
            %s
            """.formatted(formatContext(context), userInput);

        String result = llmService.generateText(prompt);
        return parseIntentResult(result);
    }

    /**
     * 提取用户输入中的关键实体
     */
    public EntityExtraction extractEntities(String userInput) {
        // 使用NER或LLM提取：
        // - 异常类型（NPE, Timeout等）
        // - 类名/方法名
        // - 文件路径
        // - 时间范围
        // - 错误关键词
    }
}
```

#### 2.2.2 对话管理器

```java
/**
 * 对话上下文管理器
 */
@Service
public class DialogueContextManager {

    private final ConversationRepository conversationRepo;

    /**
     * 对话上下文
     */
    @Data
    public static class DialogueContext {
        private String sessionId;
        private List<Message> messages;          // 对话历史
        private String currentTask;              // 当前任务
        private TaskState taskState;             // 任务状态
        private Map<String, Object> entities;    // 已识别实体
        private List<String> analyzedFiles;      // 已分析文件
        private String lastConclusion;           // 上次结论
    }

    /**
     * 创建新会话
     */
    public DialogueContext createSession(String userId) {
        DialogueContext context = new DialogueContext();
        context.setSessionId(generateSessionId());
        context.setMessages(new ArrayList<>());
        context.setTaskState(TaskState.IDLE);
        context.setEntities(new HashMap<>());
        return context;
    }

    /**
     * 更新上下文
     */
    public void updateContext(DialogueContext context, Message message, IntentResult intent) {
        // 添加消息到历史
        context.getMessages().add(message);

        // 更新实体
        if (intent.getEntities() != null) {
            context.getEntities().putAll(intent.getEntities());
        }

        // 更新任务状态
        updateTaskState(context, intent);

        // 持久化
        conversationRepo.save(context);
    }

    /**
     * 获取上下文摘要（用于LLM Prompt）
     */
    public String getContextSummary(DialogueContext context) {
        return """
            已识别信息：
            - 错误类型: %s
            - 涉及类: %s
            - 涉及方法: %s
            - 已分析文件: %s
            - 上次结论: %s

            对话历史摘要：
            %s
            """.formatted(
                context.getEntities().get("errorType"),
                context.getEntities().get("className"),
                context.getEntities().get("methodName"),
                context.getAnalyzedFiles(),
                context.getLastConclusion(),
                summarizeMessages(context.getMessages())
            );
    }
}
```

#### 2.2.3 实时交互服务

```java
/**
 * 实时交互服务 - WebSocket双向通信
 */
@Service
@RequiredArgsConstructor
public class RealtimeInteractionService {

    private final SimpMessagingTemplate messagingTemplate;
    private final AgentOrchestrator orchestrator;

    /**
     * 消息类型
     */
    public enum MessageType {
        THINKING,           // AI思考中
        PROGRESS,           // 任务进度
        AGENT_UPDATE,       // Agent状态更新
        INTERIM_RESULT,     // 中间结果
        USER_ACTION_REQUIRED, // 需要用户操作
        FINAL_RESULT,       // 最终结果
        ERROR               // 错误
    }

    /**
     * 处理用户消息
     */
    @MessageMapping("/diagnose/chat")
    public void handleUserMessage(@Payload ChatMessage message, Principal principal) {
        String sessionId = message.getSessionId();

        // 1. 推送：开始处理
        pushMessage(sessionId, MessageType.THINKING, "正在理解您的问题...");

        // 2. 意图识别
        IntentResult intent = intentRecognizer.recognize(
            message.getContent(),
            contextManager.getContext(sessionId)
        );

        // 3. 推送：意图识别结果
        pushMessage(sessionId, MessageType.PROGRESS,
            Map.of("stage", "intent_recognition", "intent", intent.getIntent()));

        // 4. 需要澄清？
        if (intent.isNeedClarification()) {
            pushMessage(sessionId, MessageType.USER_ACTION_REQUIRED,
                Map.of("question", intent.getClarificationQuestion()));
            return;
        }

        // 5. 执行Agent任务
        executeWithRealtimePush(sessionId, intent);
    }

    /**
     * 执行任务并实时推送
     */
    private void executeWithRealtimePush(String sessionId, IntentResult intent) {
        // 订阅Agent事件流
        orchestrator.executeAsync(intent)
            .subscribe(event -> {
                switch (event.getType()) {
                    case AGENT_START ->
                        pushMessage(sessionId, MessageType.AGENT_UPDATE,
                            Map.of("agent", event.getAgentName(), "status", "started"));

                    case AGENT_THINKING ->
                        pushMessage(sessionId, MessageType.THINKING, event.getContent());

                    case AGENT_PROGRESS ->
                        pushMessage(sessionId, MessageType.PROGRESS,
                            Map.of("agent", event.getAgentName(), "progress", event.getProgress()));

                    case AGENT_RESULT ->
                        pushMessage(sessionId, MessageType.INTERIM_RESULT, event.getResult());

                    case ORCHESTRATION_COMPLETE ->
                        pushMessage(sessionId, MessageType.FINAL_RESULT, event.getResult());

                    case ERROR ->
                        pushMessage(sessionId, MessageType.ERROR, event.getError());
                }
            });
    }

    /**
     * 推送消息到前端
     */
    private void pushMessage(String sessionId, MessageType type, Object content) {
        messagingTemplate.convertAndSend("/topic/diagnose/" + sessionId,
            Map.of(
                "type", type.name(),
                "content", content,
                "timestamp", System.currentTimeMillis()
            ));
    }
}
```

#### 2.2.4 响应生成器

```java
/**
 * 自然语言响应生成器
 */
@Service
public class NaturalResponseGenerator {

    private final LLMService llmService;

    /**
     * 生成友好的自然语言响应
     */
    public String generateResponse(AgentResult result, DialogueContext context) {
        String prompt = """
            你是一个专业的技术诊断助手。请将以下分析结果转换为友好的自然语言回复。

            要求：
            1. 使用简洁易懂的语言
            2. 突出关键信息
            3. 提供具体可行的建议
            4. 如果结论不确定，诚实说明

            分析结果：
            %s

            对话上下文：
            %s
            """.formatted(result.toJson(), context.getContextSummary());

        return llmService.generateText(prompt);
    }

    /**
     * 生成追问
     */
    public String generateFollowUpQuestion(AgentResult result, DialogueContext context) {
        // 根据分析结果，判断是否需要追问
        if (result.getConfidence() < 0.7) {
            return "我对这个结论不太确定。您能提供更多上下文吗？比如最近有没有相关改动？";
        }

        if (result.needsMoreContext()) {
            return "为了更精准地定位问题，我需要了解：%s".formatted(
                String.join("、", result.getMissingInfo())
            );
        }

        return null; // 不需要追问
    }

    /**
     * 生成思考链展示
     */
    public List<ThinkingStep> generateThinkingChain(AgentResult result) {
        // 将Agent的执行过程转换为用户可理解的思考步骤
        return result.getExecutionTrace().stream()
            .map(trace -> new ThinkingStep(
                trace.getAgentName(),
                trace.getAction(),
                trace.getReasoning(),
                trace.getResult()
            ))
            .toList();
    }
}
```

### 2.3 前端交互设计

```typescript
// 前端对话组件 (Vue 3)

interface ChatMessage {
  id: string
  role: 'user' | 'assistant' | 'system'
  content: string
  thinkingChain?: ThinkingStep[]
  timestamp: number
}

interface ThinkingStep {
  agent: string
  action: string
  reasoning: string
  result?: string
}

// 对话面板组件
<template>
  <div class="diagnosis-chat">
    <!-- 消息列表 -->
    <div class="message-list">
      <div v-for="msg in messages" :key="msg.id"
           :class="['message', msg.role]">
        <div class="content">{{ msg.content }}</div>

        <!-- 思考链展示 -->
        <div v-if="msg.thinkingChain" class="thinking-chain">
          <div v-for="step in msg.thinkingChain" :key="step.action"
               class="thinking-step">
            <span class="agent">{{ step.agent }}</span>
            <span class="action">{{ step.action }}</span>
            <span class="reasoning">{{ step.reasoning }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- 当前状态指示 -->
    <div v-if="isProcessing" class="status-indicator">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>{{ currentStatus }}</span>
    </div>

    <!-- 输入区域 -->
    <div class="input-area">
      <el-input
        v-model="userInput"
        type="textarea"
        placeholder="描述您遇到的问题，例如：'帮我分析这个NPE错误'"
        @keyup.enter.ctrl="sendMessage"
      />
      <el-button @click="sendMessage" :loading="isProcessing">
        发送
      </el-button>
    </div>

    <!-- 快捷操作 -->
    <div class="quick-actions">
      <el-tag v-for="action in quickActions" :key="action"
              @click="handleQuickAction(action)">
        {{ action }}
      </el-tag>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useWebSocket } from '@/composables/useWebSocket'

const messages = ref<ChatMessage[]>([])
const userInput = ref('')
const isProcessing = ref(false)
const currentStatus = ref('')

// WebSocket连接
const { subscribe, send } = useWebSocket('/topic/diagnose')

onMounted(() => {
  subscribe((message) => {
    handleMessage(message)
  })
})

const handleMessage = (data: any) => {
  switch (data.type) {
    case 'THINKING':
      currentStatus.value = data.content
      break
    case 'AGENT_UPDATE':
      currentStatus.value = `${data.content.agent} 正在分析...`
      break
    case 'FINAL_RESULT':
      messages.value.push({
        id: generateId(),
        role: 'assistant',
        content: data.content.response,
        thinkingChain: data.content.thinkingChain,
        timestamp: Date.now()
      })
      isProcessing.value = false
      currentStatus.value = ''
      break
  }
}

const sendMessage = async () => {
  if (!userInput.value.trim()) return

  // 添加用户消息
  messages.value.push({
    id: generateId(),
    role: 'user',
    content: userInput.value,
    timestamp: Date.now()
  })

  isProcessing.value = true

  // 发送到后端
  send({
    sessionId: currentSessionId,
    content: userInput.value
  })

  userInput.value = ''
}

// 快捷操作
const quickActions = [
  '分析这个NPE',
  '解释这段代码',
  '查看调用链',
  '建议修复方案'
]

const handleQuickAction = (action: string) => {
  userInput.value = action
  sendMessage()
}
</script>
```

---

## 三、实施步骤

### 3.1 版本迭代计划

```
┌─────────────────────────────────────────────────────────────┐
│                    v1.0 基础对话入口                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 1-2                                              │
│ 目标：建立基础自然语言对话能力                               │
│                                                             │
│ 功能：                                                      │
│ ├── 意图识别（规则+LLM）                                    │
│ ├── 基础对话流程                                            │
│ ├── WebSocket实时推送                                       │
│ └── 简单的对话前端                                          │
│                                                             │
│ 交付物：                                                    │
│ ├── IntentRecognizer服务                                    │
│ ├── 对话API接口                                             │
│ └── 基础聊天组件                                            │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v2.0 多轮对话与上下文                     │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 3-4                                              │
│ 目标：实现连贯的多轮对话体验                                 │
│                                                             │
│ 功能：                                                      │
│ ├── 对话上下文管理                                          │
│ ├── 实体提取与追踪                                          │
│ ├── 智能追问                                                │
│ └── 上下文感知响应                                          │
│                                                             │
│ 交付物：                                                    │
│ ├── DialogueContextManager                                 │
│ ├── 实体追踪服务                                            │
│ └── 上下文面板组件                                          │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v3.0 思考链可视化                         │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 5-6                                              │
│ 目标：透明的AI思考过程展示                                   │
│                                                             │
│ 功能：                                                      │
│ ├── 实时思考链展示                                          │
│ ├── Agent状态可视化                                         │
│ ├── 进度追踪                                                │
│ └── 中间结果预览                                            │
│                                                             │
│ 交付物：                                                    │
│ ├── ThinkingChainView组件                                  │
│ ├── Agent状态面板                                           │
│ └── 进度指示器                                              │
└─────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────┐
│                    v4.0 智能引导与个性化                     │
├─────────────────────────────────────────────────────────────┤
│ 时间：Week 7-8                                              │
│ 目标：智能引导和个性化体验                                   │
│                                                             │
│ 功能：                                                      │
│ ├── 主动问题引导                                            │
│ ├── 用户偏好学习                                            │
│ ├── 历史对话推荐                                            │
│ └── 个性化响应风格                                          │
│                                                             │
│ 交付物：                                                    │
│ ├── 智能引导引擎                                            │
│ ├── 用户画像服务                                            │
│ └── 推荐系统                                                │
└─────────────────────────────────────────────────────────────┘
```

### 3.2 详细任务分解

#### v1.0 任务清单

| 任务 | 描述 | 工时 | 依赖 |
|------|------|------|------|
| T1.1 | 设计意图类型体系 | 4h | 无 |
| T1.2 | 实现IntentRecognizer | 8h | T1.1 |
| T1.3 | 实现WebSocket消息处理 | 4h | 无 |
| T1.4 | 设计对话API | 4h | 无 |
| T1.5 | 实现基础聊天前端组件 | 8h | T1.4 |
| T1.6 | 集成测试 | 4h | T1.1-T1.5 |

---

## 四、验收标准

### 4.1 功能验收标准

| 功能 | 验收标准 | 测试方法 |
|------|---------|---------|
| 意图识别 | 准确率≥90% | 200个测试用例 |
| 实体提取 | 准确率≥85% | 100个测试用例 |
| 多轮对话 | 上下文正确传递率≥95% | 多轮对话测试 |
| 思考链展示 | 展示完整性100% | 功能测试 |

### 4.2 用户体验验收标准

| 指标 | 标准 | 测试方法 |
|------|------|---------|
| 首次响应时间 | <2s | 性能测试 |
| 首次成功率 | ≥85% | 用户测试 |
| 用户满意度 | ≥4.5/5.0 | 用户调研 |
| 学习曲线 | <5分钟上手 | 新用户测试 |

### 4.3 兼容性验收标准

| 平台 | 要求 |
|------|------|
| Chrome | 完全支持 |
| Firefox | 完全支持 |
| Safari | 完全支持 |
| Edge | 完全支持 |
| 移动端 | 响应式适配 |

---

## 五、依赖关系图

```
                    ┌─────────────────┐
                    │  用户           │
                    └────────┬────────┘
                             │ 自然语言输入
                             ▼
┌─────────────────────────────────────────────────────────────┐
│                    本方案：自然语言诊断入口                   │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  ChatController                                      │   │
│  │  - WebSocket /diagnose/chat                          │   │
│  │  - POST /api/diagnose/session                        │   │
│  └─────────────────────────────────────────────────────┘   │
│                           │                                 │
│           ┌───────────────┼───────────────┐                │
│           ▼               ▼               ▼                │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ Intent      │  │ Dialogue    │  │ Realtime    │         │
│  │ Recognizer  │  │ Manager     │  │ Interaction │         │
│  └──────┬──────┘  └──────┬──────┘  └──────┬──────┘         │
│         │                │                │                 │
└─────────┼────────────────┼────────────────┼─────────────────┘
          │                │                │
          ▼                ▼                ▼
┌─────────────────────────────────────────────────────────────┐
│                    Agent协作层（方案1）                       │
│  ┌─────────────────────────────────────────────────────┐   │
│  │  Orchestrator Agent → Diagnosis/Analysis/QA Agents  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                    能力层                                   │
│  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐         │
│  │ 代码语义理解 │  │ LLMService  │  │ WebSocket   │         │
│  │ (方案2)      │  │ (已有)       │  │ (已有)      │         │
│  └─────────────┘  └─────────────┘  └─────────────┘         │
└─────────────────────────────────────────────────────────────┘

与方案1的关系：
- 本方案是方案1的交互入口
- 通过Orchestrator接口调用方案1的Agent能力

与方案2的关系：
- 本方案可调用方案2的语义理解能力
- 用于增强意图识别和上下文理解
```

---

## 六、用户交互流程示例

```
场景：用户提交一个NPE错误日志

用户：帮我分析这个NPE错误
      [粘贴堆栈信息]

系统：正在分析您的问题...
      [思考链] 识别到NullPointerException，定位到UserService.login:142

系统：我发现问题出在UserService.login方法的第142行。
      根据分析，authToken变量可能为null。

      可能的原因：
      1. 调用方未传入authToken
      2. 认证服务返回null

      您想进一步了解哪个方向？

用户：最近有改动吗？

系统：正在查询Git历史...
      [思考链] GitHistory Agent 检索近7天提交记录

系统：发现3天前的提交(commit abc123)移除了authToken校验逻辑。
      这很可能是问题根因。

      建议修复方案：
      1. 恢复authToken校验
      2. 添加null检查

      需要我生成修复代码吗？

用户：生成修复代码

系统：正在生成修复代码...
      [思考链] CodeContext Agent 分析代码上下文

系统：推荐修复代码：
      ```java
      if (authToken == null || authToken.isEmpty()) {
          throw new AuthenticationException("Token is required");
      }
      ```
      确认应用此修复？
```

---

## 七、风险评估

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 意图识别错误 | 中 | 高 | 多轮确认 + 澄清机制 |
| WebSocket连接不稳定 | 低 | 中 | 断线重连 + 状态恢复 |
| 响应延迟过高 | 中 | 中 | 流式输出 + 加载动画 |
| 用户期望过高 | 中 | 中 | 清晰的能力边界说明 |

---

文档版本：v1.0
创建时间：2026-04-04
作者：llm-expert-2
状态：待评审