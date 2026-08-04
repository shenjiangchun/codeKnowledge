/**
 * types.ts 单元测试
 * 测试主题类型定义和 CSS 变量常量
 *
 * TDD 流程:
 * 1. RED - 编写失败的测试，验证期望的行为
 * 2. GREEN - 编写最小代码使测试通过
 * 3. REFACTOR - 优化代码
 */

import { describe, test, expect } from 'vitest'
import {
  THEME_CSS_VARS,
  THEME_STORAGE_KEY,
  type TerminalTheme,
  type TerminalThemeColors
} from './types'

describe('types.ts - CSS 变量常量', () => {
  describe('THEME_CSS_VARS', () => {
    test('THEME_CSS_VARS 常量存在', () => {
      expect(THEME_CSS_VARS).toBeDefined()
    })

    test('背景色变量定义正确', () => {
      expect(THEME_CSS_VARS.backgroundLevel1).toBe('--ct-bg-level-1')
      expect(THEME_CSS_VARS.backgroundLevel2).toBe('--ct-bg-level-2')
      expect(THEME_CSS_VARS.backgroundLevel3).toBe('--ct-bg-level-3')
      expect(THEME_CSS_VARS.backgroundLevel4).toBe('--ct-bg-level-4')
    })

    test('文字颜色变量定义正确', () => {
      expect(THEME_CSS_VARS.textPrimary).toBe('--ct-text-primary')
      expect(THEME_CSS_VARS.textSecondary).toBe('--ct-text-secondary')
      expect(THEME_CSS_VARS.textMuted).toBe('--ct-text-muted')
    })

    test('主题色变量定义正确', () => {
      expect(THEME_CSS_VARS.accentPrimary).toBe('--ct-accent-primary')
      expect(THEME_CSS_VARS.accentSuccess).toBe('--ct-accent-success')
      expect(THEME_CSS_VARS.accentWarning).toBe('--ct-accent-warning')
      expect(THEME_CSS_VARS.accentDanger).toBe('--ct-accent-danger')
    })
  })

  describe('THEME_STORAGE_KEY', () => {
    test('localStorage 键名正确', () => {
      expect(THEME_STORAGE_KEY).toBe('claude-terminal-theme')
    })
  })
})

describe('types.ts - 类型定义', () => {
  describe('TerminalThemeColors', () => {
    test('类型定义包含所有必需字段', () => {
      // 这是一个类型检查测试，确保类型存在
      const colors: TerminalThemeColors = {
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
        successTextOnAccent: '#67c23a'
      }

      expect(colors.backgroundLevel1).toBe('#1a1a1a')
      expect(colors.accentPrimary).toBe('#409eff')
    })
  })

  describe('TerminalTheme', () => {
    test('类型定义包含所有必需字段', () => {
      const theme: TerminalTheme = {
        id: 'test-theme',
        name: 'Test Theme',
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
      }

      expect(theme.id).toBe('test-theme')
      expect(theme.name).toBe('Test Theme')
      expect(theme.isDark).toBe(true)
      expect(theme.terminal).toBeDefined()
    })
  })
})