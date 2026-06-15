<!-- Generated: 2026-05-31 | Token estimate: ~500 -->

# Data Layer Codemap

## Neo4j Graph Schema

### Node Types
| Label | Key Fields | Indexes |
|-------|-----------|---------|
| MethodNode | nodeId, className, methodName, signature, description, descriptionEmbedding, codeEmbedding, sqlEmbedding, complexity, projectPath, publicProjectPath, language | VECTOR idx_description_embedding, idx_code_embedding |
| EntryPointNode | nodeId, uri, httpMethod, type (CONTROLLER/SCHEDULED/MQ_LISTENER/FEIGN_CLIENT), className, methodName, projectPath, publicProjectPath | — |
| ServiceNode | className, projectPath, publicProjectPath | — |
| SqlNode | sqlText, type (SELECT/INSERT/UPDATE/DELETE), projectPath | — |
| DataModelNode | name, className, projectPath (for USES_MODEL edges) | — |
| GenerationCheckpointNode | projectPath, timestamp, status | — |

### Relationship Types
| Type | From → To | Meaning |
|------|----------|---------|
| CALLS | Method → Method | Method A calls method B |
| EXTENDS | Service → Service | Class inheritance |
| IMPLEMENTS | Service → Service | Interface implementation |
| HAS_SQL | Method → SqlNode | Method contains SQL |
| EXPOSES | EntryPoint → Method | Entry point exposes method |
| USES_MODEL | Method → DataModelNode | Method uses data model |

## SQLite Schema
```
~/.hisi-devtool/devtool.db
├── agent_session        RAM/merge analysis sessions (id, type, status, project_path)
├── agent_event          Event sourcing log (seq, session_id, type, payload JSON)
├── remote_project       Git repos (git_url, encrypted_password, branch, clone_status)
├── kg_schedule          Cron schedules (cron_expression, task_type, enabled)
├── conversation         Claude CLI conversations
├── message              Claude CLI messages
├── workspace            Workspace definitions
├── workspace_session    Workspace ↔ session mapping
├── skill                Installed skills
├── prompt_template      Prompt templates
├── app_settings         Key-value settings
└── glossary_term        Business glossary entries
```

## Vector Indexes (Neo4j)
| Index Name | Node | Field | Dimension | Algorithm |
|-----------|------|-------|-----------|-----------|
| idx_description_embedding | MethodNode | descriptionEmbedding | 2048 | cosine |
| idx_code_embedding | MethodNode | codeEmbedding | 2048 | cosine |
| idx_sql_embedding | SqlNode | sqlEmbedding | 2048 | cosine |
