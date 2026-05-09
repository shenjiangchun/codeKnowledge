# 数据模型

> 前端所有 TS 类型定义集中于 `src/types/`,与后端 DTO 严格对齐。本章罗列核心类型与枚举关系。

---

## 1. 全局响应包装

```ts
interface ApiResponse<T> {
  code: number      // 200 = 成功
  message: string
  data: T
}

interface ValidationError {
  field: string
  message: string
}
```

---

## 2. 项目与配置

```mermaid
classDiagram
    class SelectedProjectInfo {
        +string projectName
        +string projectPath
        +string? language
    }
    class GitRepositoryInfo {
        +string name
        +string path
        +string remoteUrl
        +string currentBranch
    }
    class AppState {
        +string projectDir
        +SelectedProjectInfo[] selectedProjects
    }
    AppState "1" o-- "*" SelectedProjectInfo
```

---

## 3. 知识图谱与调用链

```mermaid
classDiagram
    class KnowledgeGraphStatus {
        +string projectPath
        +int methodCount
        +int callRelationCount
        +int bridgeCount
        +string lastGeneratedAt
    }
    class CallChainNode {
        +string id
        +string className
        +string methodName
        +string filePath
        +int line
        +CallChainNode[] children
    }
    class BridgeRelation {
        +BridgeType type
        +string source
        +string target
        +Map metadata
    }
    class BridgeType {
        <<enum>>
        MAPPER
        JPA
        MQ
        FEIGN
        HTTP
        ASPECT
        DIRECT
    }
    BridgeRelation --> BridgeType
    CallChainNode --> CallChainNode : children
```

---

## 4. 会话与消息

```mermaid
classDiagram
    class Session {
        +string id
        +string title
        +SceneType scene
        +string claudeSessionCode
        +string createdAt
        +Message[] messages
    }
    class Message {
        +string id
        +string role  // user/assistant/system
        +string content
        +string createdAt
    }
    class SceneType {
        <<enum>>
        log_analysis
        code_analysis
        trace_analysis
        impact_analysis
        free_chat
    }
    class ClaudeWorkspaceSession {
        +string id
        +string name
        +string workingDir
        +string claudeSessionId
    }
    Session --> SceneType
    Session "1" o-- "*" Message
```

---

## 5. 自然语言对话与 Agent 诊断

```mermaid
classDiagram
    class IntentResult {
        +IntentType type
        +number confidence
        +Map params
    }
    class IntentType {
        <<enum>>
        DIAGNOSE_LOG
        QUERY_CODE
        EXPLAIN_ERROR
        INTERVENE
        FOLLOW_UP
        UNKNOWN
    }
    class DialogSession {
        +string id
        +DialogMessage[] messages
        +DialogContext context
    }
    class DialogMessage {
        +string role
        +string content
        +IntentResult? intent
    }
    class AgentEvent {
        +AgentEventType type
        +AgentType agent
        +AgentStatus status
        +string content
        +long timestamp
    }
    class AgentType {
        <<enum>>
        STACK_TRACE
        CODE_CONTEXT
        GIT_HISTORY
        CONSENSUS
    }
    class FinalDiagnosticResult {
        +string summary
        +AgentResult[] agentResults
        +string suggestion
    }
    IntentResult --> IntentType
    DialogMessage --> IntentResult
    DialogSession --> DialogMessage
    AgentEvent --> AgentType
```

---

## 6. 日志与诊断报告

```ts
interface LogEntry {
  timestamp: string
  level: 'ERROR' | 'WARN' | 'INFO' | 'DEBUG'
  message: string
  traceId?: string
  appId?: string
  // ... 元数据
}

interface LogQueryDto {
  keyword?: string
  contentContains?: string
  logLevel?: string
  traceId?: string
  appId?: string
  startTime?: string
  endTime?: string
  errorOnly?: boolean
  size?: number
  sortOrder?: 'asc' | 'desc'
  dslQuery?: string  // 优先级最高
}

interface AnalyzeTaskResponse { reportId: number }
interface Report { id: number; status: 'pending'|'processing'|'completed'|'failed'; ... }
interface DetailedAnalysisReport extends Report { rootCause: string; suggestion: string; codeSnippets: ... }
```

---

## 7. Skill 系统

```mermaid
classDiagram
    class SkillDefinition {
        +string id
        +string name
        +SkillCategory category
        +string description
        +string version
    }
    class SkillCategory {
        <<enum>>
        diagnosis
        analysis
        generation
        operation
        other
    }
    class ProjectSkillStatus {
        +string projectPath
        +SkillInstallInfo[] installed
    }
    SkillDefinition --> SkillCategory
```

---

## 8. 主题

```ts
type ThemeId = 'dark-tech' | 'dark-monokai' | 'dark-dracula'
             | 'light-minimal' | 'light-sepia' | 'eye-care'

interface ThemeDefinition {
  id: ThemeId
  name: string
  type: 'dark' | 'light'
  background: string; foreground: string; cursor: string; selection: string
  black: string; red: string; ... ; white: string
  brightBlack: string; ... ; brightWhite: string
  accent: string
}
```

---

## 9. Pinia State 速览

| Store | State 形状 |
|-------|-----------|
| `app` | `{ projectDir, selectedProjects }` |
| `sessionStore` | `{ messagesCache: Map, streamingContentCache: Map, streamingStatusCache: Map }` |
| `workspaceStore` | `{ sessions: ClaudeWorkspaceSession[], currentSessionId }` |
| `themeStore` | `{ themeId, customAccent }` |
| `skillStore` | `{ skills, projectStatus, categoryStats }` |
| `promptStore` | `{ templates }` |
| `naturalLanguageStore` | `{ sessions, streamingContent, intentResults }` |

---

> **延伸阅读**:[类型与数据契约](../03-模块说明/类型与数据契约.md) · [接口文档](../05-接口文档/index.md)
