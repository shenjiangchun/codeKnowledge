# Impact Output Redesign Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Redesign ImpactNode to only output methods_to_modify + affected entries (direct/indirect), add node reasoning, unify DraftPage/ConfirmModal rendering, and support Markdown output.

**Architecture:** Clarify LLM outputs `target_methods` (method-level targets). ImpactNode uses these directly (fallback to hybrid search), then traces upstream to root entry points via `rootEntryAncestors()`. AI annotates entries as DIRECT/INDIRECT. Output is simplified to just what SEs care about. Frontend unifies rendering between DraftPage and ConfirmModal.

**Tech Stack:** Java 17 / Spring Boot 3.2 / Vue 3 / TypeScript / Element Plus / marked.js (Markdown)

---

### Task 1: Enhance Clarify Prompt — Add `target_methods` Output Field

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impl/ClaudeClarifyLlmClient.java` (3 prompt constants + normalize())
- Test: `src/test/java/com/huawei/hisi/ram/nodes/impl/ClaudeClarifyLlmClientTest.java`

**Step 1: Add `target_methods` to all 3 prompt output schemas**

In `SYSTEM_PROMPT` (line ~96), add after `target_modules`:
```
- target_methods: specific methods (ClassName#methodName) that need code changes, may be empty.
```

In `SYSTEM_PROMPT_WITH_CODE_CONTEXT` (line ~220), add:
```
"target_methods": ["<ClassName#methodName from code context>", ...],
```
And in rules section:
```
- target_methods: MUST reference actual methods found in the code context — methods that need modification to implement this requirement.
```

In `SYSTEM_PROMPT_WITH_TOOLS` (line ~327), add:
```
"target_methods": ["<ClassName#methodName found via tools>", ...],
```
And in rules:
```
- target_methods: specific methods you found via tools that need modification — use ClassName#methodName format.
```

**Step 2: Update `normalize()` to pass through `target_methods`**

In `normalize()` method (~line 567), add after `target_modules`:
```java
if (raw != null && raw.get("target_methods") != null) {
    out.put("target_methods", asStringList(raw.get("target_methods")));
}
```

**Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: success

**Step 4: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impl/ClaudeClarifyLlmClient.java
git commit -m "feat(ram): add target_methods to clarify prompt output schema"
```

---

### Task 2: New MethodTargetResolver — Resolve target_methods to nodeIds

**Files:**
- Create: `src/main/java/com/huawei/hisi/ram/nodes/impact/MethodTargetResolver.java`
- Test: `src/test/java/com/huawei/hisi/ram/nodes/impact/MethodTargetResolverTest.java`

**Step 1: Write failing test**

```java
@ExtendWith(MockitoExtension.class)
class MethodTargetResolverTest {
    @Mock KgMcpClient kg;

    @Test
    void resolve_fromTargetMethods_parsesAndResolves() {
        when(kg.calleesTree("RequireStatusSchedule", "syncReqStatusInfo", "/p", 0))
            .thenReturn(new CallTreeNode("node-123", "RequireStatusSchedule", "syncReqStatusInfo", 0, List.of()));

        List<MethodTarget> targets = new MethodTargetResolver(kg)
            .resolve(List.of("RequireStatusSchedule#syncReqStatusInfo"), List.of(), "/p");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).nodeId()).isEqualTo("node-123");
        assertThat(targets.get(0).className()).isEqualTo("RequireStatusSchedule");
        assertThat(targets.get(0).methodName()).isEqualTo("syncReqStatusInfo");
    }

    @Test
    void resolve_fallbackToSearch_whenTargetMethodsEmpty() {
        when(kg.hybridSearch("sync status", "/p", 10))
            .thenReturn(List.of(new Seed("node-456", 0.8, "sync")));

        List<MethodTarget> targets = new MethodTargetResolver(kg)
            .resolve(List.of(), List.of(new Seed("node-456", 0.8, "sync")), "/p");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).nodeId()).isEqualTo("node-456");
    }
}
```

**Step 2: Run test — verify it fails**

Run: `mvn test -Dtest=MethodTargetResolverTest -q`
Expected: FAIL (class not found)

**Step 3: Implement MethodTargetResolver**

```java
@Component
public class MethodTargetResolver {
    private final KgMcpClient kg;

    public MethodTargetResolver(KgMcpClient kg) { this.kg = kg; }

    public record MethodTarget(String nodeId, String className, String methodName, String reason) {}

    public List<MethodTarget> resolve(List<String> targetMethods, List<Seed> searchFallback, String projectPath) {
        if (targetMethods != null && !targetMethods.isEmpty()) {
            return resolveFromTargetMethods(targetMethods, projectPath);
        }
        // Fallback: use search results as targets
        return resolveFromSeeds(searchFallback != null ? searchFallback : List.of());
    }

    private List<MethodTarget> resolveFromTargetMethods(List<String> targetMethods, String projectPath) {
        List<MethodTarget> targets = new ArrayList<>();
        for (String tm : targetMethods) {
            String[] parts = tm.split("#");
            if (parts.length != 2) continue;
            String className = parts[0];
            String methodName = parts[1];
            // Use calleesTree with depth=0 just to resolve the nodeId
            CallTreeNode node = kg.calleesTree(className, methodName, projectPath, 0);
            String nodeId = (node != null && node.nodeId() != null) ? node.nodeId() : className + "#" + methodName;
            targets.add(new MethodTarget(nodeId, className, methodName, ""));
        }
        return targets;
    }

    private List<MethodTarget> resolveFromSeeds(List<Seed> seeds) {
        return seeds.stream()
            .filter(s -> s != null && s.nodeId() != null)
            .map(s -> new MethodTarget(s.nodeId(), "", "", s.summary() != null ? s.summary() : ""))
            .toList();
    }
}
```

**Step 4: Run test — verify pass**

Run: `mvn test -Dtest=MethodTargetResolverTest -q`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/ram/nodes/impact/MethodTargetResolver.java src/test/java/com/huawei/hisi/ram/nodes/impact/MethodTargetResolverTest.java
git commit -m "feat(ram): add MethodTargetResolver for target_methods→nodeId resolution"
```

---

### Task 3: New AffectedEntriesAnnotator — AI DIRECT/INDIRECT Classification

**Files:**
- Create: `src/main/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotator.java`
- Test: `src/test/java/com/huawei/hisi/ram/nodes/impact/AffectedEntriesAnnotatorTest.java`

**Step 1: Write failing test**

```java
@ExtendWith(MockitoExtension.class)
class AffectedEntriesAnnotatorTest {
    @Mock RamClaudeJsonClient claude;
    @Mock KgMcpClient kg;

    @Test
    void annotate_classifiesEntriesAsDirectAndIndirect() {
        // Setup: 3 upstream entries, AI says 2 are DIRECT
        when(claude.isAvailable()).thenReturn(true);
        when(claude.callJson(anyString(), anyString(), any())).thenReturn(Map.of(
            "analysis", List.of(
                Map.of("nodeId", "e1", "relevance", "DIRECT", "reason", "需求直接相关"),
                Map.of("nodeId", "e2", "relevance", "INDIRECT", "reason", "调用链间接影响")
            )));
        when(kg.loadMethodBodies(any(), anyString())).thenReturn(List.of());

        List<Entry> upstream = List.of(
            new Entry("e1", "Ctrl", "handle", "HTTP"),
            new Entry("e2", "Schedule", "run", "SCHEDULED"),
            new Entry("e3", "Listener", "onMsg", "MQ_LISTENER"));

        AffectedEntriesAnnotator annotator = new AffectedEntriesAnnotator(claude, kg);
        var result = annotator.annotate("修改同步逻辑", upstream, "e0", "/p");

        assertThat(result.direct()).hasSize(1);
        assertThat(result.indirect()).hasSize(1);
        assertThat(result.direct().get(0).reason()).isEqualTo("需求直接相关");
    }
}
```

**Step 2: Run test — verify fail**

**Step 3: Implement AffectedEntriesAnnotator**

Similar pattern to ScopeNarrowingService but for Entry objects. Classifies upstream entry points as DIRECT (need directly related to this requirement) or INDIRECT (affected via call chain). Returns `AnnotatedEntries` record with `direct` and `indirect` lists, each containing `AnnotatedEntry(nodeId, className, methodName, type, reason)`.

**Step 4: Run test — verify pass**

**Step 5: Commit**

```bash
git commit -m "feat(ram): add AffectedEntriesAnnotator for DIRECT/INDIRECT entry classification"
```

---

### Task 4: Rewrite ImpactNode.execute() — New Output Structure

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impact/ImpactNode.java`
- Modify: `src/test/java/com/huawei/hisi/ram/nodes/impact/ImpactNodeTest.java`

**Step 1: Rewrite ImpactNode.execute()**

New logic:
1. Read `target_methods` from input (new constant `INPUT_TARGET_METHODS = "target_methods"`)
2. If `target_methods` non-empty → MethodTargetResolver.resolve()
3. If empty → InvolvedRingResolver + ScopeNarrowing fallback → extract seeds as targets
4. For each target, call `kg.rootEntryAncestors()` → get upstream entries
5. Annotate entries via AffectedEntriesAnnotator
6. Build new output structure:
```java
output.put("methods_to_modify", methodsToModify);
output.put("affected_entries", Map.of("direct", ..., "indirect", ...));
output.put("risk", ...);
output.put("validation", ...);
output.put("reasoning", reasoningSummary);
output.put("markdown_report", generateMarkdownReport(...));
```

**Step 2: Add `generateMarkdownReport()` method**

Produces formatted Markdown with tables, risk assessment, entry lists.

**Step 3: Update ImpactNodeTest**

New test validates simplified output structure (no `involved`, no `modified.tree`, no `impacted.downstream`).

**Step 4: Run tests — verify pass**

**Step 5: Commit**

```bash
git commit -m "feat(ram): redesign ImpactNode output — only methods_to_modify + affected_entries"
```

---

### Task 5: RamClaudeJsonClient — Collect Reasoning Steps

**Files:**
- Modify: `src/main/java/com/huawei/hisi/ram/nodes/impl/RamClaudeJsonClient.java`

**Step 1: Add reasoning collection**

In the tool_use loop, after each tool call:
```java
reasoningSteps.add(String.format("%s(%s) → %s", toolName, truncatedInput, resultSummary));
```

Add `getReasoningSummary()` method that joins steps with newlines.

**Step 2: Expose reasoning in calling code**

ImpactNode and other nodes that use RamClaudeJsonClient can now collect reasoning and include it in output.

**Step 3: Verify compilation**

**Step 4: Commit**

```bash
git commit -m "feat(ram): collect LLM reasoning steps in RamClaudeJsonClient"
```

---

### Task 6: Frontend ImpactOutputView — Adapt to New Structure

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/ram/ImpactOutputView.vue`

**Step 1: Update computed properties**

- `modifiedMethods` → read from `output.methods_to_modify` (array of `{nodeId, className, methodName, reason}`)
- `entryPointMethods` → read from `output.affected_entries.direct` + `output.affected_entries.indirect`
- Remove `otherUpstreamMethods`, `downstreamMethods`, `crossServiceItems` (no longer in output)
- `reasoningText` → read from `output.reasoning`

**Step 2: Update template**

- Remove "其他上游调用方", "下游被调方", "跨服务影响" sections
- Split "受影响的接口" into "直接相关" and "间接相关" sub-sections
- Add collapsible "分析过程" section showing `reasoning`
- Add Markdown report rendering section using `marked` library

**Step 3: Install marked**

Run: `cd hisi-dev-tool-frontend && npm install marked`

**Step 4: Verify frontend compiles**

Run: `npx vue-tsc --noEmit`

**Step 5: Commit**

```bash
git commit -m "feat(frontend): adapt ImpactOutputView to new simplified output structure"
```

---

### Task 7: Frontend DraftPage — Unify with ConfirmModal

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/DraftPage.vue`

**Step 1: Replace `formatImpactOutput()` with ImpactOutputView component**

Import and use `<ImpactOutputView :output="impactData" />` in the detail panel for impact events, same as ConfirmModal does.

**Step 2: Add Clarify Q&A display**

When user clicks "澄清" DAG card, show the clarify Q&A rounds (from CLARIFY_REQ/CLARIFY_RES events) in addition to the final output.

**Step 3: Verify frontend compiles**

**Step 4: Commit**

```bash
git commit -m "feat(frontend): unify DraftPage rendering with ConfirmModal, show clarify Q&A"
```

---

### Task 8: Frontend DagFlow — Add Reasoning Expand

**Files:**
- Modify: `hisi-dev-tool-frontend/src/components/ram/DagFlow.vue`
- Modify: `hisi-dev-tool-frontend/src/components/ram/dagModel.ts`

**Step 1: Add `reasoning` field to DagNodeSnapshot**

```typescript
interface DagNodeSnapshot {
  // ...existing fields...
  reasoning?: string
}
```

**Step 2: Add expand icon + popover to DagFlow cards**

When a node has `reasoning`, show a small 💬 icon. On click/hover, show a popover with the reasoning text.

**Step 3: Populate reasoning from CHECKPOINT events**

In `useRamSession.ts`, when processing CHECKPOINT events, extract `reasoning` field and add to the node snapshot.

**Step 4: Verify frontend compiles**

**Step 5: Commit**

```bash
git commit -m "feat(frontend): add reasoning expand to DagFlow node cards"
```

---

### Task 9: Integration Test — End-to-End Validation

**Files:**
- Modify: `src/test/java/com/huawei/hisi/ram/nodes/impact/ImpactNodeTest.java`

**Step 1: Write integration test**

Test with target_methods input, verify:
- `methods_to_modify` contains target methods
- `affected_entries.direct` and `.indirect` are populated
- `markdown_report` is non-empty
- `reasoning` is non-empty
- No `involved`, `modified.tree`, `impacted.downstream` in output

**Step 2: Run all tests**

Run: `mvn test -Dtest='ImpactNodeTest,MethodTargetResolverTest,AffectedEntriesAnnotatorTest,DeterministicValidatorTest'`

**Step 3: Commit**

```bash
git commit -m "test(ram): integration test for redesigned ImpactNode output"
```
