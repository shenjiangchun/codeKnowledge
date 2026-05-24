# 影响图谱可视化重设计实施计划（DAG + 升级清单 + 小地图）

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 RAM 影响预览页从三层同心环（信息密度低、看不出影响路径）重构为「分层 DAG 主舞台 + 升级文件清单 + 小地图」的二栏布局，让用户能一眼看到"哪些是种子、传播了几层、风险集中在哪、为什么被影响"。

**Architecture:**
- **主舞台**：`@dagrejs/dagre` 计算分层布局（LR：seeds → tree → impacted），Vue + 原生 SVG 渲染（保留 happy-dom 单元测试可读性）。视觉编码三件套——position=层级、size=入度、color=riskScore 渐变、edge-width=riskScore 累积流量。
- **侧栏（≥360px）**：升级版文件清单（搜索 / 按包分组 / 多选 / 导出 / 与图谱双向联动）。
- **小地图（160×160）**：保留旧三层环作为方位指示器，复用现有 `ThreeRingGraph.vue`。
- **状态**：通过 Pinia `useRamStore` 维护 `selectedFile` / `hoveredFile` / `highlightPath`，供 DAG ↔ 清单双向联动。

**Tech Stack:** Vue 3.5 + TypeScript 5.x + Pinia + Element Plus + `@dagrejs/dagre` ^1.x + Vitest + happy-dom + @vue/test-utils

**Branch:** `feat/ram-impact-dag-redesign`

**Files index (all paths relative to repo root):**
- 新增：`hisi-dev-tool-frontend/src/components/ram/dagLayout.ts`
- 新增：`hisi-dev-tool-frontend/src/components/ram/DagGraph.vue`
- 新增：`hisi-dev-tool-frontend/src/components/ram/FileBrowserPanel.vue`
- 新增：`hisi-dev-tool-frontend/src/components/ram/Minimap.vue`
- 新增：`hisi-dev-tool-frontend/src/components/ram/__tests__/dagLayout.spec.ts`
- 新增：`hisi-dev-tool-frontend/src/components/ram/__tests__/DagGraph.spec.ts`
- 新增：`hisi-dev-tool-frontend/src/components/ram/__tests__/FileBrowserPanel.spec.ts`
- 修改：`hisi-dev-tool-frontend/src/stores/ram.ts`（新增 selectedFile / hoveredFile / highlightPath 三态）
- 修改：`hisi-dev-tool-frontend/src/views/ram/GraphPreviewPage.vue`（二栏布局 + 集成新组件）
- 保留：`hisi-dev-tool-frontend/src/components/ram/threeRingLayout.ts` + `ThreeRingGraph.vue`（被 `Minimap.vue` 复用）

---

## Task 1：安装 dagre 依赖并校验类型

**Files:**
- Modify: `hisi-dev-tool-frontend/package.json`

**Step 1: 安装运行依赖**

Run: `cd hisi-dev-tool-frontend && npm install @dagrejs/dagre@^1.1.4`
Expected: package.json 出现 `"@dagrejs/dagre": "^1.1.4"`，无 audit error。

**Step 2: 验证类型可用**

Run: `cd hisi-dev-tool-frontend && npx tsc --noEmit`
Expected: 通过（dagre v1.x 自带 d.ts，无需 @types）。

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/package.json hisi-dev-tool-frontend/package-lock.json
git commit -m "chore(ram): add @dagrejs/dagre for impact DAG layout"
```

---

## Task 2：dagLayout.ts 纯函数 —— 输入边集，输出 {x,y,layer} 坐标

**Files:**
- Create: `hisi-dev-tool-frontend/src/components/ram/dagLayout.ts`
- Create: `hisi-dev-tool-frontend/src/components/ram/__tests__/dagLayout.spec.ts`

**Step 1: 写失败测试**

```typescript
// dagLayout.spec.ts
import { describe, expect, it } from 'vitest'
import { computeDagLayout, type DagInput } from '../dagLayout'

describe('computeDagLayout', () => {
  const baseInput: DagInput = {
    width: 800,
    height: 500,
    seeds: ['A'],
    edges: [
      { from: 'A', to: 'B', kind: 'call' },
      { from: 'B', to: 'C', kind: 'call' },
      { from: 'A', to: 'D', kind: 'feign' }
    ],
    riskScores: { A: 0.9, B: 0.5, C: 0.1, D: 0.7 },
    inDegree: { A: 0, B: 1, C: 1, D: 1 }
  }

  it('returns one node per unique file', () => {
    const out = computeDagLayout(baseInput)
    expect(out.nodes.map((n) => n.id).sort()).toEqual(['A', 'B', 'C', 'D'])
  })

  it('places seeds at the leftmost layer', () => {
    const out = computeDagLayout(baseInput)
    const a = out.nodes.find((n) => n.id === 'A')!
    const others = out.nodes.filter((n) => n.id !== 'A')
    for (const o of others) expect(a.x).toBeLessThan(o.x)
  })

  it('assigns increasing layer index from seeds to leaves', () => {
    const out = computeDagLayout(baseInput)
    const layer = (id: string) => out.nodes.find((n) => n.id === id)!.layer
    expect(layer('A')).toBe(0)
    expect(layer('B')).toBe(1)
    expect(layer('C')).toBe(2)
  })

  it('encodes risk as node color and size from inDegree', () => {
    const out = computeDagLayout(baseInput)
    const a = out.nodes.find((n) => n.id === 'A')!
    const b = out.nodes.find((n) => n.id === 'B')!
    expect(a.color).toMatch(/^#[0-9a-f]{6}$/i)
    expect(b.radius).toBeGreaterThanOrEqual(6)
  })

  it('exposes edge stroke width proportional to risk flow', () => {
    const out = computeDagLayout(baseInput)
    const ab = out.edges.find((e) => e.from === 'A' && e.to === 'B')!
    const bc = out.edges.find((e) => e.from === 'B' && e.to === 'C')!
    expect(ab.strokeWidth).toBeGreaterThanOrEqual(bc.strokeWidth)
  })

  it('marks cross-service edges as dashed', () => {
    const out = computeDagLayout(baseInput)
    const ad = out.edges.find((e) => e.from === 'A' && e.to === 'D')!
    expect(ad.dashed).toBe(true)
  })
})
```

Run: `cd hisi-dev-tool-frontend && npx vitest run src/components/ram/__tests__/dagLayout.spec.ts`
Expected: FAIL —— "Cannot find module '../dagLayout'"

**Step 2: 实现 dagLayout.ts**

```typescript
/**
 * Pure layered DAG layout for the RAM impact graph.
 *
 * Wraps @dagrejs/dagre and projects the result into a flat node/edge list
 * carrying every visual property the SVG template needs (color, radius,
 * stroke width, dashed flag). Risk is encoded via a green→amber→red
 * gradient; node radius grows with in-degree; edge width grows with
 * cumulative risk flow.
 */
import dagre from '@dagrejs/dagre'

export type EdgeKind = 'call' | 'feign' | 'mq' | 'unknown'

export interface DagInput {
  readonly width: number
  readonly height: number
  readonly seeds: readonly string[]
  readonly edges: readonly { from: string; to: string; kind?: EdgeKind }[]
  readonly riskScores?: Readonly<Record<string, number>>
  readonly inDegree?: Readonly<Record<string, number>>
}

export interface DagNode {
  readonly id: string
  readonly x: number
  readonly y: number
  readonly layer: number
  readonly radius: number
  readonly color: string
  readonly isSeed: boolean
  readonly risk: number
}

export interface DagEdge {
  readonly from: string
  readonly to: string
  readonly strokeWidth: number
  readonly dashed: boolean
  readonly kind: EdgeKind
  readonly points: readonly { x: number; y: number }[]
}

export interface DagOutput {
  readonly nodes: readonly DagNode[]
  readonly edges: readonly DagEdge[]
  readonly width: number
  readonly height: number
}

const NODE_BASE_R = 6
const NODE_MAX_R = 14
const EDGE_BASE_W = 1
const EDGE_MAX_W = 5

function riskColor(score: number): string {
  const s = Math.max(0, Math.min(1, score))
  // Mirror threeRingLayout.riskHeatColor scheme for consistency.
  if (s <= 0.5) return mix('#67C23A', '#E6A23C', s / 0.5)
  return mix('#E6A23C', '#F56C6C', (s - 0.5) / 0.5)
}

function mix(a: string, b: string, t: number): string {
  const p = (s: string, i: number) => parseInt(s.slice(i, i + 2), 16)
  const ar = p(a, 1), ag = p(a, 3), ab = p(a, 5)
  const br = p(b, 1), bg = p(b, 3), bb = p(b, 5)
  const r = Math.round(ar + (br - ar) * t)
  const g = Math.round(ag + (bg - ag) * t)
  const bl = Math.round(ab + (bb - ab) * t)
  return `#${[r, g, bl].map((v) => v.toString(16).padStart(2, '0')).join('')}`
}

export function computeDagLayout(input: DagInput): DagOutput {
  const g = new dagre.graphlib.Graph({ directed: true })
  g.setGraph({ rankdir: 'LR', nodesep: 32, ranksep: 64, marginx: 24, marginy: 24 })
  g.setDefaultEdgeLabel(() => ({}))

  const allIds = new Set<string>(input.seeds)
  for (const e of input.edges) {
    allIds.add(e.from)
    allIds.add(e.to)
  }
  for (const id of allIds) g.setNode(id, { width: 32, height: 32 })
  for (const e of input.edges) g.setEdge(e.from, e.to)

  dagre.layout(g)

  const seeds = new Set(input.seeds)
  const risk = input.riskScores ?? {}
  const inDeg = input.inDegree ?? {}
  const maxIn = Math.max(1, ...Object.values(inDeg))

  const nodes: DagNode[] = []
  let minX = Infinity
  for (const id of allIds) {
    const n = g.node(id)
    if (!n) continue
    minX = Math.min(minX, n.x)
  }
  for (const id of allIds) {
    const n = g.node(id)
    if (!n) continue
    const r = risk[id] ?? 0
    const deg = inDeg[id] ?? 0
    const layer = Math.round((n.x - minX) / 96) // 96 = nodesep+padding heuristic
    nodes.push({
      id,
      x: n.x,
      y: n.y,
      layer,
      radius: NODE_BASE_R + (NODE_MAX_R - NODE_BASE_R) * (deg / maxIn),
      color: r > 0 ? riskColor(r) : '#909399',
      isSeed: seeds.has(id),
      risk: r
    })
  }

  // Accumulate risk flow per edge: max(risk(from), risk(to)).
  const edges: DagEdge[] = input.edges.map((e) => {
    const ge = g.edge(e.from, e.to)
    const flow = Math.max(risk[e.from] ?? 0, risk[e.to] ?? 0)
    const kind: EdgeKind = e.kind ?? 'call'
    return {
      from: e.from,
      to: e.to,
      strokeWidth: EDGE_BASE_W + (EDGE_MAX_W - EDGE_BASE_W) * flow,
      dashed: kind === 'feign' || kind === 'mq',
      kind,
      points: ge?.points ?? []
    }
  })

  const graphInfo = g.graph()
  return {
    nodes,
    edges,
    width: graphInfo.width ?? input.width,
    height: graphInfo.height ?? input.height
  }
}
```

**Step 3: 运行测试通过**

Run: `cd hisi-dev-tool-frontend && npx vitest run src/components/ram/__tests__/dagLayout.spec.ts`
Expected: PASS（6/6）

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/dagLayout.ts hisi-dev-tool-frontend/src/components/ram/__tests__/dagLayout.spec.ts
git commit -m "feat(ram): add dagLayout pure function for impact DAG"
```

---

## Task 3：扩展 Pinia store —— selectedFile / hoveredFile / highlightPath

**Files:**
- Modify: `hisi-dev-tool-frontend/src/stores/ram.ts`
- Create: `hisi-dev-tool-frontend/src/stores/__tests__/ram.spec.ts`（如不存在则新建）

**Step 1: 写失败测试**

```typescript
import { setActivePinia, createPinia } from 'pinia'
import { describe, expect, it, beforeEach } from 'vitest'
import { useRamStore } from '../ram'

describe('useRamStore linkage state', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('tracks selectedFile', () => {
    const s = useRamStore()
    s.selectFile('A.java')
    expect(s.selectedFile).toBe('A.java')
  })

  it('tracks hoveredFile and clears it', () => {
    const s = useRamStore()
    s.hoverFile('B.java')
    expect(s.hoveredFile).toBe('B.java')
    s.hoverFile(null)
    expect(s.hoveredFile).toBeNull()
  })

  it('stores upstream highlight path as a Set', () => {
    const s = useRamStore()
    s.setHighlightPath(['A.java', 'B.java'])
    expect(s.highlightPath.has('A.java')).toBe(true)
    expect(s.highlightPath.has('B.java')).toBe(true)
  })
})
```

Run: `cd hisi-dev-tool-frontend && npx vitest run src/stores/__tests__/ram.spec.ts`
Expected: FAIL

**Step 2: 实现 store 扩展**

在现有 `useRamStore` 的 state/actions 中追加（不影响 impact 字段）：

```typescript
// inside defineStore('ram', () => { ... })
const selectedFile = ref<string | null>(null)
const hoveredFile = ref<string | null>(null)
const highlightPath = ref<Set<string>>(new Set())

function selectFile(file: string | null): void { selectedFile.value = file }
function hoverFile(file: string | null): void { hoveredFile.value = file }
function setHighlightPath(files: readonly string[]): void {
  highlightPath.value = new Set(files)
}
function clearHighlight(): void { highlightPath.value = new Set() }

return {
  // ...existing exports
  selectedFile, hoveredFile, highlightPath,
  selectFile, hoverFile, setHighlightPath, clearHighlight
}
```

**Step 3: 运行测试通过**

Run: `cd hisi-dev-tool-frontend && npx vitest run src/stores/__tests__/ram.spec.ts`
Expected: PASS

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/stores/ram.ts hisi-dev-tool-frontend/src/stores/__tests__/ram.spec.ts
git commit -m "feat(ram): add selection/hover/highlight state to ram store"
```

---

## Task 4：DagGraph.vue —— SVG 渲染分层 DAG，带 hover 上游 DFS 高亮

**Files:**
- Create: `hisi-dev-tool-frontend/src/components/ram/DagGraph.vue`
- Create: `hisi-dev-tool-frontend/src/components/ram/__tests__/DagGraph.spec.ts`

**Step 1: 写失败测试**

```typescript
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import DagGraph from '../DagGraph.vue'

describe('DagGraph', () => {
  beforeEach(() => setActivePinia(createPinia()))
  const props = {
    seeds: ['A'],
    edges: [
      { from: 'A', to: 'B', kind: 'call' as const },
      { from: 'B', to: 'C', kind: 'call' as const }
    ],
    riskScores: { A: 0.9, B: 0.4, C: 0.1 },
    inDegree: { A: 0, B: 1, C: 1 }
  }

  it('renders one circle per node', () => {
    const w = mount(DagGraph, { props })
    expect(w.findAll('circle.dag-node')).toHaveLength(3)
  })

  it('renders one path/line per edge', () => {
    const w = mount(DagGraph, { props })
    expect(w.findAll('.dag-edge')).toHaveLength(2)
  })

  it('marks seeds with the seed class', () => {
    const w = mount(DagGraph, { props })
    const seedNode = w.findAll('circle.dag-node').find((n) => n.attributes('data-file') === 'A')!
    expect(seedNode.classes()).toContain('is-seed')
  })

  it('emits nodeClick with the file id', async () => {
    const w = mount(DagGraph, { props })
    const node = w.findAll('g.dag-node-group').find((g) =>
      g.find('circle').attributes('data-file') === 'B'
    )!
    await node.trigger('click')
    expect(w.emitted('nodeClick')?.[0]).toEqual(['B'])
  })
})
```

Run: `cd hisi-dev-tool-frontend && npx vitest run src/components/ram/__tests__/DagGraph.spec.ts`
Expected: FAIL

**Step 2: 实现 DagGraph.vue**

```vue
<script setup lang="ts">
/**
 * DagGraph — layered SVG DAG for the RAM impact view.
 *
 * Replaces the three concentric rings with a left→right propagation graph.
 * Visual encoding: position = layer, size = in-degree, color = risk
 * gradient, edge width = cumulative risk flow, dashed = cross-service.
 * Hovering a node highlights its upstream ancestor path via store state.
 */
import { computed } from 'vue'
import { computeDagLayout, type EdgeKind } from './dagLayout'
import { useRamStore } from '@/stores/ram'

interface Props {
  seeds: readonly string[]
  edges: readonly { from: string; to: string; kind?: EdgeKind }[]
  riskScores?: Readonly<Record<string, number>>
  inDegree?: Readonly<Record<string, number>>
  width?: number
  height?: number
}

const props = withDefaults(defineProps<Props>(), {
  width: 900,
  height: 520,
  riskScores: () => ({}),
  inDegree: () => ({})
})

const emit = defineEmits<{
  (e: 'nodeClick', file: string): void
}>()

const store = useRamStore()

const layout = computed(() =>
  computeDagLayout({
    width: props.width,
    height: props.height,
    seeds: props.seeds,
    edges: props.edges,
    riskScores: props.riskScores,
    inDegree: props.inDegree
  })
)

// Build adjacency map for upstream DFS (reversed edges).
const upstream = computed<Map<string, string[]>>(() => {
  const m = new Map<string, string[]>()
  for (const e of props.edges) {
    if (!m.has(e.to)) m.set(e.to, [])
    m.get(e.to)!.push(e.from)
  }
  return m
})

function collectUpstream(file: string): string[] {
  const visited = new Set<string>()
  const stack = [file]
  while (stack.length) {
    const cur = stack.pop()!
    if (visited.has(cur)) continue
    visited.add(cur)
    const parents = upstream.value.get(cur) ?? []
    for (const p of parents) stack.push(p)
  }
  return [...visited]
}

function onHover(file: string | null): void {
  store.hoverFile(file)
  if (file) store.setHighlightPath(collectUpstream(file))
  else store.clearHighlight()
}

function onClick(file: string): void {
  store.selectFile(file)
  emit('nodeClick', file)
}

function edgePath(points: readonly { x: number; y: number }[]): string {
  if (points.length === 0) return ''
  return points.map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x} ${p.y}`).join(' ')
}

function isHighlighted(id: string): boolean {
  return store.highlightPath.has(id)
}

function isEdgeHighlighted(from: string, to: string): boolean {
  return store.highlightPath.has(from) && store.highlightPath.has(to)
}
</script>

<template>
  <svg
    class="dag-graph"
    :width="props.width"
    :height="props.height"
    :viewBox="`0 0 ${layout.width} ${layout.height}`"
    role="img"
    aria-label="影响传播图"
  >
    <g class="dag-edges">
      <path
        v-for="(edge, i) in layout.edges"
        :key="`e-${i}`"
        class="dag-edge"
        :d="edgePath(edge.points)"
        :stroke="isEdgeHighlighted(edge.from, edge.to) ? '#409EFF' : '#C0C4CC'"
        :stroke-width="edge.strokeWidth"
        :stroke-dasharray="edge.dashed ? '4 4' : 'none'"
        fill="none"
        :opacity="store.highlightPath.size === 0 || isEdgeHighlighted(edge.from, edge.to) ? 1 : 0.25"
      />
    </g>
    <g class="dag-nodes">
      <g
        v-for="node in layout.nodes"
        :key="node.id"
        class="dag-node-group"
        @mouseenter="onHover(node.id)"
        @mouseleave="onHover(null)"
        @click="onClick(node.id)"
      >
        <circle
          class="dag-node"
          :class="{ 'is-seed': node.isSeed, 'is-highlighted': isHighlighted(node.id) }"
          :cx="node.x"
          :cy="node.y"
          :r="node.radius"
          :fill="node.color"
          :data-file="node.id"
          :data-layer="node.layer"
          stroke="#FFFFFF"
          stroke-width="1.5"
        >
          <title>{{ node.id }} · risk {{ node.risk.toFixed(2) }}</title>
        </circle>
        <text
          class="dag-node-label"
          :x="node.x"
          :y="node.y + node.radius + 11"
          text-anchor="middle"
          font-size="10"
          fill="#606266"
        >
          {{ node.id.split(/[/.]/).pop() }}
        </text>
      </g>
    </g>
  </svg>
</template>

<style scoped>
.dag-graph { display: block; background: #fafbfc; border-radius: 8px; }
.dag-node-group { cursor: pointer; }
.dag-node.is-seed { stroke: #303133; stroke-width: 2; }
.dag-node.is-highlighted { stroke: #409EFF; stroke-width: 2.5; }
.dag-node-label { pointer-events: none; user-select: none; }
</style>
```

**Step 3: 测试通过**

Run: `cd hisi-dev-tool-frontend && npx vitest run src/components/ram/__tests__/DagGraph.spec.ts`
Expected: PASS

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/DagGraph.vue hisi-dev-tool-frontend/src/components/ram/__tests__/DagGraph.spec.ts
git commit -m "feat(ram): add DagGraph SVG component with upstream highlight"
```

---

## Task 5：FileBrowserPanel.vue —— 搜索 / 按包分组 / 多选 / 导出 / 联动

**Files:**
- Create: `hisi-dev-tool-frontend/src/components/ram/FileBrowserPanel.vue`
- Create: `hisi-dev-tool-frontend/src/components/ram/__tests__/FileBrowserPanel.spec.ts`

**Step 1: 写失败测试**

```typescript
import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import FileBrowserPanel from '../FileBrowserPanel.vue'
import { useRamStore } from '@/stores/ram'

describe('FileBrowserPanel', () => {
  beforeEach(() => setActivePinia(createPinia()))
  const props = {
    involved: ['com/foo/A.java'],
    modified: ['com/foo/B.java'],
    impacted: ['com/bar/C.java', 'com/bar/D.java'],
    riskScores: { 'com/foo/A.java': 0.9 }
  }

  it('renders all files', () => {
    const w = mount(FileBrowserPanel, { props })
    expect(w.findAll('.file-row')).toHaveLength(4)
  })

  it('filters by search keyword', async () => {
    const w = mount(FileBrowserPanel, { props })
    await w.find('input.search').setValue('bar')
    expect(w.findAll('.file-row')).toHaveLength(2)
  })

  it('groups by package when group=package', async () => {
    const w = mount(FileBrowserPanel, { props: { ...props, groupBy: 'package' as const } })
    const groups = w.findAll('.file-group-header')
    expect(groups.length).toBeGreaterThanOrEqual(2)
  })

  it('highlights the store.selectedFile', async () => {
    const w = mount(FileBrowserPanel, { props })
    const store = useRamStore()
    store.selectFile('com/foo/A.java')
    await w.vm.$nextTick()
    expect(w.find('.file-row.is-selected').attributes('data-file')).toBe('com/foo/A.java')
  })
})
```

Run: `cd hisi-dev-tool-frontend && npx vitest run src/components/ram/__tests__/FileBrowserPanel.spec.ts`
Expected: FAIL

**Step 2: 实现 FileBrowserPanel.vue**

```vue
<script setup lang="ts">
/**
 * FileBrowserPanel — searchable, group-able, multi-select impact file list.
 *
 * Bi-directional linkage with DagGraph via useRamStore: clicking a row
 * selects the file (which the graph highlights); the row matching
 * selectedFile / hoveredFile is visually emphasised. Supports CSV export
 * of the currently filtered set.
 */
import { computed, ref } from 'vue'
import { useRamStore } from '@/stores/ram'

type RingKey = 'involved' | 'modified' | 'impacted'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  riskScores?: Readonly<Record<string, number>>
  groupBy?: 'none' | 'ring' | 'package'
}

const props = withDefaults(defineProps<Props>(), {
  riskScores: () => ({}),
  groupBy: 'ring'
})

const store = useRamStore()
const search = ref('')
const selected = ref<Set<string>>(new Set())

interface Row { file: string; ring: RingKey; risk?: number }

const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const push = (files: readonly string[], ring: RingKey) => {
    for (const f of files) out.push({ file: f, ring, risk: props.riskScores[f] })
  }
  push(props.involved, 'involved')
  push(props.modified, 'modified')
  push(props.impacted, 'impacted')
  const q = search.value.trim().toLowerCase()
  return q ? out.filter((r) => r.file.toLowerCase().includes(q)) : out
})

const grouped = computed<{ header: string; items: Row[] }[]>(() => {
  if (props.groupBy === 'none') return [{ header: '全部', items: rows.value }]
  if (props.groupBy === 'ring') {
    const buckets: Record<RingKey, Row[]> = { involved: [], modified: [], impacted: [] }
    for (const r of rows.value) buckets[r.ring].push(r)
    return [
      { header: `涉及 (${buckets.involved.length})`, items: buckets.involved },
      { header: `修改 (${buckets.modified.length})`, items: buckets.modified },
      { header: `影响 (${buckets.impacted.length})`, items: buckets.impacted }
    ]
  }
  const map = new Map<string, Row[]>()
  for (const r of rows.value) {
    const pkg = r.file.includes('/')
      ? r.file.slice(0, r.file.lastIndexOf('/'))
      : r.file.split('.').slice(0, -1).join('.')
    if (!map.has(pkg)) map.set(pkg, [])
    map.get(pkg)!.push(r)
  }
  return [...map.entries()].map(([header, items]) => ({ header, items }))
})

function toggleSelect(file: string): void {
  if (selected.value.has(file)) selected.value.delete(file)
  else selected.value.add(file)
  selected.value = new Set(selected.value)
}

function rowClick(file: string): void {
  store.selectFile(file)
}

function exportCsv(): void {
  const lines = ['file,ring,risk']
  for (const r of rows.value) lines.push(`${r.file},${r.ring},${r.risk ?? ''}`)
  const blob = new Blob([lines.join('\n')], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'impact-files.csv'
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="file-browser">
    <div class="toolbar">
      <input v-model="search" class="search" placeholder="搜索文件 / 包名…" />
      <button type="button" @click="exportCsv">导出 CSV</button>
    </div>
    <div class="groups">
      <div v-for="g in grouped" :key="g.header" class="file-group">
        <div class="file-group-header">{{ g.header }}</div>
        <div
          v-for="r in g.items"
          :key="`${r.ring}-${r.file}`"
          class="file-row"
          :class="{
            'is-selected': store.selectedFile === r.file,
            'is-hovered': store.hoveredFile === r.file,
            'is-checked': selected.has(r.file)
          }"
          :data-file="r.file"
          :data-ring="r.ring"
          @click="rowClick(r.file)"
          @mouseenter="store.hoverFile(r.file)"
          @mouseleave="store.hoverFile(null)"
        >
          <input type="checkbox" :checked="selected.has(r.file)" @click.stop="toggleSelect(r.file)" />
          <span class="file">{{ r.file }}</span>
          <span v-if="typeof r.risk === 'number'" class="risk-tag">{{ r.risk.toFixed(2) }}</span>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.file-browser { display: flex; flex-direction: column; height: 100%; }
.toolbar { display: flex; gap: 8px; padding: 8px; border-bottom: 1px solid #ebeef5; }
.search { flex: 1; padding: 4px 8px; border: 1px solid #dcdfe6; border-radius: 4px; font-size: 12px; }
.groups { flex: 1; overflow: auto; }
.file-group-header { padding: 6px 10px; font-weight: 600; font-size: 12px; color: #909399; background: #fafafa; }
.file-row { display: flex; align-items: center; gap: 6px; padding: 4px 10px; font-size: 12px; cursor: pointer; font-family: ui-monospace, monospace; }
.file-row:hover, .file-row.is-hovered { background: #ecf5ff; }
.file-row.is-selected { background: #d9ecff; }
.file-row.is-checked { font-weight: 600; }
.file { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.risk-tag { font-size: 11px; color: #f56c6c; }
</style>
```

**Step 3: 测试通过**

Run: `cd hisi-dev-tool-frontend && npx vitest run src/components/ram/__tests__/FileBrowserPanel.spec.ts`
Expected: PASS

**Step 4: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/FileBrowserPanel.vue hisi-dev-tool-frontend/src/components/ram/__tests__/FileBrowserPanel.spec.ts
git commit -m "feat(ram): add FileBrowserPanel with search/group/select/export"
```

---

## Task 6：Minimap.vue —— 把旧三层环降级为方位指示器

**Files:**
- Create: `hisi-dev-tool-frontend/src/components/ram/Minimap.vue`

**Step 1: 实现 Minimap**

```vue
<script setup lang="ts">
/**
 * Minimap — small (160×160) orientation widget reusing the existing
 * three concentric rings. Displays involved/modified/impacted at-a-glance
 * counts without competing with the main DAG for space.
 */
import ThreeRingGraph from './ThreeRingGraph.vue'

interface Props {
  involved: readonly string[]
  modified: readonly string[]
  impacted: readonly string[]
  riskScores?: Readonly<Record<string, number>>
}

const props = withDefaults(defineProps<Props>(), { riskScores: () => ({}) })
</script>

<template>
  <div class="minimap">
    <ThreeRingGraph
      :involved="props.involved"
      :modified="props.modified"
      :impacted="props.impacted"
      :risk-scores="props.riskScores"
      :width="160"
      :height="160"
    />
  </div>
</template>

<style scoped>
.minimap { width: 160px; height: 160px; pointer-events: none; opacity: 0.85; }
</style>
```

**Step 2: 手动验证（无新增逻辑，不需要单测）**

Run: `cd hisi-dev-tool-frontend && npx tsc --noEmit`
Expected: 通过。

**Step 3: Commit**

```bash
git add hisi-dev-tool-frontend/src/components/ram/Minimap.vue
git commit -m "feat(ram): add Minimap wrapping the legacy three-ring view"
```

---

## Task 7：重写 GraphPreviewPage.vue 为二栏布局

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/ram/GraphPreviewPage.vue`

**Step 1: 阅读现有页面并保留 sid / route / store 接线**

Read: `hisi-dev-tool-frontend/src/views/ram/GraphPreviewPage.vue`

**Step 2: 写失败测试（验证新 DOM 结构）**

Create `hisi-dev-tool-frontend/src/views/ram/__tests__/GraphPreviewPage.spec.ts`:

```typescript
import { describe, expect, it, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createRouter, createMemoryHistory } from 'vue-router'
import GraphPreviewPage from '../GraphPreviewPage.vue'
import { useRamStore } from '@/stores/ram'

describe('GraphPreviewPage', () => {
  beforeEach(() => setActivePinia(createPinia()))

  async function mountPage() {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/:sid', name: 'RamGraph', component: GraphPreviewPage },
        { path: '/draft/:sid', name: 'RamDraft', component: { template: '<div/>' } }
      ]
    })
    router.push('/sess-1')
    await router.isReady()
    const store = useRamStore()
    store.impact = {
      involved: ['A.java'],
      modified: ['B.java'],
      impacted: ['C.java'],
      riskScores: { 'A.java': 0.8 }
    }
    return mount(GraphPreviewPage, { global: { plugins: [router] } })
  }

  it('renders DagGraph in main canvas', async () => {
    const w = await mountPage()
    expect(w.find('.dag-graph').exists()).toBe(true)
  })

  it('renders FileBrowserPanel in side column', async () => {
    const w = await mountPage()
    expect(w.find('.file-browser').exists()).toBe(true)
  })

  it('renders Minimap overlay', async () => {
    const w = await mountPage()
    expect(w.find('.minimap').exists()).toBe(true)
  })
})
```

Run: `cd hisi-dev-tool-frontend && npx vitest run src/views/ram/__tests__/GraphPreviewPage.spec.ts`
Expected: FAIL

**Step 3: 重写 GraphPreviewPage.vue**

```vue
<script setup lang="ts">
/**
 * RAM GraphPreviewPage — two-column impact view.
 *
 *  ┌──────────────────────────────┬─────────────────┐
 *  │  DagGraph (main)             │ FileBrowserPanel│
 *  │                              │ (search/group/  │
 *  │                  ┌─────────┐ │  select/export) │
 *  │                  │ Minimap │ │                 │
 *  │                  └─────────┘ │                 │
 *  └──────────────────────────────┴─────────────────┘
 *
 * Bi-directional linkage runs through useRamStore (selectedFile,
 * hoveredFile, highlightPath).
 */
import { computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import DagGraph from '@/components/ram/DagGraph.vue'
import FileBrowserPanel from '@/components/ram/FileBrowserPanel.vue'
import Minimap from '@/components/ram/Minimap.vue'
import { useRamStore, type ImpactPayload } from '@/stores/ram'

const route = useRoute()
const router = useRouter()
const store = useRamStore()
const sid = computed<string>(() => String(route.params.sid ?? ''))

const empty: ImpactPayload = { involved: [], modified: [], impacted: [] }
const impact = computed<ImpactPayload>(() => store.impact ?? empty)

// Build DAG edges from impact: seeds=involved → modified → impacted.
const dagSeeds = computed<string[]>(() => [...impact.value.involved])
const dagEdges = computed(() => {
  const edges: { from: string; to: string; kind: 'call' }[] = []
  for (const seed of impact.value.involved)
    for (const m of impact.value.modified) edges.push({ from: seed, to: m, kind: 'call' })
  for (const m of impact.value.modified)
    for (const i of impact.value.impacted) edges.push({ from: m, to: i, kind: 'call' })
  return edges
})
const inDegree = computed<Record<string, number>>(() => {
  const d: Record<string, number> = {}
  for (const e of dagEdges.value) d[e.to] = (d[e.to] ?? 0) + 1
  return d
})

function backToDraft(): void {
  router.push({ name: 'RamDraft', params: { sid: sid.value } })
}

onMounted(() => {
  if (!store.impact) ElMessage.warning('未发现影响数据，请先返回 Draft 页等待 Impact 完成')
})
</script>

<template>
  <div class="ram-graph-view">
    <div class="topbar">
      <el-button size="small" @click="backToDraft">返回 Draft</el-button>
      <span class="title">影响图谱（分层 DAG）</span>
      <span class="sid">session: {{ sid }}</span>
    </div>
    <div class="body">
      <div class="canvas">
        <DagGraph
          :seeds="dagSeeds"
          :edges="dagEdges"
          :risk-scores="impact.riskScores ?? {}"
          :in-degree="inDegree"
        />
        <div class="minimap-overlay">
          <Minimap
            :involved="impact.involved"
            :modified="impact.modified"
            :impacted="impact.impacted"
            :risk-scores="impact.riskScores ?? {}"
          />
        </div>
      </div>
      <aside class="side">
        <FileBrowserPanel
          :involved="impact.involved"
          :modified="impact.modified"
          :impacted="impact.impacted"
          :risk-scores="impact.riskScores ?? {}"
          group-by="ring"
        />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.ram-graph-view { padding: 12px; display: flex; flex-direction: column; gap: 12px; height: 100%; }
.topbar { display: flex; align-items: center; gap: 12px; }
.title { font-weight: 600; }
.sid { color: #909399; font-size: 12px; }
.body { display: grid; grid-template-columns: 1fr 380px; gap: 12px; flex: 1; min-height: 0; }
.canvas { position: relative; background: #fafafa; border: 1px solid #ebeef5; border-radius: 4px; overflow: hidden; }
.minimap-overlay { position: absolute; right: 12px; bottom: 12px; background: rgba(255,255,255,0.9); border-radius: 8px; padding: 4px; box-shadow: 0 1px 4px rgba(0,0,0,0.08); }
.side { border: 1px solid #ebeef5; border-radius: 4px; overflow: hidden; }
</style>
```

**Step 4: 测试通过**

Run: `cd hisi-dev-tool-frontend && npx vitest run src/views/ram/__tests__/GraphPreviewPage.spec.ts`
Expected: PASS

**Step 5: Commit**

```bash
git add hisi-dev-tool-frontend/src/views/ram/GraphPreviewPage.vue hisi-dev-tool-frontend/src/views/ram/__tests__/GraphPreviewPage.spec.ts
git commit -m "feat(ram): rewrite GraphPreviewPage as two-column DAG layout"
```

---

## Task 8：全量测试 + 类型检查 + 手动 E2E 核对

**Step 1: 全量单元测试**

Run: `cd hisi-dev-tool-frontend && npx vitest run`
Expected: 全绿（原 23 个三层环测试 + 新增 dagLayout/DagGraph/FileBrowserPanel/store/page 测试）

**Step 2: TypeScript 检查**

Run: `cd hisi-dev-tool-frontend && npx tsc --noEmit`
Expected: 0 errors

**Step 3: 生产构建**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: 构建成功，产物大小合理（dagre 增加约 60KB gzip 是预期）

**Step 4: 手动验证清单**

启动 `npm run dev`，触发一个 Impact 完成的真实 session：
- [ ] 左侧 DAG 从左到右分层显示（涉及 → 修改 → 影响）
- [ ] 节点按 inDegree 大小变化、风险高的节点偏红
- [ ] hover 节点高亮上游路径
- [ ] 点击节点 → 右侧清单滚动并高亮对应行
- [ ] 在清单搜索框输入关键字 → 列表过滤
- [ ] 切换 groupBy=package（如有 UI 入口）→ 按包分组
- [ ] 右下角小地图显示三层环概览
- [ ] 导出 CSV 文件可下载

**Step 5: Commit（如有手动改动）**

```bash
git status
# 如有改动：
git commit -am "chore(ram): final polish for impact DAG redesign"
```

---

## Task 9：PR

**Step 1: Push 分支**

```bash
git push -u origin feat/ram-impact-dag-redesign
```

**Step 2: 创建 PR**

```bash
gh pr create --title "feat(ram): impact graph redesign — layered DAG + upgraded list + minimap" --body "$(cat <<'EOF'
## Summary
- 用 `@dagrejs/dagre` 分层 DAG（LR）取代旧三层同心环作为主舞台，节点尺寸 = 入度、颜色 = riskScore 渐变、边宽 = riskScore 流量、虚线 = 跨服务调用
- 升级右侧文件清单：搜索、按包/环分组、多选、CSV 导出、与 DAG 双向 hover/click 联动
- 旧三层环降级为右下角 160×160 小地图作为方位指示器
- 通过 Pinia `useRamStore` 维护 `selectedFile` / `hoveredFile` / `highlightPath` 三态打通联动
- 全部用原生 SVG + Vue 渲染，保留 happy-dom 测试可行性，原 23 个三层环测试不动

## Test plan
- [ ] `cd hisi-dev-tool-frontend && npx vitest run` 全绿
- [ ] `npx tsc --noEmit` 0 errors
- [ ] `npm run build` 通过
- [ ] 在真实 Impact 数据下手动验证：分层正确 / hover 高亮上游 / 双向联动 / 搜索过滤 / 小地图概览 / CSV 导出
EOF
)"
```

---

## 风险与回滚

| 风险 | 缓解 |
|---|---|
| dagre 在超大图（>500 节点）布局慢 | 已留 `inDegree` 接口，可在调用方提前剪枝；后续可加 worker |
| 边路径 SVG path 在 Chrome 之外有兼容性差异 | `points` 数组 fallback 到直线 `M…L…` |
| 旧三层环用户习惯 | 保留为小地图，并通过 `Minimap.vue` 复用整个 `ThreeRingGraph.vue` 实现 |
| Pinia 联动状态泄漏到其他页面 | 在 `GraphPreviewPage` `onUnmounted` 调用 `store.selectFile(null) / clearHighlight()` |

## 时间估算

- Week 1：Task 1–4（依赖、dagLayout、store、DagGraph） — 约 3–4 工作日
- Week 2：Task 5–9（清单、小地图、页面整合、测试、PR） — 约 3 工作日

