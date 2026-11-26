import type { Page, WebSocketRoute } from '@playwright/test';

/**
 * Agent 流 v2 页面测试用的后端替身。
 *
 * 把 /api/** 与 /ws/log-analysis 都替换成固定应答：测试不依赖后端是否启动，也不产生模型调用。
 * 没有登记的接口一律返回 404，并在请求记录里留下痕迹——页面如果调了不该调的接口（例如 v1 的），测试能发现。
 */
export const TOKEN = 'e2e-token';
export const REPORT_ID = '9001';
export const SESSION = 'sess-v2-e2e';
export const PROJECT = { name: 'demo-svc', path: 'C:/proj/demo-svc' };

export interface ApiCall {
  method: string
  path: string
  search: string
  body: unknown
}

export interface MockState {
  reportStatus: 'completed' | 'processing' | 'failed'
  updatedAt: string
  calls: ApiCall[]
  /** 页面建立的 /ws/log-analysis 连接（已被测试接管，不连真实后端） */
  logSockets: WebSocketRoute[]
}

export const newState = (): MockState => ({
  reportStatus: 'completed',
  updatedAt: '2026-11-25T10:00:00',
  calls: [],
  logSockets: [],
});

/** 以 method + path 记录的请求，便于断言「调了哪些接口」。 */
export const callsOn = (state: MockState, prefix: string) =>
  state.calls.filter((c) => c.path.startsWith(prefix)).map((c) => `${c.method} ${c.path}`)

export const countCalls = (state: MockState, method: string, path: string) =>
  state.calls.filter((c) => c.method === method && c.path === path).length

const ok = (data: unknown) => ({
  status: 200,
  contentType: 'application/json',
  body: JSON.stringify({ code: 200, message: 'success', data }),
})

const sse = (events: Array<Record<string, unknown>>) => ({
  status: 200,
  contentType: 'text/event-stream',
  headers: { 'Cache-Control': 'no-cache' },
  body: events.map((e, i) => `data:${JSON.stringify({ seq: i + 1, ...e })}\n\n`).join(''),
})

/** 一份 v2 日志报告：结构化字段 + v2 特有的证据与验证结果。 */
export const logReport = (updatedAt: string) => ({
  reportId: REPORT_ID,
  status: 'completed',
  createdAt: '2026-11-25T10:00:00',
  updatedAt,
  occurrenceCount: 1,
  errorSummary: {
    errorType: 'NullPointerException',
    rootCauseException: 'java.lang.NullPointerException',
    message: 'Cannot invoke "User.getEmail()" because "user" is null',
    stackTracePreview: 'at com.example.controller.UserController.getUser(UserController.java:20)',
  },
  rootCause: {
    rootCauseType: 'java.lang.NullPointerException',
    confidence: 'low',
    markdown: '## 根因结论\n\n下游方法总会返回非空对象 [[E4]]，与异常消息矛盾。',
    evidenceAnchors: [
      { id: 'E1', kind: 'frame', kindLabel: '堆栈帧', summary: 'UserController.getUser() 第 20 行 [抛出点]' },
      {
        id: 'E4',
        kind: 'source',
        kindLabel: '源码',
        summary: 'UserServiceImpl.getUserById() 的源码',
        className: 'com.example.service.UserServiceImpl',
        methodName: 'getUserById',
      },
    ],
    verification: {
      overall: { total: 1, kept: 1 },
      hypotheses: [
        {
          id: 'H1',
          title: '下游方法与异常消息矛盾',
          confidence: 'low',
          chainStatus: 'at_throw_site',
          kept: true,
          citedIds: ['E1', 'E4'],
          unknownIds: [],
          reasons: ['根因落在异常抛出处，未见更上游的依据'],
        },
      ],
    },
  },
  fixSuggestions: [
    {
      description: '核对第 20 行实际解引用的对象',
      priority: 'P0',
      affectedCode: 'com.example.controller.UserController.getUser',
      expectedEffect: '消除第 20 行的空指针访问',
    },
    {
      description: '核对运行环境部署的版本与源码是否一致',
      priority: 'P1',
      affectedCode: 'com.example.service.UserServiceImpl.getUserById',
    },
  ],
  codeSnippets: [
    { className: 'UserController', methodName: 'getUser', lineNumber: 20, fileName: 'UserController.java' },
  ],
})

/**
 * 分析失败后的报告：重新分析一开始就清空了结果列，失败后四个段落都没有。
 * 任务状态与阶段都是 failed（后端 updateError + updateAnalysisStatus("failed")）。
 */
const failedReport = (updatedAt: string) => ({
  reportId: REPORT_ID,
  status: 'failed',
  createdAt: '2026-11-25T10:00:00',
  updatedAt,
  occurrenceCount: 1,
})

/** 架构深度分析的节点输出，结构与后端 ArchReActAgentNode.buildOutput 一致。 */
export const ARCH_REPORT = {
  projectPaths: [PROJECT.path],
  rootCauseResult: {
    issues: [
      {
        title: '知识图谱域与语义搜索域存在循环依赖',
        severity: 'HIGH',
        verdict: 'confirmed',
        rootCause: '两个域互相引用',
        suggestion: '抽出共享契约',
        evidenceRefs: ['cycle_rings'],
      },
    ],
    report: {
      executive_summary: '项目架构健康度 10 分。',
      views: {
        logical: { insight: '知识图谱域与语义搜索域相互依赖', diagramRefs: ['layered_architecture'] },
      },
      roadmap: { p0: [{ action: '拆除知识图谱域两个循环依赖' }] },
      evidence_appendix: 'injection_scan 抽样返回 0 条。',
    },
    degraded: false,
  },
  markdownReport: '### 执行摘要\n\n项目架构健康度 10 分。\n\n### 重构路线图\n\n- 拆分循环依赖',
  diagrams: [],
  degraded: false,
}

const ARCH_EVENTS: Array<Record<string, unknown>> = [
  { type: 'NODE_START', payload: { nodeName: 'arch_evidence_collect' } },
  { type: 'NODE_DELTA', payload: { nodeName: 'arch_evidence_collect', message: '诊断报告生成完成' } },
  { type: 'NODE_COMPLETE', payload: { nodeName: 'arch_evidence_collect' } },
  { type: 'NODE_START', payload: { nodeName: 'arch_react_agent' } },
  { type: 'NODE_DELTA', payload: { nodeName: 'arch_react_agent', message: '推理（第 1 轮）…' } },
  { type: 'CHECKPOINT', payload: { nodeName: 'arch_react_agent', output: ARCH_REPORT } },
  { type: 'NODE_COMPLETE', payload: { nodeName: 'arch_react_agent' } },
  { type: 'RUN_COMPLETED', payload: {} },
]

export async function installBackend(page: Page, state: MockState) {
  // 已登录的管理员 + 已选中的项目：满足路由守卫，页面才会放行架构诊断
  await page.addInitScript(
    ([token, project]) => {
      localStorage.setItem('hisi-token', token)
      localStorage.setItem('hisi-selected-projects', JSON.stringify([project]))
    },
    [TOKEN, PROJECT] as const,
  )

  await page.routeWebSocket(/\/ws\/log-analysis/, (socket) => {
    state.logSockets.push(socket)
    socket.onMessage(() => {})
  })

  // 只拦截路径以 /api/ 开头的请求。不能用 '**/api/**'：它还会匹配 Vite 的源码模块 /src/api/*.ts，把页面本身弄坏。
  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const method = request.method()
    let body: unknown = null
    try {
      body = request.postDataJSON()
    } catch {
      body = null
    }
    state.calls.push({ method, path: url.pathname, search: url.search, body })

    const path = url.pathname
    const json = (data: unknown) => route.fulfill(ok(data))
    const unexpected = () =>
      route.fulfill({
        status: 404,
        contentType: 'application/json',
        body: JSON.stringify({ code: 404, message: `E2E 未预期的请求: ${method} ${path}` }),
      })

    if (path === '/api/config/project-dir') return json({ key: 'project_dir', value: 'C:/proj' })
    if (path === '/api/auth/me') return json({ username: 'root', role: 'ADMIN' })

    // —— 日志分析 v2 ——
    if (path === `/api/v2/log/report/${REPORT_ID}/status`) {
      // 与后端 getReportStatus 一致：completed 与 failed 的进度都是 100，阶段名就是 analysisStatus
      const settled = state.reportStatus !== 'processing'
      return json({
        reportId: REPORT_ID,
        status: state.reportStatus,
        progress: settled ? 100 : 75,
        stage: state.reportStatus === 'processing' ? 'analyzing' : state.reportStatus,
        etaSeconds: settled ? 0 : 30,
        queuePosition: 0,
      })
    }
    if (path === `/api/v2/log/report/${REPORT_ID}` && method === 'GET') {
      return json(state.reportStatus === 'failed' ? failedReport(state.updatedAt) : logReport(state.updatedAt))
    }
    if (path === `/api/v2/log/report/${REPORT_ID}/reanalyze` && method === 'POST') {
      state.reportStatus = 'processing'
      return json('已触发重新分析')
    }
    if (path === '/api/fix/sessions' && method === 'GET') return json([])

    // —— 架构深度分析 v2 ——
    if (path === '/api/v2/workflow/start' && method === 'POST') return json({ sessionId: SESSION })
    if (path === `/api/v2/workflow/sessions/${SESSION}/stream`) return route.fulfill(sse(ARCH_EVENTS))
    if (path === `/api/v2/workflow/sessions/${SESSION}/report`) return json({ status: 'DONE', report: ARCH_REPORT })

    // —— 页面加载时的只读接口（与 v2 无关，给个空壳） ——
    if (path === '/api/architecture-diagnosis/report') {
      return json({ generatedAt: '2026-11-25T10:00:00', healthScore: 0, grade: 'N/A', issues: [] })
    }
    if (path === '/api/architecture-diagnosis/snapshots') return json([])

    return unexpected()
  })
}
