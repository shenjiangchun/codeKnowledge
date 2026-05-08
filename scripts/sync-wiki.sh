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

# ---- clone / refresh wiki repo --------------------------------------------
clone_wiki() {
  if [[ -d "${STAGING_DIR}/.git" ]]; then
    echo "[sync-wiki] reusing existing staging clone, fetching latest"
    git -C "${STAGING_DIR}" fetch --all --prune --quiet || true
    # Try to reset to remote default branch (master, then main); tolerate empty repo.
    local default_branch
    default_branch="$(git -C "${STAGING_DIR}" remote show origin 2>/dev/null \
      | awk '/HEAD branch/ {print $NF}' || true)"
    if [[ -n "${default_branch}" && "${default_branch}" != "(unknown)" ]]; then
      git -C "${STAGING_DIR}" checkout -B "${default_branch}" \
        "origin/${default_branch}" --quiet 2>/dev/null \
        || git -C "${STAGING_DIR}" checkout "${default_branch}" --quiet 2>/dev/null \
        || echo "[sync-wiki] warning: could not switch to ${default_branch} (empty wiki?)"
    else
      echo "[sync-wiki] warning: remote has no HEAD branch yet (empty wiki?)"
    fi
  else
    echo "[sync-wiki] cloning ${WIKI_REMOTE} -> ${STAGING_DIR}"
    if ! git clone --quiet "${WIKI_REMOTE}" "${STAGING_DIR}"; then
      echo "[sync-wiki] warning: clone returned non-zero (likely empty wiki repo); initializing locally"
      mkdir -p "${STAGING_DIR}"
      git -C "${STAGING_DIR}" init --quiet -b master
      git -C "${STAGING_DIR}" remote add origin "${WIKI_REMOTE}"
    fi
  fi
}

# ---- flatten codewiki -> staging ------------------------------------------
# Wipes staging (preserving .git) then walks each codewiki source and copies
# every *.md to a flat <Prefix>-<...>.md filename.
flatten_codewiki() {
  echo "[sync-wiki] wiping staging (preserving .git)"
  if [[ -d "${STAGING_DIR}" ]]; then
    find "${STAGING_DIR}" -mindepth 1 -maxdepth 1 ! -name '.git' -exec rm -rf {} +
  fi

  local prefix src rel base flat target
  for prefix in "${!SUBPROJECTS[@]}"; do
    src="${SUBPROJECTS[$prefix]}"
    if [[ ! -d "${src}" ]]; then
      echo "[sync-wiki] warning: source not found for ${prefix}: ${src}"
      continue
    fi
    while IFS= read -r -d '' file; do
      rel="${file#${src}/}"
      if [[ "${rel}" == "README.md" ]]; then
        flat="README"
      elif [[ "${rel}" == */index.md ]]; then
        # `<dir>/index.md` -> keep full <dir> path with `/` -> `-`
        flat="${rel%/index.md}"
        flat="${flat//\//-}"
      else
        # `<dir>/<file>.md` -> replace `/` with `-`, strip `.md`.
        # Keeps full directory name so link rewriting maps 1:1.
        flat="${rel%.md}"
        flat="${flat//\//-}"
      fi
      target="${prefix}-${flat}.md"
      echo "[copy] ${prefix}/${rel} -> ${target}"
      cp "${file}" "${STAGING_DIR}/${target}"
    done < <(find "${src}" -type f -name '*.md' -print0)
  done
}

# ---- main ------------------------------------------------------------------
main() {
  clone_wiki
  flatten_codewiki
  echo "[sync-wiki] flatten phase complete"
}

main "$@"
