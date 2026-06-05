import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/project'
  },
  {
    path: '/mcp-guide',
    redirect: '/skill-market'
  },
  {
    path: '/apm-debug',
    name: 'ApmDebug',
    component: () => import('@/views/apm-debug/ApmDebugView.vue'),
    meta: { title: 'APM 调试' }
  },
  {
    path: '/claude-session',
    name: 'ClaudeSession',
    component: () => import('@/views/claude-session/ClaudeSession.vue'),
    meta: { title: 'Claude 会话' }
  },
  {
    path: '/claude-terminal',
    name: 'ClaudeTerminal',
    component: () => import('@/views/claude-terminal/ClaudeTerminal.vue'),
    meta: { title: 'Claude 终端' }
  },
  {
    path: '/search',
    name: 'SemanticSearch',
    component: () => import('@/views/search/SemanticSearchView.vue'),
    meta: { title: '增强检索' }
  },
  {
    path: '/log-analysis',
    name: 'LogAnalysis',
    component: () => import('@/views/log-analysis/LogQuery.vue'),
    meta: {
      title: '日志分析'
    }
  },
  {
    path: '/log-analysis/report/:id',
    name: 'ReportDetail',
    component: () => import('@/views/log-analysis/ReportDetail.vue'),
    meta: {
      title: '报告详情'
    }
  },
  {
    path: '/prompt-config',
    name: 'PromptConfig',
    component: () => import('@/views/prompt-config/PromptConfig.vue'),
    meta: {
      title: '提示词配置'
    }
  },
  {
    path: '/glossary',
    name: 'Glossary',
    component: () => import('@/views/glossary/GlossaryView.vue'),
    meta: {
      title: '术语管理'
    }
  },
  {
    path: '/call-chain',
    redirect: '/knowledge-graph?tab=methodRef'
  },
  {
    path: '/call-chain/uri-chain',
    redirect: '/knowledge-graph?tab=methodRef'
  },
  {
    path: '/call-chain/method-reference',
    redirect: '/knowledge-graph?tab=methodRef'
  },
  {
    path: '/call-chain/chain',
    redirect: '/knowledge-graph?tab=methodRef'
  },
  {
    path: '/knowledge-graph',
    name: 'KnowledgeGraph',
    component: () => import('@/views/knowledge-graph/KnowledgeGraphView.vue'),
    meta: {
      title: '知识图谱分析'
    }
  },
  {
    path: '/project',
    name: 'Project',
    component: () => import('@/views/project/ProjectList.vue'),
    meta: {
      title: '项目管理'
    }
  },
  {
    path: '/skill-market',
    name: 'SkillMarket',
    component: () => import('@/views/skill-market/SkillMarket.vue'),
    meta: {
      title: '技能市场'
    }
  },
  {
    path: '/ram',
    name: 'RamSessions',
    component: () => import('@/views/ram/SessionListPage.vue'),
    meta: { title: '需求分析大师' }
  },
  {
    path: '/ram/new',
    name: 'RamInput',
    component: () => import('@/views/ram/InputPage.vue'),
    meta: { title: '新建需求分析' }
  },
  {
    path: '/ram/draft/:sid',
    name: 'RamDraft',
    component: () => import('@/views/ram/DraftPage.vue'),
    meta: { title: 'RAM 草稿' }
  },
  {
    path: '/ram/graph/:sid',
    name: 'RamGraph',
    component: () => import('@/views/ram/GraphPreviewPage.vue'),
    meta: { title: 'RAM 影响图谱' }
  },
  {
    path: '/merge-analysis',
    name: 'MergeAnalysisSessions',
    component: () => import('@/views/merge-analysis/SessionListPage.vue'),
    meta: { title: '合入分析' }
  },
  {
    path: '/merge-analysis/new',
    name: 'MergeAnalysisInput',
    component: () => import('@/views/merge-analysis/InputPage.vue'),
    meta: { title: '新建合入分析' }
  },
  {
    path: '/merge-analysis/diff',
    name: 'MergeAnalysisDiff',
    component: () => import('@/views/merge-analysis/DiffPreviewPage.vue'),
    meta: { title: 'Diff 预览' }
  },
  {
    path: '/merge-analysis/result',
    name: 'MergeAnalysisResult',
    component: () => import('@/views/merge-analysis/AnalysisPage.vue'),
    meta: { title: '影响分析结果' }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/settings/SettingsView.vue'),
    meta: {
      title: '系统设置'
    }
  },
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

router.beforeEach(async (to, _from, next) => {
  // Set document title
  const title = to.meta.title as string
  if (title) {
    document.title = `${title} - HiSi Dev Tool`
  }

  // Load config on first navigation
  const appStore = useAppStore()
  if (!appStore.projectDir && !appStore.configLoading) {
    await appStore.loadProjectDir()
    // Check if there was an error loading config
    if (appStore.configError) {
      console.error('Failed to load project configuration:', appStore.configError)
      // Still proceed - user can try to configure manually
    }
  }

  // Check menu availability
  const menuAvailability = appStore.availableMenus

  if (to.path.startsWith('/knowledge-graph') && !menuAvailability['knowledge-graph']) {
    ElMessage.warning('请先在项目管理页面选择项目')
    return next('/project')
  }

  if (to.path.startsWith('/search') && !menuAvailability['search']) {
    ElMessage.warning('请先在项目管理页面选择项目')
    return next('/project')
  }

  if (to.path.startsWith('/log-analysis') && !menuAvailability['log-analysis']) {
    ElMessage.warning('请先在项目管理页面选择项目')
    return next('/project')
  }

  // Redirect mcp-guide to skill-market (replaced)
  if (to.path === '/mcp-guide') {
    return next('/skill-market')
  }

  next()
})

export default router