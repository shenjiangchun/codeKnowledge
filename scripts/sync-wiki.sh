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

# Filled by flatten_codewiki: maps flat filename (e.g. "Backend-03-模块说明-REST接口层.md")
# to the source directory it came from (e.g. "03-模块说明" or "" for top-level).
# Used by rewrite_links to resolve sibling links like `./Foo.md` or `Foo.md`.
declare -A SOURCE_DIRS=()

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
      # Record source dir (the part of `rel` before the last `/`, or empty
      # for top-level files like README.md). Used by rewrite_links() to
      # resolve sibling-style links (`./Foo.md` or bare `Foo.md`).
      if [[ "${rel}" == */* ]]; then
        SOURCE_DIRS["${target}"]="${rel%/*}"
      else
        SOURCE_DIRS["${target}"]=""
      fi
      echo "[copy] ${prefix}/${rel} -> ${target}"
      cp "${file}" "${STAGING_DIR}/${target}"
    done < <(find "${src}" -type f -name '*.md' -print0)
  done
}

# ---- rewrite relative markdown links --------------------------------------
# Per spec, three forms occur:
#   ../<dir>/index.md   -> <Prefix>-<dir>.md
#   ../<dir>/<file>.md  -> <Prefix>-<dir>-<file>.md
#   ./<file>.md         -> <Prefix>-<sourcedir>-<file>.md   (sibling)
# README files in source also use bare relative paths (no `./` or `../`):
#   <dir>/index.md      -> <Prefix>-<dir>.md
#   <dir>/<file>.md     -> <Prefix>-<dir>-<file>.md
#   <file>.md           -> <Prefix>-<sourcedir>-<file>.md   (sibling, README-less form)
# The bare sibling form skips already-rewritten links via a negative
# lookahead on the prefix. perl -i -pe is used for cross-platform sed
# compatibility.
rewrite_links() {
  local file base prefix sourcedir sib_repl
  while IFS= read -r -d '' file; do
    base="$(basename "${file}")"
    prefix="${base%%-*}"   # Backend / Frontend / MCP
    sourcedir="${SOURCE_DIRS[${base}]:-}"
    if [[ -n "${sourcedir}" ]]; then
      # Sibling rewrite target: <Prefix>-<sourcedir>-$1.md
      sib_repl="${prefix}-${sourcedir}-\$1.md"
    else
      # Top-level (README.md) — sibling links resolve as <Prefix>-$1.md
      sib_repl="${prefix}-\$1.md"
    fi

    # Order matters:
    #  1) ../ forms first (most specific)
    #  2) ./ sibling form (uses sourcedir)
    #  3) bare <dir>/<...> forms (README-style)
    #  4) bare sibling <file>.md last, with negative lookahead to skip
    #     anything already starting with a known prefix.
    perl -i -pe "
      s{\\]\\(\\.\\./([^/)]+)/index\\.md\\)}{](${prefix}-\$1.md)}g;
      s{\\]\\(\\.\\./([^/)]+)/([^/)]+)\\.md\\)}{](${prefix}-\$1-\$2.md)}g;
      s{\\]\\(\\./([^/)]+)\\.md\\)}{](${sib_repl})}g;
      s{\\]\\(([^/):#?.][^/):#?]*)/index\\.md\\)}{](${prefix}-\$1.md)}g;
      s{\\]\\(([^/):#?.][^/):#?]*)/([^/):#?]+)\\.md\\)}{](${prefix}-\$1-\$2.md)}g;
      s{\\]\\((?!Backend-|Frontend-|MCP-|https?://|\\.\\./|\\./)([^/):#?]+)\\.md\\)}{](${sib_repl})}g;
    " "${file}"
  done < <(find "${STAGING_DIR}" -maxdepth 1 -type f -name '*.md' -print0)
}

# ---- verify all internal markdown links resolve ---------------------------
# Skips http(s):// and any link still containing `/` or `..` (treated as
# external / outside the flat wiki — these are reported but not flagged
# as failures unless they reference a non-existent flat file).
verify_links() {
  local file target broken=0 verified=0 source_rel
  while IFS= read -r -d '' file; do
    source_rel="$(basename "${file}")"
    # Extract every ](...md) target on the file.
    while IFS= read -r target; do
      # Strip leading `](` and trailing `)`
      target="${target#](}"
      target="${target%)}"
      # Skip http(s) links
      [[ "${target}" =~ ^https?:// ]] && continue
      # Strip optional anchor
      target="${target%%#*}"
      [[ -z "${target}" ]] && continue
      # Skip links that still contain `/` or `..` -> they point outside
      # the flat wiki. Report them but do not count as broken (they were
      # never destined to live in the wiki).
      if [[ "${target}" == */* || "${target}" == *..* ]]; then
        echo "[external] ${source_rel} -> ${target}"
        continue
      fi
      verified=$((verified + 1))
      if [[ ! -f "${STAGING_DIR}/${target}" ]]; then
        echo "[BROKEN] ${source_rel} -> ${target}"
        broken=$((broken + 1))
      fi
    done < <(grep -oE '\]\([^)]+\.md\)' "${file}" || true)
  done < <(find "${STAGING_DIR}" -maxdepth 1 -type f -name '*.md' -print0)
  echo "[sync-wiki] verified ${verified} internal links, ${broken} broken"
  if [[ "${broken}" -gt 0 ]]; then
    return 1
  fi
}

# ---- generate Home / _Sidebar / _Footer -----------------------------------
generate_navigation() {
  local short_sha today
  short_sha="$(git -C "${ROOT_DIR}" rev-parse --short HEAD)"
  today="$(date +%Y-%m-%d)"

  # ---- Home.md -------------------------------------------------------------
  {
    echo "# CodeKnowlage Wiki"
    echo
    echo "自动同步于 ${today},基于 commit \`${short_sha}\`。"
    echo
    echo "## 子项目入口"
    echo
    echo "| 项目 | 说明 | 入口 |"
    echo "| --- | --- | --- |"
    echo "| Backend | hisi-dev-tool 后端服务 | [Backend-README](Backend-README.md) |"
    echo "| Frontend | hisi-dev-tool-frontend Vue 前端 | [Frontend-README](Frontend-README.md) |"
    echo "| MCP | hisi-mcp-server MCP 工具服务 | [MCP-README](MCP-README.md) |"
    echo
    echo "## 推荐阅读路径"
    echo
    echo "| 角色 | 起点 | 重点章节 | 备注 |"
    echo "| --- | --- | --- | --- |"
    echo "| 新人 | [Backend-README](Backend-README.md) | 项目概览 / 架构设计 | 先理解整体再切入模块 |"
    echo "| 后端工程师 | [Backend-02-架构设计](Backend-02-架构设计.md) | 模块说明 / 接口文档 | 关注分层与扩展点 |"
    echo "| 前端工程师 | [Frontend-02-架构设计](Frontend-02-架构设计.md) | 模块说明 / 状态管理 | 关注 API 契约与组件 |"
    echo "| 运维 / SRE | [Backend-07-部署运维](Backend-07-部署运维.md) | 部署运维 / 数据流程 | 关注配置与依赖 |"
  } > "${STAGING_DIR}/Home.md"

  # ---- _Sidebar.md ---------------------------------------------------------
  {
    echo "## 导航"
    echo
    local sec
    for sec in Backend Frontend MCP; do
      echo "### ${sec}"
      while IFS= read -r f; do
        local name="${f%.md}"
        echo "- [${name}](${f})"
      done < <(cd "${STAGING_DIR}" && find . -maxdepth 1 -type f -name "${sec}-*.md" -printf '%f\n' | sort)
      echo
    done
  } > "${STAGING_DIR}/_Sidebar.md"

  # ---- _Footer.md ----------------------------------------------------------
  {
    echo "*Wiki 由 \`scripts/sync-wiki.sh\` 自动生成 · 最后同步:${today} · 源 commit:[\`${short_sha}\`](https://github.com/shenjiangchun/codeKnowlage/commit/${short_sha})*"
  } > "${STAGING_DIR}/_Footer.md"

  echo "[sync-wiki] generated Home.md / _Sidebar.md / _Footer.md (commit ${short_sha})"
}

# ---- main ------------------------------------------------------------------
main() {
  clone_wiki
  flatten_codewiki
  rewrite_links
  verify_links
  generate_navigation
  echo "[sync-wiki] navigation phase complete"
}

main "$@"
