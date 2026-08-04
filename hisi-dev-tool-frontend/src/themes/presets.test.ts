/**
 * presets.ts 单元测试
 * 测试预设主题配置
 *
 * TDD 流程:
 * 1. RED - 编写失败的测试，验证期望的行为
 * 2. GREEN - 编写最小代码使测试通过
 * 3. REFACTOR - 优化代码
 */

import { describe, test, expect } from 'vitest'
import {
  THEME_PRESETS,
  getThemeById,
  DEFAULT_THEME,
  DARK_TECH,
  DARK_MONOKAI,
  DARK_DRACULA,
  LIGHT_MINIMAL,
  LIGHT_SEPIA,
  EYE_CARE
} from './presets'

describe('presets.ts - 预设主题', () => {
  describe('THEME_PRESETS', () => {
    test('预设主题数量为 6', () => {
      expect(THEME_PRESETS).toHaveLength(6)
    })

    test('所有主题都有唯一 ID', () => {
      const ids = THEME_PRESETS.map(t => t.id)
      const uniqueIds = new Set(ids)
      expect(uniqueIds.size).toBe(6)
    })
  })

  describe('深色主题', () => {
    test('DARK_TECH 主题配置正确', () => {
      expect(DARK_TECH.id).toBe('dark-tech')
      expect(DARK_TECH.name).toBe('深色科技')
      expect(DARK_TECH.isDark).toBe(true)
      expect(DARK_TECH.backgroundLevel1).toBe('#1a1a1a')
      expect(DARK_TECH.accentPrimary).toBe('#409eff')
    })

    test('DARK_MONOKAI 主题配置正确', () => {
      expect(DARK_MONOKAI.id).toBe('dark-monokai')
      expect(DARK_MONOKAI.name).toBe('Monokai 经典')
      expect(DARK_MONOKAI.isDark).toBe(true)
      expect(DARK_MONOKAI.backgroundLevel1).toBe('#1e1e1e')
      expect(DARK_MONOKAI.accentPrimary).toBe('#f92672')
    })

    test('DARK_DRACULA 主题配置正确', () => {
      expect(DARK_DRACULA.id).toBe('dark-dracula')
      expect(DARK_DRACULA.name).toBe('Dracula')
      expect(DARK_DRACULA.isDark).toBe(true)
      expect(DARK_DRACULA.backgroundLevel1).toBe('#282a36')
      expect(DARK_DRACULA.accentPrimary).toBe('#bd93f9')
    })
  })

  describe('浅色主题', () => {
    test('LIGHT_MINIMAL 主题配置正确', () => {
      expect(LIGHT_MINIMAL.id).toBe('light-minimal')
      expect(LIGHT_MINIMAL.name).toBe('浅色简约')
      expect(LIGHT_MINIMAL.isDark).toBe(false)
      expect(LIGHT_MINIMAL.backgroundLevel1).toBe('#f5f5f5')
      expect(LIGHT_MINIMAL.accentPrimary).toBe('#409eff')
    })

    test('LIGHT_SEPIA 主题配置正确', () => {
      expect(LIGHT_SEPIA.id).toBe('light-sepia')
      expect(LIGHT_SEPIA.name).toBe('护眼暖色')
      expect(LIGHT_SEPIA.isDark).toBe(false)
      expect(LIGHT_SEPIA.backgroundLevel1).toBe('#f8f4e8')
      expect(LIGHT_SEPIA.accentPrimary).toBe('#d48806')
    })
  })

  describe('护眼主题', () => {
    test('EYE_CARE 主题配置正确', () => {
      expect(EYE_CARE.id).toBe('eye-care')
      expect(EYE_CARE.name).toBe('护眼绿色')
      expect(EYE_CARE.isDark).toBe(false)
      expect(EYE_CARE.backgroundLevel1).toBe('#e8f5e9')
      expect(EYE_CARE.accentPrimary).toBe('#67c23a')
    })
  })

  describe('getThemeById', () => {
    test('getThemeById("dark-tech") 返回正确主题', () => {
      const theme = getThemeById('dark-tech')
      expect(theme).toBeDefined()
      expect(theme?.id).toBe('dark-tech')
      expect(theme?.name).toBe('深色科技')
    })

    test('getThemeById("dark-monokai") 返回正确主题', () => {
      const theme = getThemeById('dark-monokai')
      expect(theme).toBeDefined()
      expect(theme?.id).toBe('dark-monokai')
    })

    test('getThemeById("dark-dracula") 返回正确主题', () => {
      const theme = getThemeById('dark-dracula')
      expect(theme).toBeDefined()
      expect(theme?.id).toBe('dark-dracula')
    })

    test('getThemeById("light-minimal") 返回正确主题', () => {
      const theme = getThemeById('light-minimal')
      expect(theme).toBeDefined()
      expect(theme?.id).toBe('light-minimal')
    })

    test('getThemeById("light-sepia") 返回正确主题', () => {
      const theme = getThemeById('light-sepia')
      expect(theme).toBeDefined()
      expect(theme?.id).toBe('light-sepia')
    })

    test('getThemeById("eye-care") 返回正确主题', () => {
      const theme = getThemeById('eye-care')
      expect(theme).toBeDefined()
      expect(theme?.id).toBe('eye-care')
    })

    test('getThemeById("invalid") 返回 undefined', () => {
      const theme = getThemeById('invalid')
      expect(theme).toBeUndefined()
    })
  })

  describe('DEFAULT_THEME', () => {
    test('DEFAULT_THEME 是 DARK_TECH', () => {
      expect(DEFAULT_THEME).toBe(DARK_TECH)
    })

    test('DEFAULT_THEME.id 是 "dark-tech"', () => {
      expect(DEFAULT_THEME.id).toBe('dark-tech')
    })
  })

  describe('主题完整性', () => {
    test('所有主题都有 terminal 配置', () => {
      THEME_PRESETS.forEach(theme => {
        expect(theme.terminal).toBeDefined()
        expect(theme.terminal.background).toBeDefined()
        expect(theme.terminal.foreground).toBeDefined()
        expect(theme.terminal.cursor).toBeDefined()
      })
    })

    test('所有主题都有完整的颜色配置', () => {
      const requiredColors = [
        'backgroundLevel1',
        'backgroundLevel2',
        'backgroundLevel3',
        'backgroundLevel4',
        'textPrimary',
        'textSecondary',
        'textMuted',
        'accentPrimary',
        'accentSuccess',
        'accentWarning',
        'accentDanger'
      ]

      THEME_PRESETS.forEach(theme => {
        requiredColors.forEach(color => {
          expect(theme[color as keyof typeof theme]).toBeDefined()
        })
      })
    })
  })
})