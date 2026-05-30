/**
 * 主题管理 Store
 *
 * 管理终端主题状态，包括：
 * - 主题切换
 * - 自定义主色调
 * - CSS 变量注入
 * - localStorage 持久化
 */

import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import {
  THEME_PRESETS,
  DEFAULT_THEME,
  getThemeById,
  THEME_STORAGE_KEY,
  THEME_CSS_VARS,
  type TerminalTheme,
  type TerminalColors
} from '@/themes'

/**
 * 主题存储数据格式
 */
interface ThemeStorageData {
  themeId: string
  customAccent: string | null
}

/**
 * 应用 CSS 变量到 document.documentElement
 * @param theme 主题配置
 */
function applyCSSVariables(theme: TerminalTheme): void {
  const root = document.documentElement

  root.style.setProperty(THEME_CSS_VARS.backgroundLevel1, theme.backgroundLevel1)
  root.style.setProperty(THEME_CSS_VARS.backgroundLevel2, theme.backgroundLevel2)
  root.style.setProperty(THEME_CSS_VARS.backgroundLevel3, theme.backgroundLevel3)
  root.style.setProperty(THEME_CSS_VARS.backgroundLevel4, theme.backgroundLevel4)
  root.style.setProperty(THEME_CSS_VARS.textPrimary, theme.textPrimary)
  root.style.setProperty(THEME_CSS_VARS.textSecondary, theme.textSecondary)
  root.style.setProperty(THEME_CSS_VARS.textMuted, theme.textMuted)
  root.style.setProperty(THEME_CSS_VARS.accentPrimary, theme.accentPrimary)
  root.style.setProperty(THEME_CSS_VARS.accentSuccess, theme.accentSuccess)
  root.style.setProperty(THEME_CSS_VARS.accentWarning, theme.accentWarning)
  root.style.setProperty(THEME_CSS_VARS.accentDanger, theme.accentDanger)
  root.style.setProperty(THEME_CSS_VARS.textOnAccent, theme.textOnAccent)
  root.style.setProperty(THEME_CSS_VARS.textOnAccentSecondary, theme.textOnAccentSecondary)
  root.style.setProperty(THEME_CSS_VARS.successLightBg, theme.successLightBg)
  root.style.setProperty(THEME_CSS_VARS.borderHover, theme.borderHover)
  root.style.setProperty(THEME_CSS_VARS.successTextOnAccent, theme.successTextOnAccent)
}

/**
 * 保存主题配置到 localStorage
 * @param themeId 主题 ID
 * @param customAccent 自定义主色调
 */
function saveToLocalStorage(themeId: string, customAccent: string | null): void {
  const data: ThemeStorageData = {
    themeId,
    customAccent
  }
  localStorage.setItem(THEME_STORAGE_KEY, JSON.stringify(data))
}

/**
 * 从 localStorage 加载主题配置
 * @returns 主题存储数据，不存在时返回 null
 */
function loadFromLocalStorage(): ThemeStorageData | null {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY)
    if (stored) {
      return JSON.parse(stored) as ThemeStorageData
    }
  } catch {
    // 解析失败，返回 null
  }
  return null
}

/**
 * 创建带自定义颜色的主题副本
 * @param baseTheme 基础主题
 * @param customAccent 自定义主色调
 * @returns 带自定义颜色的主题
 */
function createThemeWithCustomAccent(
  baseTheme: TerminalTheme,
  customAccent: string | null
): TerminalTheme {
  if (!customAccent) {
    return baseTheme
  }

  return {
    ...baseTheme,
    accentPrimary: customAccent,
    terminal: {
      ...baseTheme.terminal,
      cursor: customAccent,
      selectionBackground: `${customAccent}40`
    }
  }
}

/**
 * 主题管理 Store
 */
export const useThemeStore = defineStore('theme', () => {
  // State
  /** 当前主题 ID */
  const themeId = ref<string>(DEFAULT_THEME.id)
  /** 自定义主色调 */
  const customAccent = ref<string | null>(null)

  // Computed
  /** 预设主题列表 */
  const presets = computed(() => THEME_PRESETS)

  /** 当前完整主题对象 */
  const currentTheme = computed(() => {
    const baseTheme = getThemeById(themeId.value) || DEFAULT_THEME
    return createThemeWithCustomAccent(baseTheme, customAccent.value)
  })

  /** 是否深色主题 */
  const isDark = computed(() => currentTheme.value.isDark)

  // Actions
  /**
   * 切换主题
   * @param id 主题 ID
   */
  function setTheme(id: string): void {
    const theme = getThemeById(id)
    if (theme) {
      themeId.value = id
      applyCSSVariables(currentTheme.value)
      saveToLocalStorage(themeId.value, customAccent.value)
    }
    // 如果主题不存在，保持原主题不变
  }

  /**
   * 设置自定义主色调
   * @param color 自定义颜色（十六进制格式），null 表示清除自定义
   */
  function setCustomAccent(color: string | null): void {
    customAccent.value = color
    applyCSSVariables(currentTheme.value)
    saveToLocalStorage(themeId.value, customAccent.value)
  }

  /**
   * 重置为默认主题
   */
  function resetToDefault(): void {
    themeId.value = DEFAULT_THEME.id
    customAccent.value = null
    applyCSSVariables(currentTheme.value)
    saveToLocalStorage(themeId.value, customAccent.value)
  }

  /**
   * 获取 xterm.js 终端主题配置
   * @returns 终端颜色配置
   */
  function getTerminalTheme(): TerminalColors {
    return currentTheme.value.terminal
  }

  /**
   * 初始化 - 从 localStorage 恢复主题配置
   */
  function init(): void {
    const stored = loadFromLocalStorage()
    if (stored) {
      const theme = getThemeById(stored.themeId)
      if (theme) {
        themeId.value = stored.themeId
        customAccent.value = stored.customAccent
      }
    }
    // 应用当前主题的 CSS 变量
    applyCSSVariables(currentTheme.value)
  }

  return {
    // State
    themeId,
    customAccent,
    // Computed
    presets,
    currentTheme,
    isDark,
    // Actions
    setTheme,
    setCustomAccent,
    resetToDefault,
    getTerminalTheme,
    init
  }
})