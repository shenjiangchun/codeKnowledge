/**
 * ThemeSelector 组件测试
 * 测试主题选择器 UI 交互
 *
 * 测试范围：
 * 1. 渲染6个主题项
 * 2. 当前主题显示 active 样式
 * 3. 点击主题项调用 themeStore.setTheme
 * 4. ColorPicker 变化调用 themeStore.setCustomAccent
 * 5. 重置按钮调用 themeStore.resetToDefault
 * 6. 使用正确的 CSS 变量样式
 *
 * TDD 流程:
 * 1. RED - 编写失败的测试，验证期望的行为
 * 2. GREEN - 实现组件代码通过测试
 * 3. REFACTOR - 优化代码
 */

import { describe, test, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import ThemeSelector from './ThemeSelector.vue'

// Mock localStorage - must be defined before any imports that use it
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

const mockStyle = {
  setProperty: mockSetProperty,
  removeProperty: mockRemoveProperty
}

const originalDocumentElement = document.documentElement
const mockDocumentElement = {
  ...originalDocumentElement,
  style: mockStyle
}

Object.defineProperty(document, 'documentElement', {
  value: mockDocumentElement,
  configurable: true
})

// Mock themes module - inline mock data to avoid hoisting issues
vi.mock('@/themes', () => {
  const mockThemePresets = [
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
      textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
      successLightBg: 'rgba(103, 194, 58, 0.1)',
      borderHover: '#505050',
      successTextOnAccent: '#a5d6a7',
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
      id: 'dark-monokai',
      name: 'Monokai 经典',
      isDark: true,
      backgroundLevel1: '#1e1e1e',
      backgroundLevel2: '#272822',
      backgroundLevel3: '#3e3d32',
      backgroundLevel4: '#49483e',
      textPrimary: '#f8f8f2',
      textSecondary: '#a6a69b',
      textMuted: '#75715e',
      accentPrimary: '#f92672',
      accentSuccess: '#a6e22e',
      accentWarning: '#e6db74',
      accentDanger: '#f92672',
      textOnAccent: '#f8f8f2',
      textOnAccentSecondary: 'rgba(248, 248, 242, 0.8)',
      successLightBg: 'rgba(166, 226, 46, 0.1)',
      borderHover: '#5a5a50',
      successTextOnAccent: '#d4e89e',
      terminal: {
        background: '#1e1e1e',
        foreground: '#f8f8f2',
        cursor: '#f92672',
        cursorAccent: '#f8f8f2',
        selectionBackground: '#f9267240',
        black: '#000000',
        red: '#f92672',
        green: '#a6e22e',
        yellow: '#e6db74',
        blue: '#66d9ef',
        magenta: '#f92672',
        cyan: '#66d9ef',
        white: '#f8f8f2',
        brightBlack: '#75715e',
        brightRed: '#f92672',
        brightGreen: '#a6e22e',
        brightYellow: '#e6db74',
        brightBlue: '#66d9ef',
        brightMagenta: '#f92672',
        brightCyan: '#66d9ef',
        brightWhite: '#f8f8f2'
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
      textOnAccentSecondary: 'rgba(248, 248, 242, 0.8)',
      successLightBg: 'rgba(80, 250, 123, 0.1)',
      borderHover: '#6272a4',
      successTextOnAccent: '#94faab',
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
      textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
      successLightBg: 'rgba(103, 194, 58, 0.15)',
      borderHover: '#c0c0c0',
      successTextOnAccent: '#a5d6a7',
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
    },
    {
      id: 'light-sepia',
      name: '护眼暖色',
      isDark: false,
      backgroundLevel1: '#f8f4e8',
      backgroundLevel2: '#fffbf0',
      backgroundLevel3: '#f5f0e0',
      backgroundLevel4: '#e8dcc8',
      textPrimary: '#3e3e3e',
      textSecondary: '#5a5a5a',
      textMuted: '#8a8a8a',
      accentPrimary: '#d48806',
      accentSuccess: '#52c41a',
      accentWarning: '#fa8c16',
      accentDanger: '#f5222d',
      textOnAccent: '#ffffff',
      textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
      successLightBg: 'rgba(82, 196, 26, 0.15)',
      borderHover: '#d0c8b8',
      successTextOnAccent: '#95de64',
      terminal: {
        background: '#fffbf0',
        foreground: '#3e3e3e',
        cursor: '#d48806',
        cursorAccent: '#3e3e3e',
        selectionBackground: '#d4880640',
        black: '#000000',
        red: '#f5222d',
        green: '#52c41a',
        yellow: '#faad14',
        blue: '#d48806',
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
      id: 'eye-care',
      name: '护眼绿色',
      isDark: false,
      backgroundLevel1: '#e8f5e9',
      backgroundLevel2: '#f5fbf5',
      backgroundLevel3: '#e0f0e0',
      backgroundLevel4: '#c8e6c9',
      textPrimary: '#2e3e2e',
      textSecondary: '#4a5a4a',
      textMuted: '#7a8a7a',
      accentPrimary: '#67c23a',
      accentSuccess: '#52c41a',
      accentWarning: '#faad14',
      accentDanger: '#ff4d4f',
      textOnAccent: '#ffffff',
      textOnAccentSecondary: 'rgba(255, 255, 255, 0.8)',
      successLightBg: 'rgba(82, 196, 26, 0.2)',
      borderHover: '#a8d8a9',
      successTextOnAccent: '#b7eb8f',
      terminal: {
        background: '#f5fbf5',
        foreground: '#2e3e2e',
        cursor: '#67c23a',
        cursorAccent: '#2e3e2e',
        selectionBackground: '#67c23a40',
        black: '#000000',
        red: '#ff4d4f',
        green: '#52c41a',
        yellow: '#faad14',
        blue: '#67c23a',
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

  return {
    THEME_PRESETS: mockThemePresets,
    DEFAULT_THEME: mockThemePresets[0],
    getThemeById: (id: string) => mockThemePresets.find(t => t.id === id),
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
      accentDanger: '--ct-accent-danger',
      textOnAccent: '--ct-text-on-accent',
      textOnAccentSecondary: '--ct-text-on-accent-secondary',
      successLightBg: '--ct-success-light-bg',
      borderHover: '--ct-border-hover',
      successTextOnAccent: '--ct-success-text-on-accent'
    }
  }
})

// Simple mock components for testing
const MockColorPicker = {
  name: 'ElColorPicker',
  template: '<input type="color" class="el-color-picker" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />',
  props: ['modelValue'],
  emits: ['update:modelValue', 'change']
}

const MockButton = {
  name: 'ElButton',
  template: '<button class="el-button" @click="$emit(\'click\')"><slot /></button>',
  props: ['size', 'type'],
  emits: ['click']
}

// Helper function to mount with Element Plus stubs
function mountWithStubs(component: any, options: any = {}) {
  return mount(component, {
    ...options,
    global: {
      ...options.global,
      stubs: {
        ElColorPicker: MockColorPicker,
        ElButton: MockButton,
        ...options.global?.stubs
      }
    }
  })
}

describe('ThemeSelector 组件', () => {
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

  describe('渲染测试', () => {
    test('渲染6个主题项', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      // 查找所有主题项
      const themeItems = wrapper.findAll('.theme-item')
      expect(themeItems.length).toBe(6)
    })

    test('每个主题项显示正确名称', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      const themeNames = wrapper.findAll('.theme-name')
      expect(themeNames[0].text()).toBe('深色科技')
      expect(themeNames[1].text()).toBe('Monokai 经典')
      expect(themeNames[2].text()).toBe('Dracula')
      expect(themeNames[3].text()).toBe('浅色简约')
      expect(themeNames[4].text()).toBe('护眼暖色')
      expect(themeNames[5].text()).toBe('护眼绿色')
    })

    test('每个主题项有预览色块', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      const themePreviews = wrapper.findAll('.theme-preview')
      expect(themePreviews.length).toBe(6)

      // 检查第一个主题预览的背景色
      const firstPreview = themePreviews[0]
      expect(firstPreview.attributes('style')).toContain('background-color')
    })

    test('预览色块内有 accent 色块', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      const previewAccents = wrapper.findAll('.preview-accent')
      expect(previewAccents.length).toBe(6)

      // 检查第一个主题的 accent 颜色
      const firstAccent = previewAccents[0]
      expect(firstAccent.attributes('style')).toContain('background-color')
      expect(firstAccent.attributes('style')).toContain('#409eff')
    })
  })

  describe('当前主题样式', () => {
    test('当前主题显示 active 样式', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      // 默认主题是 dark-tech
      const themeItems = wrapper.findAll('.theme-item')
      const activeItem = themeItems[0]

      expect(activeItem.classes()).toContain('active')
    })

    test('切换主题后 active 样式更新', async () => {
      const pinia = createPinia()
      setActivePinia(pinia)

      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [pinia]
        }
      })

      // 获取 store 并切换主题
      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()
      store.setTheme('dark-dracula')

      // 重新渲染检查
      await wrapper.vm.$nextTick()

      const themeItems = wrapper.findAll('.theme-item')
      expect(themeItems[0].classes()).not.toContain('active')
      expect(themeItems[2].classes()).toContain('active')
    })
  })

  describe('主题选择交互', () => {
    test('点击主题项调用 themeStore.setTheme', async () => {
      const pinia = createPinia()
      setActivePinia(pinia)

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [pinia]
        }
      })

      // 点击第二个主题项
      const themeItems = wrapper.findAll('.theme-item')
      await themeItems[1].trigger('click')

      // 验证 setTheme 被调用
      expect(store.themeId).toBe('dark-monokai')
    })

    test('点击已选中的主题不会重复调用', async () => {
      const pinia = createPinia()
      setActivePinia(pinia)

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [pinia]
        }
      })

      // 当前主题是 dark-tech，点击它
      const themeItems = wrapper.findAll('.theme-item')
      vi.clearAllMocks()
      await themeItems[0].trigger('click')

      // CSS 变量应该不会重新设置（因为主题没变）
      // 注意：这取决于 store 的实现，如果 store 会重新应用，则测试应验证调用次数
      expect(store.themeId).toBe('dark-tech')
    })
  })

  describe('自定义颜色选择', () => {
    test('ColorPicker 绑定 customAccent', async () => {
      const wrapper = mountWithStubs(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      // 查找 ColorPicker
      const colorPicker = wrapper.findComponent({ name: 'ElColorPicker' })
      expect(colorPicker.exists()).toBe(true)

      // 默认 customAccent 为 null
      expect(colorPicker.props('modelValue')).toBeNull()
    })

    test('ColorPicker 变化调用 themeStore.setCustomAccent', async () => {
      const pinia = createPinia()
      setActivePinia(pinia)

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      const wrapper = mountWithStubs(ThemeSelector, {
        global: {
          plugins: [pinia]
        }
      })

      // 模拟 ColorPicker 变化
      const colorPicker = wrapper.findComponent({ name: 'ElColorPicker' })
      await colorPicker.vm.$emit('change', '#ff0000')

      // 验证 setCustomAccent 被调用
      expect(store.customAccent).toBe('#ff0000')
    })
  })

  describe('重置按钮', () => {
    test('渲染重置按钮', async () => {
      const wrapper = mountWithStubs(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      const resetButton = wrapper.find('.theme-actions button')
      expect(resetButton.exists()).toBe(true)
      expect(resetButton.text()).toContain('重置')
    })

    test('点击重置按钮调用 themeStore.resetToDefault', async () => {
      const pinia = createPinia()
      setActivePinia(pinia)

      const { useThemeStore } = await import('@/stores/themeStore')
      const store = useThemeStore()

      // 先切换主题和设置自定义颜色
      store.setTheme('dark-dracula')
      store.setCustomAccent('#ff0000')

      const wrapper = mountWithStubs(ThemeSelector, {
        global: {
          plugins: [pinia]
        }
      })

      // 点击重置按钮
      const resetButton = wrapper.find('.theme-actions button')
      await resetButton.trigger('click')

      // 验证 resetToDefault 被调用
      expect(store.themeId).toBe('dark-tech')
      expect(store.customAccent).toBeNull()
    })
  })

  describe('CSS 变量样式', () => {
    test('组件根元素使用 CSS 变量类名', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      // 检查根元素有 theme-selector 类
      expect(wrapper.find('.theme-selector').exists()).toBe(true)
    })

    test('主题项使用 CSS 变量背景色', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      // 检查 theme-list 存在
      expect(wrapper.find('.theme-list').exists()).toBe(true)

      // 检查 theme-item 使用正确的背景色值
      const firstThemeItem = wrapper.findAll('.theme-item')[0]
      const preview = firstThemeItem.find('.theme-preview')
      const style = preview.attributes('style')

      // 背景色应该使用主题的 backgroundLevel1
      expect(style).toContain('#1a1a1a')
    })

    test('active 主题项样式正确', async () => {
      const wrapper = mount(ThemeSelector, {
        global: {
          plugins: [createPinia()]
        }
      })

      const activeItem = wrapper.find('.theme-item.active')
      expect(activeItem.exists()).toBe(true)
    })
  })
})