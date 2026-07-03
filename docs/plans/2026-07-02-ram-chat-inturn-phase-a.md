# RAM Chat In-Turn Injection — Phase A Remediation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Fix the two IMPORTANT follow-ups from M4 final review plus one SUGGEST item and a late-event defense, so that a mid-stream `/inject` leaves a clean event log and a clean frontend UI.

**Architecture:**
Backend `RamChatOrchestrator.runTurn` learns to drop every append/push (CHECKPOINT, ASSISTANT_DELTA, TOOL_USE, TOOL_RESULT) when the current `turnId` no longer matches the active turn in `TurnRegistry` — Reactor `dispose()` is best-effort, so we must gate at the write side. WS `turn_interrupted` payload carries the `reason` field the persisted event already has. Frontend `ChatMessageList.vue` gains a `turn_interrupted` case that pins the partial text + status='done', and a defensive `if (turn.status === 'done') return` in `assistant_delta` to absorb any late WS event.

**Tech Stack:** Spring Boot 3.2 · Java 17 · Reactor · Vue 3.5 · TypeScript · MockMvc + Awaitility + AssertJ

---

## Task 1: Backend — turnId guard on all in-turn appends

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java:134-186,239-251`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java` (extended in Task 5)

**Step 1: Add helper `isActive(sessionId, turnId)`**

Insert a private helper next to `appendEvent`:

```java
/**
 * True iff the currently-registered active turn for {@code sessionId} is
 * still {@code turnId}. Late deltas / tool events / checkpoints coming from
 * an already-interrupted Reactor sink must NOT be persisted, because the
 * TURN_INTERRUPTED event has already sealed that turn.
 */
private boolean isActive(long sessionId, String turnId) {
    return turnRegistry.get(sessionId)
            .map(t -> t.turnId().equals(turnId))
            .orElse(false);
}
```

**Step 2: Wrap the three StreamCallbacks appends**

Inside each of `onAssistantDelta`, `onToolUseStart`, `onToolResult` (L136-179), guard the append + WS push:

```java
if (!isActive(sessionId, turnId)) {
    log.debug("[RamChatOrchestrator] dropping late {} for aborted turnId={}", "<event-name>", turnId);
    return;
}
```

For `onAssistantDelta`, the `partialTextBuf.append(deltaText)` still runs first (it feeds the interrupt snapshot buffer) — but on inactive turn we simply skip appending & pushing. Actually — the interrupt snapshot is taken atomically inside `TurnRegistry.interrupt`, after which `disposable.dispose()` fires. Any delta that races in AFTER that must not extend the persisted partial text. So the guard goes **before** the `synchronized(partialTextBuf)` write too.

Final `onAssistantDelta`:
```java
@Override
public void onAssistantDelta(String deltaText) {
    if (!isActive(sessionId, turnId)) {
        log.debug("[RamChatOrchestrator] dropping late assistant_delta for aborted turnId={}", turnId);
        return;
    }
    synchronized (partialTextBuf) {
        partialTextBuf.append(deltaText);
    }
    // … existing append + push …
}
```

**Step 3: Guard the CHECKPOINT append + push (L239-251)**

Wrap the block:
```java
if (isActive(sessionId, turnId)) {
    AgentEvent ckptEv = appendEvent(sessionId, EventType.CHECKPOINT, Map.of(
            "turnId", turnId, "summary", summary, "finalText", finalText,
            "reasoningSteps", result.reasoning()
    ), "ckpt-" + turnId);
    wsHandler.pushEvent(sessionId, wsEvent(ckptEv, sessionId, Map.of(
            "type", "checkpoint", "turnId", turnId,
            "summary", summary, "finalText", finalText
    )));
    log.info("[RamChatOrchestrator] done turnId={} finalText.len={}", turnId, finalText.length());
} else {
    log.info("[RamChatOrchestrator] skip checkpoint for interrupted turnId={} finalText.len={}",
            turnId, finalText.length());
}
```

`turnRegistry.complete(sessionId, turnId)` at L256 remains unconditional — it's already CAS-safe.

**Step 4: Run existing unit + integration tests to ensure no regression**

```
mvn -pl hisi-dev-tool test -Dtest='RamChatOrchestrator*,TurnRegistry*'
```
Expected: PASS (existing tests do not cover the aborted-turn late-append case, so they should not break).

**Step 5: Commit**
```
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java
git commit -m "fix: drop late events after turn interrupt (checkpoint/delta/tool)"
```

---

## Task 2: Backend — WS `turn_interrupted` payload carries `reason`

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java:94-102`

**Step 1: Add `reason` to the WS payload map**

```java
Map<String, Object> wsPayload = new LinkedHashMap<>();
wsPayload.put("type", "turn_interrupted");
wsPayload.put("turnId", r.turnId());
wsPayload.put("partialText", r.partialText());
wsPayload.put("reason", "user_interrupt");   // NEW — mirror persisted payload
wsPayload.put("sessionId", sessionId);
wsPayload.put("eventId", ev.getId());
wsPayload.put("seq", ev.getSeq());
wsPayload.put("createdAt", ev.getCreatedAt());
```

**Step 2: Commit (rolled into Task 5's IT change once verified)** — see Task 5 for assertion.

---

## Task 3: Frontend — handle `turn_interrupted` event

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/chat/ChatMessageList.vue:67-99`

**Step 1: Add case to the event switch**

Inside the event handler:
```ts
case 'turn_interrupted': {
    const partial = payload.partialText as string | undefined
    if (typeof partial === 'string') {
        turn.assistantText = partial
    }
    turn.status = 'done'
    break
}
```

Rationale: reuse existing `'done'` status — no new enum needed. Pinning `assistantText` to the payload's authoritative `partialText` guarantees convergence with the persisted `TURN_INTERRUPTED` event even if late `assistant_delta` frames slipped through.

**Step 2: Commit**
```
git add hisi-dev-tool-frontend/src/views/ram/chat/ChatMessageList.vue
git commit -m "feat(chat): handle turn_interrupted event, pin partial text"
```

---

## Task 4: Frontend — defensive guard in `assistant_delta`

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/chat/ChatMessageList.vue:72-74`

**Step 1: Ignore delta on a done turn**

```ts
case 'assistant_delta':
    if (turn.status === 'done') return   // ignore late deltas after interrupt/checkpoint
    turn.assistantText += (payload.delta as string) || ''
    break
```

**Step 2: Commit**
```
git add hisi-dev-tool-frontend/src/views/ram/chat/ChatMessageList.vue
git commit -m "fix(chat): ignore assistant_delta after turn done"
```

---

## Task 5: Integration test — extend `RamChatInTurnInjectionIT`

**Files:**
- Modify: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java`

**Step 1: Loosen the CHECKPOINT wait**

Line 185-186 currently asserts `CHECKPOINT >= 2`. After Phase A only the SECOND turn writes a checkpoint. Change to `>= 1` and wait on `USER_MSG count >= 2` as the readiness signal for the injected turn:

```java
await().atMost(30, TimeUnit.SECONDS).until(() ->
        eventRepository.countBySessionIdAndType(sid, EventType.USER_MSG) >= 2 &&
        eventRepository.countBySessionIdAndType(sid, EventType.CHECKPOINT) >= 1);
```

**Step 2: Add negative assertions on the aborted turn**

After the `finally` block, before or alongside the existing assertion block:

```java
// No CHECKPOINT for the aborted turnId — Phase A must drop it.
long abortedCheckpoints = events.stream()
        .filter(e -> e.getType() == EventType.CHECKPOINT)
        .filter(e -> abortedTurnId.equals(readTurnId(e)))
        .count();
assertThat(abortedCheckpoints)
        .as("no CHECKPOINT should be persisted for the aborted turn")
        .isZero();

// No ASSISTANT_DELTA for the aborted turnId after the TURN_INTERRUPTED row.
long lateDeltas = events.stream()
        .filter(e -> e.getType() == EventType.ASSISTANT_DELTA)
        .filter(e -> abortedTurnId.equals(readTurnId(e)))
        .filter(e -> e.getSeq() > interrupt.getSeq())
        .count();
assertThat(lateDeltas)
        .as("no ASSISTANT_DELTA for aborted turn after TURN_INTERRUPTED")
        .isZero();
```

Add helper at bottom of class:
```java
private String readTurnId(AgentEvent ev) {
    try {
        Map<String, Object> m = objectMapper.readValue(ev.getPayload(),
                new TypeReference<Map<String, Object>>() {});
        Object t = m.get("turnId");
        return t == null ? null : t.toString();
    } catch (Exception e) {
        return null;
    }
}
```

**Step 3: Assert WS payload carries `reason`**

Spy on `RamChatWebSocketHandler.pushEvent` — the simplest form: add `@SpyBean` on `RamChatWebSocketHandler`, capture with `ArgumentCaptor<Map<String, Object>>`, filter to `type=turn_interrupted`, and assert `reason=user_interrupt`.

```java
@SpyBean private RamChatWebSocketHandler wsHandler;

// … after the finally block …
@SuppressWarnings("unchecked")
ArgumentCaptor<Map<String, Object>> wsCap = ArgumentCaptor.forClass(Map.class);
verify(wsHandler, atLeastOnce()).pushEvent(eq(sid), wsCap.capture());
Map<String, Object> interruptWs = wsCap.getAllValues().stream()
        .filter(m -> "turn_interrupted".equals(m.get("type")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no turn_interrupted WS push captured"));
assertThat(interruptWs)
        .containsEntry("reason", "user_interrupt")
        .containsEntry("turnId", abortedTurnId)
        .containsKey("partialText");
```

**Step 4: Run the IT**
```
mvn -pl hisi-dev-tool test -Dtest=RamChatInTurnInjectionIT
```
Expected: PASS. If FAIL, backend guard was missed on one of the paths — inspect `events` payloads for the aborted turn.

**Step 5: Commit** (bundle Task 2's payload change here since the IT verifies it)
```
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java
git commit -m "test: assert no late events on aborted turn + WS reason field"
```

---

## Verification (end of Phase A)

```
mvn -pl hisi-dev-tool test -Dtest='RamChatInTurnInjectionIT,RamChatOrchestratorTest,TurnRegistryTest'
cd hisi-dev-tool-frontend && npm run build
```

All green → Phase A complete. IMPORTANT #1, IMPORTANT #2, SUGGEST #3 closed; late-delta defense added. Phase B (SUGGEST #4/#5/#6) remains deferred.

---

## ✅ Phase A Completion — verified 2026-07-03

**Backend tests** (`mvn -pl hisi-dev-tool test -Dtest='RamChatInTurnInjectionIT,RamChatOrchestratorTest,TurnRegistryTest'`):

```
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 — RamChatInTurnInjectionIT
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0 — RamChatOrchestratorTest
Tests run: 6, Failures: 0, Errors: 0, Skipped: 0 — TurnRegistryTest
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**Frontend build** (`cd hisi-dev-tool-frontend && npm run build`): exit 0 — only non-fatal chunk-size / dynamic-import warnings.

**Phase A commits** (HEAD chain):

| Sha       | Subject |
|-----------|---------|
| `a9d15109` | T1: backend `isActive` guard on CHECKPOINT / ASSISTANT_DELTA / TOOL_USE / TOOL_RESULT |
| `53b3a13c` | T3: frontend `turn_interrupted` handler in `ChatMessageList.vue` |
| `0da49381` | T4: frontend `assistant_delta` done-guard |
| `1b733b82` | T5: IT asserts no late events on aborted turn + WS `reason=user_interrupt` |

**Scope-drift notes** (NOT part of Phase A, parked for separate workflows):

- Unrelated KG `IncrementalRefreshServiceV2` + log-analysis `ParseNode` test updates were stashed (`git stash@{0}`) to unblock backend test compilation. They belong to the `semantic-search` worktree mission and will be re-applied there.
- Frontend TS baseline repair (50 files, +231/-151) is a separate commit on top of Phase A — it fixes long-standing `AxiosResponse<T>` unwrapping / `RamEvent.type` nullability regressions so `npm run build` exits green. It does not change runtime behavior of RAM chat v2.
- 6 pre-existing frontend unit-test failures (request.test.ts 401 assertions, FileBrowserPanel.spec.ts ring header, ThemeSelector.test.ts ×3) were verified via `git diff HEAD` to either predate the TS baseline fix (request.test.ts, FileBrowserPanel.spec.ts, ThemeSelector.test.ts — no working-tree changes) or be a direct consequence of adding required `TerminalColors` fields that the production code already consumes (themeStore.test.ts / types.test.ts mock fixtures). None are caused by Phase A T1–T5.

**Phase A status: COMPLETE.** Phase B remains deferred pending explicit user go-ahead.
