import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useAppStore } from '@/stores/app'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    redirect: '/project'
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
    path: '/kg-skills-kit',
    name: 'KgSkillsKit',
    component: () => import('@/views/kg-skills-kit/KgSkillsKit.vue'),
    meta: {
      title: 'KG Skills 套件'
    }
  },
  {
    path: '/ram',
    name: 'RamSessions',
    component: () => import('@/views/ram/SessionListPage.vue'),
    meta: { title: '需求分析大师' }
  },
  {
    path: '/ram/status',
    name: 'StatusSessions',
    component: () => import('@/views/ram/StatusSessionListPage.vue'),
    meta: { title: '项目现状分析' }
  },
  {
    path: '/ram/new',
    name: 'RamInput',
    component: () => import('@/views/ram/InputPage.vue'),
    meta: { title: '新建需求分析' }
  },
  {
    path: '/ram/status/new',
    name: 'RamStatusInput',
    component: () => import('@/views/ram/StatusInputPage.vue'),
    meta: { title: '新建项目现状分析' }
  },
  {
    path: '/ram/draft/:sid',
    name: 'RamDraft',
    component: () => import('@/views/ram/DraftPage.vue'),
    meta: { title: 'RAM 草稿' }
  },
  {
    path: '/ram/status/:sid',
    name: 'RamStatus',
    component: () => import('@/views/ram/StatusPage.vue'),
    meta: { title: '项目现状分析' }
  },
  {
    path: '/ram/phase2/:sid',
    name: 'RamPhase2',
    component: () => import('@/views/ram/Phase2Page.vue'),
    meta: { title: '精确位置分析' }
  },
  {
    path: '/ram/graph/:sid',
    name: 'RamGraph',
    component: () => import('@/views/ram/GraphPreviewPage.vue'),
    meta: { title: 'RAM 影响图谱' }
  },
  {
    path: '/ram/chat',
    name: 'RamChat',
    component: () => import('@/views/ram/RamChatView.vue'),
    meta: { title: 'RAM 对话' }
  },
  {
    path: '/ram/chat/:sid',
    name: 'RamChatSession',
    component: () => import('@/views/ram/RamChatView.vue'),
    meta: { title: 'RAM 对话' }
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
    path: '/fix/chat',
    name: 'FixChat',
    component: () => import('@/views/fix/FixChatView.vue'),
    meta: { title: '异常修复' }
  },
  {
    path: '/fix/chat/:sid',
    name: 'FixChatSession',
    component: () => import('@/views/fix/FixChatView.vue'),
    meta: { title: '异常修复' }
  },
  {
    path: '/settings',
    name: 'Settings',
    component: () => import('@/views/settings/SettingsView.vue'),
    meta: {
      title: '系统设置'
    }
  },
  {
    path: '/admin/users',
    name: 'UserManagement',
    component: () => import('@/views/admin/UserManagement.vue'),
    meta: {
      title: '用户管理',
      requiresAdmin: true
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

  // Admin route guard
  if (to.meta.requiresAdmin) {
    const { useAuthStore } = await import('@/stores/auth')
    const authStore = useAuthStore()
    if (!authStore.initialized) {
      await authStore.init()
    }
    if (!authStore.isAdmin) {
      ElMessage.warning('仅管理员可访问此页面')
      return next('/')
    }
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

  if (to.path.startsWith('/ram') && !menuAvailability['ram']) {
    ElMessage.warning('请先在项目管理页面选择项目')
    return next('/project')
  }

  next()
})

export default router