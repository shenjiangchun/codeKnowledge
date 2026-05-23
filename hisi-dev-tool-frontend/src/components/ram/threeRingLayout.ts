/**
 * Pure helpers for the three-ring impact visualization.
 *
 * Extracted from {@code ThreeRingGraph.vue} so the layout math can be unit
 * tested without mounting an SVG. Keeping these pure functions also means the
 * component template stays declarative and the colour / radius constants live
 * in one place.
 */

export type RingKey = 'involved' | 'modified' | 'impacted'

export const RING_COLORS: Readonly<Record<RingKey, string>> = {
  involved: '#FFD700',
  modified: '#FF8C00',
  impacted: '#B0B0B0'
}

export interface RingDef {
  readonly key: RingKey
  readonly label: string
  readonly color: string
  readonly files: readonly string[]
}

export interface NodePoint {
  readonly file: string
  readonly cx: number
  readonly cy: number
  readonly ring: RingKey
  readonly color: string
  readonly radius: number
  readonly risk: number
}

export interface InterRingEdge {
  readonly from: string
  readonly to: string
  readonly x1: number
  readonly y1: number
  readonly x2: number
  readonly y2: number
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
}

export interface LayoutOutput {
  readonly center: { x: number; y: number }
  readonly radii: Readonly<Record<RingKey, number>>
  readonly rings: readonly RingDef[]
  readonly nodes: readonly NodePoint[]
  readonly edges: readonly InterRingEdge[]
}

const NODE_BASE_R = 7
const NODE_MAX_R = 14

export function computeLayout(input: LayoutInput): LayoutOutput {
  const center = { x: input.width / 2, y: input.height / 2 }
  const outer = Math.min(input.width, input.height) / 2 - 32
  const radii: Record<RingKey, number> = {
    involved: outer * 0.32,
    modified: outer * 0.62,
    impacted: outer * 0.94
  }
  const rings: RingDef[] = [
    { key: 'involved', label: '涉及', color: RING_COLORS.involved, files: input.involved },
    { key: 'modified', label: '修改', color: RING_COLORS.modified, files: input.modified },
    { key: 'impacted', label: '影响', color: RING_COLORS.impacted, files: input.impacted }
  ]
  const risk = input.riskScores ?? {}

  const nodeByFile = new Map<string, NodePoint>()
  const nodes: NodePoint[] = []
  for (const ring of rings) {
    const radius = radii[ring.key]
    const n = ring.files.length
    ring.files.forEach((file, i) => {
      const angle = n === 0 ? 0 : (2 * Math.PI * i) / n - Math.PI / 2
      const r = risk[file] ?? 0
      const node: NodePoint = {
        file,
        cx: center.x + radius * Math.cos(angle),
        cy: center.y + radius * Math.sin(angle),
        ring: ring.key,
        color: r > 0 ? riskHeatColor(r) : ring.color,
        radius: NODE_BASE_R + (NODE_MAX_R - NODE_BASE_R) * Math.max(0, Math.min(1, r)),
        risk: r
      }
      nodes.push(node)
      nodeByFile.set(`${ring.key}:${file}`, node)
    })
  }

  // Inter-ring edges: connect a file that appears in both involved↔modified or
  // modified↔impacted (same FQN/path string). Surfaces the propagation flow
  // between rings — a key piece of "why is this in the impacted ring".
  const edges: InterRingEdge[] = []
  for (const file of input.modified) {
    const a = nodeByFile.get(`involved:${file}`)
    const b = nodeByFile.get(`modified:${file}`)
    if (a && b) edges.push({ from: file, to: file, x1: a.cx, y1: a.cy, x2: b.cx, y2: b.cy })
  }
  for (const file of input.impacted) {
    const a = nodeByFile.get(`modified:${file}`)
    const b = nodeByFile.get(`impacted:${file}`)
    if (a && b) edges.push({ from: file, to: file, x1: a.cx, y1: a.cy, x2: b.cx, y2: b.cy })
  }

  return { center, radii, rings, nodes, edges }
}

export function truncate(label: string, max = 18): string {
  return label.length > max ? `${label.slice(0, max - 1)}…` : label
}
