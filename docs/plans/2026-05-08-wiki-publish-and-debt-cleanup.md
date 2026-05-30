# Wiki 发布与历史包袱整改 — 实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 把三套 CodeWiki 合并发布到 GitHub Wiki(`shenjiangchun/codeKnowlage.wiki`),并通过 3 个独立 PR 消除三项历史包袱(MCP pathUtils 缺失、前端死路由、后端 README 描述失真)。

**Architecture:** 用 Bash 脚本 `scripts/sync-wiki.sh` 拷贝三套 codewiki 到 wiki 仓库,加 `Backend-/Frontend-/MCP-` 前缀平铺、重写相对链接、生成 Home/Sidebar/Footer。三个 PR 各自分支独立串行实施,每步严格 TDD/手工验证。

**Tech Stack:** Bash · Git · Node.js (MCP 构建) · Vue 3 + Vite + Playwright (前端) · Maven (后端)

**关联设计文档:** `docs/plans/2026-05-08-wiki-publish-and-debt-cleanup-design.md`

**前置约定:**
- 本计划假定 Git 已配置好对 `shenjiangchun/codeKnowlage` 的 push 权限。
- 仓库根:`C:\Users\47583\projects\hisi_dev_tool v5.0\`
- Bash 中 `git` 不在 PATH 内,需先 `export PATH="/c/Program Files/Git/cmd:$PATH"`(或在脚本里硬编码)。

---

## Phase 0:准备

### Task 0.1:创建总跟踪分支(可选)

**目的:** 为整个 cleanup 计划开一个 tracking 分支(便于后续 rebase / 回滚),实际工作仍在子分支进行。

**Step 1:** 切到主分支并拉最新

```bash
export PATH="/c/Program Files/Git/cmd:$PATH"
cd "/c/Users/47583/projects/hisi_dev_tool v5.0"
git checkout main
git pull --ff-only
```

**Step 2:** 跳过 — 直接以 main 为基线开各 PR 分支(更简洁)。

---

## Phase 1:Wiki 同步脚本 + 推送

### Task 1.1:写 Wiki 同步脚本骨架

**Files:**
- Create: `scripts/sync-wiki.sh`

**Step 1:** 创建脚本,先实现 dry-run 框架

```bash
#!/usr/bin/env bash
# sync-wiki.sh — Sync three subprojects' CodeWiki to GitHub Wiki repo
# Usage: ./sync-wiki.sh [--dry-run] [--push]
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
STAGING_DIR="${ROOT_DIR}/.wiki-staging"
WIKI_REMOTE="https://github.com/shenjiangchun/codeKnowlage.wiki.git"

DRY_RUN=true
PUSH=false
for arg in "$@"; do
  case "$arg" in
    --push) DRY_RUN=false; PUSH=true ;;
    --dry-run) DRY_RUN=true; PUSH=false ;;
  esac
done

declare -A SUBPROJECTS=(
  [Backend]="${ROOT_DIR}/hisi-dev-tool/docs/codewiki"
  [Frontend]="${ROOT_DIR}/hisi-dev-tool-frontend/docs/codewiki"
  [MCP]="${ROOT_DIR}/hisi-mcp-server/docs/codewiki"
)

echo "[sync-wiki] dry_run=${DRY_RUN} push=${PUSH}"
echo "[sync-wiki] root=${ROOT_DIR}"
echo "[sync-wiki] staging=${STAGING_DIR}"
```

**Step 2:** 给脚本可执行权限并 dry-run

```bash
chmod +x scripts/sync-wiki.sh
./scripts/sync-wiki.sh --dry-run
```

预期输出:打印 dry_run / push / root / staging 信息,无错误退出。

**Step 3:** Commit

```bash
git add scripts/sync-wiki.sh
git commit -m "chore: add sync-wiki.sh skeleton (dry-run only)"
```

---

### Task 1.2:实现"克隆 wiki 仓库到 staging 目录"

**Files:**
- Modify: `scripts/sync-wiki.sh`

**Step 1:** 在脚本中追加 clone 逻辑(若 staging 已存在则刷新)

```bash
clone_wiki() {
  if [[ -d "${STAGING_DIR}/.git" ]]; then
    echo "[sync-wiki] reusing existing staging, fetching..."
    (cd "${STAGING_DIR}" && git fetch origin && git reset --hard origin/master 2>/dev/null || git reset --hard origin/main)
  else
    rm -rf "${STAGING_DIR}"
    git clone "${WIKI_REMOTE}" "${STAGING_DIR}"
  fi
}

clone_wiki
```

注意:GitHub Wiki 默认主分支可能是 `master`(老仓库)或 `main`,故 try-fallback。如果是空仓库 git clone 会报 warning 但成功创建空目录,需要在后续判断。

**Step 2:** dry-run

```bash
./scripts/sync-wiki.sh --dry-run
```

预期:成功克隆 wiki 仓库到 `.wiki-staging/`,或报"empty repo"警告但继续。

**Step 3:** 把 `.wiki-staging/` 加入 `.gitignore`

```bash
echo "/.wiki-staging/" >> .gitignore
```

**Step 4:** Commit

```bash
git add scripts/sync-wiki.sh .gitignore
git commit -m "chore(sync-wiki): clone wiki repo into .wiki-staging"
```

---

### Task 1.3:实现"清空 staging,平铺三套 codewiki"

**Files:**
- Modify: `scripts/sync-wiki.sh`

**Step 1:** 追加平铺逻辑

```bash
flatten_codewiki() {
  # Wipe staging contents (keep .git)
  find "${STAGING_DIR}" -mindepth 1 -maxdepth 1 ! -name ".git" -exec rm -rf {} +

  for prefix in "${!SUBPROJECTS[@]}"; do
    src="${SUBPROJECTS[$prefix]}"
    if [[ ! -d "$src" ]]; then
      echo "[sync-wiki] WARN: missing source ${src}" >&2
      continue
    fi
    # Walk all .md files
    while IFS= read -r -d '' file; do
      rel="${file#${src}/}"
      # README.md → Backend-README.md
      # 01-项目概览/index.md → Backend-01-项目概览.md
      # 03-模块说明/REST接口层.md → Backend-03-REST接口层.md
      # Strip /index.md and replace remaining "/" with "-"
      flat="${rel%/index.md}"
      flat="${flat//\//-}"
      [[ "$flat" == "$rel" ]] && flat="$rel"  # no /index.md case
      out="${STAGING_DIR}/${prefix}-${flat}"
      # If "${prefix}-${flat}" still has slashes (shouldn't), flatten
      out="${out//\//-}"
      mkdir -p "$(dirname "$out")" 2>/dev/null || true
      cp "$file" "$out"
      echo "[copy] $rel → $(basename "$out")"
    done < <(find "$src" -type f -name "*.md" -print0)
  done
}

flatten_codewiki
```

**Step 2:** dry-run 测试,人工目检 `.wiki-staging/` 下生成的文件名是否符合预期

```bash
./scripts/sync-wiki.sh --dry-run
ls .wiki-staging/ | head -30
```

预期:看到 `Backend-README.md`、`Backend-01-项目概览.md`、`Frontend-README.md` 等。

**Step 3:** Commit

```bash
git add scripts/sync-wiki.sh
git commit -m "chore(sync-wiki): flatten three codewiki sets with prefix"
```

---

### Task 1.4:实现"重写 Markdown 内的相对链接"

**Files:**
- Modify: `scripts/sync-wiki.sh`

**Step 1:** 追加链接重写函数

链接重写规则:
- `../02-架构设计/index.md` → `Backend-02-架构设计.md`(同子项目内)
- `../03-模块说明/REST接口层.md` → `Backend-03-REST接口层.md`
- `./xxx.md` → `Backend-xxx.md`(同目录)
- 跨子项目链接(如 Frontend wiki 引用 Backend 的)若存在,需识别后改成对应前缀

```bash
rewrite_links() {
  for file in "${STAGING_DIR}"/*.md; do
    [[ -e "$file" ]] || continue
    local prefix
    prefix="$(basename "$file" | cut -d'-' -f1)"  # Backend / Frontend / MCP
    # Use perl for in-place regex (BSD/GNU sed compat issue)
    perl -i -pe '
      # ../<dir>/index.md  →  <Prefix>-<dir>.md
      s{\.\./([^/\)]+)/index\.md}{'"$prefix"'-$1.md}g;
      # ../<dir>/<file>.md  →  <Prefix>-<dir>-<file>.md
      s{\.\./([^/\)]+)/([^\)]+)\.md}{'"$prefix"'-$1-$2.md}g;
      # ./<file>.md  →  <Prefix>-<file>.md
      s{\./([^\)]+)\.md}{'"$prefix"'-$1.md}g;
    ' "$file"
  done
}

rewrite_links
```

**Step 2:** dry-run 后人工 grep 检查链接

```bash
./scripts/sync-wiki.sh --dry-run
grep -E '\]\([^)]+\.md\)' .wiki-staging/Backend-README.md | head
```

预期:链接形如 `(Backend-01-项目概览.md)`,无 `../` 残留。

**Step 3:** 新增链接完整性校验

```bash
verify_links() {
  local broken=0
  for file in "${STAGING_DIR}"/*.md; do
    while IFS= read -r target; do
      target="$(echo "$target" | sed -E 's/.*\(([^)]+\.md)\).*/\1/')"
      target="${target%%#*}"  # strip anchor
      [[ -z "$target" ]] && continue
      [[ "$target" =~ ^https?:// ]] && continue
      if [[ ! -f "${STAGING_DIR}/${target}" ]]; then
        echo "[BROKEN] $(basename "$file") → $target" >&2
        broken=$((broken+1))
      fi
    done < <(grep -oE '\]\([^)]+\.md[^)]*\)' "$file" || true)
  done
  if [[ $broken -gt 0 ]]; then
    echo "[sync-wiki] FAIL: ${broken} broken links" >&2
    return 1
  fi
  echo "[sync-wiki] all links OK"
}

verify_links
```

**Step 4:** dry-run 必须 0 broken。如有 broken,人工调整 `rewrite_links` 正则或源文档中的异常链接。

**Step 5:** Commit

```bash
git add scripts/sync-wiki.sh
git commit -m "chore(sync-wiki): rewrite relative links and verify integrity"
```

---

### Task 1.5:生成 Home.md / _Sidebar.md / _Footer.md

**Files:**
- Modify: `scripts/sync-wiki.sh`

**Step 1:** 追加生成器函数

```bash
generate_navigation() {
  local commit
  commit="$(cd "${ROOT_DIR}" && git rev-parse --short HEAD)"
  local date_str
  date_str="$(date +%Y-%m-%d)"

  # Home.md
  cat > "${STAGING_DIR}/Home.md" <<EOF
# codeKnowlage 项目 Wiki

> 自动同步于 ${date_str},基于 commit \`${commit}\`

本 Wiki 收录 codeKnowlage 仓库下三个子项目的完整设计手册:

| 子项目 | 入口 | 说明 |
|---|---|---|
| Java 后端 | [Backend-README](Backend-README) | hisi-dev-tool · Spring Boot 3.2 + Neo4j 5.11 |
| 前端 | [Frontend-README](Frontend-README) | hisi-dev-tool-frontend · Vue 3.5 + Vite 8 |
| MCP 服务 | [MCP-README](MCP-README) | hisi-mcp-server · Node 18 + TypeScript 5 |

## 推荐阅读路径

| 角色 | 路径 |
|---|---|
| 新成员 | Home → Backend-01-项目概览 → Frontend-01-项目概览 → MCP-01-项目概览 |
| 后端开发 | Backend-README → Backend-02-架构设计 → Backend-03-* |
| 前端开发 | Frontend-README → Frontend-02-架构设计 → Frontend-05-接口文档 |
| MCP 工具开发 | MCP-README → MCP-05-接口文档 → MCP-03-* |
EOF

  # _Sidebar.md
  {
    echo "## 导航"
    echo ""
    for prefix in Backend Frontend MCP; do
      echo "### ${prefix}"
      echo ""
      for file in "${STAGING_DIR}/${prefix}-"*.md; do
        [[ -e "$file" ]] || continue
        local name
        name="$(basename "$file" .md)"
        echo "- [${name}](${name})"
      done
      echo ""
    done
  } > "${STAGING_DIR}/_Sidebar.md"

  # _Footer.md
  cat > "${STAGING_DIR}/_Footer.md" <<EOF
---
*Wiki 由 \`scripts/sync-wiki.sh\` 自动生成 · 最后同步:${date_str} · 源 commit:[${commit}](https://github.com/shenjiangchun/codeKnowlage/commit/${commit})*
EOF
}

generate_navigation
```

**Step 2:** dry-run 后目检三个文件

```bash
./scripts/sync-wiki.sh --dry-run
cat .wiki-staging/Home.md
cat .wiki-staging/_Sidebar.md | head -20
cat .wiki-staging/_Footer.md
```

**Step 3:** Commit

```bash
git add scripts/sync-wiki.sh
git commit -m "chore(sync-wiki): generate Home, _Sidebar, _Footer"
```

---

### Task 1.6:实现 push 阶段(--push 时才执行)

**Files:**
- Modify: `scripts/sync-wiki.sh`

**Step 1:** 追加 push 函数

```bash
commit_and_push() {
  cd "${STAGING_DIR}"
  git add -A
  if git diff --cached --quiet; then
    echo "[sync-wiki] no changes to push"
    return 0
  fi
  local commit
  commit="$(cd "${ROOT_DIR}" && git rev-parse --short HEAD)"
  local tag="wiki-sync-$(date +%Y%m%d-%H%M)"
  git -c user.email="wiki-sync@local" -c user.name="wiki-sync" \
      commit -m "sync: from main repo @ ${commit}"
  git tag "${tag}"
  if [[ "${PUSH}" == "true" ]]; then
    # Detect default branch
    local branch
    branch="$(git symbolic-ref --short HEAD)"
    git push origin "${branch}"
    git push origin "${tag}"
    echo "[sync-wiki] pushed branch=${branch} tag=${tag}"
  else
    echo "[sync-wiki] dry-run: would push branch and tag=${tag}"
  fi
}

commit_and_push
```

**Step 2:** 先 dry-run 验证(不 push)

```bash
./scripts/sync-wiki.sh --dry-run
cd .wiki-staging && git log -1 --oneline && cd ..
```

预期:`.wiki-staging` 内有一条新 commit + 一个新 tag,但远端未变。

**Step 3:** Commit 脚本

```bash
git add scripts/sync-wiki.sh
git commit -m "chore(sync-wiki): implement commit and push phase"
```

---

### Task 1.7:执行真实推送

**Step 1:** 最后一次完整 dry-run + 链接校验

```bash
rm -rf .wiki-staging
./scripts/sync-wiki.sh --dry-run
```

预期:全程 0 broken,Home/Sidebar/Footer 完整。

**Step 2:** 执行 push

```bash
./scripts/sync-wiki.sh --push
```

预期:
- 推送成功(branch + tag)
- 控制台打印 `pushed branch=master tag=wiki-sync-20260508-NNNN`(或 main)

**Step 3:** 浏览器打开 `https://github.com/shenjiangchun/codeKnowlage/wiki` 人工验证:
- Home 页正常显示
- Sidebar 三个分组完整
- 随机点 5 个链接,确认跳转正常

**Step 4:** 在主仓库打 tag 标记本次 wiki 同步对应的源 commit

```bash
cd "${ROOT_DIR}"
git tag "wiki-published-$(date +%Y%m%d)"
git push origin "wiki-published-$(date +%Y%m%d)"
```

---

## Phase 2:PR-1 — MCP 补回缺失的 pathUtils

**关键澄清(对原设计文档的修正):**
经过实地探查,`hisi-mcp-server/dist/utils/pathUtils.js` **也不存在**(`dist/` 下没有 `utils/` 目录)。这说明 `src/utils/` 从未存在过,问题更严重:`tools/index.ts` 引入了一个**幽灵模块**,只是当前编译产物 `dist/tools/index.js` 也带着这个错误 import 一起被使用 —— 之所以"还能跑",可能是 normalizePathArgs 的调用刚好在所有工具入口处,而某些 Node 运行时对 ESM 缺失 specifier 的报错时机是延迟到首次调用,或仅在 stdio 启动时打印 warn。

**因此 PR-1 必须从零创建 `src/utils/pathUtils.ts`,并通过 TDD 保证行为正确。**

### Task 2.1:开 PR-1 分支

```bash
cd "/c/Users/47583/projects/hisi_dev_tool v5.0/hisi-mcp-server"
git checkout main
git pull --ff-only
git checkout -b chore/mcp-restore-pathutils
```

### Task 2.2:实地复现 bug(确认报错)

**Step 1:** 清空 dist 并构建

```bash
rm -rf dist
npm install
npm run build
```

**Step 2:** 启动 server 检查报错

```bash
node dist/index.js < /dev/null  # 或对应的启动命令
```

预期:看到 `ERR_MODULE_NOT_FOUND ../utils/pathUtils.js` 或类似错误。

**Step 3:** 记录现状到 PR 描述草稿

---

### Task 2.3:写测试(RED)

**Files:**
- Create: `hisi-mcp-server/src/utils/pathUtils.test.ts`(若项目无测试框架则用 Node 内置 `node:test`)

**Step 1:** 检查项目测试框架

```bash
cat package.json | grep -E '"(test|jest|vitest|mocha)"'
```

若无,则使用 `node:test`。

**Step 2:** 写测试

```typescript
import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import { normalizePathArgs } from './pathUtils.js';

describe('normalizePathArgs', () => {
  it('replaces backslashes with forward slashes in projectPath', () => {
    const out = normalizePathArgs({ projectPath: 'C:\\foo\\bar' });
    assert.equal(out.projectPath, 'C:/foo/bar');
  });

  it('handles projectPaths array', () => {
    const out = normalizePathArgs({ projectPaths: ['C:\\a', 'D:\\b\\c'] });
    assert.deepEqual(out.projectPaths, ['C:/a', 'D:/b/c']);
  });

  it('leaves non-path keys untouched', () => {
    const out = normalizePathArgs({ query: 'foo\\bar', limit: 10 });
    assert.equal(out.query, 'foo\\bar');
    assert.equal(out.limit, 10);
  });

  it('returns same object when no path keys present', () => {
    const out = normalizePathArgs({ x: 1 });
    assert.deepEqual(out, { x: 1 });
  });

  it('handles undefined args defensively', () => {
    const out = normalizePathArgs(undefined as any);
    assert.deepEqual(out, {});
  });
});
```

**Step 3:** 运行测试,预期 FAIL

```bash
npx tsx --test src/utils/pathUtils.test.ts
```

预期:`Cannot find module './pathUtils.js'`(因为还没创建)。

---

### Task 2.4:实现 pathUtils(GREEN)

**Files:**
- Create: `hisi-mcp-server/src/utils/pathUtils.ts`

**Step 1:** 写最小实现

```typescript
/**
 * Normalize Windows-style backslash paths to forward slashes for known path keys.
 * Mutates a shallow copy of args; does not touch other keys.
 */
const PATH_KEYS = ['projectPath', 'projectPaths'] as const;

function toForwardSlashes(value: string): string {
  return value.replace(/\\/g, '/');
}

export function normalizePathArgs(args: Record<string, unknown> | undefined): Record<string, unknown> {
  if (!args) return {};
  const out: Record<string, unknown> = { ...args };
  for (const key of PATH_KEYS) {
    const v = out[key];
    if (typeof v === 'string') {
      out[key] = toForwardSlashes(v);
    } else if (Array.isArray(v)) {
      out[key] = v.map((item) => (typeof item === 'string' ? toForwardSlashes(item) : item));
    }
  }
  return out;
}
```

**Step 2:** 运行测试

```bash
npx tsx --test src/utils/pathUtils.test.ts
```

预期:全部 PASS。

**Step 3:** 完整构建验证

```bash
rm -rf dist
npm run build
ls dist/utils/pathUtils.js
```

预期:`dist/utils/pathUtils.js` 存在。

---

### Task 2.5:冒烟测试 MCP 服务

**Step 1:** 启动 server

```bash
node dist/index.js
```

预期:无 ERR_MODULE_NOT_FOUND;stdio 等待输入。

**Step 2:** 通过 MCP 客户端(本会话即 Claude Code)调用 3 类工具各一次:
- `mcp__hisi-mcp-server__kg_list_projects`
- `mcp__hisi-mcp-server__hybrid_search` (任意 query)
- `mcp__hisi-mcp-server__log_query`(若后端无可用,允许返回空数组,不能报路径错误)

**Step 3:** 验证 backslash → forward slash 真实生效

调用 `kg_status` 时传 `projectPath: "C:\\Users\\47583\\..."`,服务端 log 应显示已 normalize。

---

### Task 2.6:Commit + Push + PR

```bash
git add src/utils/pathUtils.ts src/utils/pathUtils.test.ts
git commit -m "fix: restore missing pathUtils module referenced by tools/index"
git push -u origin chore/mcp-restore-pathutils
gh pr create --title "fix(mcp): restore missing pathUtils module" \
  --body "$(cat <<'EOF'
## Summary
- `tools/index.ts` 引用 `../utils/pathUtils.js`,但 `src/utils/` 与 `dist/utils/` 都不存在,清理 dist 后构建会断
- 通过 TDD 补回 `src/utils/pathUtils.ts`,实现 `normalizePathArgs`(将 projectPath / projectPaths 中的反斜杠改为正斜杠)
- 5 个单测覆盖:单字符串、数组、混合非路径键、空对象、undefined 容错

## Test plan
- [x] `npm run build` 干净通过
- [x] `node:test` 5 用例全过
- [x] 启动 server 无 ERR_MODULE_NOT_FOUND
- [x] kg_list_projects / hybrid_search / log_query 各调用 1 次返回正常
- [x] 传入 Windows 反斜杠路径确认被正确 normalize

## Related
- 设计文档:`docs/plans/2026-05-08-wiki-publish-and-debt-cleanup-design.md`
- 历史包袱编号:1/3
EOF
)"
```

---

## Phase 3:PR-2 — 前端清理死路由与错调 API

### Task 3.1:开 PR-2 分支

```bash
cd "/c/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool-frontend"
git checkout main && git pull --ff-only
git checkout -b chore/frontend-prune-dead-routes
```

### Task 3.2:实地探查死路由的影响面

**Step 1:** 列出所有引用

```bash
grep -RIn "SemanticSearchView" src/ || true
grep -RIn "/search/semantic" src/ || true
grep -RIn "/call-chain" src/ || true
```

**Step 2:** 把找到的引用记录到 PR 草稿描述里。

### Task 3.3:写/调整 e2e 测试,确认废弃入口已不可达(RED)

**Files:**
- Create: `e2e/dead-routes-removed.spec.ts`

**Step 1:** 写测试(注意:此测试此刻应当是 GREEN——因为我们要验证"已经移除",所以写完测试应该先确认它会失败,即此时入口仍存在)

```typescript
import { test, expect } from '@playwright/test';

test('SemanticSearchView 入口已下线', async ({ page }) => {
  const res = await page.goto('/search/semantic');
  // 应被路由守卫导向 404 或首页,不能命中具体 SemanticSearchView 组件
  await expect(page.locator('[data-test="semantic-search-root"]')).toHaveCount(0);
});

test('/call-chain 老路径已移除', async ({ page }) => {
  const res = await page.goto('/call-chain');
  await expect(page).not.toHaveURL(/\/call-chain(?!.*\/redirected)/);
});
```

**Step 2:** 跑 e2e

```bash
npx playwright test e2e/dead-routes-removed.spec.ts
```

预期:FAIL(因为现在入口仍存在)。若 SemanticSearchView 没有 `data-test` marker,测试断言要换成"组件文本不出现"。

### Task 3.4:删除 SemanticSearchView 与对应路由(GREEN)

**Files:**
- Delete: `src/views/search/SemanticSearchView.vue`
- Modify: `src/router/index.ts`(移除 `/search/semantic` 与所有 `/call-chain*` 条目)
- Modify: `src/components/layout/*`(若菜单/导航有"语义搜索"或"调用链"入口,删除)
- Modify: `src/services/searchService.ts` 之类(若有对应 API client 函数,删除)
- Modify: `src/types/*.ts`(若有 SemanticSearch 相关类型且无其他引用,删除)
- Modify: `src/locales/*.json`(若有 i18n key 且无其他引用,删除)

**Step 1:** 逐项删除/修改,每改一个文件后跑一次 typecheck

```bash
npm run typecheck
```

直到 0 error。

**Step 2:** 跑单测

```bash
npm run test
```

**Step 3:** 跑 e2e

```bash
npx playwright test e2e/dead-routes-removed.spec.ts
```

预期:PASS。

**Step 4:** 跑全量 e2e 兜底

```bash
npx playwright test
```

预期:全绿(若有其他测试因为路由改动失败,需评估是属于本次清理影响还是先前问题)。

### Task 3.5:Commit + Push + PR

```bash
git add -A
git commit -m "chore: remove SemanticSearchView and dead /call-chain routes"
git push -u origin chore/frontend-prune-dead-routes
gh pr create --title "chore(frontend): prune dead semantic search and call-chain routes" \
  --body "..."
```

---

## Phase 4:PR-3 — 后端 README 描述对齐当前架构

### Task 4.1:开 PR-3 分支

```bash
cd "/c/Users/47583/projects/hisi_dev_tool v5.0/hisi-dev-tool"
git checkout main && git pull --ff-only
git checkout -b docs/backend-readme-align
```

### Task 4.2:摘要现状(双向对齐源)

**Step 1:** 三方文件并列阅读
- `hisi-dev-tool/README.md`(待改)
- `hisi-dev-tool/CLAUDE.md`(基准 1)
- `hisi-dev-tool/docs/codewiki/02-架构设计/index.md`(基准 2)
- `hisi-dev-tool/pom.xml`(基准 3,版本号源)

**Step 2:** 列出 README 中所有需要删除的关键词

```bash
grep -niE "OpenGauss|MySQL|ChromaDB|hisi-vector-service|向量服务" README.md
```

记录每条命中的行号 + 上下文。

### Task 4.3:重写 README(单一 commit)

**Files:**
- Modify: `hisi-dev-tool/README.md`

**Step 1:** 应用以下结构(具体内容从 CLAUDE.md / Wiki / pom.xml 摘抄,严禁编造):

```markdown
# hisi-dev-tool

> 基于 Neo4j 的代码知识图谱后端服务

## 技术栈

- Spring Boot 3.2.0 / Java 17
- Neo4j 5.11+(图存储,Spring Data Neo4j)
- SQLite(会话与工作区元数据)
- PTY4J(终端 PTY)
- Playwright(日志云爬取)
- ANTLR4 / JavaParser(代码解析)

## 架构演进

| 版本 | 主存储 | 状态 |
|---|---|---|
| v3 | OpenGauss + ChromaDB + hisi-vector-service | 已下线 |
| v5(当前) | Neo4j + SQLite | 在用 |

(下面接快速启动、目录结构、配置、相关链接等)

## 相关文档

- [CodeWiki(完整设计手册)](docs/codewiki/README.md)
- [GitHub Wiki](https://github.com/shenjiangchun/codeKnowlage/wiki)
- [架构设计](docs/codewiki/02-架构设计/index.md)
- [CLAUDE.md(开发约定)](CLAUDE.md)
```

**Step 2:** 在文件顶部 frontmatter / 注释里加追溯标记

```markdown
<!-- Last verified against commit {hash} on 2026-05-08 -->
```

替换 `{hash}` 为 `git rev-parse --short HEAD`。

**Step 3:** 自检三方一致性

```bash
diff <(grep -E "技术栈|Neo4j|SQLite" README.md) <(grep -E "技术栈|Neo4j|SQLite" CLAUDE.md)
```

人工目检无矛盾。

### Task 4.4:Commit + Push + PR

```bash
git add README.md
git commit -m "docs: align README with current Neo4j+SQLite architecture"
git push -u origin docs/backend-readme-align
gh pr create --title "docs(backend): align README with current architecture" --body "..."
```

---

## Phase 5:收尾

### Task 5.1:关闭跟踪

**Step 1:** 在主仓库 `docs/plans/2026-05-08-wiki-publish-and-debt-cleanup-design.md` 末尾追加完成清单

```markdown
## 完成记录

- [x] Wiki 推送(tag: `wiki-published-YYYYMMDD`)
- [x] PR-1 合入 main(commit: `xxxxxxx`)
- [x] PR-2 合入 main(commit: `xxxxxxx`)
- [x] PR-3 合入 main(commit: `xxxxxxx`)
```

**Step 2:** Commit

```bash
cd "/c/Users/47583/projects/hisi_dev_tool v5.0"
git checkout main && git pull --ff-only
git add docs/plans/2026-05-08-wiki-publish-and-debt-cleanup-design.md
git commit -m "docs: mark wiki publish and debt cleanup plan complete"
git push
```

---

## 风险与应急

| 场景 | 应急 |
|---|---|
| Wiki push 失败 / 内容错乱 | `cd .wiki-staging && git reset --hard <previous-tag> && git push -f origin master` |
| PR-1 合并后线上 MCP 行为异常 | 立即 revert PR-1,server 回到原状态(虽然 import 错误依然存在,但至少和合并前一致) |
| PR-2 删错路由(用户投诉) | revert PR-2;在 e2e 里加专门的 regression 用例后再重提 |
| PR-3 与 CLAUDE.md 描述再次冲突 | 两份文档以代码为准,以 commit hash 锚定时间点,后续以 doc-updater agent 定期同步 |

---

## DRY/YAGNI 自检

- 三个 PR 的"开分支 / commit / 推 / 开 PR"步骤刻意未抽象成模板,因为细节差异(测试命令、PR 描述)足够大,模板化反而增加阅读成本
- Wiki 同步脚本只解决"全量同步"一个场景,不做增量(YAGNI)
- 不引入 wiki 站点生成器(YAGNI)
- 不为 PR-3 的 README 写自动化测试(YAGNI——文档对齐用人工 review 即可)
