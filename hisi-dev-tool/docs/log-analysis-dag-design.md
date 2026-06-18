# Log Analysis DAG Architecture Design

## Goal

Implement a RAM-like DAG flow for log root cause analysis, using Claude SDK + MCP tools (KgMcpClient) for intelligent analysis.

## Current State

`LogAnalysisExecutor` only performs basic rule-based analysis:
- Extract error type from stack trace regex
- Identify root exception from "Caused by"
- Extract key stack frames

**Problem**: No real LLM analysis, no KG integration, no code context loading.

## Proposed Architecture

### DAG Flow (5 Nodes)

```
[ParseNode] → [KgSearchNode] → [CodeContextNode] → [ClaudeAnalyzeNode] → [ReportNode]
     ↓              ↓                  ↓                    ↓               ↓
  解析日志      KG检索相关代码      加载代码上下文        Claude SDK分析     生成报告
```

### Node Details

#### 1. ParseNode
**Input**: `{ message, stackTrace, errorType, serviceName, traceId }`
**Output**: `{ parsedError, keyFrames, errorFingerprint, searchTerm }`

- Extract exception type, root cause line, method signatures
- Generate search terms for KG (class names, method names from stack frames)
- Create structured error info

#### 2. KgSearchNode
**Input**: `{ searchTerm, projectPath, keyFrames }`
**Output**: `{ matchedMethods, callChain, entryPoints }`

- Use `KgMcpClient.hybridSearch()` to find related methods
- Use `KgMcpClient.calleesTree()` for call chain analysis
- Use `KgMcpClient.rootEntries()` to find entry points

#### 3. CodeContextNode
**Input**: `{ matchedMethods, projectPath }`
**Output**: `{ codeBodies, methodDetails }`

- Use `KgMcpClient.loadMethodBodies()` to load actual code
- Build context for Claude analysis

#### 4. ClaudeAnalyzeNode
**Input**: `{ parsedError, codeBodies, callChain, entryPoints }`
**Output**: `{ rootCauseAnalysis, fixSuggestions }`

- Use `ClaudeSessionService` to send analysis request
- Claude acts as root cause analyst with KG context
- MCP tools available: `kg_hybrid_search`, `kg_method_detail`, etc.

#### 5. ReportNode
**Input**: `{ rootCauseAnalysis, fixSuggestions, parsedError }`
**Output**: `{ finalReport }`

- Format analysis into structured report
- Save to database

## Reusable Components from RAM

| Component | Usage |
|-----------|-------|
| `ClaudeSessionService` | Claude SDK communication |
| `DagExecutor` | DAG execution with checkpoint |
| `KgMcpClient` | KG queries via MCP |
| `AgentEventRepository` | Event persistence |
| `InputsHasher` | Cache key generation |

## Implementation Plan

### Phase 1: Core DAG Structure
1. Create `LogAnalysisDagOrchestrator`
2. Define `LogAnalysisDagNode` interface
3. Implement `ParseNode`

### Phase 2: KG Integration
1. Implement `KgSearchNode` using `KgMcpClient`
2. Implement `CodeContextNode`

### Phase 3: Claude Integration
1. Implement `ClaudeAnalyzeNode` using `ClaudeSessionService`
2. Define MCP tools for log analysis context

### Phase 4: Completion
1. Implement `ReportNode`
2. Connect to existing `LogAnalysisExecutor`
3. Update API endpoints

## MCP Tool Definitions (New)

### log_parse
Parse error log and extract structured info.

### kg_search_error_context
Search KG for methods matching error stack frames.

### load_code_context
Load method bodies for analysis context.

### analyze_root_cause
Claude analyzes error with code context.

## Session Flow

```
User submits log → LogAnalysisExecutor.submitForAnalysis()
    → Create session (AgentSession)
    → LogAnalysisDagOrchestrator.run(sessionId)
        → ParseNode.execute()
        → KgSearchNode.execute()
        → CodeContextNode.execute()
        → ClaudeAnalyzeNode.execute()  ← Claude SDK + MCP
        → ReportNode.execute()
    → Save report to log_analysis_report
    → Return reportId
```

## HITL Integration

Like RAM, support Human-in-the-loop:
- After ClaudeAnalyzeNode, pause for user review
- User can reject analysis and provide feedback
- Re-run with feedback injected

## Cost Control

Use existing `CostLimiter` for:
- Claude API call limits
- KG query throttling
- Embedding generation limits

## Comparison with RAM

| RAM | Log Analysis DAG |
|-----|------------------|
| ClarifyNode | ParseNode |
| TechPlanNode | KgSearchNode |
| ImpactNode | CodeContextNode |
| ImplementNode | ClaudeAnalyzeNode |
| VerifyNode | ReportNode |

## File Structure

```
hisi-dev-tool/src/main/java/com/huawei/hisi/
├── service/
│   └── LogAnalysisExecutor.java (existing, modified)
├── loganalysis/
│   ├── orchestrator/
│   │   └── LogAnalysisDagOrchestrator.java
│   │   └── LogAnalysisDagNode.java (interface)
│   ├── nodes/
│   │   ├── ParseNode.java
│   │   ├── KgSearchNode.java
│   │   ├── CodeContextNode.java
│   │   ├── ClaudeAnalyzeNode.java
│   │   └── ReportNode.java
│   └── mcp/
│       └── LogAnalysisMcpTools.java
```