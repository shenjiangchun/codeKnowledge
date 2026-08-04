# 07-部署运维

## 概述

本文档描述 HiSi DevTool Frontend 的构建、部署和运维相关配置。

---

## 构建配置

### Vite 配置

**路径**：`vite.config.ts`

**核心配置**：
```typescript
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { resolve } from 'path'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': resolve(__dirname, 'src')
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws': {
        target: 'ws://localhost:8080',
        ws: true
      }
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia'],
          echarts: ['echarts'],
          element: ['element-plus']
        }
      }
    }
  }
})
```

### TypeScript 配置

**路径**：`tsconfig.json`

**核心配置**：
```json
{
  "compilerOptions": {
    "target": "ES2020",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "jsx": "preserve",
    "resolveJsonModule": true,
    "isolatedModules": true,
    "esModuleInterop": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "skipLibCheck": true,
    "noEmit": true,
    "paths": {
      "@/*": ["./src/*"]
    }
  },
  "include": ["src/**/*.ts", "src/**/*.d.ts", "src/**/*.tsx", "src/**/*.vue"],
  "references": [{ "path": "./tsconfig.node.json" }]
}
```

---

## 构建命令

### 开发环境

```bash
# 启动开发服务器
npm run dev

# 启动开发服务器（指定端口）
npm run dev -- --port 3000
```

### 生产构建

```bash
# 构建生产版本
npm run build

# 预览生产构建
npm run preview
```

### 测试

```bash
# 单元测试
npm run test:unit

# 单元测试（监听模式）
npm run test:unit:watch

# 单元测试（覆盖率）
npm run test:unit:coverage

# E2E 测试
npm run test:e2e

# E2E 测试（UI 模式）
npm run test:e2e:ui

# E2E 测试（调试模式）
npm run test:e2e:debug
```

---

## 构建产物

### 目录结构

```
dist/
├── index.html
├── assets/
│   ├── index-[hash].js      # 主包
│   ├── index-[hash].css     # 样式
│   ├── vendor-[hash].js     # Vue/Vue Router/Pinia
│   ├── echarts-[hash].js    # ECharts
│   └── element-[hash].js    # Element Plus
├── favicon.ico
└── vite.svg
```

### 代码分割

Vite 自动进行代码分割：

| Chunk | 内容 | 大小估算 |
|-------|------|---------|
| `index-[hash].js` | 应用代码 | 500KB+ |
| `vendor-[hash].js` | Vue 核心 | 100KB+ |
| `echarts-[hash].js` | ECharts | 800KB+ |
| `element-[hash].js` | Element Plus | 500KB+ |

---

## 部署方式

### 1. 静态文件部署

将 `dist/` 目录部署到 Web 服务器：

**Nginx 配置**：
```nginx
server {
    listen 80;
    server_name frontend.example.com;
    root /path/to/dist;
    index index.html;

    # SPA 路由
    location / {
        try_files $uri $uri/ /index.html;
    }

    # API 代理
    location /api {
        proxy_pass http://backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # WebSocket 代理
    location /ws {
        proxy_pass http://backend:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }

    # 静态资源缓存
    location /assets {
        expires 1y;
        add_header Cache-Control "public, immutable";
    }
}
```

### 2. Docker 部署

**Dockerfile**：
```dockerfile
# 构建阶段
FROM node:18-alpine AS builder
WORKDIR /app
COPY package*.json ./
RUN npm ci
COPY . .
RUN npm run build

# 生产阶段
FROM nginx:alpine
COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
```

**docker-compose.yml**：
```yaml
version: '3.8'
services:
  frontend:
    build: .
    ports:
      - "80:80"
    depends_on:
      - backend
    networks:
      - app-network

  backend:
    image: hisi-dev-tool-backend
    ports:
      - "8080:8080"
    networks:
      - app-network

networks:
  app-network:
    driver: bridge
```

### 3. Kubernetes 部署

**deployment.yaml**：
```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: hisi-frontend
spec:
  replicas: 2
  selector:
    matchLabels:
      app: hisi-frontend
  template:
    metadata:
      labels:
        app: hisi-frontend
    spec:
      containers:
      - name: frontend
        image: hisi-frontend:latest
        ports:
        - containerPort: 80
        resources:
          requests:
            memory: "64Mi"
            cpu: "100m"
          limits:
            memory: "128Mi"
            cpu: "200m"
```

**service.yaml**：
```yaml
apiVersion: v1
kind: Service
metadata:
  name: hisi-frontend-service
spec:
  selector:
    app: hisi-frontend
  ports:
  - port: 80
    targetPort: 80
  type: LoadBalancer
```

---

## 环境变量

### 环境变量配置

**路径**：`.env`、`.env.development`、`.env.production`

**变量列表**：
| 变量 | 说明 | 默认值 |
|------|------|--------|
| `VITE_API_BASE_URL` | API 基础 URL | `/api` |
| `VITE_WS_BASE_URL` | WebSocket 基础 URL | `ws://localhost:8080` |
| `VITE_APP_TITLE` | 应用标题 | `HiSi Dev Tool` |

**使用示例**：
```typescript
// src/api/config.ts
const apiBaseUrl = import.meta.env.VITE_API_BASE_URL || '/api'
const wsBaseUrl = import.meta.env.VITE_WS_BASE_URL || 'ws://localhost:8080'
```

---

## 性能优化

### 1. 代码分割

Vite 自动进行代码分割，可以通过 `manualChunks` 配置优化：

```typescript
// vite.config.ts
export default defineConfig({
  build: {
    rollupOptions: {
      output: {
        manualChunks: {
          vendor: ['vue', 'vue-router', 'pinia'],
          echarts: ['echarts'],
          element: ['element-plus']
        }
      }
    }
  }
})
```

### 2. 懒加载

所有页面组件使用懒加载：

```typescript
// router/index.ts
const routes = [
  {
    path: '/project',
    component: () => import('@/views/project/ProjectList.vue')
  }
]
```

### 3. 静态资源优化

- 图片压缩
- 字体子集化
- CSS 压缩

### 4. 缓存策略

**Nginx 缓存配置**：
```nginx
# 静态资源长期缓存
location /assets {
    expires 1y;
    add_header Cache-Control "public, immutable";
}

# HTML 不缓存
location = /index.html {
    add_header Cache-Control "no-cache";
}
```

---

## 监控与日志

### 1. 错误监控

集成 Sentry 或其他错误监控服务：

```typescript
// main.ts
import * as Sentry from '@sentry/vue'

Sentry.init({
  app,
  dsn: 'https://examplePublicKey@o0.ingest.sentry.io/0',
  integrations: [
    new Sentry.BrowserTracing(),
    new Sentry.Replay()
  ],
  tracesSampleRate: 1.0,
  replaysSessionSampleRate: 0.1,
  replaysOnErrorSampleRate: 1.0
})
```

### 2. 性能监控

使用 Web Vitals 监控性能：

```typescript
// main.ts
import { onCLS, onFID, onLCP } from 'web-vitals'

onCLS(console.log)
onFID(console.log)
onLCP(console.log)
```

### 3. 日志收集

前端日志收集到后端：

```typescript
// utils/logger.ts
export function log(level: string, message: string, data?: unknown) {
  console[level](message, data)
  
  // 发送到后端
  fetch('/api/logs', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ level, message, data, timestamp: new Date().toISOString() })
  }).catch(() => {})
}
```

---

## CI/CD

### GitHub Actions

**路径**：`.github/workflows/ci.yml`

```yaml
name: CI

on:
  push:
    branches: [ main ]
  pull_request:
    branches: [ main ]

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
    - uses: actions/checkout@v3

    - name: Setup Node.js
      uses: actions/setup-node@v3
      with:
        node-version: '18'
        cache: 'npm'

    - name: Install dependencies
      run: npm ci

    - name: Run tests
      run: npm run test:unit

    - name: Build
      run: npm run build

    - name: Upload build artifacts
      uses: actions/upload-artifact@v3
      with:
        name: dist
        path: dist/
```

---

## 故障排查

### 常见问题

| 问题 | 原因 | 解决方案 |
|------|------|---------|
| 白屏 | 路由配置错误 | 检查 `nginx.conf` 的 `try_files` |
| API 请求失败 | 代理配置错误 | 检查 `vite.config.ts` 或 `nginx.conf` |
| WebSocket 连接失败 | WebSocket 代理配置错误 | 检查 `/ws` 代理配置 |
| 构建失败 | TypeScript 类型错误 | 运行 `vue-tsc --noEmit` 检查 |
| 内存溢出 | 依赖过大 | 检查 `manualChunks` 配置 |

### 调试工具

```bash
# 检查 TypeScript 类型
npx vue-tsc --noEmit

# 分析构建产物
npx vite-bundle-visualizer

# 检查依赖
npm ls
```

---

## 下一步

- [技术决策](../08-技术决策/index.md) - 了解技术选型决策
- [术语表](../09-术语表/index.md) - 了解项目术语
- [项目概览](../01-项目概览/index.md) - 了解项目整体情况
