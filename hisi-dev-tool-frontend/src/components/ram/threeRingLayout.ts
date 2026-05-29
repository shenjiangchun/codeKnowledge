/**
 * Pure helpers for the three-ring impact visualization.
 *
 * Extracted from {@code ThreeRingGraph.vue} so the layout math can be unit
 * tested without mounting an SVG. Keeping these pure functions also means the
 * component template stays declarative and the colour / radius constants live
 * in one place.
 *
 * Density handling: a single ring can carry 100+ files in real impact
 * analyses, which produces an unreadable mass of dots. The layout caps each
 * ring at {@link MAX_NODES_PER_RING} (top-N by risk, then by lexical order)
 * and surfaces the remaining count via {@link LayoutOutput.overflow} so the
 * view can render a "+N 个" badge next to the ring label. Per-node labels are
 * only emitted ({@link NodePoint.showLabel}) when the ring is sparse enough
 * that text won't overlap.
 */

export type RingKey = 'involved' | 'modified' | 'impacted'

export const RING_COLORS: Readonly<Record<RingKey, string>> = {
  involved: '#FFD700',
  modified: '#FF8C00',
  impacted: '#B0B0B0'
}

/** Hard cap on rendered nodes per ring. Anything beyond becomes "+N 个". */
export const MAX_NODES_PER_RING = 24

/** Above this density, per-node text labels are suppressed (still in <title>). */
export const LABEL_DENSITY_LIMIT = 16

export interface RingDef {
  readonly key: RingKey
  readonly label: string
  readonly color: string
  readonly files: readonly string[]
  /** Total number of files in this ring before capping. */
  readonly totalCount: number
  /** Number of files hidden because of the cap (0 when all rendered). */
  readonly hiddenCount: number
}

export interface NodePoint {
  readonly file: string
  /** Short label used when a label is rendered (last path segment, no ext). */
  readonly shortLabel: string
  readonly cx: number
  readonly cy: number
  readonly ring: RingKey
  readonly color: string
  readonly radius: number
  readonly risk: number
  /** Whether the SVG should render a text label below this node. */
  readonly showLabel: boolean
}

export interface InterRingEdge {
  readonly from: string
  readonly to: string
  readonly x1: number
  readonly y1: number
  readonly x2: number
  readonly y2: number
}

export interface RingOverflowMarker {
  readonly ring: RingKey
  readonly hiddenCount: number
  /** Position for the "+N 个" badge (sits just outside the ring at 4 o'clock). */
  readonly cx: number
  readonly cy: number
}

/**
 * Linear interpolate between green → orange → red based on a 0..1 risk value.
 * Below 0.5 maps green→orange; above maps orange→red.
 */
export function riskHeatColor(score: number): string {
  const s = Math.max(0, Math.min(1, score))
  if (s <= 0.5) {
    return mix('#67C23A', '#E6A23C', s / 0.5)
  }
  return mix('#E6A23C', '#F56C6C', (s - 0.5) / 0.5)
}

function mix(a: string, b: string, t: number): string {
  const ar = parseInt(a.slice(1, 3), 16)
  const ag = parseInt(a.slice(3, 5), 16)
  const ab = parseInt(a.slice(5, 7), 16)
  const br = parseInt(b.slice(1, 3), 16)
  const bg = parseInt(b.slice(3, 5), 16)
  const bb = parseInt(b.slice(5, 7), 16)
  const r = Math.round(ar + (br - ar) * t)
  const g = Math.round(ag + (bg - ag) * t)
  const bl = Math.round(ab + (bb - ab) * t)
  return `#${[r, g, bl].map((v) => v.toString(16).padStart(2, '0')).join('')}`
}

export interface LayoutInput {
  readonly width: number
  readonly height: number
  readonly involved: readonly string[]
  readonly modified: readonly string[]
  readonly impacted: readonly string[]
  readonly riskScores?: Readonly<Record<string, number>>
  /** Optional override for the per-ring node cap (mainly for tests). */
  readonly maxNodesPerRing?: number
}

export interface LayoutOutput {
  readonly center: { x: number; y: number }
  readonly radii: Readonly<Record<RingKey, number>>
  readonly rings: readonly RingDef[]
  readonly nodes: readonly NodePoint[]
  readonly edges: readonly InterRingEdge[]
  readonly overflow: readonly RingOverflowMarker[]
}

const NODE_BASE_R = 6
const NODE_MAX_R = 12

/**
 * Pick the top-N most relevant files for a ring. Highest risk wins, ties
 * broken by lexical order so the layout is deterministic across renders.
 */
function pickTopFiles(
  files: readonly string[],
  riskScores: Readonly<Record<string, number>>,
  cap: number
): readonly string[] {
  if (files.length <= cap) return files
  const ranked = [...files].sort((a, b) => {
    const ra = riskScores[a] ?? 0
    const rb = riskScores[b] ?? 0
    if (rb !== ra) return rb - ra
    return a.localeCompare(b)
  })
  return ranked.slice(0, cap)
}

export function computeLayout(input: LayoutInput): LayoutOutput {
  const center = { x: input.width / 2, y: input.height / 2 }
  const outer = Math.min(input.width, input.height) / 2 - 32
  const radii: Record<RingKey, number> = {
    involved: outer * 0.32,
    modified: outer * 0.62,
    impacted: outer * 0.94
  }
  const cap = Math.max(1, input.maxNodesPerRing ?? MAX_NODES_PER_RING)
  const risk = input.riskScores ?? {}

  const rawRings: { key: RingKey; label: string; color: string; all: readonly string[] }[] = [
    { key: 'involved', label: '受影响的入口', color: RING_COLORS.involved, all: input.involved },
    { key: 'modified', label: '修改', color: RING_COLORS.modified, all: input.modified },
    { key: 'impacted', label: '影响', color: RING_COLORS.impacted, all: input.impacted }
  ]

  const rings: RingDef[] = rawRings.map((r) => {
    const visible = pickTopFiles(r.all, risk, cap)
    return {
      key: r.key,
      label: r.label,
      color: r.color,
      files: visible,
      totalCount: r.all.length,
      hiddenCount: Math.max(0, r.all.length - visible.length)
    }
  })

  const nodeByFile = new Map<string, NodePoint>()
  const nodes: NodePoint[] = []
  for (const ring of rings) {
    const radius = radii[ring.key]
    const n = ring.files.length
    const showLabel = n > 0 && n <= LABEL_DENSITY_LIMIT
    ring.files.forEach((file, i) => {
      const angle = n === 0 ? 0 : (2 * Math.PI * i) / n - Math.PI / 2
      const r = risk[file] ?? 0
      const node: NodePoint = {
        file,
        shortLabel: shortName(file),
        cx: center.x + radius * Math.cos(angle),
        cy: center.y + radius * Math.sin(angle),
        ring: ring.key,
        color: r > 0 ? riskHeatColor(r) : ring.color,
        radius: NODE_BASE_R + (NODE_MAX_R - NODE_BASE_R) * Math.max(0, Math.min(1, r)),
        risk: r,
        showLabel
      }
      nodes.push(node)
      nodeByFile.set(`${ring.key}:${file}`, node)
    })
  }

  // Inter-ring edges: connect a file that appears in both involved↔modified or
  // modified↔impacted (same FQN/path string). Surfaces the propagation flow
  // between rings — a key piece of "why is this in the impacted ring". Only
  // emitted when both endpoints survived the per-ring cap.
  const edges: InterRingEdge[] = []
  for (const ring of rings) {
    if (ring.key === 'involved') continue
    const prevKey: RingKey = ring.key === 'modified' ? 'involved' : 'modified'
    for (const file of ring.files) {
      const a = nodeByFile.get(`${prevKey}:${file}`)
      const b = nodeByFile.get(`${ring.key}:${file}`)
      if (a && b) edges.push({ from: file, to: file, x1: a.cx, y1: a.cy, x2: b.cx, y2: b.cy })
    }
  }

  // Overflow badges — placed slightly outside the ring, at the 4 o'clock
  // position so they don't collide with the top-of-ring label.
  const overflow: RingOverflowMarker[] = []
  const badgeAngle = Math.PI / 4 // 45° below horizontal-right
  for (const ring of rings) {
    if (ring.hiddenCount <= 0) continue
    const r = radii[ring.key] + 14
    overflow.push({
      ring: ring.key,
      hiddenCount: ring.hiddenCount,
      cx: center.x + r * Math.cos(badgeAngle),
      cy: center.y + r * Math.sin(badgeAngle)
    })
  }

  return { center, radii, rings, nodes, edges, overflow }
}

/**
 * Reduce a path / FQN to a compact label. Picks the last segment after
 * '/' or '.', strips a trailing extension. Falls back to truncated original.
 *
 * Examples:
 *   "com.foo.bar.OrderService#create" → "OrderService#create"
 *   "src/main/java/com/foo/Order.java" → "Order"
 */
export function shortName(file: string): string {
  if (!file) return ''
  // Slash-separated path → take basename then strip extension.
  if (file.includes('/') || file.includes('\\')) {
    const base = file.split(/[/\\]/).pop() ?? file
    const dot = base.lastIndexOf('.')
    const stem = dot > 0 ? base.slice(0, dot) : base
    return truncate(stem, 22)
  }
  // FQN with '#method' → keep last "ClassName#method" segment.
  const hashIdx = file.indexOf('#')
  if (hashIdx >= 0) {
    const cls = file.slice(0, hashIdx)
    const method = file.slice(hashIdx) // includes '#'
    const lastDot = cls.lastIndexOf('.')
    const shortCls = lastDot >= 0 ? cls.slice(lastDot + 1) : cls
    return truncate(`${shortCls}${method}`, 22)
  }
  // FQN without '#' → take last dot segment.
  const lastDot = file.lastIndexOf('.')
  if (lastDot >= 0 && lastDot < file.length - 1) {
    return truncate(file.slice(lastDot + 1), 22)
  }
  return truncate(file, 22)
}

export function truncate(label: string, max = 18): string {
  return label.length > max ? `${label.slice(0, max - 1)}…` : label
}
