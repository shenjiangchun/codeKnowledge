<!-- Generated: 2026-05-31 | Token estimate: ~400 -->

# Dependencies Codemap

## Backend (pom.xml)
| Dependency | Version | Purpose |
|-----------|---------|---------|
| spring-boot-starter-web | 3.2.0 | REST API + embedded Tomcat |
| spring-boot-starter-websocket | 3.2.0 | WebSocket (Claude terminal, APM spans) |
| spring-data-neo4j | 7.x | Neo4j OGM |
| neo4j-java-driver | 5.x | Direct Cypher queries |
| javaparser | 3.25+ | Java AST parsing |
| antlr4-runtime | 4.13+ | Python AST parsing (3.8-3.12) |
| jgit | 6.x | Git operations (clone, pull, diff) |
| pty4j | 0.12+ | PTY for Claude CLI process |
| zhipu-sdk | — | Zhipu AI (GLM-4-flash, embedding-3) |
| okhttp | 4.x | HTTP client for LLM APIs |
| opentelemetry-proto | 1.x | OTLP Protobuf parsing |
| caffeine | 3.x | In-memory caching |
| micrometer | 1.x | Metrics (APM span index) |
| jackson | 2.x | JSON serialization |
| xterm.js (frontend) | 5.x | Terminal emulator |
| @dagrejs/dagre (frontend) | 1.x | FlowDag layout engine |
| marked (frontend) | 5.x | Markdown rendering |

## External Services
| Service | Protocol | Purpose |
|---------|----------|---------|
| Neo4j 5.11+ | Bolt (7687) | Graph DB + vector index |
| Zhipu AI / SiliconFlow / etc. | OpenAI-compatible REST | Text generation + embeddings |
| Claude API (via dmxapi) | REST /v1/chat/completions | APM diagnosis (isolated channel) |
| OTel Agent | OTLP/HTTP Protobuf | Trace data push |
| 日志云 | HTTP API / Playwright | Log retrieval |

## Frontend (package.json)
| Package | Purpose |
|---------|---------|
| vue 3.5+ | Core framework |
| vue-router 4.x | SPA routing |
| pinia 3.x | State management |
| element-plus 2.x | UI components |
| axios 1.x | HTTP client |
| echarts 6.x | Graph visualization (legacy, being replaced by FlowDag) |
| @dagrejs/dagre | Dagre layout for FlowDag |
| xterm + xterm-addon-fit | Terminal emulator |
| marked | Markdown → HTML |
