# RAM Chat — Phase B SUGGEST Items Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans or superpowers:subagent-driven-development to implement this plan task-by-task.

**Goal:** Close the three SUGGEST follow-ups from the M4 final review that were deferred when Phase A shipped. Each is small, independent, and low-risk.

**Architecture:**
Three independent hardening items, each isolated to its own file. No cross-task dependencies. Order does not matter. Together they: (a) restore IT DB isolation, (b) externalize a hardcoded default model constant to existing config, (c) make a defensive branch in `TurnRegistry` observable.

**Tech Stack:** Spring Boot 3.2 · Java 17 · SQLite · JUnit 5 · AssertJ · Mockito · SLF4J/Logback

---

## Background

The M4 final review (Jul 2023) produced six findings: two IMPORTANT (closed by Phase A as `a9d15109` / `1b733b82`), one SUGGEST (closed by Phase A as part of T2 / T5), and three SUGGEST items (#4, #5, #6) that Phase A explicitly deferred. The Phase A completion note at `docs/plans/2026-07-02-ram-chat-inturn-phase-a.md:274` records the deferral. The original M4 review artifact was never persisted to the repo; the items below were reconstructed on 2026-07-06 from the session transcript that produced Phase A.

### Reconstructed item list (verbatim from transcript)

| # | Item | Source |
|---|------|--------|
| #4 | IT `:memory:` DB **or** `@BeforeEach` truncate — prevent long-term bloat and cross-test pollution once more ITs land | M4 final review |
| #5 | Extract `RamChatOrchestrator.DEFAULT_MODEL_ID` from `ChatModelProperties` | M4 final review |
| #6 | Add WARN log on `TurnRegistry.register` defensive overwrite branch | M4 final review |

---

## Task 1 (SUGGEST #4): IT DB isolation via `@BeforeEach` truncate

**Why `:memory:` is NOT the path:** `application-test.properties:11-16` already documents the constraint — `DataSourceConfig` returns a raw `SQLiteDataSource` with no pool, so each `JdbcTemplate.execute()` borrow sees a private empty in-memory DB. Schema init on conn A would be invisible to conn B → "no such table" errors. Switching to `:memory:` would require introducing HikariCP, which is out of scope.

**Approach:** Add `@BeforeEach` truncation to `RamChatInTurnInjectionIT` only — the one IT that writes substantial rows and currently depends on per-class uniqueness of generated IDs to avoid collisions. Future ITs can opt in by inheriting a shared base or copying the same helper.

**Files:**
- Modify: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java`

### Step 1: Write the failing test (RED)

Add a test that asserts the DB is empty at the start of each test method, before any setup writes:

```java
@Test
@DisplayName("DB starts empty for each test method (Phase B SUGGEST #4)")
void dbState_isolated_perTest(@TempDir Path tempDir) throws Exception {
    // Before any RamChat session is created in this method, agent_event / ram_chat_messages
    // must have zero rows from prior test methods.
    long eventCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM agent_event", Long.class);
    long messageCount = jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM ram_chat_messages", Long.class);
    assertThat(eventCount).isZero();
    assertThat(messageCount).isZero();
}
```

If a `@BeforeEach` truncate is NOT in place, this test fails when run after the existing `inject_persists_partial_text_and_starts_new_turn` test (which writes multiple events + messages). Run:

```
mvn -pl hisi-dev-tool test -Dtest='RamChatInTurnInjectionIT#dbState_isolated_perTest+inject_persists_partial_text_and_starts_new_turn'
```

Expected: FAIL (eventCount > 0 or messageCount > 0 from prior method).

### Step 2: Add `@BeforeEach` truncate

```java
@Autowired private JdbcTemplate jdbcTemplate;

@BeforeEach
void truncateEventTables() {
    // Phase B SUGGEST #4: per-method isolation. File-based SQLite at
    // ./target/test-devtool.db persists across test methods and across
    // runs — truncate to give each test a clean slate.
    jdbcTemplate.execute("DELETE FROM agent_event");
    jdbcTemplate.execute("DELETE FROM ram_chat_messages");
    // Add more tables here only if the IT under test writes to them.
}
```

If `@Sql` annotations or `@BeforeAll` schema init scripts exist, ensure truncate runs AFTER schema init.

### Step 3: Run all IT methods, expect green

```
mvn -pl hisi-dev-tool test -Dtest=RamChatInTurnInjectionIT
```

Expected: PASS (existing assertions still hold because they query rows they themselves write).

### Step 4: Commit

```
git add hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatInTurnInjectionIT.java
git commit -m "test(ram-chat): per-method DB isolation via @BeforeEach truncate (Phase B #4)"
```

### Risks

- If `agent_event` or `ram_chat_messages` have FK constraints from other tables, raw `DELETE` may fail. Verify schema in `SQLiteSchemaInitializer`. If FK-protected, wrap in `SET FOREIGN_KEYS=OFF` / `ON` or delete children first.
- If a future IT adds fixtures via `@Sql`, the `@BeforeEach` truncate will erase them. Move fixture loads AFTER truncate or use `@AfterEach` instead.

---

## Task 2 (SUGGEST #5): `DEFAULT_MODEL_ID` from `ChatModelProperties`

**Current state:** `RamChatOrchestrator.java:50` hardcodes `private static final String DEFAULT_MODEL_ID = "glm-5.1";`. This duplicates the model ID and bypasses the `ChatModelProperties` two-layer config (`chat-models.yml` + `application-local.yml` override) that Task 3 of the original plan established.

**Approach:** Replace the hardcoded constant with a lookup against `ChatModelProperties`. Add a `defaultModelId()` (or `getDefaultModel()`) accessor on `ChatModelProperties` that picks the first model in `models[]` or — if a `chat.default-model` property is set — uses that explicitly. Fall back to `"glm-5.1"` if config is empty (preserves current behavior).

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/config/ChatModelProperties.java`
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java:50,227,235`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/config/ChatModelPropertiesTest.java` (extend if exists, create if not)
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java` (verify the orchestrator reads from config, not the constant)

### Step 1: Write failing test for `ChatModelProperties.defaultModelId()` (RED)

```java
@Test
@DisplayName("defaultModelId returns the first model when chat.default-model is unset")
void defaultModelId_returnsFirstModel_whenNoExplicitDefault() {
    ChatModelProperties props = new ChatModelProperties();
    props.setModels(List.of(
        modelWithId("glm-5.1"),
        modelWithId("glm-4-flash")
    ));
    assertThat(props.defaultModelId()).isEqualTo("glm-5.1");
}

@Test
@DisplayName("defaultModelId honors chat.default-model override")
void defaultModelId_honorsExplicitDefault() {
    ChatModelProperties props = new ChatModelProperties();
    props.setDefaultModel("glm-4-flash");
    props.setModels(List.of(
        modelWithId("glm-5.1"),
        modelWithId("glm-4-flash")
    ));
    assertThat(props.defaultModelId()).isEqualTo("glm-4-flash");
}

@Test
@DisplayName("defaultModelId falls back to glm-5.1 when config is empty")
void defaultModelId_fallsBackToLegacyConstant() {
    ChatModelProperties props = new ChatModelProperties();
    props.setModels(List.of());
    assertThat(props.defaultModelId()).isEqualTo("glm-5.1");
}
```

Run:

```
mvn -pl hisi-dev-tool test -Dtest='ChatModelPropertiesTest'
```

Expected: FAIL (method `defaultModelId()` does not exist).

### Step 2: Implement `defaultModelId()` on `ChatModelProperties`

Add a `defaultModel` field (bindable via `chat.default-model`) and a `defaultModelId()` resolver method. Implementation sketch:

```java
private String defaultModel;  // getter/setter; bound to chat.default-model

public String defaultModelId() {
    if (defaultModel != null && !defaultModel.isBlank()) {
        return defaultModel;
    }
    if (models != null && !models.isEmpty()) {
        return models.get(0).getId();
    }
    return "glm-5.1";  // legacy fallback — matches pre-Phase-B constant
}
```

### Step 3: Refactor `RamChatOrchestrator` to use the accessor

```java
// DELETE line 50: private static final String DEFAULT_MODEL_ID = "glm-5.1";
// Lines 227, 235 — replace DEFAULT_MODEL_ID with chatProps.defaultModelId()
```

If `chatProps` is not already injected, add it to the constructor.

### Step 4: Run all orchestrator + config tests

```
mvn -pl hisi-dev-tool test -Dtest='RamChatOrchestratorTest,ChatModelPropertiesTest,RamChatInTurnInjectionIT'
```

Expected: PASS.

### Step 5: Commit

```
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/config/ChatModelProperties.java \
        hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/RamChatOrchestrator.java \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/config/ChatModelPropertiesTest.java \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/RamChatOrchestratorTest.java
git commit -m "refactor(ram-chat): source DEFAULT_MODEL_ID from ChatModelProperties (Phase B #5)"
```

### Risks

- If existing `application-local.yml` does NOT have a `chat.default-model` entry, the fallback to `models[0].id` must produce the same value as the old constant. Verify `chat-models.yml` lists `glm-5.1` first.
- If the `defaultModel` property name collides with an existing field, rename to `chat.defaults.model` or similar.

---

## Task 3 (SUGGEST #6): WARN log on `TurnRegistry.register` defensive overwrite

**Current state:** `TurnRegistry.java:53-63` silently disposes a previous turn when a new one is registered for the same session. The contract is "one active turn per session", and the normal flow ensures the previous turn's `complete()` runs before the next `register()`. A non-null `previous` at register time indicates either a bug in the caller or a race that the design didn't intend. Currently silent → invisible.

**Approach:** Add a `log.warn(...)` on the defensive branch. Keep the dispose behavior (don't change semantics — the dispose is the safety net). Just make it observable.

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/TurnRegistry.java:53-63`
- Test: `hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/TurnRegistryTest.java`

### Step 1: Write failing test (RED)

Add a test that registers two turns for the same session without an intervening `complete()`, and asserts a WARN log was emitted:

```java
@Test
@DisplayName("register logs WARN when overwriting an active turn (Phase B SUGGEST #6)")
void register_warnsOnDefensiveOverwrite() {
    ActiveTurn first = activeTurnFixture(1L, "turn-A");
    ActiveTurn second = activeTurnFixture(1L, "turn-B");
    registry.register(1L, first);

    // Use Logback ListAppender to capture logs
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    Logger logger = (Logger) LoggerFactory.getLogger(TurnRegistry.class);
    logger.addAppender(appender);

    try {
        registry.register(1L, second);

        // Assert the WARN was emitted with the expected markers
        List<ILoggingEvent> warns = appender.list.stream()
            .filter(e -> e.getLevel() == Level.WARN)
            .collect(Collectors.toList());
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
            .contains("sessionId=1")
            .contains("turnId=turn-A")
            .contains("turn-B");
        // first.disposable should be disposed
        verify(first.disposable()).dispose();
    } finally {
        logger.detachAppender(appender);
    }
}
```

Run:

```
mvn -pl hisi-dev-tool test -Dtest='TurnRegistryTest#register_warnsOnDefensiveOverwrite'
```

Expected: FAIL (no WARN log emitted; assertion on `warns` has size 0).

### Step 2: Add the WARN log

Modify `TurnRegistry.register`:

```java
public void register(long sessionId, ActiveTurn turn) {
    ActiveTurn previous = activeBySession.put(sessionId, turn);
    if (previous != null) {
        log.warn("[TurnRegistry] defensive overwrite: sessionId={} previousTurnId={} newTurnId={}. " +
                 "The previous turn did not call complete() before a new register() — " +
                 "this indicates a caller bug or unexpected race.",
                 sessionId, previous.turnId(), turn.turnId());
        try {
            previous.disposable().dispose();
        } catch (Exception e) {
            log.warn("[TurnRegistry] failed to dispose previous turn sessionId={} turnId={}: {}",
                     sessionId, previous.turnId(), e.getMessage());
        }
    }
}
```

### Step 3: Run all TurnRegistry tests

```
mvn -pl hisi-dev-tool test -Dtest=TurnRegistryTest
```

Expected: PASS (all 6 existing + 1 new = 7).

### Step 4: Run the IT to ensure no behavior regression

```
mvn -pl hisi-dev-tool test -Dtest=RamChatInTurnInjectionIT
```

Expected: PASS. If the IT now emits a WARN in the middle of a normal inject flow, that's a real bug worth investigating — the inject path should `complete()` the aborted turn before registering the new one.

### Step 5: Commit

```
git add hisi-dev-tool/src/main/java/com/huawei/hisi/ram/chat/TurnRegistry.java \
        hisi-dev-tool/src/test/java/com/huawei/hisi/ram/chat/TurnRegistryTest.java
git commit -m "feat(ram-chat): WARN log on TurnRegistry.register defensive overwrite (Phase B #6)"
```

### Risks

- If the existing inject IT or orchestrator flow relies on the silent defensive overwrite (i.e., intentionally skips `complete()`), the WARN will appear in normal operation. That would indicate the "defensive" branch is actually expected behavior and the log level should be `INFO` or `DEBUG`. Run Task 3 Step 4 before deciding.
- Logback `ListAppender` test pattern assumes SLF4J → Logback binding (current setup). If you migrate to Log4j2 in the future, swap the appender implementation.

---

## Verification (end of Phase B)

```
mvn -pl hisi-dev-tool test -Dtest='RamChatInTurnInjectionIT,RamChatOrchestratorTest,TurnRegistryTest,ChatModelPropertiesTest'
cd hisi-dev-tool-frontend && npm run build
```

All green → Phase B complete. Update `docs/plans/2026-07-02-ram-chat-inturn-phase-a.md:274` to strike the "Phase B remains deferred" sentence and append a "Phase B Completion" section mirroring the Phase A template.

---

## Out of scope (do NOT bundle)

- Migrating DataSourceConfig to HikariCP (would unblock `:memory:` SQLite but is invasive and out of Phase B scope).
- Adding the `chat.default-model` entry to `application.yml` / `application-local.yml` templates — that's a deployment concern, leave to operators.
- Adding WARN logs to other defensive branches elsewhere in the codebase — Phase B is scoped to the M4 review's three items only.

---

## References

- Phase A plan: `docs/plans/2026-07-02-ram-chat-inturn-phase-a.md`
- Original M1-M4 plan: `docs/plans/2026-07-02-ram-chat-in-turn-injection-plan.md`
- Design doc: `docs/plans/2026-07-02-ram-chat-in-turn-injection-design.md`
- Java rules: `.claude/rules/java/coding-style.md`, `.claude/rules/java/testing.md`, `.claude/rules/java/patterns.md`
