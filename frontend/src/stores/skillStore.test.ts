/**
 * skillStore 单元测试
 * 测试技能市场 Store 状态管理
 *
 * 测试范围：
 * 1. 状态初始化和更新
 * 2. loadSkills() - 加载技能列表
 * 3. loadProjectStatus() - 加载项目状态
 * 4. installSkill() - 安装技能
 * 5. uninstallSkill() - 卸载技能
 * 6. 分类筛选功能
 * 7. 搜索功能
 * 8. 错误处理
 *
 * TDD 流程:
 * 1. RED - 编写失败的测试，验证期望的行为
 * 2. GREEN - 确保 Store 实现通过测试
 * 3. REFACTOR - 优化测试代码
 */

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { SkillDefinition, ProjectSkillStatus, SkillCategory, SkillMarketResponse } from '@/types/skill'

// Mock skillMarketApi
const mockSkillMarketApi = {
  getSkillList: vi.fn(),
  getProjectStatus: vi.fn(),
  getSkillDetail: vi.fn(),
  installSkill: vi.fn(),
  uninstallSkill: vi.fn(),
  checkUpdates: vi.fn(),
  updateSkill: vi.fn()
}
vi.mock('@/api/skillMarket', () => ({
  skillMarketApi: mockSkillMarketApi
}))

// Mock ElMessage
const mockElMessageFn = vi.fn()
const mockElMessage = Object.assign(mockElMessageFn, {
  error: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
  info: vi.fn()
})
vi.mock('element-plus', () => ({
  ElMessage: mockElMessage
}))

// 测试数据工厂
function createMockSkillDefinitions(): SkillDefinition[] {
  return [
    {
      id: 'hisi-dev-tool',
      name: 'HiSi DevTool MCP',
      category: 'diagnosis' as SkillCategory,
      icon: 'Connection',
      description: '开发者工具 MCP 服务',
      version: '1.0.0',
      tags: ['devtool', 'hisi', 'MCP'],
      files: [],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      isOfficial: true,
      downloadCount: 100,
      rating: 4.5
    },
    {
      id: 'pre-tool-use',
      name: '安全钩子',
      category: 'operation' as SkillCategory,
      icon: 'Shield',
      description: '执行危险命令前的安全检查',
      version: '1.0.0',
      tags: ['安全', '危险', '保护'],
      files: [
        { path: 'hooks/pre-tool-use.js', name: 'pre-tool-use.js', type: 'script', description: '安全检查脚本' }
      ],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      downloadCount: 50
    },
    {
      id: 'skill-forced-eval',
      name: '自我进化',
      category: 'operation' as SkillCategory,
      icon: 'Brain',
      description: '从纠正中学习，不再重复犯错',
      version: '1.0.0',
      tags: ['进化', '学习', '纠正'],
      files: [
        { path: 'hooks/skill-forced-eval.js', name: 'skill-forced-eval.js', type: 'script', description: '进化学习脚本' }
      ],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      downloadCount: 30
    },
    {
      id: 'crud-development',
      name: 'CRUD 开发规范',
      category: 'generation' as SkillCategory,
      icon: 'Document',
      description: '后端三层架构标准代码模板',
      version: '1.0.0',
      tags: ['CRUD', '增删改查', 'Service', 'DAO'],
      files: [
        { path: 'skills/crud-development/SKILL.md', name: 'SKILL.md', type: 'template', description: 'CRUD模板' }
      ],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      isOfficial: true,
      downloadCount: 200
    },
    {
      id: 'ui-pc',
      name: 'UI 开发规范',
      category: 'generation' as SkillCategory,
      icon: 'Monitor',
      description: 'PC端 UI 组件开发规范',
      version: '1.0.0',
      tags: ['UI', '组件', 'Vue', 'Element'],
      files: [
        { path: 'skills/ui-pc/SKILL.md', name: 'SKILL.md', type: 'template', description: 'UI模板' }
      ],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      downloadCount: 150
    },
    {
      id: 'code-patterns',
      name: '代码模式库',
      category: 'analysis' as SkillCategory,
      icon: 'Grid',
      description: '通用代码模式和最佳实践',
      version: '1.0.0',
      tags: ['模式', '最佳实践', '规范'],
      files: [
        { path: 'skills/code-patterns/SKILL.md', name: 'SKILL.md', type: 'template', description: '代码模式' }
      ],
      createdAt: '2026-01-01T00:00:00Z',
      updatedAt: '2026-01-01T00:00:00Z',
      downloadCount: 80
    }
  ]
}

function createMockSkillMarketResponse(): SkillMarketResponse {
  return {
    skills: createMockSkillDefinitions(),
    total: 6,
    categoryStats: {
      diagnosis: 1,
      analysis: 1,
      generation: 2,
      operation: 2,
      other: 0
    }
  }
}

function createMockProjectStatus(): ProjectSkillStatus[] {
  return [
    {
      skillId: 'crud-development',
      skillName: 'CRUD 开发规范',
      status: 'installed',
      installedVersion: '1.0.0',
      installedAt: '2026-01-01T00:00:00Z',
      installPath: '.claude/skills/crud-development'
    },
    {
      skillId: 'pre-tool-use',
      skillName: '安全钩子',
      status: 'installed',
      installedVersion: '1.0.0',
      installedAt: '2026-01-01T00:00:00Z',
      installPath: '.claude/hooks/pre-tool-use.js'
    },
    {
      skillId: 'ui-pc',
      skillName: 'UI 开发规范',
      status: 'not_installed'
    }
  ]
}

describe('skillStore 状态管理', () => {
  beforeEach(async () => {
    // 创建新的 Pinia 实例
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  describe('状态初始化', () => {
    test('初始状态应为空', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()

      // 验证初始状态
      expect(store.skills).toEqual([])
      expect(store.projectStatus).toEqual([])
      expect(store.selectedCategory).toBe('')
      expect(store.searchKeyword).toBe('')
      expect(store.loading).toBe(false)
      expect(store.statusLoading).toBe(false)
    })

    test('初始分类统计为默认值', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()

      expect(store.categoryStats).toEqual({
        diagnosis: 0,
        analysis: 0,
        generation: 0,
        operation: 0,
        other: 0
      })
    })

    test('初始技能总数为0', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()

      expect(store.totalSkills).toBe(0)
    })
  })

  describe('loadSkills - 加载技能列表', () => {
    test('成功加载技能列表', async () => {
      mockSkillMarketApi.getSkillList.mockResolvedValueOnce(createMockSkillMarketResponse())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      await store.loadSkills()

      expect(store.skills.length).toBe(6)
      expect(store.totalSkills).toBe(6)
      expect(store.loading).toBe(false)
      expect(mockSkillMarketApi.getSkillList).toHaveBeenCalled()
      expect(store.categoryStats.diagnosis).toBe(1)
      expect(store.categoryStats.generation).toBe(2)
    })

    test('加载失败显示错误消息', async () => {
      mockSkillMarketApi.getSkillList.mockRejectedValueOnce(new Error('网络错误'))

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      await store.loadSkills()

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('加载技能列表失败')
      expect(store.loading).toBe(false)
    })

    test('加载中状态正确设置', async () => {
      mockSkillMarketApi.getSkillList.mockImplementationOnce(() =>
        new Promise(resolve => setTimeout(() => resolve(createMockSkillMarketResponse()), 100))
      )

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()

      const loadPromise = store.loadSkills()
      expect(store.loading).toBe(true)

      await loadPromise
      expect(store.loading).toBe(false)
    })

    test('加载空列表时设置空数组', async () => {
      mockSkillMarketApi.getSkillList.mockResolvedValueOnce({
        skills: [],
        total: 0,
        categoryStats: {
          diagnosis: 0,
          analysis: 0,
          generation: 0,
          operation: 0,
          other: 0
        }
      })

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      await store.loadSkills()

      expect(store.skills).toEqual([])
      expect(store.totalSkills).toBe(0)
    })
  })

  describe('loadProjectStatus - 加载项目状态', () => {
    test('成功加载项目状态', async () => {
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      await store.loadProjectStatus('D:\\projects\\test-project')

      expect(store.projectStatus.length).toBe(3)
      expect(mockSkillMarketApi.getProjectStatus).toHaveBeenCalledWith('D:\\projects\\test-project')
    })

    test('空项目路径清空状态', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = createMockProjectStatus()

      await store.loadProjectStatus('')

      expect(store.projectStatus).toEqual([])
      expect(mockSkillMarketApi.getProjectStatus).not.toHaveBeenCalled()
    })

    test('加载失败清空状态并显示错误', async () => {
      mockSkillMarketApi.getProjectStatus.mockRejectedValueOnce(new Error('检测失败'))

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      await store.loadProjectStatus('D:\\projects\\test-project')

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('加载项目技能状态失败')
      expect(store.projectStatus).toEqual([])
      expect(store.statusLoading).toBe(false)
    })

    test('加载中状态正确设置', async () => {
      mockSkillMarketApi.getProjectStatus.mockImplementationOnce(() =>
        new Promise(resolve => setTimeout(() => resolve(createMockProjectStatus()), 100))
      )

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()

      const loadPromise = store.loadProjectStatus('D:\\projects\\test-project')
      expect(store.statusLoading).toBe(true)

      await loadPromise
      expect(store.statusLoading).toBe(false)
    })
  })

  describe('installSkill - 安装技能', () => {
    test('成功安装技能', async () => {
      mockSkillMarketApi.installSkill.mockResolvedValueOnce({
        success: true,
        message: '安装成功',
        installPath: '.claude/skills/ui-pc'
      })
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.installSkill('ui-pc', 'D:\\projects\\test-project')

      expect(result).toBe(true)
      expect(mockSkillMarketApi.installSkill).toHaveBeenCalledWith({
        skillId: 'ui-pc',
        projectDir: 'D:\\projects\\test-project'
      })
    })

    test('安装成功后刷新项目状态', async () => {
      mockSkillMarketApi.installSkill.mockResolvedValueOnce({
        success: true,
        message: '安装成功',
        installPath: '.claude/skills/ui-pc'
      })
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      await store.installSkill('ui-pc', 'D:\\projects\\test-project')

      expect(mockSkillMarketApi.getProjectStatus).toHaveBeenCalledWith('D:\\projects\\test-project')
    })

    test('安装成功显示成功消息', async () => {
      mockSkillMarketApi.installSkill.mockResolvedValueOnce({
        success: true,
        message: '安装成功',
        installPath: '.claude/skills/ui-pc'
      })
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      await store.installSkill('ui-pc', 'D:\\projects\\test-project')

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.success).toHaveBeenCalledWith('技能 "UI 开发规范" 安装成功')
    })

    test('空项目目录显示警告', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.installSkill('ui-pc', '')

      expect(result).toBe(false)
      const { ElMessage } = await import('element-plus')
      expect(ElMessage.warning).toHaveBeenCalledWith('请先选择项目目录')
      expect(mockSkillMarketApi.installSkill).not.toHaveBeenCalled()
    })

    test('不存在技能显示错误', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.installSkill('non-existing', 'D:\\projects\\test-project')

      expect(result).toBe(false)
      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('技能不存在')
    })

    test('安装失败显示错误消息', async () => {
      mockSkillMarketApi.installSkill.mockResolvedValueOnce({
        success: false,
        message: '文件复制失败'
      })

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.installSkill('ui-pc', 'D:\\projects\\test-project')

      expect(result).toBe(false)
      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('文件复制失败')
    })

    test('安装异常显示错误消息', async () => {
      mockSkillMarketApi.installSkill.mockRejectedValueOnce(new Error('网络错误'))

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.installSkill('ui-pc', 'D:\\projects\\test-project')

      expect(result).toBe(false)
      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('安装技能失败: 网络错误')
    })

    test('安装中状态正确设置', async () => {
      mockSkillMarketApi.installSkill.mockImplementationOnce(() =>
        new Promise(resolve => setTimeout(() => resolve({ success: true }), 100))
      )
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const installPromise = store.installSkill('ui-pc', 'D:\\projects\\test-project')
      expect(store.isSkillOperating('ui-pc')).toBe(true)

      await installPromise
      expect(store.isSkillOperating('ui-pc')).toBe(false)
    })
  })

  describe('uninstallSkill - 卸载技能', () => {
    test('成功卸载技能', async () => {
      mockSkillMarketApi.uninstallSkill.mockResolvedValueOnce({
        success: true,
        message: '卸载成功'
      })
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.uninstallSkill('crud-development', 'D:\\projects\\test-project')

      expect(result).toBe(true)
      expect(mockSkillMarketApi.uninstallSkill).toHaveBeenCalledWith({
        skillId: 'crud-development',
        projectDir: 'D:\\projects\\test-project'
      })
    })

    test('卸载成功后刷新项目状态', async () => {
      mockSkillMarketApi.uninstallSkill.mockResolvedValueOnce({
        success: true,
        message: '卸载成功'
      })
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      await store.uninstallSkill('crud-development', 'D:\\projects\\test-project')

      expect(mockSkillMarketApi.getProjectStatus).toHaveBeenCalledWith('D:\\projects\\test-project')
    })

    test('卸载成功显示成功消息', async () => {
      mockSkillMarketApi.uninstallSkill.mockResolvedValueOnce({
        success: true,
        message: '卸载成功'
      })
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      await store.uninstallSkill('crud-development', 'D:\\projects\\test-project')

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.success).toHaveBeenCalledWith('技能 "CRUD 开发规范" 已卸载')
    })

    test('空项目目录显示警告', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.uninstallSkill('crud-development', '')

      expect(result).toBe(false)
      const { ElMessage } = await import('element-plus')
      expect(ElMessage.warning).toHaveBeenCalledWith('请先选择项目目录')
    })

    test('不存在技能显示错误', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const result = await store.uninstallSkill('non-existing', 'D:\\projects\\test-project')

      expect(result).toBe(false)
      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('技能不存在')
    })

    test('卸载失败显示错误消息', async () => {
      mockSkillMarketApi.uninstallSkill.mockRejectedValueOnce(new Error('文件删除失败'))

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      await store.uninstallSkill('crud-development', 'D:\\projects\\test-project')

      const { ElMessage } = await import('element-plus')
      expect(ElMessage.error).toHaveBeenCalledWith('卸载技能失败: 文件删除失败')
    })

    test('卸载中状态正确设置', async () => {
      mockSkillMarketApi.uninstallSkill.mockImplementationOnce(() =>
        new Promise(resolve => setTimeout(() => resolve({ success: true }), 100))
      )
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const uninstallPromise = store.uninstallSkill('crud-development', 'D:\\projects\\test-project')
      expect(store.isSkillOperating('crud-development')).toBe(true)

      await uninstallPromise
      expect(store.isSkillOperating('crud-development')).toBe(false)
    })
  })

  describe('filteredSkills - 筛选功能', () => {
    test('无筛选时返回所有技能', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.selectedCategory = ''
      store.searchKeyword = ''

      expect(store.filteredSkills.length).toBe(6)
    })

    test('按分类筛选 - operation 分类', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.setCategory('operation')
      store.searchKeyword = ''

      expect(store.selectedCategory).toBe('operation')
      expect(store.filteredSkills.length).toBe(2)
      expect(store.filteredSkills.every(s => s.category === 'operation')).toBe(true)
    })

    test('按分类筛选 - generation 分类', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.setCategory('generation')
      store.searchKeyword = ''

      expect(store.selectedCategory).toBe('generation')
      expect(store.filteredSkills.length).toBe(2)
      expect(store.filteredSkills.every(s => s.category === 'generation')).toBe(true)
    })

    test('按关键词搜索 - 名称匹配', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.selectedCategory = ''
      store.setSearchKeyword('CRUD')

      expect(store.filteredSkills.length).toBe(1)
      expect(store.filteredSkills[0].id).toBe('crud-development')
    })

    test('按关键词搜索 - 描述匹配', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.selectedCategory = ''
      store.setSearchKeyword('安全')

      expect(store.filteredSkills.length).toBe(1)
      expect(store.filteredSkills[0].id).toBe('pre-tool-use')
    })

    test('按关键词搜索 - 标签匹配', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.selectedCategory = ''
      store.setSearchKeyword('Vue')

      expect(store.filteredSkills.length).toBe(1)
      expect(store.filteredSkills[0].id).toBe('ui-pc')
    })

    test('分类和关键词组合筛选', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.setCategory('generation')
      store.setSearchKeyword('UI')

      expect(store.filteredSkills.length).toBe(1)
      expect(store.filteredSkills[0].id).toBe('ui-pc')
    })

    test('清空筛选条件', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()
      store.setCategory('operation')
      store.setSearchKeyword('安全')

      store.clearFilters()

      expect(store.selectedCategory).toBe('')
      expect(store.searchKeyword).toBe('')
      expect(store.filteredSkills.length).toBe(6)
    })
  })

  describe('isSkillInstalled - 检查技能是否已安装', () => {
    test('已安装技能返回 true', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = createMockProjectStatus()

      expect(store.isSkillInstalled('crud-development')).toBe(true)
      expect(store.isSkillInstalled('pre-tool-use')).toBe(true)
    })

    test('未安装技能返回 false', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = createMockProjectStatus()

      expect(store.isSkillInstalled('ui-pc')).toBe(false)
      expect(store.isSkillInstalled('hisi-dev-tool')).toBe(false)
    })

    test('无项目状态时返回 false', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = []

      expect(store.isSkillInstalled('crud-development')).toBe(false)
    })
  })

  describe('getSkillInstallStatus - 获取技能安装状态详情', () => {
    test('获取已安装技能的状态', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = createMockProjectStatus()

      const status = store.getSkillInstallStatus('crud-development')

      expect(status).toBeDefined()
      expect(status?.status).toBe('installed')
      expect(status?.installedVersion).toBe('1.0.0')
      expect(status?.installPath).toBe('.claude/skills/crud-development')
    })

    test('获取未安装技能的状态', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = createMockProjectStatus()

      const status = store.getSkillInstallStatus('ui-pc')

      expect(status).toBeDefined()
      expect(status?.status).toBe('not_installed')
      expect(status?.installedVersion).toBeUndefined()
    })

    test('获取不存在技能的状态返回 undefined', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.projectStatus = createMockProjectStatus()

      const status = store.getSkillInstallStatus('non-existing')

      expect(status).toBeUndefined()
    })
  })

  describe('isSkillOperating - 检查技能是否正在操作中', () => {
    test('未操作时返回 false', async () => {
      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()

      expect(store.isSkillOperating('ui-pc')).toBe(false)
    })

    test('安装中返回 true', async () => {
      mockSkillMarketApi.installSkill.mockImplementationOnce(() =>
        new Promise(resolve => setTimeout(() => resolve({ success: true }), 100))
      )
      mockSkillMarketApi.getProjectStatus.mockResolvedValueOnce(createMockProjectStatus())

      const { useSkillStore } = await import('@/stores/skillStore')
      const store = useSkillStore()
      store.skills = createMockSkillDefinitions()

      const installPromise = store.installSkill('ui-pc', 'D:\\projects\\test-project')
      expect(store.isSkillOperating('ui-pc')).toBe(true)

      await installPromise
      expect(store.isSkillOperating('ui-pc')).toBe(false)
    })
  })
})