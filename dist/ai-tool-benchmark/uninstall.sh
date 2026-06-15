#!/usr/bin/env bash
# AI Tool Benchmark - Unix one-click uninstaller
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$HOME/.claude/skills/ai-tool-benchmark/uninstall.py"

if [[ -f "$TARGET" ]]; then
    RUN="$TARGET"
else
    RUN="$SCRIPT_DIR/uninstall.py"
fi

if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    echo "[ERROR] python not found. Install Python 3.8+." >&2
    exit 1
fi
PY=$(command -v python3 || command -v python)

echo
echo "=== Step 1/2: DRY-RUN preview (no changes yet) ==="
"$PY" "$RUN"

echo
read -r -p "Proceed with actual removal? Type YES to confirm: " CONFIRM
if [[ "$CONFIRM" != "YES" ]]; then
    echo "[Abort] No changes made."
    exit 0
fi

echo
read -r -p "Also delete recorded session data in ~/.claude/ai-bench/ ? (y/N): " PURGE
if [[ "$PURGE" =~ ^[Yy]$ ]]; then
    "$PY" "$RUN" --yes --purge-data
else
    "$PY" "$RUN" --yes
fi

echo
echo "Done."
