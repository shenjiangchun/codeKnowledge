# RAM Node Redesign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deepen ImpactNode output, reposition ImplementNode as "Requirement Implementation Plan", add TechPlanNode with tool-use, and enhance VerifyNode validation.

**Architecture:** ImpactNode adds 4 fields to affected_entries (AI + programmatic). ImplementNode replaces flat tech_plan/ui_plan with structured change specs. New TechPlanNode uses Claude+8tools for deep analysis. VerifyNode goes from 3→6 checks.

**Tech Stack:** Spring Boot 3.2 / Java 17 / Neo4j KG / Claude API (tool-use) / Vue 3 + Element Plus + Mermaid.js

---

## Task 1: Extend AnnotatedEntry record with deep analysis fields

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotator.java`

**Step 1: Write the failing test**

Add to `src/test/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotatorTest.java`:

```java
@Test
void annotatedEntry_holdsDeepAnalysisFields() {
    AffectedEntriesAnnotator.AnnotatedEntry entry =
        new AffectedEntriesAnnotator.AnnotatedEntry(
            "n1", "ReqController", "deliver", "CONTROLLER",
            "DIRECT", "直接相关",
            "协作交付HTTP端点", "deliver调用syncReqStatus",
            "原逻辑：状态不变；新逻辑：下游>上游则回卷",
            "deliver → RequireStatusService.syncReqStatus");
    assertThat(entry.businessFunction()).isEqualTo("协作交付HTTP端点");
    assertThat(entry.impactMechanism()).isEqualTo("deliver调用syncReqStatus");
    assertThat(entry.changeBehavior()).isEqualTo("原逻辑：状态不变；新逻辑：下游>上游则回卷");
    assertThat(entry.callPath()).isEqualTo("deliver → RequireStatusService.syncReqStatus");
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="AffectedEntriesAnnotatorTest" -pl . -q`
Expected: FAIL — AnnotatedEntry record doesn't have the new fields yet.

**Step 3: Extend AnnotatedEntry record**

In `AffectedEntriesAnnotator.java`, change the record:

```java
public record AnnotatedEntry(
        String nodeId, String className, String methodName,
        String type, String relevance, String reason,
        String businessFunction, String impactMechanism,
        String changeBehavior, String callPath) {}
```

Add a backward-compatible factory for code that creates entries without deep fields:

```java
/** Create an entry with deep analysis fields defaulting to empty. */
public static AnnotatedEntry shallow(String nodeId, String className, String methodName,
                                     String type, String relevance, String reason) {
    return new AnnotatedEntry(nodeId, className, methodName, type, relevance, reason,
            "", "", "", "");
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="AffectedEntriesAnnotatorTest" -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotator.java src/test/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotatorTest.java
git commit -m "feat(ram): extend AnnotatedEntry with deep analysis fields"
```

---

## Task 2: Enhance AffectedEntriesAnnotator prompt for deep analysis

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotator.java`

**Step 1: Write the failing test**

```java
@Test
void annotate_returnsDeepAnalysisFields() {
    // Mock claude to return deep analysis
    when(claude.isAvailable()).thenReturn(true);
    Map<String, Object> aiResponse = Map.of("analysis", List.of(
        Map.of("nodeId", "n1", "relevance", "DIRECT",
               "business_function", "协作交付端点",
               "impact_mechanism", "deliver调用syncReqStatus",
               "change_behavior", "交付后状态回卷")));
    when(claude.callJson(anyString(), anyString(), any())).thenReturn(aiResponse);

    List<Entry> upstream = List.of(new Entry("n1", "ReqController", "deliver", "CONTROLLER"));
    AnnotatedEntries result = annotator.annotate("需求状态回卷", upstream, "syncReqStatus", "/p");

    assertThat(result.direct()).hasSize(1);
    assertThat(result.direct().get(0).businessFunction()).isEqualTo("协作交付端点");
    assertThat(result.direct().get(0).impactMechanism()).isEqualTo("deliver调用syncReqStatus");
    assertThat(result.direct().get(0).changeBehavior()).isEqualTo("交付后状态回卷");
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="AffectedEntriesAnnotatorTest" -pl . -q`
Expected: FAIL — parseAnnotationResult doesn't extract new fields.

**Step 3: Update SYSTEM_PROMPT and parseAnnotationResult**

Replace SYSTEM_PROMPT with version that requests `business_function`, `impact_mechanism`, `change_behavior` in the JSON response.

Update `parseAnnotationResult` to extract these 3 fields from the AI response map and construct `AnnotatedEntry` with them.

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="AffectedEntriesAnnotatorTest" -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotator.java src/test/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotatorTest.java
git commit -m "feat(ram): enhance AffectedEntriesAnnotator prompt for deep analysis"
```

---

## Task 3: Programmatic call_path fill in ImpactNode

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impact/ImpactNode.java`

**Step 1: Write the failing test**

```java
@Test
void execute_fillsCallPathForAnnotatedEntries() {
    // Setup: targets + upstream + annotation with empty callPath
    // ImpactNode.execute should fill callPath using KG
    // Assert: output.affected_entries.direct[0].callPath is non-empty
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="ImpactNodeTest" -pl . -q`
Expected: FAIL — callPath field not populated

**Step 3: Implement call_path fill**

In `ImpactNode.java`, after AI annotation (Step 3), add:

```java
// Step 3.5: Programmatic call_path fill
List<AnnotatedEntry> directWithPath = annotated.direct().stream()
        .map(ae -> {
            if (ae.callPath() != null && !ae.callPath().isBlank()) return ae;
            String path = resolveCallPath(ae.nodeId(), targetNodeIds, primaryPath);
            return new AnnotatedEntry(ae.nodeId(), ae.className(), ae.methodName(),
                    ae.type(), ae.relevance(), ae.reason(),
                    ae.businessFunction(), ae.impactMechanism(), ae.changeBehavior(), path);
        }).toList();
// Same for indirect...
```

Add helper `resolveCallPath` that uses `kg.calleesTree()` to trace from entry to target.

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest="ImpactNodeTest" -pl . -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impact/ImpactNode.java src/test/java/com/huawei/hisi/ram/nodes/impact/ImpactNodeTest.java
git commit -m "feat(ram): programmatic call_path fill for affected entries"
```

---

## Task 4: Update ImpactOutputView.vue for deep analysis display

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/ram/ImpactOutputView.vue`

**Step 1: Update TypeScript types**

Add `business_function`, `impact_mechanism`, `change_behavior`, `call_path` to `AnnotatedEntry` interface.

**Step 2: Enhance direct entries template**

For each direct entry, replace the single-line display with a collapsible detail card:

```html
<li v-for="(ae, idx) in directEntries" :key="idx" class="method-item">
  <span class="entry-icon">{{ entryTypeIcon(ae.type) }}</span>
  <el-tag size="small" type="primary">{{ entryTypeLabel(ae.type) }}</el-tag>
  <code class="method-name">{{ formatMethod(ae.className, ae.methodName, ae.nodeId) }}</code>
  <!-- Deep analysis detail -->
  <div v-if="ae.business_function" class="entry-detail">
    <div class="entry-subtitle">{{ ae.business_function }}</div>
    <div v-if="ae.impact_mechanism" class="entry-mechanism">
      <span class="detail-label">影响机制：</span>{{ ae.impact_mechanism }}
    </div>
    <div v-if="ae.change_behavior" class="entry-behavior">
      <span class="detail-label">行为变化：</span>{{ ae.change_behavior }}
    </div>
    <div v-if="ae.call_path" class="entry-callpath">
      <span class="detail-label">调用路径：</span><code>{{ ae.call_path }}</code>
    </div>
  </div>
</li>
```

**Step 3: Add CSS for entry-detail styles**

```css
.entry-detail {
  margin-top: 4px;
  padding: 6px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  width: 100%;
}
.entry-subtitle { font-size: 12px; color: #606266; margin-bottom: 4px; }
.entry-mechanism, .entry-behavior { font-size: 12px; color: #303133; line-height: 1.6; }
.entry-callpath code { font-size: 11px; background: #ecf5ff; padding: 1px 4px; border-radius: 2px; }
.detail-label { font-weight: 500; color: #909399; }
```

**Step 4: Verify in browser**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Navigate to RAM page, trigger impact analysis, check direct entries show deep analysis.

**Step 5: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/ImpactOutputView.vue
git commit -m "feat(ram): deepen ImpactOutputView with business_function/impact_mechanism/change_behavior/call_path"
```

---

## Task 5: Update ImpactNode markdown_report with deep analysis

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impact/ImpactNode.java`

**Step 1: Update generateMarkdownReport**

For each direct entry in the markdown, add the deep analysis fields:

```java
// In the direct entries section:
for (AnnotatedEntry ae : annotated.direct()) {
    md.append("| ").append(formatEntry(ae)).append(" | ").append(ae.reason()).append(" |\n");
    if (ae.businessFunction() != null && !ae.businessFunction().isBlank()) {
        md.append("| | **功能**: ").append(ae.businessFunction()).append(" |\n");
    }
    if (ae.impactMechanism() != null && !ae.impactMechanism().isBlank()) {
        md.append("| | **影响机制**: ").append(ae.impactMechanism()).append(" |\n");
    }
    if (ae.changeBehavior() != null && !ae.changeBehavior().isBlank()) {
        md.append("| | **行为变化**: ").append(ae.changeBehavior()).append(" |\n");
    }
    if (ae.callPath() != null && !ae.callPath().isBlank()) {
        md.append("| | **调用路径**: `").append(ae.callPath()).append("` |\n");
    }
}
```

**Step 2: Run existing ImpactNodeTest to verify no regression**

Run: `mvn test -Dtest="ImpactNodeTest" -pl . -q`
Expected: PASS

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impact/ImpactNode.java
git commit -m "feat(ram): include deep analysis in ImpactNode markdown_report"
```

---

## Task 6: Update impact.output.json schema

**Files:**
- Modify: `src/main/resources/schemas/ram/impact.output.json`

**Step 1: Add new fields to affected_entries items**

In the `direct` and `indirect` array item definitions, add:

```json
"business_function": { "type": "string", "description": "业务功能说明" },
"impact_mechanism": { "type": "string", "description": "影响机制" },
"change_behavior": { "type": "string", "description": "行为变化" },
"call_path": { "type": "string", "description": "调用路径" }
```

**Step 2: Commit**

```bash
git add src/main/resources/schemas/ram/impact.output.json
git commit -m "feat(ram): add deep analysis fields to impact output schema"
```

---

## Task 7: Rewrite ImplementNode output structure

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impl/ClaudeImplementLlmClient.java`
- Modify: `src/main/resources/schemas/ram/implement.output.json`

**Step 1: Write the failing test**

```java
@Test
void draft_producesNewOutputStructure() {
    when(claude.isAvailable()).thenReturn(true);
    Map<String, Object> mockResult = Map.of(
        "biz_plan", Map.of("steps", List.of("Step1"), "data_flow", "flow",
                           "acceptance_mapping", Map.of("AC1", List.of("Step1"))),
        "api_changes", List.of(Map.of("endpoint", "POST /api/deliver",
            "current_behavior", "不变", "new_behavior", "回卷",
            "method_ref", "ReqController#deliver")),
        "state_machine_changes", List.of(),
        "data_model_changes", List.of(),
        "config_changes", List.of()
    );
    when(claude.callJson(anyString(), anyString(), any())).thenReturn(mockResult);

    Map<String, Object> result = client.draft(impactOutput, List.of("AC1"), null);
    assertThat(result).containsKey("api_changes");
    assertThat(result).containsKey("state_machine_changes");
    assertThat(result).doesNotContainKey("tech_plan");
    assertThat(result).doesNotContainKey("ui_plan");
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="ClaudeImplementLlmClientTest" -pl . -q`
Expected: FAIL — normalize() still produces tech_plan/ui_plan

**Step 3: Rewrite SYSTEM_PROMPT and normalize()**

New SYSTEM_PROMPT instructs the model to produce `biz_plan` (with `acceptance_mapping`), `api_changes`, `state_machine_changes`, `data_model_changes`, `config_changes`. No `tech_plan` or `ui_plan`.

Update `normalize()` to ensure all 5 top-level keys exist with sensible defaults.

**Step 4: Update implement.output.json schema**

Replace old schema with new structure: required `biz_plan`, `api_changes`; optional `state_machine_changes`, `data_model_changes`, `config_changes`.

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest="ClaudeImplementLlmClientTest" -pl . -q`
Expected: PASS

**Step 6: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impl/ClaudeImplementLlmClient.java src/main/resources/schemas/ram/implement.output.json
git commit -m "feat(ram): reposition ImplementNode as Requirement Implementation Plan with structured change specs"
```

---

## Task 8: Update frontend for new ImplementNode output

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/DraftPage.vue`
- Modify: `hisi-dev-tool-frontend/src/components/ram/ConfirmModal.vue`

**Step 1: Rewrite formatImplementOutput()**

Replace the old biz_plan/tech_plan/ui_plan rendering with:

- `biz_plan.steps` — numbered steps (same as before)
- `biz_plan.acceptance_mapping` — new section: AC→Steps mapping table
- `biz_plan.data_flow` — same as before
- `api_changes` — table: endpoint | current_behavior | new_behavior | method_ref
- `state_machine_changes` — each: enum_type, old→new values, migration_note
- `data_model_changes` — table: entity | field | change_type | detail
- `config_changes` — table: key | old_value | new_value

**Step 2: Update ConfirmModal isImpactOutput detection**

Add detection for new ImplementNode output keys: `api_changes`, `state_machine_changes`.

**Step 3: Verify in browser**

Run: `cd hisi-dev-tool-frontend && npm run dev`

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/ram/DraftPage.vue hisi-dev-tool-frontend/src/components/ram/ConfirmModal.vue
git commit -m "feat(ram): frontend support for new ImplementNode output structure"
```

---

## Task 9: Rewrite VerifyNode with 6 checks

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/VerifyNode.java`
- Modify: `src/main/resources/schemas/ram/verify.output.json`

**Step 1: Write the failing test**

```java
@Test
void execute_withNewImplementOutput_runs6Checks() {
    Map<String, Object> impact = Map.of(
        "validation", Map.of("passed", true, "violations", List.of()),
        "affected_entries", Map.of("direct", List.of(
            Map.of("className", "ReqController", "methodName", "deliver", "type", "CONTROLLER"))),
        "methods_to_modify", List.of(
            Map.of("className", "RequireStatusServiceImpl", "methodName", "syncReqStatus")));
    Map<String, Object> implement = Map.of(
        "biz_plan", Map.of("steps", List.of("修改syncReqStatus")),
        "api_changes", List.of(Map.of("method_ref", "ReqController#deliver",
            "endpoint", "POST /api/deliver", "current_behavior", "不变", "new_behavior", "回卷")),
        "state_machine_changes", List.of(), "data_model_changes", List.of(), "config_changes", List.of());
    Map<String, Object> input = Map.of("impact", impact, "implement", implement,
        "acceptance_criteria", List.of("状态回卷"));

    Map<String, Object> result = node.execute(input);
    List<Map<String, Object>> checks = (List<Map<String, Object>>) result.get("checks");
    assertThat(checks).hasSize(6);
    assertThat(result.get("pass")).isEqualTo(true);
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest="VerifyNodeTest" -pl . -q`
Expected: FAIL — VerifyNode still does 3 checks with old field names.

**Step 3: Rewrite VerifyNode checks**

Replace the 3 old checks with 6 new checks:
1. `acceptance_criteria_addressed` — KEPT, same logic
2. `api_changes_consistent` — each direct affected_entry.className#methodName must appear in api_changes[].method_ref
3. `state_changes_complete` — if intent contains "状态" + value pattern, state_machine_changes must be non-empty
4. `data_migration_covered` — if state_machine_changes exists and has enum changes, must have migration_note or data_model_changes
5. `impact_validation_passed` — KEPT, same logic
6. `change_coverage_ratio` — count methods_to_modify referenced in api_changes/state_machine_changes/data_model_changes, compute ratio

Remove `tech_plan_files_in_impact` check (no longer applicable).

**Step 4: Update verify.output.json schema**

Update check name documentation.

**Step 5: Run test to verify it passes**

Run: `mvn test -Dtest="VerifyNodeTest" -pl . -q`
Expected: PASS

**Step 6: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/VerifyNode.java src/main/resources/schemas/ram/verify.output.json
git commit -m "feat(ram): expand VerifyNode from 3 to 6 validation checks"
```

---

## Task 10: Update frontend for VerifyNode new checks

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/DraftPage.vue`

**Step 1: Update formatVerifyOutput()**

Update to render all 6 check names with appropriate Chinese labels:

| Check Name | Chinese Label |
|---|---|
| acceptance_criteria_addressed | 验收标准覆盖 |
| api_changes_consistent | API变更一致性 |
| state_changes_complete | 状态变更完整性 |
| data_migration_covered | 数据迁移覆盖 |
| impact_validation_passed | 影响分析验证 |
| change_coverage_ratio | 变更覆盖率 |

Render `change_coverage_ratio` detail as a percentage/ratio.

**Step 2: Verify in browser**

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/ram/DraftPage.vue
git commit -m "feat(ram): frontend support for VerifyNode 6-check output"
```

---

## Task 11: Create TechPlanLlmClient interface

**Files:**
- Create: `src/main/java/com/huawei/hisi/ram/nodes/TechPlanLlmClient.java`

**Step 1: Define interface**

```java
package com.huawei.hisi.ram.nodes;

import java.util.List;
import java.util.Map;

public interface TechPlanLlmClient {
    /**
     * Generate a complete technical plan using tool-enhanced analysis.
     *
     * @param impactOutput  output from the Impact node
     * @param implementOutput output from the Implement node
     * @param intent the original requirement description
     * @param projectPath Neo4j project path
     * @return structured technical plan output
     */
    Map<String, Object> generate(Map<String, Object> impactOutput,
                                  Map<String, Object> implementOutput,
                                  String intent,
                                  String projectPath);

    boolean isAvailable();
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/TechPlanLlmClient.java
git commit -m "feat(ram): add TechPlanLlmClient interface"
```

---

## Task 12: Create ClaudeTechPlanLlmClient implementation

**Files:**
- Create: `src/main/java/com/huawei/hisi/ram/nodes/impl/ClaudeTechPlanLlmClient.java`

**Step 1: Implement with tool-use**

Key structure:
- Inject `RamClaudeJsonClient` + `KgToolRegistry`
- Build system prompt instructing Claude to produce the TechPlan output structure
- Build user prompt from impact + implement output + intent
- Call `claude.callJsonWithToolsAndReasoning()` with all 8 tools from `KgToolRegistry.buildToolDefinitions()`
- Register all 8 handlers from `KgToolRegistry.buildToolHandlers()`
- Parse result, normalize missing fields, return

**Step 2: Write test**

```java
@Test
void generate_callsClaudeWithToolsAndReturnsStructuredOutput() {
    // Mock claude + kgToolRegistry
    // Verify callJsonWithToolsAndReasoning is called with 8 tools
    // Verify output has target_methods_detail, sequence_diagrams, test_scope keys
}
```

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impl/ClaudeTechPlanLlmClient.java src/test/java/com/huawei/hisi/ram/nodes/impl/ClaudeTechPlanLlmClientTest.java
git commit -m "feat(ram): implement ClaudeTechPlanLlmClient with KG+FS tool-use"
```

---

## Task 13: Create TechPlanNode

**Files:**
- Create: `src/main/java/com/huawei/hisi/ram/nodes/TechPlanNode.java`
- Create: `src/main/resources/schemas/ram/tech_plan.output.json`

**Step 1: Write the failing test**

```java
@Test
void execute_callsLlmClientAndReturnsStructuredOutput() {
    // Mock llmClient.generate() to return valid tech plan
    // Verify output structure
}
```

**Step 2: Implement TechPlanNode**

```java
@Component
public class TechPlanNode implements RamNode {
    private final TechPlanLlmClient llmClient;
    private final SchemaValidator schemaValidator;

    @Override
    public String name() { return "tech_plan"; }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        Map<String, Object> impactOutput = nestedMap(input, "impact");
        Map<String, Object> implementOutput = nestedMap(input, "implement");
        String intent = stringInput(input, "intent");
        String projectPath = stringInput(input, "projectPath");

        Map<String, Object> output = llmClient.generate(impactOutput, implementOutput, intent, projectPath);
        schemaValidator.validate("tech_plan.output", output);
        return output;
    }
}
```

**Step 3: Create tech_plan.output.json schema**

Define schema for: target_methods_detail (array), sequence_diagrams (array), flow_diagrams (array), test_scope (object), risk_mitigations (array), reasoning (string), markdown_report (string).

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/TechPlanNode.java src/main/resources/schemas/ram/tech_plan.output.json src/test/java/com/huawei/hisi/ram/nodes/TechPlanNodeTest.java
git commit -m "feat(ram): add TechPlanNode with schema validation"
```

---

## Task 14: Add TechPlan execution REST endpoint

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/controller/RamController.java`

**Step 1: Add endpoint**

```java
@PostMapping("/sessions/{sessionId}/nodes/tech-plan")
public ResponseEntity<?> executeTechPlan(@PathVariable String sessionId) {
    // Load session state, get impact+implement+intent from prior checkpoints
    // Execute TechPlanNode
    // Emit CHECKPOINT event via SSE
    // Return 202 Accepted with nextSeq
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/controller/RamController.java
git commit -m "feat(ram): add manual TechPlan execution endpoint"
```

---

## Task 15: Add tech_plan to DAG model and flow

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/ram/dagModel.ts`
- Modify: `hisi-dev-tool-frontend/src/components/ram/DagFlow.vue`

**Step 1: Update dagModel.ts**

```typescript
export type DagNodeKey = 'clarify' | 'impact' | 'implement' | 'verify' | 'tech_plan'
export const DAG_ORDER: DagNodeKey[] = ['clarify', 'impact', 'implement', 'verify', 'tech_plan']
```

Add tech_plan phase label: `tech_plan: '技术方案'`

**Step 2: Update DagFlow.vue**

Add 5th card in the SVG layout. The tech_plan card should be visually distinct (e.g. dashed border) to indicate it's manually triggered.

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/dagModel.ts hisi-dev-tool-frontend/src/components/ram/DagFlow.vue
git commit -m "feat(ram): add tech_plan node to DAG model and flow visualization"
```

---

## Task 16: Create TechPlanOutputView.vue with Mermaid rendering

**Files:**
- Create: `hisi-dev-tool-frontend/src/components/ram/TechPlanOutputView.vue`
- Create: `hisi-dev-tool-frontend/src/components/ram/MermaidDiagram.vue`

**Step 1: Install mermaid dependency**

Run: `cd hisi-dev-tool-frontend && npm install mermaid`

**Step 2: Create MermaidDiagram.vue**

Simple component: takes `code` prop, renders Mermaid SVG on mount, falls back to code block on error.

**Step 3: Create TechPlanOutputView.vue**

Sections:
1. **方法改动规格** — table: method | file | lines | current_logic | change_spec | pseudocode
2. **时序图** — MermaidDiagram for each sequence_diagrams entry
3. **流程图** — MermaidDiagram for each flow_diagrams entry
4. **测试范围** — 3 lists: unit_tests, integration_tests, data_migration
5. **风险缓解** — table: risk | mitigation

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/MermaidDiagram.vue hisi-dev-tool-frontend/src/components/ram/TechPlanOutputView.vue hisi-dev-tool-frontend/package.json hisi-dev-tool-frontend/package-lock.json
git commit -m "feat(ram): add TechPlanOutputView with Mermaid diagram rendering"
```

---

## Task 17: Wire TechPlan into DraftPage with trigger button

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/DraftPage.vue`
- Modify: `hisi-dev-tool-frontend/src/api/ram.ts`

**Step 1: Add API function**

In `api/ram.ts`:

```typescript
export function executeTechPlan(sessionId: string): Promise<{ nextSeq: number }> {
  return api.post(`/ram/sessions/${sessionId}/nodes/tech-plan`).then(r => r.data)
}
```

**Step 2: Add trigger button in DraftPage**

Show "生成技术方案" button when:
- verify node status is `done`
- tech_plan node status is not `done` or `running`

**Step 3: Add tech_plan output rendering**

In `formatNodeOutput()`, add case for `'tech_plan'` that uses `TechPlanOutputView`.

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/ram/DraftPage.vue hisi-dev-tool-frontend/src/api/ram.ts
git commit -m "feat(ram): wire TechPlan trigger button and output rendering in DraftPage"
```

---

## Task 18: Full integration test and regression verification

**Step 1: Run all backend tests**

Run: `mvn test -pl . -q`
Expected: ALL PASS

**Step 2: Run frontend build**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: Build succeeds

**Step 3: Manual end-to-end test**

1. Start backend + frontend
2. Start a RAM session with a complex requirement
3. Verify: ImpactNode shows deep analysis (business_function, impact_mechanism, change_behavior, call_path)
4. Verify: ImplementNode shows structured change specs (api_changes, state_machine_changes, etc.)
5. Verify: VerifyNode shows 6 checks
6. Verify: After Verify completes, "生成技术方案" button appears
7. Click button, verify TechPlanNode executes with tools and produces output with Mermaid diagrams

**Step 4: Commit any fixes**

---

## Summary

| Task | Part | Description |
|---|---|---|
| 1 | P1 | Extend AnnotatedEntry record |
| 2 | P1 | Enhance Annotator prompt for deep analysis |
| 3 | P1 | Programmatic call_path fill |
| 4 | P1 | Frontend ImpactOutputView deep display |
| 5 | P1 | Markdown report deep analysis |
| 6 | P1 | Impact output schema update |
| 7 | P2 | Rewrite ImplementNode output structure |
| 8 | P2 | Frontend ImplementNode rendering |
| 9 | P4 | Rewrite VerifyNode 6 checks |
| 10 | P4 | Frontend VerifyNode rendering |
| 11 | P3 | TechPlanLlmClient interface |
| 12 | P3 | ClaudeTechPlanLlmClient implementation |
| 13 | P3 | TechPlanNode + schema |
| 14 | P3 | REST endpoint for manual execution |
| 15 | P3 | DAG model + flow update |
| 16 | P3 | TechPlanOutputView + Mermaid |
| 17 | P3 | DraftPage trigger button + wiring |
| 18 | ALL | Full integration test |
