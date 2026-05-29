/**
 * Pure transforms for the impact Sankey visualization.
 *
 * Converts raw ring arrays (involved / modified / impacted) into a Sankey
 * graph model that ECharts can render.  No Vue, no DOM -- just data in, data
 * out.
 *
 * Imports colour helpers and the short-name formatter from the existing
 * {@link threeRingLayout} module so visuals stay consistent with the three-ring
 * view.
 */

import { riskHeatColor, RING_COLORS, shortName } from './threeRingLayout'

// ---------------------------------------------------------------------------
// Public types
// ---------------------------------------------------------------------------

export type RingColumn = 'involved' | 'modified' | 'impacted'

export interface SankeyTransformInput {
  readonly involved: readonly string[]
  readonly modified: readonly string[]
  readonly impacted: readonly string[]
  readonly riskScores?: Readonly<Record<string, number>>
}

export interface SankeyNode {
  readonly name: string
  readonly label: string
  readonly column: RingColumn
  readonly depth: number
  readonly color: string
  readonly nodeIds: readonly string[]
}

export interface SankeyLink {
  readonly source: string
  readonly target: string
  readonly value: number
}

export interface SankeyData {
  readonly nodes: readonly SankeyNode[]
  readonly links: readonly SankeyLink[]
  /** Total unique nodeId count per ring, used for column headers. */
  readonly ringCounts: Readonly<Record<RingColumn, number>>
}

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Minimum methods for a package to keep its own node; smaller groups merge. */
const COALESCE_THRESHOLD = 3

export const RING_LABELS: Readonly<Record<RingColumn, string>> = {
  involved: '受影响的入口',
  modified: '修改',
  impacted: '影响'
}

// ---------------------------------------------------------------------------
// extractPackage
// ---------------------------------------------------------------------------

/**
 * Extract a short package name from a Neo4j nodeId.
 *
 * NodeId format: `projectPath:com.foo.bar.ClassName.methodName.signatureHash`
 *
 * Algorithm:
 *   1. If the id contains `:`, take the part after the last colon (handles
 *      Windows drive-letter paths like `C:/proj:...`).
 *   2. Split by `.` and collect leading lowercase-starting segments (the
 *      package path).  Stop at the first uppercase-starting segment (the
 *      class name).
 *   3. If more than 3 package segments, keep only the last 3 for brevity.
 *   4. Join with `.` and return.  Fall back to `'(default)'` when empty.
 */
export function extractPackage(nodeId: string): string {
  if (!nodeId) return '(default)'

  const colonIdx = nodeId.lastIndexOf(':')
  const fqn = colonIdx >= 0 ? nodeId.slice(colonIdx + 1) : nodeId

  if (!fqn) return '(default)'

  const segments = fqn.split('.')
  const pkgSegments: string[] = []

  for (const seg of segments) {
    if (seg.length === 0) continue
    if (seg[0] === seg[0].toUpperCase() && seg[0] !== seg[0].toLowerCase()) {
      // Uppercase start → class name; stop collecting
      break
    }
    pkgSegments.push(seg)
  }

  if (pkgSegments.length === 0) return '(default)'

  const trimmed =
    pkgSegments.length > 3
      ? pkgSegments.slice(pkgSegments.length - 3)
      : pkgSegments

  return trimmed.join('.')
}

// ---------------------------------------------------------------------------
// transformImpactToSankey
// ---------------------------------------------------------------------------

const RING_COLUMNS: readonly { column: RingColumn; depth: number }[] = [
  { column: 'involved', depth: 0 },
  { column: 'modified', depth: 1 },
  { column: 'impacted', depth: 2 }
]

const ADJACENT_PAIRS: readonly [RingColumn, RingColumn][] = [
  ['involved', 'modified'],
  ['modified', 'impacted']
]

function pickRingArray(
  input: SankeyTransformInput,
  column: RingColumn
): readonly string[] {
  switch (column) {
    case 'involved':
      return input.involved
    case 'modified':
      return input.modified
    case 'impacted':
      return input.impacted
  }
}

/**
 * Transform raw ring arrays into a Sankey graph model.
 *
 * Each unique package within a ring becomes a {@link SankeyNode}; links are
 * created between adjacent rings that share the same package name.
 *
 * Packages with fewer than {@link COALESCE_THRESHOLD} methods are merged into
 * a "(其他)" bucket per ring to reduce visual clutter.
 */
export function transformImpactToSankey(
  input: SankeyTransformInput
): SankeyData {
  const riskScores = input.riskScores ?? {}

  // Maps: column → packageName → SankeyNode
  const columnPkgMap = new Map<RingColumn, Map<string, SankeyNode>>()
  const allNodes: SankeyNode[] = []
  const ringCounts: Record<RingColumn, number> = {
    involved: 0,
    modified: 0,
    impacted: 0
  }

  for (const { column, depth } of RING_COLUMNS) {
    const raw = pickRingArray(input, column)
    const unique = [...new Set(raw)]
    ringCounts[column] = unique.length

    // Group by package
    const groups = new Map<string, string[]>()
    for (const id of unique) {
      const pkg = extractPackage(id)
      const arr = groups.get(pkg)
      if (arr) {
        arr.push(id)
      } else {
        groups.set(pkg, [id])
      }
    }

    // Coalesce small groups into "(其他)" when total groups > 2
    const needCoalesce = groups.size > 2
    const coalescedIds: string[] = []
    let coalescedMaxRisk = 0

    const pkgNodeMap = new Map<string, SankeyNode>()
    for (const [pkg, nodeIds] of groups) {
      if (needCoalesce && nodeIds.length < COALESCE_THRESHOLD) {
        coalescedIds.push(...nodeIds)
        coalescedMaxRisk = nodeIds.reduce(
          (mx, id) => Math.max(mx, riskScores[id] ?? 0),
          coalescedMaxRisk
        )
        continue
      }

      const maxRisk = nodeIds.reduce(
        (mx, id) => Math.max(mx, riskScores[id] ?? 0),
        0
      )
      const color =
        maxRisk > 0 ? riskHeatColor(maxRisk) : RING_COLORS[column]

      const node: SankeyNode = {
        name: `${column}::${pkg}`,
        label: `${pkg} (${nodeIds.length})`,
        column,
        depth,
        color,
        nodeIds: [...nodeIds]
      }

      pkgNodeMap.set(pkg, node)
      allNodes.push(node)
    }

    // Add coalesced "(其他)" node if any
    if (coalescedIds.length > 0) {
      const otherPkg = '(其他)'
      const color =
        coalescedMaxRisk > 0
          ? riskHeatColor(coalescedMaxRisk)
          : RING_COLORS[column]
      const node: SankeyNode = {
        name: `${column}::${otherPkg}`,
        label: `其他 (${coalescedIds.length})`,
        column,
        depth,
        color,
        nodeIds: [...coalescedIds]
      }
      pkgNodeMap.set(otherPkg, node)
      allNodes.push(node)
    }

    columnPkgMap.set(column, pkgNodeMap)
  }

  // Build links between adjacent rings that share a package name
  const allLinks: SankeyLink[] = []
  for (const [srcCol, tgtCol] of ADJACENT_PAIRS) {
    const srcMap = columnPkgMap.get(srcCol)!
    const tgtMap = columnPkgMap.get(tgtCol)!

    for (const [pkg, srcNode] of srcMap) {
      if (tgtMap.has(pkg)) {
        allLinks.push({
          source: srcNode.name,
          target: `${tgtCol}::${pkg}`,
          value: srcNode.nodeIds.length
        })
      }
    }
  }

  return { nodes: allNodes, links: allLinks, ringCounts }
}

// ---------------------------------------------------------------------------
// buildSankeyOption
// ---------------------------------------------------------------------------

/**
 * Build an ECharts option object for a Sankey chart from the given
 * {@link SankeyData}.
 *
 * Includes:
 * - Three column header graphic elements showing ring labels + counts
 * - Node labels with package name + method count
 * - Gradient links with adjacency emphasis
 * - Rich tooltip showing methods
 */
export function buildSankeyOption(
  data: SankeyData,
  width: number,
  _height: number
): Record<string, unknown> {
  const leftMargin = 90
  const rightMargin = 90
  const topMargin = 56
  const usableWidth = width - leftMargin - rightMargin

  // Column header positions: depth 0, 1, 2 → evenly spaced
  const columnHeaders = RING_COLUMNS.map(({ column, depth }) => {
    const x = leftMargin + (usableWidth * depth) / 2
    const count = data.ringCounts[column]
    return {
      type: 'text' as const,
      left: x,
      top: 8,
      style: {
        text: `${RING_LABELS[column]}  ${count} 个方法`,
        fontSize: 13,
        fontWeight: 'bold' as const,
        fill: RING_COLORS[column]
      }
    }
  })

  return {
    graphic: { elements: columnHeaders },
    tooltip: {
      trigger: 'item',
      confine: true,
      formatter: (params: any) => {
        if (params.dataType === 'node') {
          const node = data.nodes.find((n) => n.name === params.name)
          if (!node) return ''
          const methods = node.nodeIds
            .slice(0, 5)
            .map((id) => shortName(id))
          const more =
            node.nodeIds.length > 5
              ? `<br/><span style="color:#909399">... +${node.nodeIds.length - 5} more</span>`
              : ''
          return (
            `<b style="font-size:13px">${node.label}</b><br/>` +
            `<span style="color:${node.color}">■</span> ${RING_LABELS[node.column]}<br/>` +
            `<hr style="margin:4px 0;border-color:#eee"/>` +
            methods.map((m) => `<span style="color:#606266">· ${m}</span>`).join('<br/>') +
            more
          )
        }
        if (params.dataType === 'edge') {
          const src = params.data.source.split('::')[1]
          const tgt = params.data.target.split('::')[1]
          return (
            `<b>${src}</b> → <b>${tgt}</b><br/>` +
            `${params.data.value} 个方法流转`
          )
        }
        return ''
      }
    },
    series: [
      {
        type: 'sankey',
        orient: 'horizontal',
        layoutIterations: 0,
        left: leftMargin,
        right: rightMargin,
        top: topMargin,
        bottom: 20,
        nodeWidth: 22,
        nodeGap: 14,
        label: {
          show: true,
          fontSize: 11,
          color: '#303133',
          fontWeight: 500
        },
        emphasis: { focus: 'adjacency' },
        lineStyle: { color: 'gradient', opacity: 0.35 },
        data: data.nodes.map((n) => ({
          name: n.name,
          depth: n.depth,
          itemStyle: {
            color: n.color,
            borderColor: n.color,
            borderWidth: 1
          },
          label: { formatter: n.label }
        })),
        links: data.links.map((l) => ({
          source: l.source,
          target: l.target,
          value: l.value
        }))
      }
    ]
  }
}
