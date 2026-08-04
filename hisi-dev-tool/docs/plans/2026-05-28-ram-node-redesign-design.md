# RAM Node Redesign Design

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign Impact/Implement/Verify output structures and add a new TechPlanNode to produce complete, SE-facing deliverables.

**Architecture:** 4-node pipeline (Clarify→Impact→Implement→Verify) extended to 5 nodes with optional TechPlanNode after Verify. ImpactNode deepens affected_entries analysis. ImplementNode is repositioned as "Requirement Implementation Plan" with structured change specs. TechPlanNode uses Claude with KG+FS tools for deep technical analysis. VerifyNode adds 3 new validation dimensions.

**Tech Stack:** Spring Boot 3.2 + Java 17 + Claude API (tool-use) + Neo4j KG + Vue 3 + Element Plus + Mermaid.js

---

## Part 1: ImpactNode Enhanced Output Depth

### Current Problem

`AffectedEntriesAnnotator` prompt only asks for `"reason": "一句话理由"`, producing shallow output like "该HTTP接口本身就是本次需求状态变更优化的直接修改目标之一" — no business function explanation, impact mechanism, or call path.

### Design

Extend `AnnotatedEntry` record with 4 new fields:

| Field | Source | Description | Example |
|---|---|---|---|
| `business_function` | AI | What this entry does | "协作交付接口：下游项目接收上游需求交付的HTTP端点" |
| `impact_mechanism` | AI | How the modification affects this entry | "deliver内部调用syncReqStatus，状态回卷逻辑修改会改变deliver的返回状态值" |
| `change_behavior` | AI | What behavior changes after modification | "原逻辑：交付后状态不变；新逻辑：交付后若下游状态>上游则回卷" |
| `call_path` | KG (programmatic) | Call chain from entry to modified method | "deliver → RequireStatusService.syncReqStatus" |

### Implementation Strategy

1. **AffectedEntriesAnnotator.java**: Modify SYSTEM_PROMPT to require `business_function`, `impact_mechanism`, `change_behavior` fields in the JSON response. Update `AnnotatedEntry` record to include these fields.

2. **ImpactNode.java**: After AI annotation, use `kg.rootEntryAncestors()` or `kg.callPath()` to programmatically fill `call_path` for each annotated entry. The call path is a deterministic chain from the entry to the target method — no AI guesswork needed.

3. **ImpactOutputView.vue**: Enhance direct entries to show these fields. Each entry expands from a single line to a collapsible detail card with: business_function (subtitle), impact_mechanism + change_behavior (detail section), call_path (code-styled path).

4. **ImpactOutput markdown_report**: Include the deeper analysis in the generated markdown.

### Changed Files

- `AffectedEntriesAnnotator.java` — prompt + record
- `ImpactNode.java` — call_path programmatic fill
- `ImpactOutputView.vue` — UI enhancement
- `impact.output.json` schema — add new fields

---

## Part 2: ImplementNode Repositioning

### Current Problem

- `biz_plan.steps` is essentially a task list, not a technical spec
- `tech_plan` only lists file names/API paths/table changes — no "current→target" change specs
- `ui_plan` is almost always empty for backend projects

### Design: Reposition as "Requirement Implementation Plan"

**New output structure:**

```json
{
  "biz_plan": {
    "steps": ["..."],
    "data_flow": "...",
    "acceptance_mapping": {
      "AC1": ["Step2", "Step3"],
      "AC2": ["Step1"]
    }
  },
  "api_changes": [
    {
      "endpoint": "POST /api/req/deliver",
      "current_behavior": "交付后状态不变",
      "new_behavior": "交付后若下游状态>上游则回卷",
      "method_ref": "ReqCollaborationInterface#deliver"
    }
  ],
  "state_machine_changes": [
    {
      "enum_type": "ReqStatus",
      "old_values": ["初始","设计","已发行","测试中","已完成","已取消"],
      "new_values": ["初始","设计","开发","测试","完成","已取消"],
      "migration_note": "存量'已发行'→'设计'，'已完成'→'完成'，历史快照不处理"
    }
  ],
  "data_model_changes": [
    {
      "entity": "Requirement",
      "field": "status",
      "change_type": "ENUM_UPDATE",
      "detail": "枚举值替换"
    }
  ],
  "config_changes": [
    {
      "key": "req.status.flow.initial-transition",
      "old_value": "初始→已发行",
      "new_value": "初始→设计"
    }
  ]
}
```

**Key changes from old structure:**
- `biz_plan.steps` + `data_flow`: KEPT (unchanged)
- `biz_plan.acceptance_mapping`: NEW — maps each AC to covering steps
- `tech_plan.files/new_apis/schema_changes`: REMOVED — replaced by structured change specs
- `api_changes`: NEW — replaces `tech_plan.new_apis` with current→new behavior pairs
- `state_machine_changes`: NEW — covers enum/status value changes
- `data_model_changes`: NEW — replaces `tech_plan.schema_changes`
- `config_changes`: NEW — covers properties/yml changes
- `ui_plan`: REMOVED (almost always empty in backend projects)

### Changed Files

- `ClaudeImplementLlmClient.java` — rewrite SYSTEM_PROMPT + normalize()
- `implement.output.json` schema — new structure
- `DraftPage.vue` — update `formatImplementOutput()` for new fields
- `VerifyNode.java` — update to work with new structure (api_changes instead of tech_plan.new_apis)

---

## Part 3: New TechPlanNode (Tool-Enhanced)

### Design

A new DAG node after Verify, **manually triggered** (not auto-executed). Uses Claude with `KgToolRegistry` tools (5 KG + 3 FS) for deep code analysis.

**Output structure:**

```json
{
  "target_methods_detail": [
    {
      "method": "RequireStatusServiceImpl#syncReqStatus",
      "file": "com/hisilicon/rms/service/impl/RequireStatusServiceImpl.java",
      "lines": "120-185",
      "current_logic": "当前只同步状态字段，不做回卷判断",
      "change_spec": "新增下游状态>上游的判断逻辑；若下游状态更大则回卷；已取消状态不回卷",
      "pseudocode": "if (downstreamStatus > upstreamStatus && downstreamStatus != CANCELLED) { upstream.setStatus(downstreamStatus) }"
    }
  ],
  "sequence_diagrams": [
    {
      "name": "需求状态回卷时序",
      "mermaid": "sequenceDiagram\n  participant Scheduler\n  ..."
    }
  ],
  "flow_diagrams": [
    {
      "name": "需求状态变更决策流程",
      "mermaid": "flowchart TD\n  A[有子项?] -->|是| B[看子项卷积]..."
    }
  ],
  "test_scope": {
    "unit_tests": [
      "syncReqStatus_下游状态大于上游_应回卷",
      "syncReqStatus_已取消状态_不应回卷"
    ],
    "integration_tests": [
      "deliver_交付后状态回卷到上游",
      "reqBaseline_基线后初始变设计"
    ],
    "data_migration": [
      "UPDATE req_table SET status='设计' WHERE status='已发行'"
    ]
  },
  "risk_mitigations": [
    {
      "risk": "存量数据'已发行'状态需要迁移",
      "mitigation": "先跑迁移脚本，再上线代码；回滚时先回代码再回数据"
    }
  ],
  "reasoning": "...",
  "markdown_report": "..."
}
```

### Tool Usage Strategy

TechPlanNode uses `RamClaudeJsonClient.callJsonWithToolsAndReasoning()` with all 8 tools from `KgToolRegistry`:

**KG tools (primary):**
1. `hybrid_search` — Find related methods by semantic query
2. `load_method_bodies` — Read method source code
3. `callees_tree` — Get downstream call chains
4. `root_entries` — Find upstream entry points
5. `entry_points` — List all system entry points

**FS tools (supplementary, when KG results insufficient):**
6. `grep_project` — Search for config, annotations, string constants
7. `read_file` — Read configuration files, pom.xml, application.yml
8. `list_files` — Explore project structure

### Node Lifecycle

1. User clicks "生成技术方案" button in frontend
2. Frontend sends POST `/api/ram/sessions/{id}/nodes/tech-plan/execute`
3. Backend creates TechPlanNode, injects implement + impact + clarify outputs as context
4. TechPlanNode calls Claude with tools, allowing up to 10 tool rounds
5. Each round: Claude decides which tool to call → handler executes → result fed back
6. Final round: Claude generates structured JSON output
7. Backend stores output as CHECKPOINT event
8. Frontend renders the tech plan with Mermaid diagrams

### Trigger Mechanism

- NOT part of the auto-executing DAG pipeline
- New REST endpoint: `POST /api/ram/sessions/{sessionId}/nodes/tech-plan`
- Frontend shows button only after Verify node completes
- TechPlanNode is a separate phase, not in `phaseOne` list

### Changed Files

- NEW: `TechPlanNode.java`
- NEW: `TechPlanLlmClient.java` (interface + ClaudeTechPlanLlmClient implementation)
- NEW: `tech_plan.output.json` schema
- NEW: `TechPlanOutputView.vue` — specialized renderer with Mermaid diagrams
- `RamController.java` — new endpoint
- `RamDagNodes.java` — register TechPlanNode
- `DraftPage.vue` — add trigger button + render tech plan output
- `dagModel.ts` — add 'tech_plan' to DagNodeKey and DAG_ORDER
- `DagFlow.vue` — 5th card

---

## Part 4: VerifyNode Enhanced Validation

### Design

Expand from 3 checks to 6 checks:

| # | Check Name | Logic | Old/New |
|---|---|---|---|
| 1 | `acceptance_criteria_addressed` | Each AC referenced by a biz_plan step (substring match) | KEPT |
| 2 | `api_changes_consistent` | Each direct affected_entry has a corresponding api_change entry | NEW |
| 3 | `state_changes_complete` | If ImpactNode identified enum-related entries, ImplementNode must have state_machine_changes | NEW |
| 4 | `data_migration_covered` | If state_machine_changes exist, must have migration_note or data_model_changes | NEW |
| 5 | `impact_validation_passed` | impact.validation.passed == true | KEPT |
| 6 | `change_coverage_ratio` | methods_to_modify covered by api_changes / state_machine_changes / data_model_changes | NEW |

### Removed Check

- `tech_plan_files_in_impact` — REMOVED because `tech_plan.files` no longer exists in new ImplementNode output. Its purpose is superseded by `api_changes_consistent` and `change_coverage_ratio`.

### Implementation

All 6 checks are deterministic — no LLM needed. `state_changes_complete` uses simple pattern matching on the intent text (look for "状态" + value change patterns like "已发行→设计").

### Changed Files

- `VerifyNode.java` — rewrite checks
- `verify.output.json` schema — update check names
- `DraftPage.vue` — update `formatVerifyOutput()` for new checks

---

## Dependency Graph

```
Part 2 (ImplementNode) → Part 4 (VerifyNode)   [Verify reads Implement output]
Part 1 (ImpactNode)    → Part 2 (ImplementNode) [Implement reads Impact output]
Part 2 (ImplementNode) → Part 3 (TechPlanNode)  [TechPlan reads Implement output]
Part 1 (ImpactNode)    → Part 3 (TechPlanNode)  [TechPlan reads Impact output]
```

**Recommended implementation order**: Part 1 → Part 2 → Part 4 → Part 3

---

## Frontend Mermaid Integration

For TechPlanNode's sequence/flow diagrams, frontend needs Mermaid.js rendering:

- Add `mermaid` npm dependency
- Create a `MermaidDiagram.vue` component that takes a `code` prop and renders SVG
- Use in `TechPlanOutputView.vue` for each diagram entry
- Fallback: if Mermaid rendering fails, show raw code block

---

## Session Storage Impact

- `RamConfig.java`: New table for tech_plan node state (if needed)
- `RamController.java`: New REST endpoint for manual tech-plan execution
- SSE events: TechPlanNode emits CHECKPOINT when done, same as other nodes
- HITL: TechPlanNode DOES have HITL (user confirms the tech plan before it's "final")
