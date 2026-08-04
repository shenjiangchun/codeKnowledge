#!/usr/bin/env bash
# AI Tool Benchmark - Unregister hooks from CodeAgent CLI (nga)
set -uo pipefail

if ! command -v nga >/dev/null 2>&1; then
    echo "[ERROR] 'nga' not found in PATH." >&2
    exit 1
fi

echo "Removing nga hooks ..."
echo "(Errors below are harmless if a hook was never registered.)"
echo

nga hooks remove chat.message        2>/dev/null || true
nga hooks remove tool.execute.before 2>/dev/null || true
nga hooks remove tool.execute.after  2>/dev/null || true
nga hooks remove session.stop        2>/dev/null || true

echo
echo "Done. Verify with:  nga hooks list"
