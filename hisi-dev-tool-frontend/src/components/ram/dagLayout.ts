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
