# APM OTLP Fixtures (P0)

Three canned OTLP/JSON span fixtures used by the Failure Locator E2E tests
to drive the `OtlpReceiverController` → `SpanIngestionService` →
`ExceptionSpanIndex` → `FailureLocatorService` pipeline without needing a
live target JVM.

## Receiver endpoint

Discovered in `com.huawei.hisi.apm.controller.OtlpReceiverController`:

```
POST /v1/traces
Content-Type: application/json   (the JSON overload, used by the replayer)
```

The default base URL the replayer targets is `http://localhost:8080/v1/traces`.

> The production agent path uses `application/x-protobuf`; the controller also
> exposes an `application/json` overload that accepts the same
> `OtlpTraceData.ExportTraceServiceRequest` shape — that is what these fixtures
> are designed for.

## Fixtures

| File | Scenario | traceId | Expected exception | Failure-Locator expectation |
|------|----------|--------------------------------------|-----------------------------------|-----------------------------|
| `npe.json` | Java NPE thrown inside a service method, propagating up through `@RestController`. Two spans (`SERVER` + `INTERNAL`), both ERROR, both carry `exception.type=java.lang.NullPointerException`. | `00000000000000000000000000000001` | `java.lang.NullPointerException` | DiagnoseReport must include the `OrderService.findById` internal span as the deepest exception frame; root cause = NPE on `Customer.getName()`. |
| `sql-fail.json` | JDBC failure: PostgreSQL reports `relation "orders" does not exist`. Four-level hierarchy (server → service → repository → DB). Exception event is on the DB span only; ERROR propagated up. | `00000000000000000000000000000002` | `org.postgresql.util.PSQLException` | DiagnoseReport must highlight the DB span (`db.system=postgresql`) as the failure origin and surface `db.statement`. |
| `http-5xx.json` | Downstream HTTP call returns 502. Server span wraps a CLIENT span. Per OTel HTTP semconv, the client span carries `http.status_code=502` + ERROR status but no `exception` event; the parent server span carries a `ServletException`. | `00000000000000000000000000000003` | `jakarta.servlet.ServletException` (wrapping a 502) | DiagnoseReport must flag the CLIENT span via `http.status_code>=500` even though it has no exception event, and link to `peer.service=payment-service`. |

## Span structure summary

All fixtures share:
- Resource attributes: `service.name`, `service.version`, `telemetry.sdk.language=java`.
- Status codes use OTLP integer encoding (`0`=UNSET, `1`=OK, `2`=ERROR).
- Span kinds use OTLP integer encoding (`1`=INTERNAL, `2`=SERVER, `3`=CLIENT).
- Timestamps in nanoseconds, anchored around 2025-05-21.

## Replay usage

```java
import com.huawei.hisi.apm.fixture.FixtureReplayer;

String json = FixtureReplayer.loadFixture("npe");
FixtureReplayer.postToReceiver("http://localhost:8080/v1/traces", json);

// Or iterate all three:
for (String name : FixtureReplayer.listFixtures()) {
    FixtureReplayer.postToReceiver("http://localhost:8080/v1/traces",
        FixtureReplayer.loadFixture(name));
}
```

The smoke test `FixtureReplayerTest` validates that every fixture loads,
parses as JSON, declares an ERROR span or an `exception` event, and uses the
expected traceId — no Spring context, no HTTP.
