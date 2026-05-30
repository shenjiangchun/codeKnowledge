import type { GraphNode, GraphEdge, CallChainGraphData } from '@/api/knowledgeGraph'

export interface MergedGraph {
  readonly nodes: GraphNode[]
  readonly edges: GraphEdge[]
  readonly entrySources: Map<string, Set<string>>
  readonly entryLabels: Map<string, string>
  readonly entryColors: Map<string, string>
}

const PALETTE = [
  '#409eff', '#67c23a', '#e6a23c', '#f56c6c',
  '#9b59b6', '#1abc9c', '#e74c3c', '#3498db',
] as const

export function mergeCallChainGraphs(
  graphs: readonly { entryFqn: string; data: CallChainGraphData }[],
): MergedGraph {
  const nodeMap = new Map<string, GraphNode>()
  const edgeMap = new Map<string, GraphEdge>()
  const entrySources = new Map<string, Set<string>>()
  const entryLabels = new Map<string, string>()
  const entryColors = new Map<string, string>()

  for (let i = 0; i < graphs.length; i++) {
    const { entryFqn, data } = graphs[i]
    const entryId = data.entryId

    entryLabels.set(entryId, entryFqn)
    entryColors.set(entryId, PALETTE[i % PALETTE.length])

    for (const node of data.nodes) {
      if (!nodeMap.has(node.id)) {
        nodeMap.set(node.id, node)
      }
      let sources = entrySources.get(node.id)
      if (!sources) {
        sources = new Set()
        entrySources.set(node.id, sources)
      }
      sources.add(entryId)
    }

    for (const edge of data.edges) {
      const key = `${edge.source}|${edge.target}`
      if (!edgeMap.has(key)) {
        edgeMap.set(key, edge)
      }
    }
  }

  return {
    nodes: Array.from(nodeMap.values()),
    edges: Array.from(edgeMap.values()),
    entrySources,
    entryLabels,
    entryColors,
  }
}
