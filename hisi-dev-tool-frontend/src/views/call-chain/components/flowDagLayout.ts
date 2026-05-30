import dagre from '@dagrejs/dagre'
import type { GraphNode, GraphEdge, BridgeType } from '@/api/knowledgeGraph'

export interface FlowNode {
  readonly id: string
  readonly x: number
  readonly y: number
  readonly width: number
  readonly height: number
  readonly name: string
  readonly className: string
  readonly description?: string
  readonly bridgeType?: BridgeType
  readonly callType: string
  readonly depth: number
  readonly isEntry: boolean
  readonly inCycle: boolean
  readonly sources: ReadonlySet<string>
}

export interface FlowEdge {
  readonly from: string
  readonly to: string
  readonly callType: string
  readonly isCycleEdge: boolean
  readonly points: readonly { readonly x: number; readonly y: number }[]
}

export interface FlowLayout {
  readonly nodes: readonly FlowNode[]
  readonly edges: readonly FlowEdge[]
  readonly width: number
  readonly height: number
}

export const BRIDGE_COLORS: Readonly<Record<BridgeType, string>> = {
  MAPPER: '#67c23a',
  JPA: '#409eff',
  MQ: '#e6a23c',
  FEIGN: '#f56c6c',
  HTTP: '#f5d44d',
  ASPECT: '#b37feb',
  DIRECT: '#909399',
} as const

const NODE_W = 220
const NODE_H_BASE = 56
const NODE_H_DESC = 80

export function computeFlowLayout(
  nodes: readonly GraphNode[],
  edges: readonly GraphEdge[],
  direction: 'TB' | 'BT' = 'TB',
  entrySources?: ReadonlyMap<string, ReadonlySet<string>>,
): FlowLayout {
  const g = new dagre.graphlib.Graph({ directed: true })
  g.setGraph({ rankdir: direction, nodesep: 40, ranksep: 60, marginx: 30, marginy: 30 })
  g.setDefaultEdgeLabel(() => ({}))

  for (const n of nodes) {
    const h = n.description ? NODE_H_DESC : NODE_H_BASE
    g.setNode(n.id, { width: NODE_W, height: h })
  }

  for (const e of edges) {
    g.setEdge(e.source, e.target)
  }

  dagre.layout(g)

  const entryIds = new Set<string>()
  for (const n of nodes) {
    if (n.depth === 0) entryIds.add(n.id)
  }

  const flowNodes: FlowNode[] = nodes.map((n) => {
    const laid = g.node(n.id)
    const h = n.description ? NODE_H_DESC : NODE_H_BASE
    return {
      id: n.id,
      x: laid?.x ?? 0,
      y: laid?.y ?? 0,
      width: NODE_W,
      height: h,
      name: n.name,
      className: n.className,
      description: n.description,
      callType: n.callType,
      depth: n.depth,
      isEntry: entryIds.has(n.id),
      inCycle: n.inCycle,
      sources: entrySources?.get(n.id) ?? new Set<string>(),
    }
  })

  const flowEdges: FlowEdge[] = edges.map((e) => {
    const ge = g.edge(e.source, e.target)
    return {
      from: e.source,
      to: e.target,
      callType: e.callType,
      isCycleEdge: e.isCycleEdge,
      points: ge?.points ?? [],
    }
  })

  const graphInfo = g.graph()
  return {
    nodes: flowNodes,
    edges: flowEdges,
    width: graphInfo.width ?? 0,
    height: graphInfo.height ?? 0,
  }
}

export function buildEdgePath(points: readonly { readonly x: number; readonly y: number }[]): string {
  if (points.length === 0) return ''
  if (points.length === 1) return `M${points[0].x},${points[0].y}`

  const parts: string[] = [`M${points[0].x},${points[0].y}`]

  for (let i = 1; i < points.length - 1; i++) {
    const curr = points[i]
    const next = points[i + 1]
    const cx = curr.x
    const cy = curr.y
    const ex = (curr.x + next.x) / 2
    const ey = (curr.y + next.y) / 2
    if (i === 1) {
      parts.push(`Q${cx},${cy} ${ex},${ey}`)
    } else {
      parts.push(`T${ex},${ey}`)
    }
  }

  const last = points[points.length - 1]
  parts.push(`L${last.x},${last.y}`)

  return parts.join(' ')
}

export function getNodeColor(node: FlowNode): string {
  if (node.bridgeType) return BRIDGE_COLORS[node.bridgeType]
  if (node.inCycle) return '#f0a020'
  return '#409eff'
}
