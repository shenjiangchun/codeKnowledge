/**
 * themeStore 单元测试
 * 测试主题管理 Store 状态管理
 *
 * 测试范围：
 * 1. 状态初始化
 * 2. setTheme(id) - 切换主题
 * 3. setCustomAccent(color) - 自定义颜色
 * 4. resetToDefault() - 重置主题
 * 5. currentTheme computed - 返回正确主题
 * 6. isDark computed - 正确判断深色/浅色
 * 7. getTerminalTheme() - 返回 terminal 配置
 * 8. init() - 从 localStorage 恢复
 * 9. CSS 变量注入到 document.documentElement
 *
 * TDD 流程:
 * 1. RED - 编写失败的测试，验证期望的行为
 * 2. GREEN - 确保 Store 实现通过测试
 * 3. REFACTOR - 优化代码
 */

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { setActivePinia, createPinia } from 'pinia'
import type { TerminalTheme } from '@/themes/types'

// Mock localStorage
const localStorageMock = (() => {
  let store: Record<string, string> = {}
  return {
    getItem: vi.fn((key: string) => store[key] || null),
    setItem: vi.fn((key: string, value: string) => {
      store[key] = value
    }),
    removeItem: vi.fn((key: string) => {
      delete store[key]
    }),
    clear: vi.fn(() => {
      store = {}
    })
  }
})()

Object.defineProperty(window, 'localStorage', {
  value: localStorageMock,
  configurable: true
})

// Mock document.documentElement.style
const mockSetProperty = vi.fn()
const mockRemoveProperty = vi.fn()

// 创建 mock style 对象
const mockStyle = {
  setProperty: mockSetProperty,
  removeProperty: mockRemoveProperty
}

// Mock document.documentElement
const originalDocumentElement = document.documentElement
const mockDocumentElement = {
  ...originalDocumentElement,
  style: mockStyle
}

Object.defineProperty(document, 'documentElement', {
  value: mockDocumentElement,
  configurable: true
})

// 测试数据
const mockThemePresets: TerminalTheme[] = [
  {
    id: 'dark-tech',
    name: '深色科技',
    isDark: true,
    backgroundLevel1: '#1a1a1a',
    backgroundLevel2: '#252525',
    backgroundLevel3: '#303030',
    backgroundLevel4: '#404040',
    textPrimary: '#ffffff',
    textSecondary: '#a0a0a0',
    textMuted: '#606060',
    accentPrimary: '#409eff',
    accentSuccess: '#67c23a',
    accentWarning: '#e6a23c',
    accentDanger: '#f56c6c',
    textOnAccent: '#ffffff',
    textOnAccentSecondary: '#e0e0e0',
    successLightBg: '#67c23a20',
    borderHover: '#409eff80',
    successTextOnAccent: '#67c23a',
    terminal: {
      background: '#1a1a1a',
      foreground: '#ffffff',
      cursor: '#409eff',
      cursorAccent: '#ffffff',
      selectionBackground: '#409eff40',
      black: '#000000',
      red: '#f56c6c',
      green: '#67c23a',
      yellow: '#e6a23c',
      blue: '#409eff',
      magenta: '#f92672',
      cyan: '#00d4ff',
      white: '#ffffff',
      brightBlack: '#808080',
      brightRed: '#ff6b6b',
      brightGreen: '#90ee90',
      brightYellow: '#ffd700',
      brightBlue: '#6bb3ff',
      brightMagenta: '#ff79c6',
      brightCyan: '#00ffff',
      brightWhite: '#ffffff'
    }
  },
  {
    id: 'dark-dracula',
    name: 'Dracula',
    isDark: true,
    backgroundLevel1: '#282a36',
    backgroundLevel2: '#2d2f3f',
    backgroundLevel3: '#3b3e50',
    backgroundLevel4: '#44475a',
    textPrimary: '#f8f8f2',
    textSecondary: '#b0b0b0',
    textMuted: '#6272a4',
    accentPrimary: '#bd93f9',
    accentSuccess: '#50fa7b',
    accentWarning: '#ffb86c',
    accentDanger: '#ff5555',
    textOnAccent: '#f8f8f2',
    textOnAccentSecondary: '#b0b0b0',
    successLightBg: '#50fa7b20',
    borderHover: '#bd93f980',
    successTextOnAccent: '#50fa7b',
    terminal: {
      background: '#282a36',
      foreground: '#f8f8f2',
      cursor: '#bd93f9',
      cursorAccent: '#f8f8f2',
      selectionBackground: '#bd93f940',
      black: '#000000',
      red: '#ff5555',
      green: '#50fa7b',
      yellow: '#f1fa8c',
      blue: '#bd93f9',
      magenta: '#ff79c6',
      cyan: '#8be9fd',
      white: '#f8f8f2',
      brightBlack: '#6272a4',
      brightRed: '#ff6e6e',
      brightGreen: '#69ff94',
      brightYellow: '#ffffa5',
      brightBlue: '#d6acff',
      brightMagenta: '#ff92df',
      brightCyan: '#a4ffff',
      brightWhite: '#ffffff'
    }
  },
  {
    id: 'light-minimal',
    name: '浅色简约',
    isDark: false,
    backgroundLevel1: '#f5f5f5',
    backgroundLevel2: '#ffffff',
    backgroundLevel3: '#fafafa',
    backgroundLevel4: '#e0e0e0',
    textPrimary: '#303133',
    textSecondary: '#606266',
    textMuted: '#909399',
    accentPrimary: '#409eff',
    accentSuccess: '#67c23a',
    accentWarning: '#e6a23c',
    accentDanger: '#f56c6c',
    textOnAccent: '#ffffff',
    textOnAccentSecondary: '#e0e0e0',
    successLightBg: '#67c23a20',
    borderHover: '#409eff80',
    successTextOnAccent: '#67c23a',
    terminal: {
      background: '#ffffff',
      foreground: '#303133',
      cursor: '#409eff',
      cursorAccent: '#303133',
      selectionBackground: '#409eff40',
      black: '#000000',
      red: '#f56c6c',
      green: '#67c23a',
      yellow: '#e6a23c',
      blue: '#409eff',
      magenta: '#f92672',
      cyan: '#00d4ff',
      white: '#ffffff',
      brightBlack: '#808080',
      brightRed: '#ff6b6b',
      brightGreen: '#90ee90',
      brightYellow: '#ffd700',
      brightBlue: '#6bb3ff',
      brightMagenta: '#ff79c6',
      brightCyan: '#00ffff',
      brightWhite: '#ffffff'
    }
  }
]

// Mock themes module
vi.mock('@/themes', () => ({
  THEME_PRESETS: mockThemePresets,
  DEFAULT_THEME: mockThemePresets[0],
  getThemeById: vi.fn((id: string) => mockThemePresets.find(t => t.id === id)),
  THEME_STORAGE_KEY: 'claude-terminal-theme',
  THEME_CSS_VARS: {
    backgroundLevel1: '--ct-bg-level-1',
    backgroundLevel2: '--ct-bg-level-2',
    backgroundLevel3: '--ct-bg-level-3',
    backgroundLevel4: '--ct-bg-level-4',
    textPrimary: '--ct-text-primary',
    textSecondary: '--ct-text-secondary',
    textMuted: '--ct-text-muted',
    accentPrimary: '--ct-accent-primary',
    accentSuccess: '--ct-accent-success',
    accentWarning: '--ct-accent-warning',
    accentDanger: '--ct-accent-danger'
  }
}))

describe('themeStore 状态管理', () => {
  beforeEach(async () => {
    // 创建新的 Pinia 实例
    setActivePinia(createPinia())
    // 清空 localStorage mock
    localStorageMock.clear()
    vi.clearAllMocks()
  })

  afterEach(() => {
    vi.clearAllMocks()
  })

  describe('状态初始化', () => {
    test('初始状态应为默认主题', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      // 验证初始状态
      expect(store.themeId).toBe('dark-tech')
      expect(store.customAccent).toBeNull()
    })

    test('presets 返回预设主题列表', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      expect(store.presets).toEqual(mockThemePresets)
      expect(store.presets.length).toBe(3)
    })
  })

  describe('currentTheme computed', () => {
    test('默认返回 dark-tech 主题', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      expect(store.currentTheme.id).toBe('dark-tech')
      expect(store.currentTheme.name).toBe('深色科技')
      expect(store.currentTheme.isDark).toBe(true)
    })

    test('切换主题后返回对应主题', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')

      expect(store.currentTheme.id).toBe('dark-dracula')
      expect(store.currentTheme.name).toBe('Dracula')
      expect(store.currentTheme.accentPrimary).toBe('#bd93f9')
    })

    test('自定义颜色时覆盖 accentPrimary', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setCustomAccent('#ff0000')

      expect(store.currentTheme.accentPrimary).toBe('#ff0000')
      // 其他颜色应保持不变
      expect(store.currentTheme.accentSuccess).toBe('#67c23a')
    })
  })

  describe('isDark computed', () => {
    test('深色主题返回 true', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      expect(store.isDark).toBe(true)
    })

    test('浅色主题返回 false', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('light-minimal')

      expect(store.isDark).toBe(false)
    })
  })

  describe('setTheme - 切换主题', () => {
    test('切换到 dark-dracula', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')

      expect(store.themeId).toBe('dark-dracula')
      expect(store.currentTheme.id).toBe('dark-dracula')
    })

    test('切换到不存在的主题保持原主题', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      const originalId = store.themeId
      store.setTheme('non-existing')

      expect(store.themeId).toBe(originalId)
    })

    test('切换主题后更新 CSS 变量', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')

      // 验证 CSS 变量已设置
      expect(mockSetProperty).toHaveBeenCalled()
      // 检查背景色是否更新
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-1', '#282a36')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-text-primary', '#f8f8f2')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-primary', '#bd93f9')
    })

    test('切换主题后保存到 localStorage', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')

      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'claude-terminal-theme',
        JSON.stringify({ themeId: 'dark-dracula', customAccent: null })
      )
    })
  })

  describe('setCustomAccent - 自定义颜色', () => {
    test('设置自定义颜色 #ff0000', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setCustomAccent('#ff0000')

      expect(store.customAccent).toBe('#ff0000')
      expect(store.currentTheme.accentPrimary).toBe('#ff0000')
    })

    test('自定义颜色更新 CSS 变量', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setCustomAccent('#ff0000')

      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-primary', '#ff0000')
    })

    test('自定义颜色保存到 localStorage', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')
      store.setCustomAccent('#ff0000')

      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'claude-terminal-theme',
        JSON.stringify({ themeId: 'dark-dracula', customAccent: '#ff0000' })
      )
    })

    test('清空自定义颜色恢复原主题颜色', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')
      store.setCustomAccent('#ff0000')
      store.setCustomAccent(null)

      expect(store.customAccent).toBeNull()
      expect(store.currentTheme.accentPrimary).toBe('#bd93f9')
    })
  })

  describe('resetToDefault - 重置主题', () => {
    test('重置到默认主题', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')
      store.setCustomAccent('#ff0000')
      store.resetToDefault()

      expect(store.themeId).toBe('dark-tech')
      expect(store.customAccent).toBeNull()
      expect(store.currentTheme.id).toBe('dark-tech')
    })

    test('重置后更新 CSS 变量', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')
      vi.clearAllMocks()
      store.resetToDefault()

      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-1', '#1a1a1a')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-primary', '#409eff')
    })

    test('重置后保存到 localStorage', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')
      vi.clearAllMocks()
      store.resetToDefault()

      expect(localStorageMock.setItem).toHaveBeenCalledWith(
        'claude-terminal-theme',
        JSON.stringify({ themeId: 'dark-tech', customAccent: null })
      )
    })
  })

  describe('getTerminalTheme - 获取终端配置', () => {
    test('返回 xterm.js 主题配置', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      const terminalTheme = store.getTerminalTheme()

      expect(terminalTheme.background).toBe('#1a1a1a')
      expect(terminalTheme.foreground).toBe('#ffffff')
      expect(terminalTheme.cursor).toBe('#409eff')
    })

    test('切换主题后返回新配置', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')
      const terminalTheme = store.getTerminalTheme()

      expect(terminalTheme.background).toBe('#282a36')
      expect(terminalTheme.foreground).toBe('#f8f8f2')
      expect(terminalTheme.cursor).toBe('#bd93f9')
    })

    test('自定义颜色时 cursor 随自定义颜色变化', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setCustomAccent('#ff0000')
      const terminalTheme = store.getTerminalTheme()

      // cursor 应使用自定义颜色
      expect(terminalTheme.cursor).toBe('#ff0000')
    })
  })

  describe('init - 从 localStorage 恢复', () => {
    test('localStorage 有数据时恢复主题', async () => {
      localStorageMock.getItem.mockReturnValueOnce(
        JSON.stringify({ themeId: 'dark-dracula', customAccent: null })
      )

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.init()

      expect(store.themeId).toBe('dark-dracula')
      expect(store.customAccent).toBeNull()
    })

    test('localStorage 有自定义颜色时恢复', async () => {
      localStorageMock.getItem.mockReturnValueOnce(
        JSON.stringify({ themeId: 'dark-dracula', customAccent: '#ff0000' })
      )

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.init()

      expect(store.themeId).toBe('dark-dracula')
      expect(store.customAccent).toBe('#ff0000')
    })

    test('localStorage 无数据时使用默认主题', async () => {
      localStorageMock.getItem.mockReturnValueOnce(null)

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.init()

      expect(store.themeId).toBe('dark-tech')
      expect(store.customAccent).toBeNull()
    })

    test('localStorage 数据格式错误时使用默认主题', async () => {
      localStorageMock.getItem.mockReturnValueOnce('invalid-json')

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.init()

      expect(store.themeId).toBe('dark-tech')
    })

    test('init 后更新 CSS 变量', async () => {
      localStorageMock.getItem.mockReturnValueOnce(
        JSON.stringify({ themeId: 'dark-dracula', customAccent: null })
      )

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.init()

      expect(mockSetProperty).toHaveBeenCalled()
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-1', '#282a36')
    })
  })

  describe('CSS 变量注入', () => {
    test('应用主题时设置所有 CSS 变量', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-tech')

      // 验证所有 CSS 变量已设置
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-1', '#1a1a1a')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-2', '#252525')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-3', '#303030')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-bg-level-4', '#404040')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-text-primary', '#ffffff')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-text-secondary', '#a0a0a0')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-text-muted', '#606060')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-primary', '#409eff')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-success', '#67c23a')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-warning', '#e6a23c')
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-danger', '#f56c6c')
    })

    test('自定义颜色只更新 accentPrimary', async () => {
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setCustomAccent('#ff0000')

      // accentPrimary 应更新为自定义颜色
      expect(mockSetProperty).toHaveBeenCalledWith('--ct-accent-primary', '#ff0000')
      // 其他变量不应被此次调用影响（只检查最后一次调用）
    })
  })
})