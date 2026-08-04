#!/usr/bin/env bash
# ============================================================================
# APM Failure Locator — E2E human smoke test
# ============================================================================
#
# Prereqs:
#   1. Backend running: cd hisi-dev-tool && mvn spring-boot:run
#      (port 8080 by default; override with HOST=http://localhost:8081)
#   2. application-local.yml has hisi.apm.diagnose.llm.api-key set
#      (real dmxapi token) so ApmClaudeLlmClient activates.
#   3. jq installed (any modern git-bash on Windows ships it).
#
# What it does:
#   1. POST NPE fixture to /v1/traces  (OTLP ingest)
#   2. POST /api/apm/diagnose with traceId  -> 202 + reportId
#   3. GET /api/apm/diagnose/{id}/status every 1.5s until terminal
#   4. GET /api/apm/diagnose/{id}        -> pretty-print full report
#
# Usage:
#   bash src/test/resources/apm/fixtures/smoke.sh                  # NPE by default
#   FIXTURE=sql-fail bash src/test/resources/apm/fixtures/smoke.sh
#   FIXTURE=http-5xx bash src/test/resources/apm/fixtures/smoke.sh
#   HOST=http://localhost:8081 bash src/test/resources/apm/fixtures/smoke.sh
# ============================================================================

set -u
HOST="${HOST:-http://localhost:8080}"
FIXTURE="${FIXTURE:-npe}"
PROJECT_PATH="${PROJECT_PATH:-C:/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool}"
POLL_INTERVAL="${POLL_INTERVAL:-1.5}"
MAX_POLLS="${MAX_POLLS:-60}"   # 60 * 1.5s = 90s hard cap

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURE_FILE="${SCRIPT_DIR}/${FIXTURE}.json"

if [[ ! -f "$FIXTURE_FILE" ]]; then
  echo "FATAL: fixture not found: $FIXTURE_FILE" >&2
  echo "Available: npe, sql-fail, http-5xx" >&2
  exit 1
fi

# Extract traceId from the fixture (first span's traceId)
TRACE_ID=$(jq -r '.resourceSpans[0].scopeSpans[0].spans[0].traceId' "$FIXTURE_FILE")
if [[ -z "$TRACE_ID" || "$TRACE_ID" == "null" ]]; then
  echo "FATAL: could not extract traceId from $FIXTURE_FILE" >&2
  exit 1
fi

echo "======================================================================"
echo " APM Failure Locator — Smoke Test"
echo "======================================================================"
echo " Host         : $HOST"
echo " Fixture      : $FIXTURE  ($FIXTURE_FILE)"
echo " traceId      : $TRACE_ID"
echo " projectPath  : $PROJECT_PATH"
echo "======================================================================"
echo

# ---------------------------------------------------------------- STEP 1
echo "[1/4] Posting OTLP fixture -> $HOST/v1/traces"
INGEST_CODE=$(curl -sS -o /tmp/ingest.out -w "%{http_code}" \
  -X POST "$HOST/v1/traces" \
  -H "Content-Type: application/json" \
  --data-binary @"$FIXTURE_FILE")
echo "      HTTP $INGEST_CODE"
if [[ "$INGEST_CODE" != "200" && "$INGEST_CODE" != "202" && "$INGEST_CODE" != "204" ]]; then
  echo "FATAL: ingest failed:" >&2
  cat /tmp/ingest.out >&2
  exit 1
fi
echo

# Give SpanIngestionService a moment to flush to ExceptionSpanIndex
sleep 1

# ---------------------------------------------------------------- STEP 2
echo "[2/4] Starting diagnose -> $HOST/api/apm/diagnose"
START_PAYLOAD=$(jq -n --arg t "$TRACE_ID" --arg p "$PROJECT_PATH" \
  '{traceId:$t, projectPath:$p, forceRefresh:true}')
echo "      payload: $START_PAYLOAD"
START_RESP=$(curl -sS -X POST "$HOST/api/apm/diagnose" \
  -H "Content-Type: application/json" \
  --data "$START_PAYLOAD")
echo "      response: $START_RESP"
REPORT_ID=$(echo "$START_RESP" | jq -r '.reportId')
if [[ -z "$REPORT_ID" || "$REPORT_ID" == "null" ]]; then
  echo "FATAL: no reportId returned" >&2
  exit 1
fi
echo "      reportId: $REPORT_ID"
echo

# ---------------------------------------------------------------- STEP 3
echo "[3/4] Polling status every ${POLL_INTERVAL}s (max ${MAX_POLLS} polls)"
TERMINAL_STATUSES="DONE FAILED CANCELLED TIMEOUT LOW_CONFIDENCE"
for i in $(seq 1 "$MAX_POLLS"); do
  STATUS_RESP=$(curl -sS "$HOST/api/apm/diagnose/$REPORT_ID/status")
  STATUS=$(echo "$STATUS_RESP" | jq -r '.status')
  ELAPSED=$(echo "$STATUS_RESP" | jq -r '.elapsedMs // "-"')
  CONFIDENCE=$(echo "$STATUS_RESP" | jq -r '.confidence // "-"')
  ERR_CODE=$(echo "$STATUS_RESP" | jq -r '.errorCode // "-"')
  printf "      poll %02d | status=%-15s elapsed=%-6s conf=%-5s err=%s\n" \
    "$i" "$STATUS" "$ELAPSED" "$CONFIDENCE" "$ERR_CODE"
  for T in $TERMINAL_STATUSES; do
    if [[ "$STATUS" == "$T" ]]; then
      echo "      reached terminal: $STATUS"
      break 2
    fi
  done
  sleep "$POLL_INTERVAL"
done
echo

# ---------------------------------------------------------------- STEP 4
echo "[4/4] Fetching full report -> $HOST/api/apm/diagnose/$REPORT_ID"
FULL=$(curl -sS "$HOST/api/apm/diagnose/$REPORT_ID")
echo "----- DiagnoseReport (JSON) -----"
echo "$FULL" | jq .
echo
echo "----- rootCauseMarkdown -----"
echo "$FULL" | jq -r '.rootCauseMarkdown // "(empty)"'
echo
echo "----- evidence anchors -----"
echo "$FULL" | jq -r '.evidence // [] | .[] | "  - \(.className).\(.methodName) :: \(.snippet // "")"'
echo
echo "======================================================================"
echo " DONE"
echo "======================================================================"
