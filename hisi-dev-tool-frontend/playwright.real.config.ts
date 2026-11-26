import { defineConfig } from '@playwright/test'

/**
 * 真实环境的页面 E2E：不打桩，前端开发服务器 + 真实后端 + 真实模型调用，以 test-projects 之外的真实项目作为分析对象。
 * 与默认配置分开：单 worker 串行（模型调用慢、且共用同一个后端状态），超时按分钟计。
 * 运行：npx playwright test -c playwright.real.config.ts --output <仓库外的目录>
 */
export default defineConfig({
  testDir: './e2e-real',
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 60 * 60 * 1000,
  expect: { timeout: 60_000 },
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
    screenshot: 'only-on-failure',
    trace: 'off',
    video: 'off',
  },
  projects: [{ name: 'chromium', use: { viewport: { width: 1600, height: 1000 } } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 60000,
  },
})
