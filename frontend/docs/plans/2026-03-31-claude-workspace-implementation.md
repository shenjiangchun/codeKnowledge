# Claude 工作台实现计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 将 ClaudeSession 和 ClaudeTerminal 合并为统一的 Claude 工作台页面，利用 Claude CLI --resume 恢复会话历史。

**Architecture:** WebSocket + PTY4J 启动本地 Claude CLI，前端 xterm.js 渲染终端，会话列表管理多个终端会话，切换时用 --resume 恢复。

**Tech Stack:** Spring Boot WebSocket, PTY4J 0.13.10, Vue 3, xterm.js, Pinia

---

## Task 1: 后端 - 创建 ClaudeWorkspaceSession 实体

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/model/ClaudeWorkspaceSession.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/repository/ClaudeWorkspaceSessionRepository.java`

**Step 1: 创建实体类**

```java
package com.huawei.hisi.model;

import jakarta.persistence.*;
import lombok.Data;
import java.util.Date;

@Entity
@Table(name = "claude_workspace_session")
@Data
public class ClaudeWorkspaceSession {
    @Id
    private String id;

    @Column(unique = true)
    private String claudeSessionId;

    private String title;
    private String scene;
    private String status;
    private Date createdAt;
    private Date updatedAt;
    private String workingDirectory;
    private String initialPrompt;
}
```

**Step 2: 创建 Repository**

```java
package com.huawei.hisi.repository;

import com.huawei.hisi.model.ClaudeWorkspaceSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface ClaudeWorkspaceSessionRepository extends JpaRepository<ClaudeWorkspaceSession, String> {
    List<ClaudeWorkspaceSession> findByStatus(String status);
    ClaudeWorkspaceSession findByClaudeSessionId(String claudeSessionId);
}
```

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/model/ClaudeWorkspaceSession.java src/main/java/com/huawei/hisi/repository/ClaudeWorkspaceSessionRepository.java
git commit -m "feat: add ClaudeWorkspaceSession entity and repository"
```

---

## Task 2: 后端 - 创建 WorkspaceSessionService

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/WorkspaceSessionService.java`
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/service/impl/WorkspaceSessionServiceImpl.java`

**Step 1: 创建 Service 接口**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.model.ClaudeWorkspaceSession;
import java.util.List;

public interface WorkspaceSessionService {
    List<ClaudeWorkspaceSession> getSessions(String status);
    ClaudeWorkspaceSession getSession(String id);
    ClaudeWorkspaceSession createSession(String scene, String initialPrompt, String workingDirectory);
    ClaudeWorkspaceSession updateSession(String id, String title, String status);
    void deleteSession(String id);
    ClaudeWorkspaceSession archiveSession(String id);
}
```

**Step 2: 创建 Service 实现**

```java
package com.huawei.hisi.service.impl;

import com.huawei.hisi.model.ClaudeWorkspaceSession;
import com.huawei.hisi.repository.ClaudeWorkspaceSessionRepository;
import com.huawei.hisi.service.WorkspaceSessionService;
import com.huawei.hisi.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Date;
import java.util.List;

@Service
@RequiredArgsConstructor
public class WorkspaceSessionServiceImpl implements WorkspaceSessionService {

    private final ClaudeWorkspaceSessionRepository repository;
    private final SnowflakeIdGenerator idGenerator;

    @Override
    public List<ClaudeWorkspaceSession> getSessions(String status) {
        if (status != null && !status.isEmpty()) {
            return repository.findByStatus(status);
        }
        return repository.findAll();
    }

    @Override
    public ClaudeWorkspaceSession getSession(String id) {
        return repository.findById(id).orElse(null);
    }

    @Override
    public ClaudeWorkspaceSession createSession(String scene, String initialPrompt, String workingDirectory) {
        ClaudeWorkspaceSession session = new ClaudeWorkspaceSession();
        session.setId(idGenerator.nextId());
        session.setScene(scene != null ? scene : "general");
        session.setInitialPrompt(initialPrompt);
        session.setWorkingDirectory(workingDirectory);
        session.setStatus("active");
        session.setTitle("新会话");
        session.setCreatedAt(new Date());
        session.setUpdatedAt(new Date());
        return repository.save(session);
    }

    @Override
    public ClaudeWorkspaceSession updateSession(String id, String title, String status) {
        ClaudeWorkspaceSession session = repository.findById(id).orElse(null);
        if (session == null) return null;
        if (title != null) session.setTitle(title);
        if (status != null) session.setStatus(status);
        session.setUpdatedAt(new Date());
        return repository.save(session);
    }

    @Override
    public void deleteSession(String id) {
        repository.deleteById(id);
    }

    @Override
    public ClaudeWorkspaceSession archiveSession(String id) {
        return updateSession(id, null, "archived");
    }
}
```

**Step 3: Commit**

```bash
git add src/main/java/com/huawei/hisi/service/WorkspaceSessionService.java src/main/java/com/huawei/hisi/service/impl/WorkspaceSessionServiceImpl.java
git commit -m "feat: add WorkspaceSessionService for session management"
```

---

## Task 3: 后端 - 创建 WorkspaceSessionController

**Files:**
- Create: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/WorkspaceSessionController.java`

**Step 1: 创建 Controller**

```java
package com.huawei.hisi.controller;

import com.huawei.hisi.model.ApiResponse;
import com.huawei.hisi.model.ClaudeWorkspaceSession;
import com.huawei.hisi.service.WorkspaceSessionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/workspace-sessions")
@RequiredArgsConstructor
public class WorkspaceSessionController {

    private final WorkspaceSessionService service;

    @GetMapping
    public ApiResponse<List<ClaudeWorkspaceSession>> list(@RequestParam(required = false) String status) {
        return ApiResponse.success(service.getSessions(status));
    }

    @GetMapping("/{id}")
    public ApiResponse<ClaudeWorkspaceSession> get(@PathVariable String id) {
        ClaudeWorkspaceSession session = service.getSession(id);
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    @PostMapping
    public ApiResponse<ClaudeWorkspaceSession> create(@RequestBody CreateSessionRequest request) {
        ClaudeWorkspaceSession session = service.createSession(
            request.getScene(),
            request.getInitialPrompt(),
            request.getWorkingDirectory()
        );
        return ApiResponse.success(session);
    }

    @PutMapping("/{id}")
    public ApiResponse<ClaudeWorkspaceSession> update(@PathVariable String id, @RequestBody UpdateSessionRequest request) {
        ClaudeWorkspaceSession session = service.updateSession(id, request.getTitle(), request.getStatus());
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        service.deleteSession(id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/archive")
    public ApiResponse<ClaudeWorkspaceSession> archive(@PathVariable String id) {
        ClaudeWorkspaceSession session = service.archiveSession(id);
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        return ApiResponse.success(session);
    }

    @PostMapping("/{id}/bind-claude-session")
    public ApiResponse<ClaudeWorkspaceSession> bindClaudeSession(
        @PathVariable String id,
        @RequestParam String claudeSessionId
    ) {
        ClaudeWorkspaceSession session = service.getSession(id);
        if (session == null) {
            return ApiResponse.error(404, "Session not found");
        }
        session.setClaudeSessionId(claudeSessionId);
        session.setUpdatedAt(new java.util.Date());
        return ApiResponse.success(service.updateSession(id, session.getTitle(), session.getStatus()));
    }

    @Data
    public static class CreateSessionRequest {
        private String scene;
        private String initialPrompt;
        private String workingDirectory;
    }

    @Data
    public static class UpdateSessionRequest {
        private String title;
        private String status;
    }
}
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/WorkspaceSessionController.java
git commit -m "feat: add WorkspaceSessionController for session CRUD"
```

---

## Task 4: 后端 - 重构 TerminalWebSocketHandler 支持 start/resume

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/handler/TerminalWebSocketHandler.java`

**Step 1: 添加消息解析和 action 处理**

在文件顶部添加导入：
```java
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
```

**Step 2: 添加消息处理方法**

替换原有的 `handleTextMessage` 方法，添加 action 解析：

```java
@Override
protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String payload = message.getPayload();
    JSONObject msg = JSON.parseObject(payload);
    String action = msg.getString("action");

    switch (action) {
        case "start":
            startClaudeProcess(session, null, msg.getString("workingDirectory"));
            break;
        case "resume":
            startClaudeProcess(session, msg.getString("claudeSessionId"), msg.getString("workingDirectory"));
            break;
        case "input":
            PtyProcess process = ptyProcessMap.get(session);
            if (process != null) {
                OutputStream os = process.getOutputStream();
                os.write(msg.getString("data").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            break;
        case "resize":
            PtyProcess pty = ptyProcessMap.get(session);
            if (pty != null) {
                int cols = msg.getIntValue("cols");
                int rows = msg.getIntValue("rows");
                pty.setWindowSize(cols, rows);
            }
            break;
    }
}
```

**Step 3: 重构 startClaudeProcess 方法**

```java
private void startClaudeProcess(WebSocketSession session, String claudeSessionId, String workingDirectory) throws Exception {
    // 发送就绪消息
    session.sendMessage(new TextMessage(JSON.toJSONString(Map.of("type", "ready"))));

    String[] command;
    String osName = System.getProperty("os.name").toLowerCase();

    if (claudeSessionId != null && !claudeSessionId.isEmpty()) {
        // 恢复会话
        if (osName.contains("win")) {
            command = new String[]{"cmd.exe", "/c", claudePath, "--resume", claudeSessionId};
        } else {
            command = new String[]{claudePath, "--resume", claudeSessionId};
        }
    } else {
        // 新建会话
        if (osName.contains("win")) {
            command = new String[]{"cmd.exe", "/c", claudePath};
        } else {
            command = new String[]{claudePath};
        }
    }

    PtyProcessBuilder builder = new PtyProcessBuilder(command);
    String workDir = workingDirectory != null ? workingDirectory : defaultWorkingDirectory;
    builder.setDirectory(workDir);
    builder.setEnvironment(new HashMap<>(System.getenv()));
    builder.setInitialColumns(120);
    builder.setInitialRows(30);

    PtyProcess ptyProcess = builder.start();
    ptyProcessMap.put(session, ptyProcess);
    log.info("Claude CLI process started for session: {}, claudeSessionId: {}", session.getId(), claudeSessionId);

    // 输出流读取线程
    InputStream inputStream = ptyProcess.getInputStream();
    Thread outputThread = new Thread(() -> {
        byte[] buffer = new byte[4096];
        int len;
        try {
            while ((len = inputStream.read(buffer)) != -1) {
                String output = new String(buffer, 0, len, StandardCharsets.UTF_8);
                if (session.isOpen()) {
                    // 发送输出消息
                    session.sendMessage(new TextMessage(JSON.toJSONString(Map.of(
                        "type", "output",
                        "data", output
                    ))));

                    // 尝试从输出中提取 claude session id
                    extractClaudeSessionId(session, output);
                }
            }
        } catch (IOException e) {
            log.debug("Output stream closed for session: {}", session.getId());
        } finally {
            cleanupProcess(session);
        }
    }, "pty-output-" + session.getId());
    outputThread.setDaemon(true);
    outputThread.start();
}
```

**Step 4: 添加 session_id 提取方法**

```java
private final Map<WebSocketSession, String> extractedSessionIds = new ConcurrentHashMap<>();

private void extractClaudeSessionId(WebSocketSession session, String output) {
    // Claude CLI 在启动时会输出类似 "Session ID: xxx" 的信息
    // 格式可能是: "session_id: abc123" 或 "<session>abc123</session>"
    // 需要根据实际输出格式调整正则表达式
    Pattern pattern = Pattern.compile("session[_\\-]?id[:\\s]+([a-zA-Z0-9\\-]+)", Pattern.CASE_INSENSITIVE);
    Matcher matcher = pattern.matcher(output);
    if (matcher.find()) {
        String claudeSessionId = matcher.group(1);
        if (!extractedSessionIds.containsKey(session)) {
            extractedSessionIds.put(session, claudeSessionId);
            try {
                session.sendMessage(new TextMessage(JSON.toJSONString(Map.of(
                    "type", "session_info",
                    "claudeSessionId", claudeSessionId
                ))));
                log.info("Extracted Claude session ID: {} for WebSocket session: {}", claudeSessionId, session.getId());
            } catch (IOException e) {
                log.error("Failed to send session_info message", e);
            }
        }
    }
}
```

添加导入：
```java
import java.util.regex.Pattern;
import java.util.regex.Matcher;
```

**Step 5: Commit**

```bash
git add src/main/java/com/huawei/hisi/handler/TerminalWebSocketHandler.java
git commit -m "feat: refactor TerminalWebSocketHandler to support start/resume actions"
```

---

## Task 5: 前端 - 创建类型定义

**Files:**
- Modify: `hisi-dev-tool-frontend/src/types/terminal.ts`

**Step 1: 添加会话类型**

```typescript
// 在现有类型后添加

export interface ClaudeWorkspaceSession {
  id: string
  claudeSessionId: string
  title: string
  scene: string
  status: 'active' | 'archived'
  createdAt: Date
  updatedAt: Date
  workingDirectory?: string
  initialPrompt?: string
}

export interface TerminalClientMessage {
  action: 'start' | 'resume' | 'input' | 'resize'
  data?: string
  claudeSessionId?: string
  cols?: number
  rows?: number
  workingDirectory?: string
}

export interface TerminalServerMessage {
  type: 'output' | 'session_info' | 'error' | 'ready'
  data?: string
  claudeSessionId?: string
}
```

**Step 2: Commit**

```bash
git add src/types/terminal.ts
git commit -m "feat: add ClaudeWorkspaceSession and WebSocket message types"
```

---

## Task 6: 前端 - 创建会话管理 API

**Files:**
- Create: `hisi-dev-tool-frontend/src/api/workspaceSession.ts`

**Step 1: 创建 API 模块**

```typescript
import request from '@/utils/request'
import type { ClaudeWorkspaceSession } from '@/types/terminal'

export interface CreateSessionRequest {
  scene?: string
  initialPrompt?: string
  workingDirectory?: string
}

export interface UpdateSessionRequest {
  title?: string
  status?: string
}

export const workspaceSessionApi = {
  list(status?: string) {
    return request.get<ClaudeWorkspaceSession[]>('/api/workspace-sessions', { params: { status } })
  },

  get(id: string) {
    return request.get<ClaudeWorkspaceSession>(`/api/workspace-sessions/${id}`)
  },

  create(data: CreateSessionRequest) {
    return request.post<ClaudeWorkspaceSession>('/api/workspace-sessions', data)
  },

  update(id: string, data: UpdateSessionRequest) {
    return request.put<ClaudeWorkspaceSession>(`/api/workspace-sessions/${id}`, data)
  },

  delete(id: string) {
    return request.delete(`/api/workspace-sessions/${id}`)
  },

  archive(id: string) {
    return request.post<ClaudeWorkspaceSession>(`/api/workspace-sessions/${id}/archive`)
  },

  bindClaudeSession(id: string, claudeSessionId: string) {
    return request.post<ClaudeWorkspaceSession>(`/api/workspace-sessions/${id}/bind-claude-session`, null, {
      params: { claudeSessionId }
    })
  }
}
```

**Step 2: Commit**

```bash
git add src/api/workspaceSession.ts
git commit -m "feat: add workspace session API module"
```

---

## Task 7: 前端 - 创建会话状态管理

**Files:**
- Create: `hisi-dev-tool-frontend/src/stores/workspaceStore.ts`

**Step 1: 创建 Pinia Store**

```typescript
import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import type { ClaudeWorkspaceSession } from '@/types/terminal'
import { workspaceSessionApi } from '@/api/workspaceSession'
import { ElMessage } from 'element-plus'

export const useWorkspaceStore = defineStore('workspace', () => {
  const sessions = ref<ClaudeWorkspaceSession[]>([])
  const currentSessionId = ref<string | null>(null)
  const loading = ref(false)

  const currentSession = computed(() =>
    sessions.value.find(s => s.id === currentSessionId.value) || null
  )

  const activeSessions = computed(() =>
    sessions.value.filter(s => s.status === 'active')
  )

  const archivedSessions = computed(() =>
    sessions.value.filter(s => s.status === 'archived')
  )

  async function loadSessions(status?: string) {
    loading.value = true
    try {
      const res = await workspaceSessionApi.list(status)
      sessions.value = res.data || []
    } catch (e) {
      console.error('Failed to load sessions:', e)
    } finally {
      loading.value = false
    }
  }

  async function createSession(scene?: string, initialPrompt?: string, workingDirectory?: string) {
    try {
      const res = await workspaceSessionApi.create({ scene, initialPrompt, workingDirectory })
      sessions.value.unshift(res.data)
      currentSessionId.value = res.data.id
      return res.data
    } catch (e) {
      ElMessage.error('创建会话失败')
      throw e
    }
  }

  async function updateSession(id: string, title?: string, status?: string) {
    try {
      const res = await workspaceSessionApi.update(id, { title, status })
      const index = sessions.value.findIndex(s => s.id === id)
      if (index >= 0) {
        sessions.value[index] = res.data
      }
    } catch (e) {
      ElMessage.error('更新会话失败')
    }
  }

  async function deleteSession(id: string) {
    try {
      await workspaceSessionApi.delete(id)
      sessions.value = sessions.value.filter(s => s.id !== id)
      if (currentSessionId.value === id) {
        currentSessionId.value = null
      }
    } catch (e) {
      ElMessage.error('删除会话失败')
    }
  }

  async function archiveSession(id: string) {
    try {
      const res = await workspaceSessionApi.archive(id)
      const index = sessions.value.findIndex(s => s.id === id)
      if (index >= 0) {
        sessions.value[index] = res.data
      }
    } catch (e) {
      ElMessage.error('归档会话失败')
    }
  }

  async function bindClaudeSession(id: string, claudeSessionId: string) {
    try {
      const res = await workspaceSessionApi.bindClaudeSession(id, claudeSessionId)
      const index = sessions.value.findIndex(s => s.id === id)
      if (index >= 0) {
        sessions.value[index] = res.data
      }
    } catch (e) {
      console.error('Failed to bind Claude session:', e)
    }
  }

  function selectSession(id: string) {
    currentSessionId.value = id
  }

  return {
    sessions,
    currentSessionId,
    currentSession,
    activeSessions,
    archivedSessions,
    loading,
    loadSessions,
    createSession,
    updateSession,
    deleteSession,
    archiveSession,
    bindClaudeSession,
    selectSession
  }
})
```

**Step 2: Commit**

```bash
git add src/stores/workspaceStore.ts
git commit -m "feat: add workspace store for session management"
```

---

## Task 8: 前端 - 重构终端 WebSocket 连接逻辑

**Files:**
- Modify: `hisi-dev-tool-frontend/src/api/terminal.ts`

**Step 1: 修改 WebSocket 连接以支持 action 消息**

```typescript
import type { TerminalConnectionStatus, TerminalClientMessage, TerminalServerMessage } from '@/types/terminal'

export interface TerminalCallbacks {
  onOpen?: () => void
  onClose?: () => void
  onError?: (error: string) => void
  onOutput?: (data: string) => void
  onSessionInfo?: (claudeSessionId: string) => void
  onReady?: () => void
}

export interface TerminalConnection {
  send: (message: TerminalClientMessage) => void
  close: () => void
  getStatus: () => TerminalConnectionStatus
}

export function createTerminalConnection(callbacks: TerminalCallbacks): TerminalConnection {
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  const host = window.location.host
  const wsUrl = `${protocol}//${host}/ws/terminal`

  const status = ref<TerminalConnectionStatus>('disconnected')

  const socket = new WebSocket(wsUrl)

  const updateStatus = (newStatus: TerminalConnectionStatus) => {
    status.value = newStatus
  }

  socket.onopen = () => {
    updateStatus('connected')
    callbacks.onOpen?.()
  }

  socket.onclose = () => {
    updateStatus('disconnected')
    callbacks.onClose?.()
  }

  socket.onerror = () => {
    updateStatus('error')
    callbacks.onError?.('WebSocket connection error')
  }

  socket.onmessage = (event: MessageEvent) => {
    try {
      const msg: TerminalServerMessage = JSON.parse(event.data)
      switch (msg.type) {
        case 'output':
          callbacks.onOutput?.(msg.data || '')
          break
        case 'session_info':
          callbacks.onSessionInfo?.(msg.claudeSessionId || '')
          break
        case 'ready':
          callbacks.onReady?.()
          break
        case 'error':
          callbacks.onError?.(msg.data || 'Unknown error')
          break
      }
    } catch (e) {
      // 如果不是 JSON，可能是纯文本输出
      callbacks.onOutput?.(event.data)
    }
  }

  return {
    send: (message: TerminalClientMessage) => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify(message))
      }
    },
    close: () => {
      socket.close()
    },
    getStatus: () => status.value
  }
}
```

**Step 2: Commit**

```bash
git add src/api/terminal.ts
git commit -m "feat: refactor terminal WebSocket to support structured messages"
```

---

## Task 9: 前端 - 创建 SessionList 组件

**Files:**
- Create: `hisi-dev-tool-frontend/src/views/claude-terminal/components/SessionList.vue`

**Step 1: 创建会话列表组件**

```vue
<template>
  <div class="session-list-panel" :class="{ collapsed: collapsed }">
    <div class="panel-header">
      <span v-if="!collapsed">会话列表</span>
      <el-button text @click="collapsed = !collapsed">
        <el-icon><component :is="collapsed ? 'DArrowRight' : 'DArrowLeft'" /></el-icon>
      </el-button>
    </div>

    <template v-if="!collapsed">
      <div class="session-actions">
        <el-input
          v-model="searchKeyword"
          placeholder="搜索会话"
          prefix-icon="Search"
          clearable
          size="small"
        />
        <el-button
          type="primary"
          size="small"
          style="margin-top: 8px; width: 100%;"
          @click="handleNewSession"
        >
          <el-icon><Plus /></el-icon>
          新建会话
        </el-button>
      </div>

      <div class="session-groups">
        <div class="session-group">
          <div class="group-header" @click="toggleGroup('active')">
            <el-icon><FolderOpened v-if="expandedGroups.active" /><Folder v-else /></el-icon>
            <span>进行中 ({{ workspaceStore.activeSessions.length }})</span>
          </div>
          <div v-show="expandedGroups.active" class="group-items">
            <div
              v-for="session in filteredActiveSessions"
              :key="session.id"
              class="session-item"
              :class="{ active: workspaceStore.currentSessionId === session.id }"
              @click="selectSession(session.id)"
            >
              <div class="session-title">{{ session.title || '新会话' }}</div>
              <div class="session-meta">{{ formatDate(session.createdAt) }}</div>
            </div>
          </div>
        </div>

        <div class="session-group">
          <div class="group-header" @click="toggleGroup('archived')">
            <el-icon><FolderOpened v-if="expandedGroups.archived" /><Folder v-else /></el-icon>
            <span>已归档 ({{ workspaceStore.archivedSessions.length }})</span>
          </div>
          <div v-show="expandedGroups.archived" class="group-items">
            <div
              v-for="session in filteredArchivedSessions"
              :key="session.id"
              class="session-item"
              :class="{ active: workspaceStore.currentSessionId === session.id }"
              @click="selectSession(session.id)"
            >
              <div class="session-title">{{ session.title || '新会话' }}</div>
              <div class="session-meta">{{ formatDate(session.createdAt) }}</div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { ref, computed } from 'vue'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { FolderOpened, Folder, Plus, DArrowRight, DArrowLeft } from '@element-plus/icons-vue'

const emit = defineEmits<{
  newSession: []
  selectSession: [sessionId: string]
}>()

const workspaceStore = useWorkspaceStore()

const collapsed = ref(false)
const searchKeyword = ref('')
const expandedGroups = ref({ active: true, archived: false })

const filteredActiveSessions = computed(() => {
  if (!searchKeyword.value) return workspaceStore.activeSessions
  return workspaceStore.activeSessions.filter(s =>
    s.title?.toLowerCase().includes(searchKeyword.value.toLowerCase())
  )
})

const filteredArchivedSessions = computed(() => {
  if (!searchKeyword.value) return workspaceStore.archivedSessions
  return workspaceStore.archivedSessions.filter(s =>
    s.title?.toLowerCase().includes(searchKeyword.value.toLowerCase())
  )
})

function toggleGroup(group: 'active' | 'archived') {
  expandedGroups.value[group] = !expandedGroups.value[group]
}

function handleNewSession() {
  emit('newSession')
}

function selectSession(sessionId: string) {
  emit('selectSession', sessionId)
}

function formatDate(date: Date): string {
  return new Date(date).toLocaleDateString('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  })
}
</script>

<style scoped>
.session-list-panel {
  width: 220px;
  background-color: #f5f7fa;
  border-right: 1px solid #e4e7ed;
  height: 100%;
  display: flex;
  flex-direction: column;
  transition: width 0.3s;
}

.session-list-panel.collapsed {
  width: 40px;
}

.panel-header {
  padding: 12px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  border-bottom: 1px solid #e4e7ed;
}

.session-actions {
  padding: 12px;
}

.session-groups {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.session-group {
  margin-bottom: 8px;
}

.group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px;
  cursor: pointer;
  font-weight: 500;
  color: #606266;
}

.group-header:hover {
  background-color: #e9ecf0;
}

.group-items {
  padding-left: 8px;
}

.session-item {
  padding: 8px 12px;
  cursor: pointer;
  border-radius: 4px;
  margin-bottom: 4px;
}

.session-item:hover {
  background-color: #e9ecf0;
}

.session-item.active {
  background-color: #409eff;
  color: white;
}

.session-title {
  font-size: 14px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-meta {
  font-size: 12px;
  color: #909399;
}

.session-item.active .session-meta {
  color: rgba(255, 255, 255, 0.8);
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/claude-terminal/components/SessionList.vue
git commit -m "feat: add SessionList component for workspace"
```

---

## Task 10: 前端 - 重构 ClaudeTerminal.vue 整合会话管理

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/claude-terminal/ClaudeTerminal.vue`

**Step 1: 重构主组件整合会话列表**

```vue
<template>
  <div class="claude-workspace">
    <SessionList
      @new-session="handleNewSession"
      @select-session="handleSelectSession"
    />

    <div class="terminal-wrapper">
      <div class="terminal-header">
        <div class="terminal-title">
          <el-icon><Monitor /></el-icon>
          <span>{{ currentSession?.title || 'Claude 终端' }}</span>
        </div>
        <div class="terminal-status">
          <el-tag :type="statusTagType" size="small">{{ statusText }}</el-tag>
          <el-button-group size="small">
            <el-button @click="handleReconnect" :disabled="connectionStatus === 'connected'">
              <el-icon><RefreshRight /></el-icon>重连
            </el-button>
            <el-button @click="handleClear">
              <el-icon><Delete /></el-icon>清屏
            </el-button>
          </el-button-group>
        </div>
      </div>

      <div class="terminal-container" ref="terminalContainerRef"></div>

      <div class="quick-actions">
        <span class="actions-label">快捷命令：</span>
        <el-button v-for="action in quickActions" :key="action.command" size="small" @click="executeCommand(action.command)">
          {{ action.label }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import '@xterm/xterm/css/xterm.css'
import { Monitor, RefreshRight, Delete } from '@element-plus/icons-vue'
import { createTerminalConnection } from '@/api/terminal'
import type { TerminalConnectionStatus, TerminalClientMessage } from '@/types/terminal'
import { useWorkspaceStore } from '@/stores/workspaceStore'
import { ElMessage } from 'element-plus'
import SessionList from './components/SessionList.vue'

const terminalContainerRef = ref<HTMLElement | null>(null)
let terminal: Terminal | null = null
let fitAddon: FitAddon | null = null
const connectionStatus = ref<TerminalConnectionStatus>('disconnected')
let terminalConnection: ReturnType<typeof createTerminalConnection> | null = null

const workspaceStore = useWorkspaceStore()

const quickActions = [
  { label: '/help', command: '/help\n' },
  { label: '/plugin', command: '/plugin\n' },
  { label: '/config', command: '/config\n' },
  { label: '/clear', command: '/clear\n' },
]

const currentSession = computed(() => workspaceStore.currentSession)

const statusText = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return '已连接'
    case 'connecting': return '连接中...'
    case 'disconnected': return '已断开'
    case 'error': return '连接错误'
    default: return '未知'
  }
})

const statusTagType = computed(() => {
  switch (connectionStatus.value) {
    case 'connected': return 'success'
    case 'connecting': return 'warning'
    case 'disconnected': return 'info'
    case 'error': return 'danger'
    default: return 'info'
  }
})

function initTerminal() {
  if (!terminalContainerRef.value) return

  terminal = new Terminal({
    cursorBlink: true,
    cursorStyle: 'block',
    convertEol: true,
    scrollback: 5000,
    theme: {
      foreground: '#ECECEC',
      background: '#1E1E1E',
      cursor: '#FFFFFF',
      cursorAccent: '#1E1E1E',
      selectionBackground: 'rgba(100, 100, 100, 0.5)',
    },
    fontFamily: '"JetBrains Mono", "Fira Code", "Consolas", monospace',
    fontSize: 14,
    lineHeight: 1.4,
  })

  fitAddon = new FitAddon()
  terminal.loadAddon(fitAddon)
  terminal.loadAddon(new WebLinksAddon())
  terminal.open(terminalContainerRef.value)
  fitTerminal()

  terminal.onData((data) => {
    if (terminalConnection) {
      terminalConnection.send({ action: 'input', data })
    }
  })

  terminal.onResize(({ cols, rows }) => {
    if (terminalConnection) {
      terminalConnection.send({ action: 'resize', cols, rows })
    }
  })
}

function connectTerminal(action: 'start' | 'resume', claudeSessionId?: string) {
  connectionStatus.value = 'connecting'

  terminalConnection = createTerminalConnection({
    onOpen: () => {
      connectionStatus.value = 'connected'
    },
    onClose: () => {
      connectionStatus.value = 'disconnected'
    },
    onError: (error) => {
      connectionStatus.value = 'error'
      ElMessage.error(error)
    },
    onOutput: (data) => {
      if (terminal) {
        terminal.write(data)
      }
    },
    onSessionInfo: (claudeSessionId) => {
      // 收到 Claude session_id，绑定到当前会话
      if (workspaceStore.currentSessionId) {
        workspaceStore.bindClaudeSession(workspaceStore.currentSessionId, claudeSessionId)
      }
    },
    onReady: () => {
      // 终端就绪，发送初始消息
      const message: TerminalClientMessage = { action }
      if (claudeSessionId) {
        message.claudeSessionId = claudeSessionId
      }
      terminalConnection?.send(message)

      // 如果有初始提示词，自动发送
      if (action === 'start' && currentSession.value?.initialPrompt) {
        setTimeout(() => {
          terminalConnection?.send({
            action: 'input',
            data: currentSession.value!.initialPrompt + '\n'
          })
        }, 500)
      }
    }
  })
}

function fitTerminal() {
  if (fitAddon && terminalContainerRef.value) {
    fitAddon.fit()
  }
}

function handleNewSession() {
  workspaceStore.createSession().then((session) => {
    connectTerminal('start')
  })
}

function handleSelectSession(sessionId: string) {
  workspaceStore.selectSession(sessionId)
  const session = workspaceStore.getSession(sessionId)

  // 断开现有连接
  if (terminalConnection) {
    terminalConnection.close()
  }

  // 清空终端
  if (terminal) {
    terminal.clear()
  }

  // 恢复会话
  if (session?.claudeSessionId) {
    connectTerminal('resume', session.claudeSessionId)
  } else {
    connectTerminal('start')
  }
}

function handleReconnect() {
  if (terminalConnection) {
    terminalConnection.close()
  }

  if (currentSession.value?.claudeSessionId) {
    connectTerminal('resume', currentSession.value.claudeSessionId)
  } else {
    connectTerminal('start')
  }
}

function handleClear() {
  if (terminal) {
    terminal.clear()
  }
}

function executeCommand(command: string) {
  if (terminalConnection) {
    terminalConnection.send({ action: 'input', data: command })
  }
}

// 监听会话切换
watch(() => workspaceStore.currentSessionId, (newId) => {
  if (newId && !terminalConnection) {
    const session = workspaceStore.currentSession
    if (session?.claudeSessionId) {
      connectTerminal('resume', session.claudeSessionId)
    }
  }
})

onMounted(() => {
  initTerminal()
  workspaceStore.loadSessions()

  // 如果没有当前会话，自动创建
  if (!workspaceStore.currentSessionId && workspaceStore.sessions.length === 0) {
    handleNewSession()
  } else if (workspaceStore.currentSessionId) {
    const session = workspaceStore.currentSession
    connectTerminal('start')
  }

  window.addEventListener('resize', fitTerminal)
})

onUnmounted(() => {
  if (terminalConnection) {
    terminalConnection.close()
  }
  if (terminal) {
    terminal.dispose()
  }
  window.removeEventListener('resize', fitTerminal)
})
</script>

<style scoped>
.claude-workspace {
  display: flex;
  height: 100%;
}

.terminal-wrapper {
  flex: 1;
  display: flex;
  flex-direction: column;
  background-color: #1E1E1E;
}

.terminal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 16px;
  background-color: #2D2D2D;
  border-bottom: 1px solid #3D3D3D;
}

.terminal-title {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #ECECEC;
}

.terminal-status {
  display: flex;
  align-items: center;
  gap: 12px;
}

.terminal-container {
  flex: 1;
  padding: 8px;
}

.quick-actions {
  padding: 12px 16px;
  background-color: #2D2D2D;
  border-top: 1px solid #3D3D3D;
  display: flex;
  align-items: center;
  gap: 8px;
}

.actions-label {
  color: #909399;
  font-size: 14px;
}
</style>
```

**Step 2: Commit**

```bash
git add src/views/claude-terminal/ClaudeTerminal.vue
git commit -m "feat: integrate session management into ClaudeTerminal"
```

---

## Task 11: 前端 - 更新路由和菜单

**Files:**
- Modify: `hisi-dev-tool-frontend/src/router/index.ts`
- Modify: `hisi-dev-tool-frontend/src/components/layout/AppSidebar.vue`
- Modify: `hisi-dev-tool-frontend/src/stores/app.ts`

**Step 1: 移除 claude-session 路由**

在 `src/router/index.ts` 中删除 `/claude-session` 路由定义：
```typescript
// 删除这段
{
  path: '/claude-session',
  name: 'ClaudeSession',
  component: () => import('@/views/claude-session/ClaudeSession.vue'),
  meta: {
    title: 'Claude 会话',
    keepAlive: true
  }
},
```

**Step 2: 更新 AppSidebar.vue 移除 Claude 会话菜单项**

```typescript
// 从 MenuKey 类型中移除 'claude-session'
type MenuKey = 'call-chain' | 'log-analysis' | 'ops' | 'project-management' | 'mcp-guide' | 'claude-terminal' | 'prompt-config'

// 从 baseMenuItems 中移除 Claude 会话菜单项
// 删除这段:
{
  index: '/claude-session',
  title: 'Claude 会话',
  icon: ChatDotRound,
  menuKey: 'claude-session'
},
```

同时从 import 中移除 `ChatDotRound`：
```typescript
import { Document, Share, Folder, Cpu, Monitor, Setting } from '@element-plus/icons-vue'
```

**Step 3: 更新 app.ts 移除 claude-session 可用性**

```typescript
const availableMenus = computed(() => ({
  'project-management': true,
  'mcp-guide': true,
  'claude-terminal': true,
  'prompt-config': true,
  'call-chain': projectDirConfigured.value && projectSelected.value,
  'log-analysis': projectDirConfigured.value && projectSelected.value,
  'ops': false
}))
```

**Step 4: Commit**

```bash
git add src/router/index.ts src/components/layout/AppSidebar.vue src/stores/app.ts
git commit -m "feat: remove claude-session route and menu, use unified claude-terminal"
```

---

## Task 12: 后端 - 清理旧的 Session 相关代码

**Files:**
- Modify: `hisi-dev-tool/src/main/java/com/huawei/hisi/controller/SessionController.java`（标记为废弃或移除消息相关 API）
- Optional: 移除 `ClaudeMessage` 实体和相关 Repository

**Step 1: 简化 SessionController**

移除消息相关 API，只保留会话 CRUD（或直接废弃整个 Controller）：

```java
// 在 SessionController.java 顶部添加注释标记为废弃
/**
 * @deprecated 使用 WorkspaceSessionController 代替
 * 此控制器将在下个版本移除
 */
@Deprecated
@RestController
@RequestMapping("/api/sessions")
```

**Step 2: Commit**

```bash
git add src/main/java/com/huawei/hisi/controller/SessionController.java
git commit -m "refactor: deprecate old SessionController, use WorkspaceSessionController"
```

---

## Task 13: 前端 - 更新项目管理页面的代码分析跳转

**Files:**
- Modify: `hisi-dev-tool-frontend/src/views/project/ProjectList.vue`

**Step 1: 修改代码分析和影响分析的跳转逻辑**

将原来跳转到 `ClaudeSession` 改为跳转到 `ClaudeTerminal` 并传递初始提示词：

```typescript
// 修改 handleCodeAnalysis 和 handleImpactAnalysis 方法
// 原来的跳转:
router.push({ name: 'ClaudeSession', query: { sessionId } })

// 改为:
import { useWorkspaceStore } from '@/stores/workspaceStore'

const workspaceStore = useWorkspaceStore()

// 在 handleCodeAnalysis 中:
async function handleCodeAnalysis() {
  analysisLoading.value = true
  try {
    const commitInfos = selectedCommits.value.map(c =>
      `${c.commitId}: ${c.shortMessage} (${c.author})`
    ).join('\n')

    const prompt = `分析以下 Git 提交:\n\n${commitInfos}`

    // 创建会话并设置初始提示词
    const session = await workspaceStore.createSession('code-analysis', prompt)

    // 跳转到终端页面
    router.push({ name: 'ClaudeTerminal' })
  } finally {
    analysisLoading.value = false
  }
}

// 类似修改 handleImpactAnalysis
```

**Step 2: Commit**

```bash
git add src/views/project/ProjectList.vue
git commit -m "refactor: update code analysis to use ClaudeTerminal with initial prompt"
```

---

## Task 14: 集成测试

**步骤:**

1. 启动后端：`mvn spring-boot:run`
2. 启动前端：`npm run dev`
3. 验证功能：
   - 新建会话按钮工作正常
   - 会话列表显示正确
   - 终端连接成功
   - 切换会话能恢复历史（需要 Claude CLI 的 session_id 正确提取）
   - 代码分析跳转到终端并发送提示词
4. 检查浏览器控制台无错误
5. 检查后端日志无异常

**Step: Commit test verification**

```bash
# 如果测试通过，无需额外提交
# 如果有修复，提交修复内容
```

---

## Task 15: 推送所有更改到远程

**Step 1: 推送前端**

```bash
cd hisi-dev-tool-frontend
git push origin release_v3
```

**Step 2: 推送后端**

```bash
cd hisi-dev-tool
git push origin release_v3
```

---

## 总结

实现完成后：
- `/claude-session` 页面移除
- `/claude-terminal` 页面整合会话管理
- 后端新增 `WorkspaceSessionController` 和相关实体
- WebSocket 支持 `start`/`resume`/`input`/`resize` action
- 利用 Claude CLI 的 `--resume` 恢复历史对话