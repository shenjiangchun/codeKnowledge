<!-- Generated: 2026-05-31 | Views: 16 | Token estimate: ~700 -->

# Frontend Codemap

## Page Tree
```
src/views/
├── HomeView.vue                     Landing / dashboard
├── knowledge-graph/                 ★ KG management + semantic search
│   ├── KnowledgeGraphView.vue       Main page with tabs
│   └── components/
│       ├── SemanticSearchPanel.vue   Hybrid search input + results
│       ├── GraphExplorerTab.vue      Browse by entry-type / class / search
│       ├── GraphVisualization.vue    Neo4j graph viz (ECharts)
│       └── VectorGenerationPanel.vue Trigger embedding generation
├── call-chain/                      ★ Call chain visualization
│   ├── CallChainGraph.vue           URI → call chain (4 view modes)
│   ├── MethodReferenceGraph.vue     Multi-method upstream/downstream
│   └── components/
│       ├── FlowDag.vue              SVG flowchart (dagre layout, bridge colors)
│       ├── flowDagLayout.ts         dagre coordinate computation
│       ├── mergeGraphs.ts           Multi-entry merge + coupling detection
│       └── ChainChart.vue           Tree/flow/list/dag view switcher
├── ram/                             ★ Requirements Analysis Module
│   ├── RamView.vue                  Main RAM page
│   └── components/
│       ├── ImpactOutputView.vue     Impact analysis result renderer
│       └── ...                      Clarify/Implement/Verify views
├── apm-debug/                       ★ APM Trace analysis
│   ├── ApmDebugView.vue             Debug session management
│   └── components/
│       └── ...                      Span table, diagnosis report
├── merge-analysis/                  ★ Merge impact analysis
│   ├── InputPage.vue                Branch selection
│   ├── DiffPreviewPage.vue          Diff viewer
│   ├── AnalysisPage.vue             SSE streaming + step progress
│   └── components/
│       └── useMergeAnalysisSession   SSE composable
├── project/                         Project management (local + remote)
├── log-analysis/                    Log query + AI analysis
├── claude-session/                  Claude CLI terminal
├── claude-terminal/                 Claude terminal (xterm.js)
├── skill-market/                    Skill marketplace
├── mcp/                             MCP configuration
├── prompt-config/                   Prompt templates
├── settings/                        App settings
├── search/                          Global search (legacy)
├── glossary/                        Business glossary
└── AboutView.vue                    About page
```

## State Management (Pinia)
```
stores/
├── knowledgeGraph.ts    Project list, scan state
├── search.ts           Search results, history
├── session.ts          Claude sessions
├── settings.ts         App settings
└── mergeAnalysis.ts    Merge analysis session state
```

## API Layer
```
api/
├── index.ts              Axios instance + interceptors (unwraps ApiResponse<T>)
├── knowledgeGraph.ts     /api/knowledge-graph/*
├── vectorSearch.ts       /api/vector-search
├── vectorGeneration.ts   /api/vector-generation/*
├── callChain.ts          /api/callchain/*
├── logAnalysis.ts        /api/log/*
├── project.ts            /api/projects/*
├── remoteProject.ts      /api/remote-projects/*
├── mergeAnalysis.ts      /api/merge-analysis/*
├── ram.ts                /api/ram/*
├── apm.ts                /api/apm/*
├── ops.ts                /api/ops/*
├── git.ts                /api/git/*
├── mcp.ts                /api/mcp/*
├── skillMarket.ts        /api/skill/*
├── claude.ts             Claude API
├── session.ts            /api/session/*
└── workspaceSession.ts   /api/workspace-session/*
```

## Key Components
| Component | Location | Purpose |
|-----------|----------|---------|
| FlowDag.vue | call-chain/components/ | SVG flowchart with dagre layout |
| mergeGraphs.ts | call-chain/components/ | Multi-entry graph merge + coupling |
| GraphExplorerTab.vue | knowledge-graph/components/ | 3-mode method browser |
| ImpactOutputView.vue | ram/components/ | Risk badge + method table + entries |
| SemanticSearchPanel.vue | knowledge-graph/components/ | 9-strategy search UI |
