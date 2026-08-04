# HiSi DevTool 项目重建实施计划

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 从 codeanalyser.txt 逆向还原 Spring Boot 后端项目，补充测试，生成文档，开发 Vue 3 前端应用。

**Architecture:** 后端采用 Spring Boot 3.2 + Java 17，使用 JdbcTemplate 操作 OpenGauss 数据库；前端采用 Vue 3 + TypeScript + Element Plus，通过 REST API 与后端交互。

**Tech Stack:** Spring Boot 3.2.0, Java 17, OpenGauss, Vue 3.4+, TypeScript 5.x, Vite 5.x, Element Plus, Pinia, Axios

---

## Phase 1: 后端项目还原

### Task 1: 创建解析脚本

**Files:**
- Create: `C:\Users\47583\projects\restore_project.py`

**Step 1: 编写解析脚本**

```python
#!/usr/bin/env python3
"""
项目还原脚本：从 codeanalyser.txt 解析并还原项目结构
"""

import os
import re
from pathlib import Path

def parse_and_restore(input_file: str, output_dir: str):
    """解析合并文档并还原项目结构"""

    with open(input_file, 'r', encoding='utf-8') as f:
        content = f.read()

    # 匹配文件分隔符和内容
    # 格式: =================\n文件 N/M: path\n================\n\ncontent
    pattern = r'=+\n文件 \d+/\d+: (.+?)\n=+\n\n(.*?)(?==+\n文件|\Z)'

    matches = re.findall(pattern, content, re.DOTALL)

    print(f"找到 {len(matches)} 个文件")

    for file_path, file_content in matches:
        file_path = file_path.strip()
        # 转换路径分隔符
        file_path = file_path.replace('\\', '/')

        # 构建完整输出路径
        full_path = Path(output_dir) / file_path

        # 创建目录
        full_path.parent.mkdir(parents=True, exist_ok=True)

        # 写入文件内容
        content_to_write = file_content.rstrip('\n')
        if content_to_write:
            full_path.write_text(content_to_write, encoding='utf-8')
            print(f"已还原: {file_path}")
        else:
            print(f"跳过空文件: {file_path}")

    print(f"\n还原完成! 共 {len(matches)} 个文件")

if __name__ == '__main__':
    input_file = r'C:\Users\47583\projects\codeanalyser.txt'
    output_dir = r'C:\Users\47583\projects\hisi-dev-tool'
    parse_and_restore(input_file, output_dir)
```

**Step 2: 运行脚本还原项目**

Run: `cd C:\Users\47583\projects && python restore_project.py`
Expected: 输出 "找到 XX 个文件" 和 "还原完成"

**Step 3: 验证目录结构**

Run: `ls -la C:\Users\47583\projects\hisi-dev-tool\src\main\java\com\huawei\hisi\`
Expected: 显示 config, controller, model, service, utils 等目录

---

### Task 2: 补充缺失的基础文件

**Files:**
- Create: `C:\Users\47583\projects\hisi-dev-tool\pom.xml`
- Create: `C:\Users\47583\projects\hisi-dev-tool\.gitignore`
- Create: `C:\Users\47583\projects\hisi-dev-tool\README.md`

**Step 1: 写入 pom.xml**

用户提供完整 pom.xml，直接写入文件。

**Step 2: 创建 .gitignore**

```gitignore
# Maven
target/
!.mvn/wrapper/maven-wrapper.jar

# IDE
.idea/
*.iml
.vscode/
.settings/

# OS
.DS_Store
Thumbs.db

# Logs
logs/
*.log

# Config with secrets
application-local.yml
```

**Step 3: 创建 README.md**

```markdown
# HiSi DevTool

华为开发者工具平台 - 日志分析与调用链分析系统

## 技术栈

- Spring Boot 3.2.0
- Java 17
- OpenGauss (PostgreSQL 兼容)
- Vue 3 + TypeScript

## 快速开始

### 后端启动

```bash
mvn clean install
mvn spring-boot:run
```

### 前端启动

```bash
cd hisi-dev-tool-frontend
npm install
npm run dev
```

## 功能模块

- 日志分析：查询日志、异步分析、报告生成
- 调用链分析：项目查询、URI 调用链可视化
- 项目管理：Git 仓库克隆、项目状态
- 运维监控：健康检查、影响分析
```

**Step 4: 验证文件创建**

Run: `ls C:\Users\47583\projects\hisi-dev-tool\`
Expected: 显示 pom.xml, .gitignore, README.md, src/

---

### Task 3: 初始化 Git 仓库

**Files:**
- Modify: `.git` (初始化仓库)

**Step 1: 初始化仓库**

Run: `cd C:\Users\47583\projects\hisi-dev-tool && git init`
Expected: "Initialized empty Git repository"

**Step 2: 添加所有文件**

Run: `cd C:\Users\47583\projects\hisi-dev-tool && git add .`
Expected: 无错误输出

**Step 3: 提交初始代码**

Run: `cd C:\Users\47583\projects\hisi-dev-tool && git commit -m "feat: 初始化项目 - 从 codeanalyser.txt 还原"`
Expected: 提交成功信息

---

### Task 4: 敏感信息脱敏处理

**Files:**
- Modify: `src/main/resources/application.yml`
- Modify: `src/main/resources/application-prod.yml`

**Step 1: 检查配置文件中的敏感信息**

Run: `grep -E "(password|api-key|secret)" C:\Users\47583\projects\hisi-dev-tool\src\main\resources\application.yml`
Expected: 显示包含敏感信息的行

**Step 2: 替换敏感信息为环境变量占位符**

在 application.yml 中，将敏感信息替换为 `${ENV_VAR:default}` 格式：

```yaml
# 示例替换
llm:
  api-key: ${LLM_API_KEY:sk-placeholder}

app:
  dbpassword: ${DB_PASSWORD:placeholder}
  codeHubPassword: ${CODEHUB_PASSWORD:placeholder}

logcloud:
  password: ${LOGCLOUD_PASSWORD:}
  api:
    header-appkey: ${LOGCLOUD_APPKEY:placeholder}
```

**Step 3: 提交脱敏更改**

Run: `git add . && git commit -m "security: 配置文件敏感信息脱敏"`
Expected: 提交成功

---

### Task 5: 代码结构梳理与分析

**Files:**
- Read: 所有 Java 源文件

**Step 1: 分析项目依赖关系**

使用 Agent 工具探索代码结构：

```bash
# 分析 Controller 层
ls src/main/java/com/huawei/hisi/controller/

# 分析 Service 层
ls src/main/java/com/huawei/hisi/service/

# 分析 Model 层
ls src/main/java/com/huawei/hisi/model/
```

**Step 2: 记录代码分析结果**

创建 `docs/code-analysis.md` 记录：
- 各模块职责
- 类之间的依赖关系
- 潜在的重构点（不做过度拆分）

**Step 3: 提交分析文档**

Run: `git add docs/code-analysis.md && git commit -m "docs: 添加代码结构分析"`
Expected: 提交成功

---

### Task 6: Maven 构建验证

**Files:**
- 无新增文件，验证构建

**Step 1: 清理并编译**

Run: `cd C:\Users\47583\projects\hisi-dev-tool && mvn clean compile`
Expected: BUILD SUCCESS

**Step 2: 如有编译错误，修复依赖问题**

常见问题：
- 缺少 Lombok 注解处理配置
- OpenGauss 驱动版本问题
- Java 版本不匹配

**Step 3: 打包验证**

Run: `mvn package -DskipTests`
Expected: BUILD SUCCESS, 生成 target/*.jar

**Step 4: 提交修复（如有）**

Run: `git add . && git commit -m "fix: 修复编译问题"`
Expected: 提交成功

---

## Phase 2: 单元测试补充

### Task 7: 补充 LogAnalysisService 测试

**Files:**
- Create: `src/test/java/com/huawei/hisi/service/LogAnalysisExecutorTest.java`

**Step 1: 编写测试类**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.repository.LogAnalysisRepository;
import com.huawei.hisi.service.LogAnalysisExecutor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LogAnalysisExecutorTest {

    @Mock
    private LogAnalysisRepository repository;

    @Mock
    private RootCauseAnalysisService rootCauseAnalysisService;

    @InjectMocks
    private LogAnalysisExecutor executor;

    @Test
    @DisplayName("执行分析任务 - 成功场景")
    void executeAnalysis_success() {
        // Given
        Long reportId = 123456789L;

        // When
        executor.executeAnalysis(reportId);

        // Then - 验证方法调用
        verify(repository).findById(reportId);
    }
}
```

**Step 2: 运行测试验证**

Run: `mvn test -Dtest=LogAnalysisExecutorTest`
Expected: 测试通过

**Step 3: 提交测试代码**

Run: `git add . && git commit -m "test: 添加 LogAnalysisExecutor 测试"`
Expected: 提交成功

---

### Task 8: 补充 LLMService 测试

**Files:**
- Create: `src/test/java/com/huawei/hisi/service/LLMServiceImplTest.java`

**Step 1: 编写测试类**

```java
package com.huawei.hisi.service;

import com.huawei.hisi.config.LLMConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class LLMServiceImplTest {

    @Mock
    private LLMConfig llmConfig;

    @InjectMocks
    private LLMServiceImpl llmService;

    @BeforeEach
    void setUp() {
        when(llmConfig.getBaseUrl()).thenReturn("http://localhost:8080/v1/chat");
        when(llmConfig.getApiKey()).thenReturn("test-key");
        when(llmConfig.getModelName()).thenReturn("test-model");
        when(llmConfig.getTimeout()).thenReturn(30000);
    }

    @Test
    @DisplayName("生成文本 - 正常调用")
    void generateText_success() {
        // 测试 LLM 服务调用
        // 注意：实际测试需要 Mock HTTP 客户端
    }
}
```

**Step 2: 运行测试验证**

Run: `mvn test -Dtest=LLMServiceImplTest`
Expected: 测试通过

**Step 3: 提交测试代码**

Run: `git add . && git commit -m "test: 添加 LLMService 测试"`
Expected: 提交成功

---

### Task 9: 全量测试回归

**Files:**
- 无新增文件，运行所有测试

**Step 1: 运行全量测试**

Run: `mvn test`
Expected: 显示所有测试结果

**Step 2: 分析测试报告**

检查 `target/surefire-reports/` 中的测试报告

**Step 3: 修复失败的测试（如有）**

针对失败的测试进行修复

**Step 4: 确保测试通过率 100%**

Run: `mvn test` 再次验证
Expected: BUILD SUCCESS, 所有测试通过

---

## Phase 3: 文档生成

### Task 10: 生成 API 接口文档

**Files:**
- Create: `docs/api-document.md`

**Step 1: 分析所有 Controller**

使用 Agent 工具提取所有接口信息

**Step 2: 生成 Markdown 文档**

```markdown
# HiSi DevTool API 接口文档

## 1. 日志分析模块

### 1.1 查询日志

**POST** `/api/log/query`

请求体：
\`\`\`json
{
  "appId": "string",
  "startTime": "2024-01-01T00:00:00",
  "endTime": "2024-01-02T00:00:00",
  "logLevel": "ERROR",
  "keyword": "string"
}
\`\`\`

响应：
\`\`\`json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 10,
    "logs": [...]
  }
}
\`\`\`

... (其他接口类似)
```

**Step 3: 提交文档**

Run: `git add docs/api-document.md && git commit -m "docs: 生成 API 接口文档"`
Expected: 提交成功

---

### Task 11: 生成 .claude 项目说明文件

**Files:**
- Create: `.claude/CLAUDE.md`
- Create: `.claude/settings.json`

**Step 1: 创建 .claude 目录**

Run: `mkdir -p C:\Users\47583\projects\hisi-dev-tool\.claude`
Expected: 目录创建成功

**Step 2: 创建 CLAUDE.md**

```markdown
# HiSi DevTool 项目说明

## 项目概述

华为开发者工具平台，提供日志分析、调用链分析、项目管理、运维监控等功能。

## 技术栈

- **后端**: Spring Boot 3.2.0 + Java 17
- **数据库**: OpenGauss (PostgreSQL 兼容)
- **前端**: Vue 3 + TypeScript + Element Plus

## 项目结构

\`\`\`
hisi-dev-tool/
├── src/main/java/com/huawei/hisi/
│   ├── config/          # 配置类
│   ├── controller/      # REST 控制器
│   ├── service/         # 业务服务层
│   ├── model/           # 数据模型
│   ├── repository/      # 数据访问层
│   └── utils/           # 工具类
└── src/main/resources/
    ├── application.yml
    └── db/migration/    # 数据库迁移脚本
\`\`\`

## 开发指南

### 环境要求

- Java 17+
- Maven 3.6+
- OpenGauss/PostgreSQL 数据库

### 启动命令

\`\`\`bash
mvn spring-boot:run
\`\`\`

### 测试命令

\`\`\`bash
mvn test
\`\`\`

## API 端点

| 模块 | 基础路径 |
|------|----------|
| 日志分析 | `/api/log/*` |
| 调用链 | `/api/callchain/*` |
| 项目管理 | `/api/projects/*` |
| 运维监控 | `/api/ops/*` |

## 注意事项

1. 配置文件中的敏感信息已脱敏，使用环境变量替代
2. LLM 服务需要配置有效的 API Key
3. 日志云服务需要配置认证信息
```

**Step 3: 提交配置**

Run: `git add .claude/ && git commit -m "docs: 添加 .claude 项目说明"`
Expected: 提交成功

---

## Phase 4: 前端项目开发

### Task 12: 创建前端项目骨架

**Files:**
- Create: `hisi-dev-tool-frontend/` 目录及基础文件

**Step 1: 使用 Vite 创建项目**

Run: `cd C:\Users\47583\projects && npm create vite@latest hisi-dev-tool-frontend -- --template vue-ts`
Expected: 项目创建成功

**Step 2: 安装依赖**

Run: `cd hisi-dev-tool-frontend && npm install`
Expected: 依赖安装成功

**Step 3: 安装额外依赖**

Run: `cd hisi-dev-tool-frontend && npm install element-plus @element-plus/icons-vue axios pinia vue-router@4 echarts`
Expected: 依赖安装成功

**Step 4: 提交初始项目**

Run: `cd hisi-dev-tool-frontend && git init && git add . && git commit -m "feat: 初始化 Vue 3 前端项目"`
Expected: 提交成功

---

### Task 13: 配置前端项目

**Files:**
- Modify: `vite.config.ts`
- Modify: `tsconfig.json`
- Create: `.env.development`

**Step 1: 配置 Vite**

```typescript
// vite.config.ts
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      }
    }
  }
})
```

**Step 2: 创建环境配置**

```env
# .env.development
VITE_API_BASE_URL=http://localhost:8080
VITE_APP_TITLE=HiSi DevTool
```

**Step 3: 提交配置**

Run: `git add . && git commit -m "config: 配置 Vite 和环境变量"`
Expected: 提交成功

---

### Task 14: 实现布局组件

**Files:**
- Create: `src/components/layout/AppLayout.vue`
- Create: `src/components/layout/AppHeader.vue`
- Create: `src/components/layout/AppSidebar.vue`

**Step 1: 创建主布局**

```vue
<!-- src/components/layout/AppLayout.vue -->
<template>
  <el-container class="app-layout">
    <el-aside width="220px">
      <AppSidebar />
    </el-aside>
    <el-container>
      <el-header>
        <AppHeader />
      </el-header>
      <el-main>
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import AppHeader from './AppHeader.vue'
import AppSidebar from './AppSidebar.vue'
</script>

<style scoped>
.app-layout {
  height: 100vh;
}
</style>
```

**Step 2: 创建侧边栏**

```vue
<!-- src/components/layout/AppSidebar.vue -->
<template>
  <el-menu
    :default-active="activeMenu"
    router
    class="sidebar-menu"
  >
    <el-menu-item index="/log-analysis">
      <el-icon><Document /></el-icon>
      <span>日志分析</span>
    </el-menu-item>
    <el-menu-item index="/call-chain">
      <el-icon><Share /></el-icon>
      <span>调用链分析</span>
    </el-menu-item>
    <el-menu-item index="/project">
      <el-icon><Folder /></el-icon>
      <span>项目管理</span>
    </el-menu-item>
    <el-menu-item index="/ops">
      <el-icon><Monitor /></el-icon>
      <span>运维监控</span>
    </el-menu-item>
  </el-menu>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Document, Share, Folder, Monitor } from '@element-plus/icons-vue'

const route = useRoute()
const activeMenu = computed(() => route.path)
</script>
```

**Step 3: 创建头部**

```vue
<!-- src/components/layout/AppHeader.vue -->
<template>
  <div class="app-header">
    <h1>HiSi DevTool</h1>
  </div>
</template>

<script setup lang="ts">
</script>

<style scoped>
.app-header {
  display: flex;
  align-items: center;
  height: 100%;
}
.app-header h1 {
  font-size: 18px;
  margin: 0;
}
</style>
```

**Step 4: 提交布局组件**

Run: `git add . && git commit -m "feat: 实现布局组件"`
Expected: 提交成功

---

### Task 15: 配置路由

**Files:**
- Create: `src/router/index.ts`

**Step 1: 创建路由配置**

```typescript
// src/router/index.ts
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    redirect: '/log-analysis'
  },
  {
    path: '/log-analysis',
    name: 'LogAnalysis',
    component: () => import('@/views/log-analysis/LogQuery.vue'),
    meta: { title: '日志分析' }
  },
  {
    path: '/call-chain',
    name: 'CallChain',
    component: () => import('@/views/call-chain/ProjectList.vue'),
    meta: { title: '调用链分析' }
  },
  {
    path: '/project',
    name: 'Project',
    component: () => import('@/views/project/ProjectList.vue'),
    meta: { title: '项目管理' }
  },
  {
    path: '/ops',
    name: 'Ops',
    component: () => import('@/views/ops/HealthCheck.vue'),
    meta: { title: '运维监控' }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router
```

**Step 2: 在 main.ts 中注册路由**

```typescript
// src/main.ts
import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'

import App from './App.vue'
import router from './router'

const app = createApp(App)

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

app.mount('#app')
```

**Step 3: 更新 App.vue**

```vue
<!-- src/App.vue -->
<template>
  <AppLayout />
</template>

<script setup lang="ts">
import AppLayout from '@/components/layout/AppLayout.vue'
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}
</style>
```

**Step 4: 提交路由配置**

Run: `git add . && git commit -m "feat: 配置 Vue Router"`
Expected: 提交成功

---

### Task 16: 实现 API 封装

**Files:**
- Create: `src/utils/request.ts`
- Create: `src/api/logAnalysis.ts`
- Create: `src/api/callChain.ts`
- Create: `src/api/project.ts`
- Create: `src/api/ops.ts`

**Step 1: 创建 Axios 封装**

```typescript
// src/utils/request.ts
import axios, { AxiosInstance, AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'

const request: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 60000
})

request.interceptors.response.use(
  (response: AxiosResponse) => {
    const { data } = response
    if (data.code === 200) {
      return data
    }
    ElMessage.error(data.message || '请求失败')
    return Promise.reject(new Error(data.message))
  },
  (error) => {
    ElMessage.error(error.message || '网络错误')
    return Promise.reject(error)
  }
)

export default request
```

**Step 2: 创建日志分析 API**

```typescript
// src/api/logAnalysis.ts
import request from '@/utils/request'
import type { LogQueryDto, LogAnalyzeRequest, AnalyzeTaskResponse } from '@/types'

export const logAnalysisApi = {
  // 查询日志
  queryLogs(data: LogQueryDto) {
    return request.post('/api/log/query', data)
  },

  // 提交分析任务
  analyze(data: LogAnalyzeRequest) {
    return request.post<AnalyzeTaskResponse>('/api/log/analyze', data)
  },

  // 获取报告列表
  getReports(params: { userId?: string; status?: string; page?: number; pageSize?: number }) {
    return request.get('/api/log/reports', { params })
  },

  // 获取报告详情
  getReport(id: number) {
    return request.get(`/api/log/report/${id}`)
  },

  // 获取任务状态
  getStatus(id: number) {
    return request.get(`/api/log/report/${id}/status`)
  }
}
```

**Step 3: 创建其他 API 文件**

类似地创建 `callChain.ts`, `project.ts`, `ops.ts`

**Step 4: 提交 API 封装**

Run: `git add . && git commit -m "feat: 实现 API 封装"`
Expected: 提交成功

---

### Task 17: 实现日志分析模块

**Files:**
- Create: `src/views/log-analysis/LogQuery.vue`
- Create: `src/views/log-analysis/AnalyzeTask.vue`
- Create: `src/views/log-analysis/ReportDetail.vue`

**Step 1: 创建日志查询页面**

```vue
<!-- src/views/log-analysis/LogQuery.vue -->
<template>
  <div class="log-query">
    <el-card header="日志查询">
      <el-form :model="queryForm" label-width="100px">
        <el-row :gutter="20">
          <el-col :span="8">
            <el-form-item label="应用ID">
              <el-input v-model="queryForm.appId" />
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="日志级别">
              <el-select v-model="queryForm.logLevel" clearable>
                <el-option label="ERROR" value="ERROR" />
                <el-option label="WARN" value="WARN" />
                <el-option label="INFO" value="INFO" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item>
              <el-button type="primary" @click="handleQuery">查询</el-button>
            </el-form-item>
          </el-col>
        </el-row>
      </el-form>
    </el-card>

    <el-card header="查询结果" class="mt-4">
      <el-table :data="logs" v-loading="loading">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column prop="level" label="级别" width="80" />
        <el-table-column prop="message" label="消息" />
        <el-table-column prop="timestamp" label="时间" width="180" />
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { logAnalysisApi } from '@/api/logAnalysis'
import type { LogEntry } from '@/types'

const loading = ref(false)
const logs = ref<LogEntry[]>([])

const queryForm = reactive({
  appId: '',
  logLevel: 'ERROR'
})

const handleQuery = async () => {
  loading.value = true
  try {
    const res = await logAnalysisApi.queryLogs(queryForm)
    logs.value = res.data.logs || []
  } finally {
    loading.value = false
  }
}
</script>
```

**Step 2: 创建分析任务页面**

**Step 3: 创建报告详情页面**

**Step 4: 提交日志分析模块**

Run: `git add . && git commit -m "feat: 实现日志分析模块"`
Expected: 提交成功

---

### Task 18: 实现调用链分析模块

**Files:**
- Create: `src/views/call-chain/ProjectList.vue`
- Create: `src/views/call-chain/UriList.vue`
- Create: `src/views/call-chain/CallChainGraph.vue`

**Step 1: 创建项目列表页面**

**Step 2: 创建 URI 列表页面**

**Step 3: 创建调用链可视化页面（使用 ECharts）**

**Step 4: 提交调用链模块**

Run: `git add . && git commit -m "feat: 实现调用链分析模块"`
Expected: 提交成功

---

### Task 19: 实现项目管理模块

**Files:**
- Create: `src/views/project/ProjectList.vue`
- Create: `src/views/project/CloneProject.vue`

**Step 1: 创建项目列表页面**

**Step 2: 创建克隆项目页面**

**Step 3: 提交项目管理模块**

Run: `git add . && git commit -m "feat: 实现项目管理模块"`
Expected: 提交成功

---

### Task 20: 实现运维监控模块

**Files:**
- Create: `src/views/ops/HealthCheck.vue`
- Create: `src/views/ops/ImpactAnalysis.vue`
- Create: `src/views/ops/ApiDocs.vue`

**Step 1: 创建健康检查页面**

**Step 2: 创建影响分析页面**

**Step 3: 创建接口文档页面**

**Step 4: 提交运维监控模块**

Run: `git add . && git commit -m "feat: 实现运维监控模块"`
Expected: 提交成功

---

### Task 21: 前端构建与测试

**Files:**
- 无新增文件，验证构建

**Step 1: 运行开发服务器**

Run: `cd hisi-dev-tool-frontend && npm run dev`
Expected: 服务启动在 http://localhost:5173

**Step 2: 构建生产版本**

Run: `cd hisi-dev-tool-frontend && npm run build`
Expected: 生成 dist/ 目录

**Step 3: 最终提交**

Run: `git add . && git commit -m "feat: 前端项目完成"`
Expected: 提交成功

---

## Phase 5: 最终验收

### Task 22: 后端启动验证

**Step 1: 启动后端服务**

Run: `cd hisi-dev-tool && mvn spring-boot:run`
Expected: 服务启动在 http://localhost:8080

**Step 2: 验证健康检查**

Run: `curl http://localhost:8080/api/ops/health`
Expected: 返回健康状态 JSON

---

### Task 23: 前后端联调

**Step 1: 同时启动前后端**

**Step 2: 测试各模块功能**

- 日志查询功能
- 分析任务提交
- 调用链查询
- 项目克隆

**Step 3: 修复发现的问题**

---

### Task 24: 生成最终文档

**Files:**
- Create: `docs/deployment-guide.md`

**Step 1: 编写部署指南**

**Step 2: 更新 README**

**Step 3: 最终提交**

Run: `git add . && git commit -m "docs: 完善项目文档"`
Expected: 提交成功

---

## 任务清单汇总

| Phase | Task | 描述 | 预计时间 |
|-------|------|------|----------|
| 1 | Task 1 | 创建解析脚本 | 10min |
| 1 | Task 2 | 补充基础文件 | 10min |
| 1 | Task 3 | 初始化 Git | 5min |
| 1 | Task 4 | 敏感信息脱敏 | 10min |
| 1 | Task 5 | 代码结构分析 | 20min |
| 1 | Task 6 | Maven 构建验证 | 15min |
| 2 | Task 7 | LogAnalysisService 测试 | 15min |
| 2 | Task 8 | LLMService 测试 | 15min |
| 2 | Task 9 | 全量测试回归 | 20min |
| 3 | Task 10 | API 文档生成 | 20min |
| 3 | Task 11 | .claude 文件 | 15min |
| 4 | Task 12 | 前端项目骨架 | 10min |
| 4 | Task 13 | 前端配置 | 10min |
| 4 | Task 14 | 布局组件 | 20min |
| 4 | Task 15 | 路由配置 | 15min |
| 4 | Task 16 | API 封装 | 20min |
| 4 | Task 17 | 日志分析模块 | 30min |
| 4 | Task 18 | 调用链模块 | 30min |
| 4 | Task 19 | 项目管理模块 | 20min |
| 4 | Task 20 | 运维监控模块 | 25min |
| 4 | Task 21 | 前端构建测试 | 15min |
| 5 | Task 22 | 后端启动验证 | 10min |
| 5 | Task 23 | 前后端联调 | 30min |
| 5 | Task 24 | 最终文档 | 20min |

**总计预计时间**: 约 5-6 小时