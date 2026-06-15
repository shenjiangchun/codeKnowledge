#!/usr/bin/env bash
# AI Tool Benchmark - Register hooks into CodeAgent CLI (nga)
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if ! command -v nga >/dev/null 2>&1; then
    echo "[ERROR] 'nga' not found in PATH. Install CodeAgent CLI first." >&2
    exit 1
fi

echo "Registering nga hooks (path: $DIR) ..."
echo

nga hooks add chat.message        "$DIR/chat-message.js"        "AI Tool Benchmark - capture chat messages"
nga hooks add tool.execute.before "$DIR/tool-execute-before.js" "AI Tool Benchmark - capture tool calls (before)"
nga hooks add tool.execute.after  "$DIR/tool-execute-after.js"  "AI Tool Benchmark - capture tool calls (after)"
# Optional: only register if your nga build emits this event
nga hooks add session.stop        "$DIR/session-stop.js"        "AI Tool Benchmark - capture session stop" 2>/dev/null || true

echo
echo "Done. Verify with:  nga hooks list"
