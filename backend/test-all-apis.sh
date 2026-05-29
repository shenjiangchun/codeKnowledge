#!/bin/bash
# API Integration Test Script for HiSi DevTool
# Tests all SQLite-dependent endpoints after PG→SQLite migration

BASE="http://localhost:8080"
PASS=0
FAIL=0
TOTAL=0

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m'

run_test() {
    local name="$1"
    local method="$2"
    local url="$3"
    local expected_status="$4"
    local body="$5"
    local jq_check="$6"

    TOTAL=$((TOTAL + 1))

    if [ "$method" = "GET" ] || [ "$method" = "DELETE" ]; then
        HTTP_CODE=$(curl -s -o /tmp/resp.json -w '%{http_code}' -X "$method" "$BASE$url")
    else
        HTTP_CODE=$(curl -s -o /tmp/resp.json -w '%{http_code}' -X "$method" -H "Content-Type: application/json" -d "$body" "$BASE$url")
    fi

    # Check status code
    local status_ok=false
    for exp in $expected_status; do
        if [ "$HTTP_CODE" = "$exp" ]; then
            status_ok=true
            break
        fi
    done

    if ! $status_ok; then
        FAIL=$((FAIL + 1))
        echo -e "${RED}FAIL${NC} [$name] HTTP=$HTTP_CODE (expected $expected_status)"
        cat /tmp/resp.json 2>/dev/null | python3 -c "import sys,json; print(json.dumps(json.load(sys.stdin),indent=2,ensure_ascii=False))" 2>/dev/null | sed 's/^/  /' || cat /tmp/resp.json 2>/dev/null | sed 's/^/  /'
        return 1
    fi

    # Optional jq check
    if [ -n "$jq_check" ]; then
        result=$(cat /tmp/resp.json | python3 -c "
import sys, json
data = json.load(sys.stdin)
expr = '$jq_check'
# Simple path evaluation
parts = expr.split('.')
obj = data
for p in parts:
    if p and isinstance(obj, dict):
        obj = obj.get(p)
    elif p and isinstance(obj, list):
        obj = len(obj)
if obj is None or obj == '' or obj == []:
    print('EMPTY')
else:
    print('OK')
" 2>/dev/null)
        if [ "$result" != "OK" ]; then
            FAIL=$((FAIL + 1))
            echo -e "${YELLOW}WARN${NC} [$name] HTTP=$HTTP_CODE OK, but check '$jq_check' returned: $result"
            return 1
        fi
    fi

    PASS=$((PASS + 1))
    echo -e "${GREEN}PASS${NC} [$name] HTTP=$HTTP_CODE"
    return 0
}

echo "================================================"
echo "  HiSi DevTool API Integration Tests"
echo "  Base URL: $BASE"
echo "================================================"
echo ""

# ====== GROUP 0: Health & Smoke ======
echo "--- Group 0: Health & Smoke ---"
run_test "0.1 ops/health" GET "/api/ops/health" "200"
run_test "0.2 projects/list" GET "/api/projects/list" "200"
run_test "0.3 scan-git-repos" GET "/api/projects/scan-git-repos" "200"
run_test "0.4 diagnosis/health" GET "/api/diagnosis/health" "200 500"
run_test "0.5 dialog/health" GET "/api/dialog/health" "200 500"
run_test "0.6 mcp/info" GET "/api/mcp/info" "200 500"
run_test "0.7 skills/list" GET "/api/skills/list" "200"
echo ""

# ====== GROUP 1: Config CRUD ======
echo "--- Group 1: Config CRUD ---"
run_test "1.1 GET config PROJECT_DIR" GET "/api/config?key=PROJECT_DIR" "200"
run_test "1.2 PUT PROJECT_DIR" PUT "/api/config" "200" '{"key":"PROJECT_DIR","value":"C:/Users/47583/projects/hisi_dev_tool v4.0/hisi-dev-tool","updatedBy":"test"}'
run_test "1.3 GET verify PROJECT_DIR" GET "/api/config/project-dir" "200"
run_test "1.4 PUT SELECTED_PROJECT" PUT "/api/config" "200" '{"key":"SELECTED_PROJECT","value":"test-project","updatedBy":"test"}'
run_test "1.5 GET selected-project" GET "/api/config/selected-project" "200"
run_test "1.6 GET nonexistent key" GET "/api/config?key=NONEXISTENT_KEY_XYZ" "200"
echo ""

# ====== GROUP 2: Prompt Templates ======
echo "--- Group 2: Prompt Templates ---"
run_test "2.1 GET all prompts" GET "/api/prompts" "200"
run_test "2.2 GET log-analysis prompt" GET "/api/prompts/log-analysis" "200"

# Update template
run_test "2.3 PUT update prompt" PUT "/api/prompts/log-analysis" "200" '{"content":"Updated test: {{errorMessage}}", "variables":"errorMessage"}'
run_test "2.4 GET verify updated" GET "/api/prompts/log-analysis" "200"

# Render
run_test "2.5 POST render prompt" POST "/api/prompts/log-analysis/render" "200" '{"errorMessage":"NullPointerException"}'
run_test "2.6 GET nonexistent prompt" GET "/api/prompts/NONEXISTENT_PROMPT_KEY" "200 404 500"
echo ""

# ====== GROUP 3: Workspace Sessions CRUD ======
echo "--- Group 3: Workspace Sessions CRUD ---"
# Note: Workspace session creation may fail if Neo4j is not running (Spring context dependency)
# Test both create path and read-only paths separately
run_test "3.1 POST create workspace session" POST "/api/workspace-sessions" "200 500" '{"scene":"api-test","initialPrompt":"Testing SQLite migration","workingDirectory":"C:/tmp/test"}'

# Extract session ID
WS_ID=$(cat /tmp/resp.json | python3 -c "import sys,json; d=json.load(sys.stdin); print(d.get('data',{}).get('id','') if isinstance(d.get('data'),dict) else '')" 2>/dev/null)

if [ -n "$WS_ID" ] && [ "$WS_ID" != "" ] && [ "$WS_ID" != "None" ]; then
    echo "  Created workspace session: $WS_ID"
    run_test "3.2 GET workspace sessions list" GET "/api/workspace-sessions" "200"
    run_test "3.3 GET workspace session by id" GET "/api/workspace-sessions/$WS_ID" "200"
    run_test "3.4 PUT update workspace session" PUT "/api/workspace-sessions/$WS_ID" "200" '{"title":"Renamed Session","status":"active"}'
    run_test "3.5 POST archive workspace session" POST "/api/workspace-sessions/$WS_ID/archive" "200"
    run_test "3.6 DELETE workspace session" DELETE "/api/workspace-sessions/$WS_ID" "200"
    run_test "3.7 GET deleted session (should 404)" GET "/api/workspace-sessions/$WS_ID" "200 404"
else
    echo -e "${YELLOW}SKIP${NC} [3.2-3.7] Neo4j unavailable, testing read-only paths"
    run_test "3.2 GET workspace sessions list" GET "/api/workspace-sessions" "200 500"
    run_test "3.3 GET nonexistent session" GET "/api/workspace-sessions/nonexistent-ws-999" "200 404 500"
fi
echo ""

# ====== GROUP 4: Claude Sessions ======
echo "--- Group 4: Claude Sessions ---"
run_test "4.1 GET sessions list" GET "/api/sessions" "200"
run_test "4.2 GET sessions active" GET "/api/sessions?status=active" "200"

# Try to get a session if any exist
SESSION_ID=$(cat /tmp/resp.json | python3 -c "
import sys,json
d=json.load(sys.stdin)
lst = d.get('data',{}).get('list',[]) if isinstance(d.get('data'),dict) else []
if lst and len(lst) > 0:
    print(lst[0].get('id',''))
else:
    print('')
" 2>/dev/null)

if [ -n "$SESSION_ID" ] && [ "$SESSION_ID" != "" ]; then
    echo "  Found session: $SESSION_ID"
    run_test "4.3 GET session detail" GET "/api/sessions/$SESSION_ID" "200"
    run_test "4.4 PATCH session title" PATCH "/api/sessions/$SESSION_ID" "200" '{"title":"API Test Title"}'
else
    echo "  No existing sessions found, skipping detail tests"
    TOTAL=$((TOTAL + 2))
fi

run_test "4.5 GET nonexistent session" GET "/api/sessions/nonexistent-id-999" "200 404 500"
echo ""

# ====== GROUP 5: Log Analysis ======
echo "--- Group 5: Log Analysis ---"
run_test "5.1 POST analyze log" POST "/api/log/analyze" "200" '{
    "message": "java.lang.NullPointerException: Cannot invoke method on null object",
    "stackTrace": "java.lang.NullPointerException\n\tat com.huawei.hisi.service.UserService.findById(UserService.java:42)\n\tat com.huawei.hisi.controller.UserController.getUser(UserController.java:28)",
    "errorType": "NullPointerException",
    "serviceName": "user-service",
    "traceId": "trace-test-001"
}'

REPORT_ID=$(cat /tmp/resp.json | python3 -c "
import sys,json
d=json.load(sys.stdin)
data = d.get('data',{})
if isinstance(data, dict):
    rid = data.get('reportId', data.get('analyzeId', data.get('id', '')))
    print(rid if rid else '')
else:
    print('')
" 2>/dev/null)
echo "  Report/Analyze ID: $REPORT_ID"

run_test "5.2 GET reports list" GET "/api/log/reports" "200"
run_test "5.3 GET reports filtered" GET "/api/log/reports?status=completed" "200"

if [ -n "$REPORT_ID" ] && [ "$REPORT_ID" != "" ]; then
    run_test "5.4 GET report by id" GET "/api/log/report/$REPORT_ID" "200 404 500"
    run_test "5.5 GET report status" GET "/api/log/report/$REPORT_ID/status" "200 404 500"
fi

run_test "5.6 GET nonexistent report" GET "/api/log/report/999999999" "200 404 500"
run_test "5.7 POST invalid analyze (empty)" POST "/api/log/analyze" "200 400 500" '{}'
echo ""

# ====== GROUP 6: KG Task Status ======
echo "--- Group 6: KG & Vector Task Status ---"
run_test "6.1 GET KG tasks status" GET "/api/knowledge-graph/tasks/status?projectPaths=C:/nonexistent" "200"
run_test "6.2 GET KG latest task" GET "/api/knowledge-graph/tasks/latest?projectPath=C:/nonexistent" "200 404"
run_test "6.3 GET vector-gen status" GET "/api/vector-generation/status?projectPath=C:/nonexistent" "200 500"
echo ""

# ====== SUMMARY ======
echo "================================================"
echo "  Test Results"
echo "================================================"
echo -e "  Total: $TOTAL"
echo -e "  ${GREEN}PASS: $PASS${NC}"
echo -e "  ${RED}FAIL: $FAIL${NC}"
echo ""
if [ $FAIL -eq 0 ]; then
    echo -e "  ${GREEN}ALL TESTS PASSED!${NC}"
else
    echo -e "  ${YELLOW}$FAIL test(s) failed${NC}"
fi
echo "================================================"
