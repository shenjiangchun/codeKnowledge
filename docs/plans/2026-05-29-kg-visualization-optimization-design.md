# Knowledge Graph Visualization Optimization Design

**Date**: 2026-05-29
**Scope**: Frontend only (no backend API changes)

## Problem

1. DAG view in ChainChart.vue uses ECharts force-directed graph -- blue circles + random layout, unreadable
2. Upstream analysis shows only direct callers in flat table, discards root entry data
3. Downstream call chain tree lacks natural language descriptions
4. Multi-entry queries render independently, no coupling visibility

## Technical Approach

**Rendering**: Hand-drawn SVG + `@dagrejs/dagre` hierarchical layout
**Rationale**: Project already has mature SVG+dagre pattern in RAM module (DagGraph.vue). foreignObject allows full HTML cards. Avoids ECharts node customization limits and Vue Flow CSS conflicts.

## Feature 1: Replace DAG View with Hierarchical Flowchart

### What changes

- New `FlowDag.vue` component replaces ECharts graph in ChainChart.vue `dag` view mode
- New `flowDagLayout.ts` for dagre layout computation

### Node card design

```
+---------------------------------------+
| save()                    [MAPPER]    |
| OrderService                          |
| 保存订单并发送通知                      |
+---------------------------------------+
```

- Top: method short name + call type tag (colored by bridge type)
- Middle: class name (gray, smaller)
- Bottom: natural language description (from `description` field, if available)

### Layout

- dagre direction: `TB` (top-to-bottom)
- Node dimensions: 220px wide, height auto-sized by content
- Edges: SVG path with arrowhead markers, colored by call type
- Interaction: SVG viewBox zoom/pan, click for details, right-click context menu

### Unchanged

- tree/flow/list view modes in ChainChart.vue remain untouched

## Feature 2: Upstream Query Visualization

### What changes

- `MethodReferenceGraph.vue` upstream section renders FlowDag instead of flat table
- Uses existing `getRootEntries` API data (both rootEntries and directCallers)
- Layout direction: `BT` (bottom-to-top) -- query target at bottom, root entries at top

### Entry point nodes

- Double-border style + entry type icon (Controller/MQ/Scheduled etc.)
- Distinguished from regular method nodes

### Flat table preserved as optional list view

## Feature 3: Multi-Entry Merged Graph with Coupling Markers

### What changes

- New `mergeGraphs.ts` utility for merging multiple `CallChainGraphData` results
- Each node tracks `sources: Set<entryId>` -- which entries' call chains include it

### Merge logic

1. Query each entry method's downstream call chain independently
2. Merge nodes by dedup on `id`, merge edges by dedup on `source+target`
3. Compute `sources` set per node

### Rendering rules

- **Single-entry nodes**: solid border in that entry's assigned color
- **Multi-entry shared nodes (coupling points)**: multi-color gradient border + corner badge showing count, hover tooltip lists which entries
- **Entry point nodes**: highlighted with double border, larger font, entry type icon
- **Legend panel**: shows each entry's color, click to toggle visibility

## New Files

| File | Purpose |
|---|---|
| `views/call-chain/components/FlowDag.vue` | Core flowchart component (SVG + foreignObject + dagre) |
| `views/call-chain/components/flowDagLayout.ts` | dagre layout computation |
| `views/call-chain/components/mergeGraphs.ts` | Multi-entry graph merge + coupling detection |

## Modified Files

| File | Change |
|---|---|
| `ChainChart.vue` | dag view branch uses FlowDag.vue |
| `MethodReferenceGraph.vue` | Upstream uses FlowDag; downstream multi-entry adds merge logic |

## Not Changed

- Backend APIs: no interface signature changes
- ChainChart tree/flow/list views
- RAM module (DagGraph, DagFlow, ThreeRingGraph, ImpactSankeyGraph)
- Other consumers of getCalleesTree/getRootEntries (APM Debug, EntryDetail)
