#!/usr/bin/env bash
# AI Tool Benchmark - macOS/Linux installer
set -euo pipefail

SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST="$HOME/.claude/skills/ai-tool-benchmark"
DATA="$HOME/.claude/ai-bench"

echo "=========================================="
echo " AI Tool Benchmark - Installer (Unix)"
echo "=========================================="
echo

if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
    echo "[ERROR] python not found. Install Python 3.8+ first." >&2
    exit 1
fi
PY=$(command -v python3 || command -v python)
echo "Python : $($PY --version 2>&1)"
echo "Source : $SRC"
echo "Target : $DEST"
echo "Data   : $DATA"
echo

mkdir -p "$DEST" "$DATA/sessions"
cp "$SRC/bench.py"     "$DEST/bench.py"
cp "$SRC/report.py"    "$DEST/report.py"
cp "$SRC/SKILL.md"     "$DEST/SKILL.md"
cp "$SRC/uninstall.py" "$DEST/uninstall.py"
echo "[OK] Files copied."
echo

echo "Smoke test:"
"$PY" "$DEST/bench.py" list

echo
echo "=========================================="
echo " Install complete."
echo
echo " Next steps:"
echo "   1) Read QUICKSTART.md"
echo "   2) $PY $DEST/bench.py start --tool <name> --task <task>"
echo "   3) (Optional) For Claude Code, merge hooks-snippet.json into:"
echo "      $HOME/.claude/settings.json"
echo "=========================================="
