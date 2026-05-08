# Python Call Chain Analysis Feature Design

> **Version:** 1.0.0
> **Date:** 2026-04-06
> **Author:** Architecture Team

## 1. Overview

### 1.1 Goal

Extend the existing Java call chain analysis system to support Python projects (FastAPI, Flask, Django), enabling:
1. Static analysis of Python code to generate call chains
2. Cross-service call chain tracking via HTTP bridge
3. Inline expansion of external calls in the frontend

### 1.2 Scope

| Feature | Priority | Description |
|---------|----------|-------------|
| Python AST Parsing | P0 | Parse Python files using Tree-sitter |
| FastAPI Support | P0 | Recognize @app.get(), @router.post() decorators |
| Flask Support | P1 | Recognize @app.route() decorators |
| Django Support | P1 | Parse urls.py and views.py |
| HTTP Client Detection | P0 | Detect requests/httpx/aiohttp calls |
| Inline Expansion | P0 | Frontend feature to expand external calls inline |

### 1.3 Out of Scope

- Python async call chain analysis (P2)
- gRPC support (P2)
- Celery task bridge (P2)

---

## 2. Architecture

### 2.1 Overall Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    ChainAnalysisCoordinator                      │
│                    (入口，委托给Registry)                         │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    LanguageAnalyzerRegistry                      │
│                    (语言检测与分析器选择)                          │
└─────────────────────────────────────────────────────────────────┘
                                │
                ┌───────────────┴───────────────┐
                ▼                               ▼
┌───────────────────────────┐   ┌───────────────────────────┐
│    JavaChainAnalyzer      │   │    PythonChainAnalyzer    │
│    (现有代码，零修改)       │   │    (新增)                 │
├───────────────────────────┤   ├───────────────────────────┤
│  - MQEndpointScanner      │   │  - FastAPIParser          │
│  - FeignClientScanner     │   │  - FlaskParser            │
│  - HttpCallScanner        │   │  - DjangoParser           │
│  - ProxyClassScanner      │   │  - PythonHttpCallParser   │
│  - MQChainBridge          │   │  - PythonCallGraphBuilder │
│  - HttpChainBridge        │   │                           │
│  - ProxyChainBridge       │   │                           │
└───────────────────────────┘   └───────────────────────────┘
                │                               │
                └───────────────┬───────────────┘
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Database Layer                           │
│  method_call_graph5 | http_call_bridge | mq_call_bridge        │
│  (复用现有表，通过package区分Java/Python项目)                     │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 AbstractChainAnalyzer (Template Method Pattern)

```java
public abstract class AbstractChainAnalyzer {

    // Template method - defines the analysis skeleton
    public final AnalysisResult analyze(String projectDir) {
        validateProjectDir(projectDir);
        AnalyzeContext context = createContext(projectDir);

        List<Path> sourceFiles = discoverFiles(projectDir, context);
        parseAndExtract(sourceFiles, context);      // Abstract
        buildCallGraph(context);                     // Abstract
        saveToDatabase(context);                     // Common logic
        buildBridgeTables(context);                  // Common logic
        cleanup(context);

        return buildResult(context);
    }

    // Abstract methods - subclass must implement
    protected abstract LanguageType getLanguageType();
    protected abstract List<String> getSupportedExtensions();
    protected abstract void parseAndExtract(List<Path> files, AnalyzeContext context);
    protected abstract void buildCallGraph(AnalyzeContext context);

    // Common logic - shared by all languages
    protected void saveToDatabase(AnalyzeContext context) { ... }
    protected void buildBridgeTables(AnalyzeContext context) { ... }
}
```

### 2.3 Python Parsing Strategy

Using **Tree-sitter** for Python AST parsing:

```
Python Source File
        │
        ▼
┌───────────────────┐
│   Tree-sitter     │  ← Fast, incremental parsing
│   Python Parser   │
└───────────────────┘
        │
        ▼
┌───────────────────┐
│  PythonASTParser  │  ← Convert to unified model
│  - extract_decorators()
│  - extract_function_calls()
│  - extract_imports()
└───────────────────┘
        │
        ▼
┌───────────────────┐
│ Framework Parsers │
│  - FastAPIParser  │  ← @app.get(), @router.post()
│  - FlaskParser    │  ← @app.route()
│  - DjangoParser   │  ← urls.py, views.py
└───────────────────┘
```

---

## 3. Data Model

### 3.1 Database Schema Changes

**No schema changes required.** The existing tables can store Python data:

| Table | How to distinguish |
|-------|-------------------|
| `method_call_graph5` | `package` field: Python uses `app.*`, `src.*` |
| `http_call_bridge` | `source_class`: Python module path |
| `mq_call_bridge` | Not applicable for Python (P2) |
| `service_topology` | Works as-is |

### 3.2 Python Method Signature Format

```
Java:    com.huawei.hisi.service.UserService.login:[String, String]
Python:  app.services.user_service.UserService.login
         (no type info, simpler format)
```

---

## 4. API Design

### 4.1 Backend API

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/method_chain/generate` | GET | Generate call chain (existing) |
| `/api/method_chain/generate-python` | GET | Generate Python call chain (new) |
| `/api/callchain/calls/expand-external` | GET | Expand external call inline |

### 4.2 New API: Expand External Call

```
GET /api/callchain/calls/expand-external?method={method}&targetUri={uri}

Response:
{
  "success": true,
  "data": {
    "externalCallChain": [...],
    "bridgeInfo": {
      "type": "HTTP",
      "serviceName": "user-service",
      "uriPattern": "/api/users/{id}"
    }
  }
}
```

### 4.3 Frontend Integration

```
Call Chain Node
      │
      ├── Normal method call → Click → Navigate to method
      │
      └── External call (HTTP/MQ)
              │
              ├── Show indicator icon (🌐)
              ├── Click "Expand" button
              │       │
              │       ▼
              │   API: /api/callchain/calls/expand-external
              │       │
              │       ▼
              │   Render external call chain inline (nested)
              │
              └── Double-click → Navigate to external service
```

---

## 5. Implementation Phases

### Phase 1: Core Infrastructure (Week 1)
- AbstractChainAnalyzer base class
- LanguageAnalyzerRegistry
- AnalyzeContext

### Phase 2: Python Analyzer (Week 2)
- PythonASTParser (Tree-sitter)
- FastAPIParser
- PythonCallGraphBuilder

### Phase 3: HTTP Client Detection (Week 2)
- PythonHttpCallParser
- requests/httpx/aiohttp detection
- http_call_bridge integration

### Phase 4: Frontend Enhancement (Week 3)
- External call indicator
- Inline expansion UI
- API integration

### Phase 5: Testing & Documentation (Week 3)
- Unit tests
- Integration tests
- User documentation

---

## 6. Risk Analysis

| Risk | Probability | Impact | Mitigation |
|------|-------------|--------|------------|
| Tree-sitter learning curve | Medium | Low | Use Python AST as fallback |
| Java code affected | Low | High | Zero-modification strategy |
| Performance degradation | Low | Medium | Batch processing optimization |

---

## 7. Success Criteria

1. Java call chain analysis works exactly as before (928 tests pass)
2. Python projects can be analyzed and call chains generated
3. External calls show indicator and can be expanded inline
4. All new code has >80% test coverage

---

## 8. References

- Tree-sitter: https://tree-sitter.github.io/tree-sitter/
- FastAPI: https://fastapi.tiangolo.com/
- Flask: https://flask.palletsprojects.com/
- Django: https://docs.djangoproject.com/