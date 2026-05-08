#!/usr/bin/env bash
# sync-wiki.sh — flatten three CodeWiki source trees into the GitHub Wiki repo.
# Usage: ./scripts/sync-wiki.sh [--dry-run|--push]
set -euo pipefail

# ---- arg parsing -----------------------------------------------------------
DRY_RUN=true
PUSH=false
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=true;  PUSH=false ;;
    --push)    DRY_RUN=false; PUSH=true  ;;
    -h|--help)
      cat <<EOF
Usage: $0 [--dry-run|--push]
  --dry-run  (default) clone, flatten, rewrite, verify, commit locally; no push
  --push     same as dry-run plus push branch and tag to wiki remote
EOF
      exit 0
      ;;
    *) echo "[sync-wiki] unknown arg: $arg" >&2; exit 2 ;;
  esac
done

# ---- paths -----------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
STAGING_DIR="${ROOT_DIR}/.wiki-staging"
WIKI_REMOTE="https://github.com/shenjiangchun/codeKnowlage.wiki.git"

# ---- subprojects map (Prefix -> codewiki source dir) -----------------------
declare -A SUBPROJECTS=(
  [Backend]="${ROOT_DIR}/hisi-dev-tool/docs/codewiki"
  [Frontend]="${ROOT_DIR}/hisi-dev-tool-frontend/docs/codewiki"
  [MCP]="${ROOT_DIR}/hisi-mcp-server/docs/codewiki"
)

# ---- banner ----------------------------------------------------------------
echo "[sync-wiki] dry_run=${DRY_RUN}  push=${PUSH}"
echo "[sync-wiki] root=${ROOT_DIR}"
echo "[sync-wiki] staging=${STAGING_DIR}"

# ---- main ------------------------------------------------------------------
main() {
  echo "[sync-wiki] (skeleton) main pipeline not yet implemented"
}

main "$@"
